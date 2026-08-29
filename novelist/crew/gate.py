"""L1 一致性闸门：机械检查，不调 LLM。

这一层能确定性拦住的，就不该花钱让 critic 去"感觉"。
判据全部来自记忆系统已有的事实：演员被给了哪些记忆、必带 State 是什么、本场谁在。
"""

from __future__ import annotations

from .models import (
    ActionProposal,
    ActorBrief,
    Beat,
    Bible,
    GateReport,
    Violation,
    ViolationCode,
)


class ContinuityGate:
    def __init__(self, bible: Bible) -> None:
        self._known = set(bible.playable_names)

    def check(
        self, proposal: ActionProposal, brief: ActorBrief, beat: Beat
    ) -> GateReport:
        violations: list[Violation] = []

        if not proposal.intent:
            violations.append(
                Violation(ViolationCode.EMPTY_INTENT, "没有给出具体动作")
            )

        # 只能引用导演实际交给它的记忆。编号对不上就是凭空编造往事。
        for memory_id in proposal.cites:
            if memory_id not in brief.citable:
                violations.append(
                    Violation(
                        ViolationCode.UNCITABLE_MEMORY,
                        f"引用了不在可引用清单里的记忆 {memory_id}，想不起来就别提",
                    )
                )

        # 自述位置必须与必带 State 对得上。
        expected = brief.required_states.get("location")
        if expected and proposal.claimed_location and proposal.claimed_location != expected:
            violations.append(
                Violation(
                    ViolationCode.LOCATION_CONFLICT,
                    f"自述在 {proposal.claimed_location}，但记忆里此刻在 {expected}",
                )
            )

        # 只能对在场的人说话。
        #
        # 判据只覆盖"设定里的可上场角色"：对这些人说话，人必须在本场 cast 里。
        # 无名配角（守门兵、船家、路人）不在设定里，也不该在设定里——挡下它们
        # 只会让演员为了过闸门把配角写成主角。
        for target in proposal.addresses:
            if target == proposal.owner_id:
                continue
            if target in beat.cast:
                continue
            if target in self._known:
                violations.append(
                    Violation(
                        ViolationCode.ABSENT_CHARACTER,
                        f"对不在场的 {target} 说话；本场在场：{'、'.join(beat.cast)}",
                    )
                )

        return GateReport(tuple(violations))
