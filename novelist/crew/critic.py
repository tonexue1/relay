"""L2 质检：只管逻辑，不管文笔。

不做整章重写循环——DOC 实测那一步对最终质量没有显著贡献，成本却很高。
这里只做动作级判定：具体、在人物性格内、推进当前目标。
顺手产出建议的状态变更和 beat 是否达成，让导演少一次调用。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

from .models import (
    ActionProposal,
    ActorBrief,
    Beat,
    CriticVerdict,
    StateChange,
)

_SYSTEM = """你是剧组的一致性质检。你不评价文笔，只判四件事：

具体：这是一个能被拍出来的动作，不是情绪描述。
在性格内：符合这个角色的人物设定和说话方式。
推进：对当前 beat 目标有实质推进，不是原地绕圈。
不越界：没有用到这个角色不可能知道的信息。

不合格就 accepted=false，并在 feedback 里说清"哪一条不合格、怎么改"，
不要重写他的动作。合格就顺手给出这个动作导致的状态变更。

状态变更里有一条是硬要求：动作让这个角色离开了当前所在地，就必须给出 location
的新值。漏了这一条，后面几场会以为他还在原地。

location 是【闭集】，只能填给你的候选值之一，一字不改。不许写"洛阳·府衙外·石阶"
或"门内"这种细节方位——台阶第几级、门里门外属于正文里的位置细节，不是账本事实。
人还在同一个候选值范围内，就不要给 location 变更。

只输出 JSON。"""

_USER = """beat 目标：{goal}
完成判据：{done_when}
地点：{location}
在场：{cast}
location 的候选值（只能填这些里的一个）：{locations}

角色：{owner}
人物设定：{persona}
它此刻的状态：
{states}
它被允许知道的事：
{context}

它提交的动作：
  意图：{intent}
  台词：{dialogue}
  情绪：{emotion}
  细节位置：{position}

输出 JSON：
{{
  "accepted": true,
  "feedback": "不合格时说清哪条不合格、怎么改",
  "state_changes": [
    {{"owner_id": "角色名", "field_id": "location|current_goal|status",
      "value": "新值", "reason": "为什么变"}}
  ]
}}"""

#: 允许 critic 建议改动的字段。超出这个集合的建议直接丢掉。
SUGGESTABLE_FIELDS = frozenset({"location", "current_goal", "status"})


class Critic:
    def __init__(
        self,
        llm: LLMProvider,
        allowed_fields: frozenset[str] | None = None,
        locations: tuple[str, ...] = (),
    ) -> None:
        self._llm = llm
        self._allowed = allowed_fields or SUGGESTABLE_FIELDS
        self._locations = locations

    def review(
        self, proposal: ActionProposal, brief: ActorBrief, beat: Beat
    ) -> CriticVerdict:
        states = (
            "\n".join(f"- {k}：{v}" for k, v in sorted(brief.required_states.items()))
            or "-（未设定）"
        )
        data = self._llm.complete_json(
            Role.CRITIC,
            _SYSTEM,
            _USER.format(
                goal=beat.goal,
                done_when=beat.done_when,
                location=beat.location,
                cast="、".join(beat.cast),
                locations="、".join(self._locations) or "（未声明闭集，写地点名即可）",
                owner=proposal.owner_id,
                persona=brief.persona,
                states=states,
                context=brief.memory_context or "（无）",
                intent=proposal.intent,
                dialogue=proposal.dialogue or "（无）",
                emotion=proposal.emotion or "（无）",
                position=proposal.position_detail or "（未说明）",
            ),
            temperature=0.2,
        )

        changes = []
        for raw in data.get("state_changes", []):
            field_id = raw.get("field_id")
            owner_id = raw.get("owner_id")
            if field_id not in self._allowed:
                continue
            # critic 只能改本场在场角色的状态。
            if owner_id not in beat.cast:
                continue
            changes.append(
                StateChange(
                    owner_id=owner_id,
                    field_id=field_id,
                    value=raw.get("value"),
                    reason=raw.get("reason", ""),
                )
            )

        return CriticVerdict(
            accepted=bool(data.get("accepted", False)),
            feedback=str(data.get("feedback", "")).strip(),
            state_changes=tuple(changes),
        )
