"""编排：两段式。多智能体演戏求"意外"，单智能体成文求"连贯"。

    每场：Director 搭场 →（每拍：Director 点人 → Actor 提议 → L1 闸门 → L2 质检）
          → Director 判 beat → 动作序列落成事实（状态 + 记忆）
    每章：全部场演完 → Narrator 一次落笔整章 → Reviewer 审校 ⇄ Narrator 定向修订
          → 正文落库 → Titler 起题

事实和正文是两条线，刻意分开：
* 事实必须【场级】落，因为下一场演员开场要读上一场写的状态。
* 正文必须【章级】落，因为一场一次落笔的话，一章里会有好几段各自生成的正文，
  接缝是真实存在的——真模型上撞过，同一章"踩石阶"写了两遍。
* 事实源是过闸的动作序列，不是正文。正文是渲染，它多写的东西没过 L1/L2。

L1 和 L2 管【成文之前】的动作提议，审校管【成文之后】的正文——叙述者重新落笔，
这一步之后必须还有人读一遍，否则它加的戏没人知道。

Planner 只在 beat 达成或卡住时被唤醒。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Callable

from ledger.runtime import MemoryRuntime
from ledger.types import MemoryKind, RecallRequest
from llm.provider import LLMProvider

from . import clock, stage
from .actor import Actor
from .chronicler import Chronicler
from .context import AnchorBuilder, SceneAnchors
from .critic import Critic
from .director import Director, SceneBlocked
from .gate import ContinuityGate
from .models import (
    ActionProposal,
    Beat,
    BeatStatus,
    Bible,
    ChapterResult,
    NarratableScene,
    ReflectRound,
    RoughOutline,
    SceneResult,
    SceneSpec,
    StateChange,
    Violation,
    WORLD_OWNER,
)
from .narrator import Narrator
from .planner import Planner
from .policy import HostPolicy
from .reflect import reflect_review
from .reviewer import Reviewer
from .style import StyleCard, load_style_card
from .titler import Titler

Reporter = Callable[[str], None]


def _location_values(bible: Bible) -> tuple[str, ...]:
    """location 的闭集。声明了才有得可选，没声明就退回自由文本。"""
    for seed in bible.state_fields:
        if seed.field_id == "location" and seed.kind == "ENUM":
            return seed.allowed_values
    return ()


@dataclass(frozen=True)
class LandedFacts:
    """一场戏落下的事实：状态 + 记忆。这一步没有正文。"""

    committed: int
    rejected: list[str]


@dataclass(frozen=True)
class LandedChapter:
    prose: str
    review_rounds: tuple[ReflectRound, ...] = ()


@dataclass
class StudioConfig:
    #: 一个演员的动作最多被打回几次。超了就跳过这一拍。
    max_actor_retries: int = 2
    #: 一个 beat 最多演几拍。导演说 end 就提前收，这是硬上限。
    #: 别调太高：真跑上两个 beat 都顶到了 8 拍，一章攒出 16 个动作，
    #: 叙述者要一次写三千多字，预算直接被推理吃光、正文一个字没吐出来。
    max_beats: int = 5
    #: 导演连着几拍没给出有效指令就收场。它点了不在场的人，或者接连点空。
    max_idle_cues: int = 2
    #: 一个 beat 卡住后最多换几次目标。
    max_replans: int = 1
    #: 每章规划几个 beat。
    beats_per_chapter: int = 2
    #: 叙述者每个动作的目标字数。篇幅跟动作量走，不跟拍数走——
    #: 按拍数给篇幅，动作少的时候叙述者只能靠加戏填满，那就等于让它自己编剧情。
    chars_per_action: int = 220
    #: 一章的篇幅下限。
    min_chapter_length: int = 600
    #: 一章的篇幅上限。按动作数线性给会无上限地涨——演满拍的一章能要到三千多字，
    #: 那既超出单次输出的合理预算，也不是一章该有的长度。
    max_chapter_length: int = 2_200
    #: 成文后最多审校修订几轮。设 0 关掉审校。
    max_review_rounds: int = 2
    #: 是否给每章起标题。
    title_chapters: bool = True


@dataclass
class Studio:
    runtime: MemoryRuntime
    llm: LLMProvider
    bible: Bible
    config: StudioConfig = field(default_factory=StudioConfig)
    report: Reporter = lambda message: None

    def __post_init__(self) -> None:
        self.style: StyleCard | None = (
            load_style_card(self.bible.style_card) if self.bible.style_card else None
        )
        intensity = self.bible.style_intensity
        planner_style = self.style.for_planner() if self.style else ""
        director_style = self.style.for_director() if self.style else ""
        narrator_style = (
            self.style.for_narrator(intensity)
            if self.style
            else f"文风要求：{self.bible.style or '白描，节制，少形容词'}"
        )

        self.planner = Planner(self.llm, planner_style)
        self.director = Director(self.runtime, self.llm, self.bible, director_style)
        self.actor = Actor(self.llm)
        self.gate = ContinuityGate(self.bible)
        self.critic = Critic(self.llm, locations=_location_values(self.bible))
        self.narrator = Narrator(self.llm, self.bible, narrator_style)
        self.reviewer = Reviewer(self.llm)
        self.titler = Titler(self.llm)
        self.chronicler = Chronicler(self.llm, self.bible)
        self.policy = HostPolicy(self.runtime, self.bible)
        self.anchors = AnchorBuilder(self.runtime, self.bible)
        #: 全书已用过的章节标题，起新标题时避重。
        self._used_titles: list[str] = []

    # ------------------------------------------------------------------

    def prepare(self) -> RoughOutline:
        stage.build_space(self.runtime, self.bible)
        self.report("搭台完成，开始规划粗纲")
        outline = self.planner.rough_outline(self.bible)
        self.report(f"粗纲：{outline.main_conflict}（{len(outline.acts)} 幕）")
        return outline

    def write_novel(
        self, outline: RoughOutline | None = None, chapters: int | None = None
    ) -> list[ChapterResult]:
        outline = outline or self.prepare()
        total = chapters or self.bible.total_chapters
        results = []
        for chapter in range(1, total + 1):
            results.append(self.write_chapter(chapter, outline))
        return results

    # ------------------------------------------------------------------

    def write_chapter(self, chapter: int, outline: RoughOutline) -> ChapterResult:
        opening = clock.story_time(chapter, 0)
        history = self._world_history(opening)
        states = self.director.character_states(opening)

        beats = self.planner.next_beats(
            self.bible,
            outline,
            chapter,
            history=history,
            states=states,
            count=self.config.beats_per_chapter,
        )
        if not beats:
            self.report(f"第 {chapter} 章：规划没给出可用 beat，跳过")
            return ChapterResult(chapter=chapter, scenes=())

        scenes: list[SceneResult] = []
        committed = 0
        rejected: list[str] = []
        #: 本章下一个可用的故事时刻槽位。换目标的 beat 要占新槽，不能跟前一场同刻。
        next_slot = len(beats)

        for beat in beats:
            current = beat
            for _ in range(self.config.max_replans + 1):
                scene_result, beat_committed, beat_rejected = self._play_beat(current)
                committed += beat_committed
                rejected.extend(beat_rejected)
                if scene_result is None:
                    break
                scenes.append(scene_result)
                if scene_result.beat_status != BeatStatus.STALLED:
                    break
                current = self.planner.replan(
                    self.bible,
                    outline,
                    current,
                    scene_result.beat_status_reason,
                    scene_index=next_slot,
                )
                next_slot += 1
                self.report(f"换目标：{current.goal}")

        # 全部 beat 演完了才落笔。章内连贯靠这一次调用的全局视角，不靠场间锚点缝合。
        landed = self._land_chapter(chapter, tuple(scenes))
        title = self._title_for(chapter, landed.prose)
        self.report(
            f"第 {chapter} 章完成：{len(scenes)} 场 {len(landed.prose)} 字，"
            f"落库 {committed} 条记忆"
        )
        return ChapterResult(
            chapter=chapter,
            scenes=tuple(scenes),
            prose=landed.prose,
            committed_memories=committed,
            rejected_proposals=tuple(rejected),
            title=title,
            review_rounds=landed.review_rounds,
        )

    def _title_for(self, chapter: int, prose: str) -> str:
        if not self.config.title_chapters or not prose.strip():
            return ""
        title = self.titler.title_for(
            self.bible.title, chapter, prose, tuple(self._used_titles)
        )
        if title:
            self._used_titles.append(title)
            self.report(f"第 {chapter} 章题作《{title}》")
        return title

    # ------------------------------------------------------------------

    def _play_beat(self, beat: Beat) -> tuple[SceneResult | None, int, list[str]]:
        """演完一个 beat，产出一场戏的动作序列。

        一拍一个演员，由导演挑人：被问话的人该答，被逼到墙角的人该反应。
        搭场只做一次（每个演员各一次 POV recall），之后每拍只问导演"下一个谁"。

        这里不落正文。正文是章级的——一章的全部动作攒齐了才落笔一次，
        所以章内不存在"两段各自生成的正文接不上"这种缝。
        """
        rejected: list[str] = []
        accumulated: list[ActionProposal] = []
        changes: list[StateChange] = []
        gate_rejections: list[Violation] = []
        stage_notes: list[str] = []
        critic_rounds = 0
        beats_played = 0
        idle = 0

        try:
            scene = self.director.build_scene(beat)
        except SceneBlocked as blocked:
            # fail-closed：必带状态缺失就不硬演。
            self.report(f"{beat.beat_id} 阻断：{blocked}")
            return None, 0, [str(blocked)]

        ended_by_director = False
        for beat_no in range(1, self.config.max_beats + 1):
            cue = self.director.next_actor(
                scene, tuple(accumulated), beat_no, self.config.max_beats
            )
            if cue.stage:
                stage_notes.append(cue.stage)
            if cue.ends:
                ended_by_director = True
                self.report(f"{beat.beat_id} 第 {beat_no} 拍 → 导演收场（{cue.reason}）")
                break
            if not cue.actor:
                idle += 1
                if idle >= self.config.max_idle_cues:
                    self.report(f"{beat.beat_id} 导演连着 {idle} 拍没点到人，收场")
                    break
                continue
            idle = 0

            proposal = self._play_one(
                cue.actor, scene, beat, tuple(accumulated), tuple(stage_notes)
            )
            if proposal is None:
                gate_rejections.extend(self._last_violations)
                critic_rounds += self._last_critic_calls
                continue
            gate_rejections.extend(self._last_violations)
            critic_rounds += self._last_critic_calls
            accumulated.append(proposal)
            changes.extend(self._last_changes)
            beats_played += 1

        if not accumulated:
            rejected.append(f"{beat.beat_id}：一拍都没过闸")
            return None, 0, rejected

        # 顶到上限值得报出来：说明导演一次都没主动收场，这场戏的长度是被硬截的，
        # 不是演够了。攒出来的动作会一路顶到叙述者那一次调用的输出预算。
        if not ended_by_director:
            self.report(
                f"{beat.beat_id} 顶到 {self.config.max_beats} 拍上限，导演没主动收场"
            )

        # 只认导演的判定。critic 只看单个动作，导演看整场累积的动作序列。
        status, reason = self.director.judge_beat(beat, tuple(accumulated))
        self.report(f"{beat.beat_id} 演了 {beats_played} 拍 → {status}（{reason}）")

        # 收场和判据是两个问题，可以合法地不一致：戏演完了，但判据没走完。
        # 拍级调度下收场归导演管，所以这里【不】回去补演——它给出的收场理由通常比
        # 判据的字面残留更靠谱（"城门尚未合上"这种）。但不一致得报出来，
        # 否则 CONTINUE 就是个悄悄被丢掉的信号，看日志的人会以为它还有作用。
        if status == BeatStatus.CONTINUE:
            self.report(f"{beat.beat_id} 导演已收场，但判据未走完 → 按收场处理")

        landed = self._land_facts(beat, tuple(accumulated), tuple(changes))
        rejected.extend(landed.rejected)

        return (
            SceneResult(
                beat=beat,
                accepted=tuple(accumulated),
                beat_status=status,
                beat_status_reason=reason,
                gate_rejections=tuple(gate_rejections),
                critic_rounds=critic_rounds,
                rounds=beats_played,
                stage_notes=tuple(stage_notes),
            ),
            landed.committed,
            rejected,
        )

    def _play_one(
        self,
        owner_id: str,
        scene: SceneSpec,
        beat: Beat,
        so_far: tuple[ActionProposal, ...],
        stage_notes: tuple[str, ...],
    ) -> ActionProposal | None:
        """让一个演员出一个动作，过 L1 和 L2。都不过就返回 None。

        brief 在搭场时建好，但 scene_so_far 每拍都要刷新——不刷新的话后演的人
        看不见先演的人刚做了什么，两个动作不可能是在回应对方，最后只能靠叙述者
        重排顺序把矛盾圆过去，等于它替导演干了活。
        """
        brief = replace(
            scene.briefs[owner_id],
            scene_so_far=tuple(a.as_beat_line() for a in so_far) + stage_notes,
        )
        self._last_violations: list[Violation] = []
        self._last_critic_calls = 0
        self._last_changes: tuple[StateChange, ...] = ()

        feedback: tuple = ()
        for attempt in range(self.config.max_actor_retries + 1):
            proposal = self.actor.act(brief, feedback)

            report = self.gate.check(proposal, brief, beat)
            if not report.ok:
                self._last_violations.extend(report.violations)
                feedback = report.violations
                self.report(
                    f"{owner_id} 第 {attempt + 1} 版被 L1 拦下："
                    + "；".join(v.code for v in report.violations)
                )
                continue

            verdict = self.critic.review(proposal, brief, beat)
            self._last_critic_calls += 1
            if not verdict.accepted:
                feedback = (verdict.feedback,) if verdict.feedback else ()
                self.report(f"{owner_id} 第 {attempt + 1} 版被 L2 打回")
                continue

            self._last_changes = verdict.state_changes
            return proposal
        return None

    def _land_facts(
        self,
        beat: Beat,
        accepted: tuple[ActionProposal, ...],
        changes: tuple[StateChange, ...],
    ) -> LandedFacts:
        """把一场戏过闸后的动作序列落成事实：原文 + 状态 + 记忆。

        必须在本场收尾时就落，不能攒到章末跟正文一起落——下一场的演员开场要读
        本场写下的状态，攒到章末的话他们会拿着过期状态演。

        事实源是【动作序列】而不是正文。正文是叙述者对这串动作的一次渲染，它多写
        出来的东西没过 L1/L2；从正文抽记忆，叙述者一加戏、加出来的戏就进了账本，
        唯一的拦阻是审校，而审校静默失效过一整轮。
        """
        lines = "\n".join(action.as_beat_line() for action in accepted)
        source = self.policy.capture_actions(beat.chapter, beat.scene_index, lines)

        rejected: list[str] = []
        written, skip_states, state_rejects = self.policy.apply_state_changes(
            beat.chapter, beat.scene_index, changes, source, run_id=beat.beat_id
        )
        rejected.extend(state_rejects)

        proposals = self.chronicler.extract(beat.chapter, lines, beat.cast)
        chronicle_written, chronicle_rejects = self.policy.commit_scene(
            beat.chapter,
            beat.scene_index,
            proposals,
            source,
            run_id=beat.beat_id,
            skip_states=skip_states,
        )
        rejected.extend(chronicle_rejects)
        return LandedFacts(
            committed=written + chronicle_written,
            rejected=rejected,
        )

    def _land_chapter(
        self, chapter: int, scenes: tuple[SceneResult, ...]
    ) -> LandedChapter:
        """一章的全部动作序列，一次落笔 → 章级审校 → 正文落库。

        审校必须排在 capture 之前：落进账本的正文要是审过的那一版，否则硬伤会
        变成下一章的承接锚点。事实已经在场级落过了，这里只落"已发表的正文"。
        """
        narratable = tuple(
            NarratableScene(
                location=scene.beat.location,
                cast=scene.beat.cast,
                goal=scene.beat.goal,
                actions=scene.accepted,
                stage_notes=scene.stage_notes,
            )
            for scene in scenes
        )
        actions = tuple(a for scene in scenes for a in scene.accepted)
        if not actions:
            return LandedChapter(prose="")

        anchors = self.anchors.build(chapter)
        length = min(
            self.config.max_chapter_length,
            max(
                self.config.min_chapter_length,
                self.config.chars_per_action * len(actions),
            ),
        )
        draft = self.narrator.narrate_chapter(
            chapter, narratable, length=length, anchors=anchors
        )

        reviewed = reflect_review(
            self.reviewer,
            self.narrator,
            draft,
            narratable,
            anchors,
            max_rounds=self.config.max_review_rounds,
            on_round=lambda r: self._report_review(chapter, r),
        )
        prose = reviewed.prose
        if prose.strip():
            self.policy.capture_chapter_prose(chapter, prose)
        return LandedChapter(prose=prose, review_rounds=reviewed.rounds)

    def _report_review(self, chapter: int, record: ReflectRound) -> None:
        head = f"第 {chapter} 章审校第 {record.round} 轮"
        if record.error:
            self.report(f"{head}中断：{record.error}")
        elif record.issue_count == 0:
            note = "通过" if record.parsed else "审不出，按通过兜底"
            self.report(f"{head} → {note}")
        else:
            note = "已修订" if record.revised else "修订稿没过防截断，弃用"
            self.report(f"{head} → {record.issue_count} 处硬伤，{note}")

    # ------------------------------------------------------------------

    def _world_history(self, at: int) -> tuple[str, ...]:
        """世界仓的客观事件流，只给规划和导演看。"""
        result = self.runtime.recall(
            RecallRequest(
                space_id=self.bible.space_id,
                owner_id=WORLD_OWNER,
                query=self.bible.premise,
                at=clock.at(at),
                kinds=frozenset({MemoryKind.EPISODE}),
                budget_chars=1_800,
                limit_per_channel=20,
            )
        )
        ordered = sorted(result.selected, key=lambda s: s.business_time or 0)
        return tuple(s.text for s in ordered)
