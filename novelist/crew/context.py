"""对照锚点：叙述和审校拿来判对错的事实清单。

这些全部从账本查出来，不是让模型"回忆"。审校能不能审出硬伤，取决于它手里
有没有一份可信的基准——没有基准的审校只能凭语感挑刺，那还不如不审。

四类锚点对应四类常犯的错：
* 上一章原文尾 → 章间复述、时点接不上
* 已故名册     → 死人开口
* 道具账本     → 同一件东西同时在两个人手里
* 各人所在地   → 隔着半座城对话

刻意用逐字原文而不是检索摘要来做承接：摘要丢掉的正是"上一段最后怎么收的"。

承接是章间的，不是场间的。正文一章落笔一次，章内不存在两段各自生成的接缝——
那个缝原本是"一场一次落笔"自己造出来的，成文改成章级之后就没了。
"""

from __future__ import annotations

from dataclasses import dataclass

from ledger.runtime import MemoryRuntime
from ledger.types import (
    ClockDomain,
    ClockStamp,
    MemoryKind,
    RecallRequest,
    StateReadRequest,
    StateSelector,
)

from . import clock
from .models import Bible, WORLD_OWNER
from .policy import ROLE_PROSE

#: 上一章正文取多少字做承接。太多会诱使叙述者复述，太少接不上语气。
TAIL_CHARS = 400


@dataclass(frozen=True)
class SceneAnchors:
    """一章的对照锚点。空字段表示"账本里没有这项事实"，不渲染。"""

    previous_tail: str = ""
    dead_roster: str = ""
    prop_ledger: str = ""
    whereabouts: str = ""
    achievements: str = ""

    def render(self, *, for_revision: bool = False) -> str:
        """渲染成 prompt 里的锚点块。

        修订时要额外标"判对错用，勿抄入正文"：不标的话，执笔人会把锚点里提到、
        但正文原本没有的道具和称呼一路搬进正文，定向修订就变成了扩写。
        """
        caveat = "（判对错用，勿抄入正文）" if for_revision else ""
        blocks = []
        if self.previous_tail:
            label = (
                f"【上一章结尾{caveat or '（只用来无缝承接语气与时点，切勿照抄或复述）'}】"
            )
            blocks.append(f"{label}\n{self.previous_tail}")
        if self.dead_roster:
            blocks.append(f"【已故人物{caveat or '（只能被回忆或提及，不得写成活人）'}】\n{self.dead_roster}")
        if self.prop_ledger:
            blocks.append(f"【关键物品归属{caveat or '（以此为准，不要改写谁拿着什么）'}】\n{self.prop_ledger}")
        if self.whereabouts:
            blocks.append(f"【各人此刻所在地{caveat}】\n{self.whereabouts}")
        if self.achievements:
            blocks.append(f"【已发生·勿重复{caveat}】\n{self.achievements}")
        return "\n\n".join(blocks)

    def __bool__(self) -> bool:
        return bool(
            self.previous_tail
            or self.dead_roster
            or self.prop_ledger
            or self.whereabouts
            or self.achievements
        )


class AnchorBuilder:
    def __init__(self, runtime: MemoryRuntime, bible: Bible) -> None:
        self._runtime = runtime
        self._bible = bible

    def build(self, chapter: int, scene_index: int = 0) -> SceneAnchors:
        at = clock.story_time(chapter, scene_index)
        return SceneAnchors(
            previous_tail=self._previous_tail(at),
            dead_roster=self._dead_roster(at),
            prop_ledger=self._prop_ledger(at),
            whereabouts=self._whereabouts(at),
            achievements=self._achievements(at),
        )

    # ------------------------------------------------------------------

    def _previous_tail(self, at: int) -> str:
        rows = self._runtime.recent_raw_events(
            self._bible.space_id, WORLD_OWNER, before=at, limit=1, role=ROLE_PROSE
        )
        if not rows:
            return ""
        content = (rows[0]["content"] or "").strip()
        return content[-TAIL_CHARS:] if len(content) > TAIL_CHARS else content

    def _states(self, at: int) -> dict[str, dict[str, object]]:
        selectors = tuple(StateSelector(s.field_id) for s in self._bible.state_fields)
        found: dict[str, dict[str, object]] = {}
        for owner_id in self._bible.playable_names:
            read = self._runtime.get_states(
                StateReadRequest(
                    space_id=self._bible.space_id,
                    owner_id=owner_id,
                    selectors=selectors,
                    at=ClockStamp(ClockDomain.STORY_TIME, at),
                )
            )
            found[owner_id] = {
                fid: item.payload.get("value") for fid, item in read.present.items()
            }
        return found

    def _dead_roster(self, at: int) -> str:
        if not self._bible.death_states:
            return ""
        dead = self._bible.death_states
        lines = [
            f"- {owner}（{values.get('status')}）"
            for owner, values in self._states(at).items()
            if values.get("status") in dead
        ]
        return "\n".join(lines)

    def _prop_ledger(self, at: int) -> str:
        fields = self._bible.prop_fields
        if not fields:
            return ""
        lines = []
        for owner, values in self._states(at).items():
            for field_id in fields:
                items = values.get(field_id)
                if isinstance(items, list) and items:
                    lines.append(f"- {owner} 的{field_id}：{'、'.join(str(i) for i in items)}")
        return "\n".join(lines)

    def _whereabouts(self, at: int) -> str:
        lines = [
            f"- {owner}：{values['location']}"
            for owner, values in self._states(at).items()
            if values.get("location")
        ]
        return "\n".join(lines)

    def _achievements(self, at: int, limit: int = 12) -> str:
        result = self._runtime.recall(
            RecallRequest(
                space_id=self._bible.space_id,
                owner_id=WORLD_OWNER,
                query=self._bible.premise,
                at=ClockStamp(ClockDomain.STORY_TIME, at),
                kinds=frozenset({MemoryKind.EPISODE}),
                budget_chars=1_600,
                limit_per_channel=limit,
            )
        )
        ordered = sorted(result.selected, key=lambda s: s.business_time or 0)
        return "\n".join(f"- {s.text}" for s in ordered[-limit:])
