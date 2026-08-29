"""书记：按 POV 切分一场戏的动作序列，产出 Proposal。

抽取器只出建议，不能写库。这是记忆合同的硬约束：
LLM → Proposal → 宿主策略层裁决 → AuthorizedCommand → Memory。

读的是【过闸后的动作序列】而不是正文。正文是叙述者对动作序列的一次渲染，它多写
出来的东西没经过 L1/L2；从正文抽记忆，叙述者一加戏、加出来的戏就进了账本。

同一件事在世界仓是客观记述，在角色仓是主观记述。
"沈砚没赴约" 是事实；"他抛弃了我" 是林晚的记忆。两者都要存，存在不同 owner 下。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

from .models import (
    Bible,
    ChapterProposals,
    EpisodeProposal,
    ReflectionProposal,
    StateProposal,
    WORLD_OWNER,
)

_SYSTEM = """你是剧组的书记。你把一场戏的动作序列拆成记忆条目。你不评价，不续写。

动作序列是这场戏已经定稿的事实，每行是"角色：做了什么／说了什么"。
只写序列里真的有的事，不要补写它没写的过程、动机、后续。

三件事分清楚：

world_episodes：客观发生了什么。第三人称。
  例："沈砚在城南破庙留下一封信，林晚未拆。"

per_character[X].episodes：X 这个角色**自己经历或知道**的事，用 X 的主观口吻。
  只能写 X 在场看到、听到、或被明确告知的。X 不在场的事不许写进它的仓。
  主观判断可以写，因为那就是它的记忆。例："他没来。他不要我了。"

per_character[X].states：X 在这场戏结束时的当前状态。
  只能用给定的字段。值必须严格符合字段契约：
  枚举字段只能填闭集里的原词，不许自己造词；数组字段必须给数组，不许给字符串。
  拿不准就不要写这个字段——写错会被整条丢掉，不写只是保持原值。
  没变化就别写。不知道的值不要用"无"/"未知"/"不明"占位。

per_character[X].reflections：X 对自己或他人的认知发生了改变。
  没有实质认知变化就留空数组。不要为了填满而编。

只输出 JSON。"""

_USER = """《{title}》第 {chapter} 章，一场戏的动作序列：

{actions}

这场戏在场角色：{cast}

可用状态字段（值必须符合契约）：
{fields}

输出 JSON：
{{
  "world_episodes": ["客观记述"],
  "per_character": {{
    "角色名": {{
      "episodes": [{{"summary": "主观记述", "tags": ["可选标签"], "salience": 0.6}}],
      "states": [{{"field_id": "字段名", "value": "新值"}}],
      "reflections": [{{"memory_key": "self_model|trust:对方名|belief:主题",
                        "summary": "认知变成了什么"}}]
    }}
  }}
}}"""


def _contract_line(seed) -> str:
    """把字段契约写成人话。

    只给字段名，抽取器只能猜：真模型第一次跑就把 status 填成"目送林晚离开"、
    把 TEXT_LIST 的随身物填成一个字符串，六条 State 全被 SCHEMA_MISMATCH 弹回。
    """
    if seed.kind == "ENUM":
        allowed = " / ".join(seed.allowed_values)
        return f"- {seed.field_id}：枚举，只能原样填其中一个：{allowed}"
    if seed.kind == "TEXT_LIST":
        return f"- {seed.field_id}：字符串数组，最多 {seed.max_items} 项，例 [\"路引\", \"包袱\"]"
    if seed.kind == "NUMBER":
        return f"- {seed.field_id}：数字"
    if seed.kind == "BOOL":
        return f"- {seed.field_id}：true 或 false"
    return f"- {seed.field_id}：一句短文本"


class Chronicler:
    def __init__(self, llm: LLMProvider, bible: Bible) -> None:
        self._llm = llm
        self._bible = bible
        # 不把质检独占的字段列给抽取器。列了它就会填，填了又被挡回来，
        # 白花一趟 token 还制造一堆噪音拒绝。
        self._fields = "\n".join(
            _contract_line(s) for s in bible.state_fields if not s.extractor_locked
        )

    def extract(
        self, chapter: int, actions: str, cast: tuple[str, ...]
    ) -> ChapterProposals:
        if not actions.strip():
            return ChapterProposals()

        data = self._llm.complete_json(
            Role.CHRONICLER,
            _SYSTEM,
            _USER.format(
                title=self._bible.title,
                chapter=chapter,
                actions=actions,
                cast="、".join(cast),
                fields=self._fields or "-（无）",
            ),
            temperature=0.3,
        )

        episodes: list[EpisodeProposal] = []
        states: list[StateProposal] = []
        reflections: list[ReflectionProposal] = []

        for summary in data.get("world_episodes", []):
            if str(summary).strip():
                episodes.append(
                    EpisodeProposal(WORLD_OWNER, str(summary).strip(), salience=0.6)
                )

        known = set(self._bible.playable_names)
        for owner_id, block in (data.get("per_character") or {}).items():
            # 抽取器提到不存在的角色：整块丢掉，不要试图猜它想说谁。
            if owner_id not in known or owner_id == WORLD_OWNER:
                continue
            if owner_id not in cast:
                # 不在场的角色不该在这场戏长出记忆。
                continue
            for raw in block.get("episodes", []):
                summary = str(raw.get("summary", "")).strip()
                if not summary:
                    continue
                episodes.append(
                    EpisodeProposal(
                        owner_id=owner_id,
                        summary=summary,
                        tags=tuple(str(t) for t in raw.get("tags", [])),
                        salience=float(raw.get("salience", 0.5)),
                    )
                )
            for raw in block.get("states", []):
                field_id = str(raw.get("field_id", "")).strip()
                if not field_id or raw.get("value") in (None, ""):
                    continue
                states.append(
                    StateProposal(owner_id=owner_id, field_id=field_id, value=raw["value"])
                )
            for raw in block.get("reflections", []):
                key = str(raw.get("memory_key", "")).strip()
                summary = str(raw.get("summary", "")).strip()
                if not key or not summary:
                    continue
                reflections.append(ReflectionProposal(owner_id, key, summary))

        return ChapterProposals(
            episodes=tuple(episodes),
            states=tuple(states),
            reflections=tuple(reflections),
        )
