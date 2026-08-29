"""演员：只出动作提议，不写散文。

文风归叙述者独占。演员各写一段散文的话，一章里会出现好几种笔调。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

from .models import ActionProposal, ActorBrief, Violation

_SYSTEM = """你在扮演一个角色。你只输出这个角色此刻做的一个动作，不写旁白、不写景物、不写别人的反应。

铁律：
1. 你只知道 brief 里给你的东西。没写的你就不知道，不要推断别人的动机。
2. 引用往事必须用 `cites` 填记忆编号，且只能用"可引用记忆"里列出的编号。
   凭印象编一段过去 = 违规。想不起来就别提。
3. `claimed_location` 逐字照抄"你此刻的状态"里 location 的值，一个字都不要改。
   要写更细的位置（门洞里、窗边、船头）就填 `position_detail`。
4. `addresses` 里的主要角色必须在场；无名配角（守门兵、船家）可以随便对话。
5. 动作要具体。"她感到不安"不是动作，"她把信推回去，手指压住封口"是动作。
6. 如果给了"本场已经发生"，你的动作必须接着往下走。把已经做过的事再做一遍 = 违规。

只输出 JSON。"""

_USER = """你是{owner}。

人物：{persona}
说话方式：{voice}
只有你知道的事：
{secrets}

你此刻的状态：
{states}

你此刻想做的：{goal}

在场的还有：{partners}
{so_far}
你记得的事（可引用记忆，编号→内容）：
{citable}

其余背景（不可引用，仅供语气参考）：
{context}
{feedback}
输出 JSON：
{{
  "intent": "你做的一个具体动作",
  "dialogue": "你说的话，没说就留空",
  "emotion": "一个词",
  "cites": ["用到的记忆编号"],
  "claimed_location": "照抄状态里的 location",
  "position_detail": "更细的位置，没有就留空",
  "addresses": ["你对谁说话"],
  "mentions": ["你提到的人"]
}}"""

_FEEDBACK = """
上一版被打回了，原因：
{reasons}
按原因改，不要换一个方向重写。
"""

_SO_FAR = """
本场已经发生（你的动作要接着这里往下走，不要重复）：
{lines}
"""


class Actor:
    def __init__(self, llm: LLMProvider) -> None:
        self._llm = llm

    def act(
        self, brief: ActorBrief, feedback: tuple[Violation, ...] | tuple[str, ...] = ()
    ) -> ActionProposal:
        citable = (
            "\n".join(f"[{mid}] {text}" for mid, text in brief.citable.items())
            or "（你想不起任何具体往事）"
        )
        states = (
            "\n".join(f"- {k}：{v}" for k, v in sorted(brief.required_states.items()))
            or "-（未设定）"
        )
        reasons = "\n".join(f"- {item}" for item in feedback)
        data = self._llm.complete_json(
            Role.ACTOR,
            _SYSTEM,
            _USER.format(
                owner=brief.owner_id,
                persona=brief.persona,
                voice=brief.voice,
                secrets="\n".join(f"- {s}" for s in brief.secrets) or "-（无）",
                states=states,
                goal=brief.scene_goal,
                partners="、".join(brief.partners) or "（无人）",
                so_far=(
                    _SO_FAR.format(
                        lines="\n".join(f"- {line}" for line in brief.scene_so_far)
                    )
                    if brief.scene_so_far
                    else ""
                ),
                citable=citable,
                context=brief.memory_context or "（无）",
                feedback=_FEEDBACK.format(reasons=reasons) if feedback else "",
            ),
            temperature=0.85,
        )
        return ActionProposal(
            owner_id=brief.owner_id,
            intent=str(data.get("intent", "")).strip(),
            dialogue=str(data.get("dialogue", "")).strip(),
            emotion=str(data.get("emotion", "")).strip(),
            cites=tuple(str(c) for c in data.get("cites", [])),
            claimed_location=(data.get("claimed_location") or None),
            position_detail=str(data.get("position_detail", "")).strip(),
            addresses=tuple(str(a) for a in data.get("addresses", [])),
            mentions=tuple(str(m) for m in data.get("mentions", [])),
        )
