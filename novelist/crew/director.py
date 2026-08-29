"""导演：唯一的全知视角。

导演是唯一被允许 `include_owners=[世界]` 的角色。演员的 recall 一律
只读自己的 owner，所以"演员知道了它不该知道的事"在 SQL 层面就不可能。
一次 recall 一个 ownerId，多角色就是多次调用。
"""

from __future__ import annotations

from ledger.runtime import MemoryRuntime
from ledger.types import (
    MemoryKind,
    OnMissing,
    RecallRequest,
    RequiredField,
    StateIssue,
)
from llm.provider import LLMProvider, Role

from . import clock
from .models import (
    ActionProposal,
    ActorBrief,
    Beat,
    BeatStatus,
    Bible,
    DirectorCue,
    SceneSpec,
    WORLD_OWNER,
)

CONTEXT_CONTRACT_ID = "novel.scene"
CONTEXT_CONTRACT_VERSION = "1"

#: 每场必须知道的字段。location 缺失直接阻断——宁可报错也不让演员猜自己在哪。
REQUIRED_FIELDS = (
    RequiredField("location", OnMissing.BLOCK),
    RequiredField("current_goal", OnMissing.WARN),
    RequiredField("status", OnMissing.WARN),
)

_FRAME_SYSTEM = """你是导演。你不写正文，只搭场。
要求：
1. `scene_goal` 是本场对外的客观目标，一句话。
2. `briefs` 里每个角色只能拿到"这个角色自己此刻会关心的事"。
   绝对不要把别人的秘密、别人的动机、或者读者才知道的信息写进任何一个 brief。
3. `order` 是本场的点名单，把最有理由先开口的人放前面。它只定第一拍的倾向，
   之后每一拍谁动会另外单独问你，所以不必在这里把整场顺序排完。
只输出 JSON。"""

_FRAME_USER = """《{title}》第 {chapter} 章，本场。

{style}beat 目标：{goal}
必须落地：
{must_happen}
完成判据：{done_when}
地点：{location}
在场：{cast}

世界此刻（只有你能看到）：
{world}

输出 JSON：
{{
  "scene_goal": "本场客观目标",
  "order": [{order_hint}],
  "briefs": {{
    "角色名": "这个角色此刻自己的目的，一到两句，不含它不知道的信息"
  }}
}}"""

_NEXT_SYSTEM = """你是导演，正在一拍一拍地调度这场戏。你不写正文，只决定下一拍谁动。

【每一拍先问自己：完成判据已经拿到了吗？】拿到了就 end，这是默认选择。
戏是靠"该收的时候收"好看的，不是靠拍数多。多演的每一拍都在稀释这场戏。

二选一：
end  —— 收场。以下任一条成立就收：
        · 完成判据里要求的事实已经落地了（哪怕只用了两拍）；
        · 必须落地的事都发生了；
        · 最近两拍没有产生新的事实，只是在重复表态、来回劝说、加重语气。
act  —— 指定一个在场角色接着动。只有【这场戏还缺一个具体事实】才选它。
        挑此刻最有理由动的那个人：刚被问话的人该答，被逼到墙角的人该反应，
        掌握主动的人该推进。不要按名单轮流点，也不要为了让每个人都出场而点人。

`stage` 是可空的一句环境或旁白事件（风停了、更鼓响了、门外脚步声近了），
用来给下一拍换个压力。没必要就留空，不要每拍都加。

只输出 JSON。"""

_NEXT_USER = """beat 目标：{goal}
完成判据：{done_when}
必须落地：
{must_happen}

在场：{cast}
这是第 {beat_no} 拍，最多 {max_beats} 拍。{pressure}

本场已经发生：
{transcript}

先对着完成判据核一遍上面已经发生的事：判据要求的都有了吗？有了就 end。

输出 JSON：
{{"stage": "可空的环境/旁白事件", "action": "act 或 end",
  "actor": "action=act 时必须是在场角色之一",
  "reason": "一句话缘由；选 act 要说出这场戏还缺哪个具体事实"}}"""

_JUDGE_SYSTEM = """你是导演，判断一个 beat 是否完成。
REACHED：完成判据已经满足。
CONTINUE：有推进但还没满足，再演一轮有用。
STALLED：这一轮没有实质推进，或者在原地绕圈，需要换目标。

判的是判据的实质，不是字面。判据说"她决定走"，演员做出了等价的表态就算满足；
不要因为某句台词没被逐字说出、某个道具没出现就判不满足。
只输出 JSON。"""

_JUDGE_USER = """beat 目标：{goal}
完成判据：{done_when}
必须落地：
{must_happen}

本场已发生：
{actions}

输出 JSON：{{"status": "REACHED|CONTINUE|STALLED", "reason": "一句话"}}"""

class SceneBlocked(Exception):
    """必带状态缺失。fail-closed：不猜，不硬演。"""

    def __init__(self, owner_id: str, issues: tuple[StateIssue, ...]) -> None:
        detail = "；".join(f"{i.field_id}={i.status.value}" for i in issues)
        super().__init__(f"{owner_id} 的必带状态不可用：{detail}")
        self.owner_id = owner_id
        self.issues = issues


class Director:
    def __init__(
        self,
        runtime: MemoryRuntime,
        llm: LLMProvider,
        bible: Bible,
        style_brief: str = "",
    ) -> None:
        self._runtime = runtime
        self._llm = llm
        self._bible = bible
        self._style = f"{style_brief}\n\n" if style_brief else ""

    # ------------------------------------------------------------------
    # 全知侧
    # ------------------------------------------------------------------

    def world_digest(self, at: int, query: str, budget: int = 1_200) -> str:
        """导演的世界视角。演员永远拿不到这个。"""
        result = self._runtime.recall(
            RecallRequest(
                space_id=self._bible.space_id,
                owner_id=WORLD_OWNER,
                query=query,
                at=clock.at(at),
                budget_chars=budget,
            )
        )
        return result.context

    def character_states(self, at: int) -> str:
        lines = []
        for spec in self._bible.characters:
            if spec.owner_id == WORLD_OWNER:
                continue
            read = self._runtime.get_states(
                _state_request(self._bible.space_id, spec.owner_id, at)
            )
            values = "；".join(
                f"{fid}={item.payload.get('value')}" for fid, item in sorted(read.present.items())
            )
            lines.append(f"- {spec.owner_id}：{values or '（未设定）'}")
        return "\n".join(lines)

    # ------------------------------------------------------------------
    # 搭场
    # ------------------------------------------------------------------

    def build_scene(self, beat: Beat) -> SceneSpec:
        """搭场：定场目标、点名单、给每个演员做一份 POV 隔离的 brief。

        一场只搭一次。每个演员的 recall 是这里唯一一次，之后每拍只刷新
        scene_so_far（本场公开事实），不重新检索——重检索既贵又会让演员的
        "已知"在一场之内漂移。
        """
        at = clock.story_time(beat.chapter, beat.scene_index)
        world = self.world_digest(at, f"{beat.goal} {beat.location}")
        order_hint = ", ".join(f'"{name}"' for name in beat.cast)
        data = self._llm.complete_json(
            Role.DIRECTOR,
            _FRAME_SYSTEM,
            _FRAME_USER.format(
                title=self._bible.title,
                chapter=beat.chapter,
                style=self._style,
                goal=beat.goal,
                must_happen="\n".join(f"- {m}" for m in beat.must_happen) or "-（无）",
                done_when=beat.done_when,
                location=beat.location,
                cast="、".join(beat.cast),
                world=world or "（尚无记录）",
                order_hint=order_hint,
            ),
            temperature=0.6,
        )

        scene_goal = data.get("scene_goal", beat.goal)
        raw_briefs = data.get("briefs", {})
        ordered = tuple(
            name for name in data.get("order", beat.cast) if name in beat.cast
        ) or beat.cast

        briefs: dict[str, ActorBrief] = {}
        for owner_id in ordered:
            briefs[owner_id] = self._brief_for(
                owner_id,
                beat,
                raw_briefs.get(owner_id) or scene_goal,
                partners=tuple(n for n in ordered if n != owner_id),
            )

        return SceneSpec(beat=beat, scene_goal=scene_goal, order=ordered, briefs=briefs)

    def _brief_for(
        self,
        owner_id: str,
        beat: Beat,
        actor_goal: str,
        partners: tuple[str, ...],
    ) -> ActorBrief:
        spec = self._bible.character(owner_id)
        # 演员的 recall：include_owners 留空，只读自己的仓。
        result = self._runtime.recall(
            RecallRequest(
                space_id=self._bible.space_id,
                owner_id=owner_id,
                query=f"{beat.goal} {beat.location} {'、'.join(partners)}",
                at=clock.stamp(beat.chapter, beat.scene_index),
                include_owners=(),
                kinds=frozenset({MemoryKind.EPISODE, MemoryKind.REFLECTION}),
                required_fields=REQUIRED_FIELDS,
                context_contract_id=CONTEXT_CONTRACT_ID,
                context_contract_version=CONTEXT_CONTRACT_VERSION,
                budget_chars=1_400,
            )
        )
        if not result.ready:
            raise SceneBlocked(owner_id, result.issues)

        citable = {item.memory_id: item.text for item in result.selected}
        required = {
            field_id: item.payload.get("value")
            for field_id, item in result.required_states.items()
        }
        return ActorBrief(
            owner_id=owner_id,
            persona=spec.persona,
            voice=spec.voice,
            secrets=spec.secrets,
            scene_goal=actor_goal,
            required_states=required,
            memory_context=result.context,
            citable=citable,
            partners=partners,
        )

    # ------------------------------------------------------------------
    # 拍级调度
    # ------------------------------------------------------------------

    def next_actor(
        self,
        scene: SceneSpec,
        transcript: tuple[ActionProposal, ...],
        beat_no: int,
        max_beats: int,
    ) -> DirectorCue:
        """决定下一拍谁动，或者收场。

        一拍问一次，比"一轮全员各演一次"贵，换来的是导演能顺着刚发生的事挑人：
        被问话的人该答，被逼到墙角的人该反应。按名单轮流点做不到这个。
        """
        beat = scene.beat
        # 快到上限就明说。不说的话它会一路演到被硬截，收场判断全靠编排层兜——
        # 真跑上两个 beat 都顶到了上限，导演一次 end 都没说过。
        remaining = max_beats - beat_no + 1
        pressure = (
            f"　只剩 {remaining} 拍，判据差不多就该收了。" if remaining <= 2 else ""
        )
        data = self._llm.complete_json(
            Role.DIRECTOR,
            _NEXT_SYSTEM,
            _NEXT_USER.format(
                goal=beat.goal,
                done_when=beat.done_when,
                must_happen="\n".join(f"- {m}" for m in beat.must_happen) or "-（无）",
                cast="、".join(scene.order),
                beat_no=beat_no,
                max_beats=max_beats,
                pressure=pressure,
                transcript="\n".join(f"- {a.as_beat_line()}" for a in transcript)
                or "-（还没开始）",
            ),
            temperature=0.5,
        )

        action = str(data.get("action", "")).strip().lower()
        actor = str(data.get("actor", "") or "").strip()
        # 点了不在场的人：当成"导演没给出有效指令"，由编排层兜底，不要硬演。
        if action == "act" and actor not in scene.order:
            actor = ""
        return DirectorCue(
            action="end" if action == "end" else "act",
            actor=actor,
            stage=str(data.get("stage", "") or "").strip(),
            reason=str(data.get("reason", "") or "").strip(),
        )

    # ------------------------------------------------------------------
    # 判 beat
    # ------------------------------------------------------------------

    def judge_beat(
        self, beat: Beat, actions: tuple[ActionProposal, ...]
    ) -> tuple[str, str]:
        data = self._llm.complete_json(
            Role.DIRECTOR,
            _JUDGE_SYSTEM,
            _JUDGE_USER.format(
                goal=beat.goal,
                done_when=beat.done_when,
                must_happen="\n".join(f"- {m}" for m in beat.must_happen) or "-（无）",
                actions="\n".join(f"- {a.as_beat_line()}" for a in actions) or "-（无）",
            ),
            temperature=0.2,
        )
        status = data.get("status", BeatStatus.CONTINUE)
        if status not in (BeatStatus.REACHED, BeatStatus.CONTINUE, BeatStatus.STALLED):
            status = BeatStatus.CONTINUE
        return status, data.get("reason", "")


def _state_request(space_id: str, owner_id: str, at: int):
    from ledger.types import StateReadRequest, StateSelector

    return StateReadRequest(
        space_id=space_id,
        owner_id=owner_id,
        selectors=tuple(StateSelector(rf.field_id) for rf in REQUIRED_FIELDS),
        at=clock.at(at),
    )
