"""反射循环：审校挑刺 → 叙述者定向修订 → 复审，直到无硬伤或用满轮数。

抽成独立模块是为了让正式管线和离线测试跑的是同一段逻辑，测试才真看得住线上行为。

全程 best-effort：任一轮里审校或修订抛异常（超时、限流、网络抖动），不外抛、不崩，
记一条带 error 的轮次就收手，返回目前最好的一版。一次超时不该拖垮整章。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

from .context import SceneAnchors
from .models import NarratableScene, ReflectRound
from .narrator import Narrator
from .reviewer import Reviewer

#: 定向修订本该与原文长度相当。缩到这个比例以下多半是截断或答非所问。
MIN_KEEP_RATIO = 0.5


@dataclass(frozen=True)
class ReflectResult:
    prose: str
    rounds: tuple[ReflectRound, ...] = ()
    #: 是否以"审校无硬伤"收尾。用满轮数仍有硬伤则为 False。
    passed: bool = True


def accept_revision(prev: str, nxt: str, min_keep_ratio: float = MIN_KEEP_RATIO) -> bool:
    """防截断护栏：这份修订稿能不能采纳。

    修订稿为空、与原文一字不差、或相比原文骤缩到不足阈值，多半是模型截断、
    答非所问或误删大段。宁可弃用保留上一版，也不让烂稿盖掉好稿。
    """
    prev_text = (prev or "").strip()
    next_text = (nxt or "").strip()
    if not next_text or next_text == prev_text:
        return False
    if not prev_text:
        return True
    return len(next_text) >= len(prev_text) * min_keep_ratio


def reflect_review(
    reviewer: Reviewer,
    narrator: Narrator,
    prose: str,
    scenes: tuple[NarratableScene, ...],
    anchors: SceneAnchors | None = None,
    *,
    goal: str = "",
    max_rounds: int = 2,
    on_round: Callable[[ReflectRound], None] | None = None,
    min_keep_ratio: float = MIN_KEEP_RATIO,
) -> ReflectResult:
    rounds: list[ReflectRound] = []
    current = prose

    if max_rounds <= 0 or not current.strip():
        return ReflectResult(current)

    def note(record: ReflectRound) -> None:
        rounds.append(record)
        if on_round is not None:
            on_round(record)

    for index in range(1, max_rounds + 1):
        try:
            critique = reviewer.critique(current, scenes, anchors, goal)
        except Exception as error:  # noqa: BLE001 - best-effort，审不动就保留当前稿
            note(ReflectRound(index, 0, False, False, _one_line(error)))
            break

        if critique.ok:
            note(ReflectRound(index, 0, critique.parsed, False))
            return ReflectResult(current, tuple(rounds), passed=True)

        try:
            candidate = narrator.revise(current, critique.issues, anchors)
        except Exception as error:  # noqa: BLE001 - 改不动就保留已采纳的修订
            note(
                ReflectRound(
                    index, len(critique.issues), critique.parsed, False, _one_line(error)
                )
            )
            break

        accepted = accept_revision(current, candidate, min_keep_ratio)
        note(ReflectRound(index, len(critique.issues), critique.parsed, accepted))
        if not accepted:
            # 改不出能用的稿，再审一轮也是同样结果。收手。
            break
        current = candidate

    return ReflectResult(current, tuple(rounds), passed=False)


def _one_line(error: Exception) -> str:
    return str(error).split("\n")[0]
