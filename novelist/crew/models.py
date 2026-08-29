"""剧组之间流动的数据。

这些结构本身就是护栏：演员拿到的是 `ActorBrief`（只含它该知道的），
产出的是 `ActionProposal`（意图和台词，不是散文），
抽取器产出的是 `*Proposal`（建议，不是命令）。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

WORLD_OWNER = "世界"


# --------------------------------------------------------------------------
# 设定
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class FieldSeed:
    field_id: str
    kind: str = "TEXT"
    allowed_values: tuple[str, ...] = ()
    user_lock: bool = False
    max_items: int = 32
    #: 禁止抽取器写这个字段。
    #:
    #: 有些字段的权威是质检而不是抽取器：质检看得见动作，能判断"她走了所以
    #: 地点变了"；抽取器只是事后从正文里猜。真模型上撞过——最后一场没人移动、
    #: 质检没提地点，抽取器就自作主张把两个人都写回了临安，其中一个人根本
    #: 没离开过洛阳。这种字段错一次就静默污染后面每一场的对照锚点。
    extractor_locked: bool = False


@dataclass(frozen=True)
class CharacterSpec:
    owner_id: str
    persona: str
    voice: str
    secrets: tuple[str, ...] = ()
    initial_state: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class Bible:
    space_id: str
    title: str
    premise: str
    ending_direction: str
    total_chapters: int
    world_rules: tuple[str, ...] = ()
    #: 世界标志词，供离线计分卡数"世界观有没有落到正文里"。
    #: 跟 world_rules 分开：规则是成句的约束，这里要的是短专名。
    world_terms: tuple[str, ...] = ()
    taboos: tuple[str, ...] = ()
    characters: tuple[CharacterSpec, ...] = ()
    state_fields: tuple[FieldSeed, ...] = ()
    style: str = ""
    #: 风味卡 id，见 crew/style.py。留空则只用 style 这句话。
    style_card: str = ""
    style_intensity: str = "medium"
    #: status 里代表"已故"的取值。写进名册喂给叙述者和审校，防止死人开口。
    death_states: tuple[str, ...] = ()

    def character(self, owner_id: str) -> CharacterSpec:
        for spec in self.characters:
            if spec.owner_id == owner_id:
                return spec
        raise KeyError(f"Bible 里没有角色 {owner_id}")

    @property
    def prop_fields(self) -> tuple[str, ...]:
        """当作"道具账本"渲染的字段。默认所有数组字段。"""
        return tuple(s.field_id for s in self.state_fields if s.kind == "TEXT_LIST")

    @property
    def cast_names(self) -> tuple[str, ...]:
        return tuple(c.owner_id for c in self.characters)

    @property
    def playable_names(self) -> tuple[str, ...]:
        """可以上场的角色。世界仓只存客观事实，不参演。"""
        return tuple(c.owner_id for c in self.characters if c.owner_id != WORLD_OWNER)


# --------------------------------------------------------------------------
# 规划
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Act:
    name: str
    summary: str
    chapter_from: int
    chapter_to: int


@dataclass(frozen=True)
class RoughOutline:
    """粗纲冻结一次。细纲滚动重规划。"""

    main_conflict: str
    ending: str
    acts: tuple[Act, ...]

    def act_for(self, chapter: int) -> Act | None:
        for act in self.acts:
            if act.chapter_from <= chapter <= act.chapter_to:
                return act
        return None


@dataclass(frozen=True)
class Beat:
    beat_id: str
    chapter: int
    #: 本章第几场。跟 chapter 一起决定业务时间，见 crew/clock.py。
    scene_index: int
    goal: str
    cast: tuple[str, ...]
    location: str
    must_happen: tuple[str, ...] = ()
    done_when: str = ""


class BeatStatus:
    REACHED = "REACHED"
    CONTINUE = "CONTINUE"
    STALLED = "STALLED"


# --------------------------------------------------------------------------
# 一场戏
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class DirectorCue:
    """导演对一拍的指令。"""

    action: str  # "act" | "end"
    actor: str = ""
    #: 可空的环境/旁白事件。它不是任何角色的动作，只用来给下一拍换个压力，
    #: 所以不进动作序列、不落账，只喂给下一个演员和最后的叙述者。
    stage: str = ""
    reason: str = ""

    @property
    def ends(self) -> bool:
        return self.action == "end"


@dataclass(frozen=True)
class ActorBrief:
    """交给演员的全部信息。刻意不含全知视角。"""

    owner_id: str
    persona: str
    voice: str
    secrets: tuple[str, ...]
    scene_goal: str
    required_states: dict[str, Any]
    memory_context: str
    citable: dict[str, str]
    partners: tuple[str, ...]
    #: 本场此前已经发生的动作，含本轮排在自己前面那几个人刚做完的。
    #:
    #: 跨轮的部分由导演搭场时填，轮内的部分由编排层在每个演员落笔前追加——
    #: 导演一轮只搭一次场，不追加的话同一轮的演员互相看不见，order 就只是
    #: 调用顺序而不是信息顺序。
    scene_so_far: tuple[str, ...] = ()


@dataclass(frozen=True)
class SceneSpec:
    beat: Beat
    scene_goal: str
    order: tuple[str, ...]
    briefs: dict[str, ActorBrief]


@dataclass(frozen=True)
class ActionProposal:
    owner_id: str
    intent: str
    dialogue: str = ""
    emotion: str = ""
    #: 引用的记忆 id，必须来自 brief.citable，否则就是凭空编造。
    cites: tuple[str, ...] = ()
    #: 演员自述的所在地，必须逐字复制必带 State 的 location，用来跟库对账。
    claimed_location: str | None = None
    #: 地点内的细节位置（"城门门洞"、"窗边"）。自由文本，不参与对账，只喂叙述。
    position_detail: str = ""
    #: 对谁说话。已知可上场角色必须在本场 cast 里；无名配角不受限。
    addresses: tuple[str, ...] = ()
    #: 提到（但不一定在场）的角色。
    mentions: tuple[str, ...] = ()

    def as_beat_line(self) -> str:
        parts = [f"{self.owner_id}：{self.intent}"]
        if self.dialogue:
            parts.append(f"台词「{self.dialogue}」")
        if self.emotion:
            parts.append(f"情绪：{self.emotion}")
        if self.position_detail:
            parts.append(f"位置：{self.position_detail}")
        return "，".join(parts)


# --------------------------------------------------------------------------
# 质检
# --------------------------------------------------------------------------


class ViolationCode:
    UNCITABLE_MEMORY = "UNCITABLE_MEMORY"
    LOCATION_CONFLICT = "LOCATION_CONFLICT"
    ABSENT_CHARACTER = "ABSENT_CHARACTER"
    EMPTY_INTENT = "EMPTY_INTENT"


@dataclass(frozen=True)
class Violation:
    code: str
    detail: str

    def __str__(self) -> str:
        return f"{self.code}: {self.detail}"


@dataclass(frozen=True)
class GateReport:
    violations: tuple[Violation, ...] = ()

    @property
    def ok(self) -> bool:
        return not self.violations


@dataclass(frozen=True)
class StateChange:
    owner_id: str
    field_id: str
    value: Any
    reason: str = ""


@dataclass(frozen=True)
class CriticVerdict:
    accepted: bool
    feedback: str = ""
    state_changes: tuple[StateChange, ...] = ()


@dataclass(frozen=True)
class ReviewIssue:
    """审校挑出的一处硬伤。"""

    #: 原句片段，逐字照抄，供修订方精确定位。
    quote: str
    #: 为何是硬伤：撞了哪条锚点、哪条常识。
    why: str
    #: 改法方向。只给方向，不重写整段。
    fix: str = ""


@dataclass(frozen=True)
class CritiqueResult:
    issues: tuple[ReviewIssue, ...] = ()
    #: 审校产出是否解析成功。False 表示"审不出"，上层按通过兜底，
    #: 既不崩也不误判成有硬伤。
    parsed: bool = True

    @property
    def ok(self) -> bool:
        return not self.issues


@dataclass(frozen=True)
class ReflectRound:
    round: int
    issue_count: int
    parsed: bool
    revised: bool
    error: str = ""


# --------------------------------------------------------------------------
# 抽取建议（不是命令）
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class EpisodeProposal:
    owner_id: str
    summary: str
    tags: tuple[str, ...] = ()
    salience: float = 0.5


@dataclass(frozen=True)
class StateProposal:
    owner_id: str
    field_id: str
    value: Any
    confidence: float = 0.8


@dataclass(frozen=True)
class ReflectionProposal:
    owner_id: str
    memory_key: str
    summary: str


@dataclass(frozen=True)
class ChapterProposals:
    episodes: tuple[EpisodeProposal, ...] = ()
    states: tuple[StateProposal, ...] = ()
    reflections: tuple[ReflectionProposal, ...] = ()


# --------------------------------------------------------------------------
# 产出
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class SceneResult:
    """一场戏的动作序列和过闸记录。

    这里没有正文：正文是章级的，一章的全部动作攒齐了才落笔一次。
    """

    beat: Beat
    accepted: tuple[ActionProposal, ...]
    beat_status: str
    beat_status_reason: str = ""
    gate_rejections: tuple[Violation, ...] = ()
    critic_rounds: int = 0
    #: 这一场演了几拍才收。用来看导演是不是收得过早或者拖到了上限。
    rounds: int = 1
    #: 导演插的环境/旁白事件。不是谁的动作，所以不落账，只喂给叙述者。
    stage_notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class NarratableScene:
    """喂给章级叙述者的一场戏。"""

    location: str
    cast: tuple[str, ...]
    goal: str
    actions: tuple[ActionProposal, ...]
    stage_notes: tuple[str, ...] = ()

    def render(self, index: int) -> str:
        lines = [
            f"── 第 {index} 场：{self.location}"
            f"｜在场 {'、'.join(self.cast)}｜要达成：{self.goal}"
        ]
        for order, action in enumerate(self.actions, start=1):
            lines.append(f"{order}. {action.owner_id}：{action.intent}")
            if action.dialogue:
                lines.append(f'   台词：「{action.dialogue}」')
            if action.emotion:
                lines.append(f"   情绪：{action.emotion}")
            if action.position_detail:
                lines.append(f"   位置：{action.position_detail}")
        # 导演的旁白事件不是谁的动作，所以不编号，单列在后面当环境素材。
        if self.stage_notes:
            lines.append(
                "   （可用的环境事件，不是任何人的动作）：" + "；".join(self.stage_notes)
            )
        return "\n".join(lines)


def render_scene_blocks(scenes: tuple[NarratableScene, ...]) -> str:
    """把一章的动作序列渲染成分场的块。

    叙述者和审校共用这一份渲染，不是省代码：审校要审的正是"叙述者有没有超出
    它拿到的依据"。两边各自渲染的话，审校手里的依据会跟叙述者拿到的对不上，
    它就会把叙述者本来有据可依的地方报成硬伤，或者反过来漏掉真的加戏。
    """
    return "\n\n".join(
        scene.render(index)
        for index, scene in enumerate(scenes, start=1)
        if scene.actions
    )


@dataclass(frozen=True)
class ChapterResult:
    chapter: int
    scenes: tuple[SceneResult, ...]
    prose: str = ""
    committed_memories: int = 0
    rejected_proposals: tuple[str, ...] = ()
    title: str = ""
    #: 章级成文后的审校修订记录。
    review_rounds: tuple[ReflectRound, ...] = ()
