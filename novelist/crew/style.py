"""风味卡：可插拔的文风技能包。

以前文风只有 Bible 里一句 `style`，而且只有叙述者看得到。结果是规划的节奏、导演
的场面调度、叙述的笔调各走各的——文风只在最后一步生效，前面两步已经把戏搭成了
另一种气质。

风味卡把文风拆成七个可分别注入的面，按需要投给三层：
* 规划看 hook 和 setpiece，决定章末留什么钩子、大场面怎么顶起来
* 导演看 direction.scene，决定场面的格局和取景
* 叙述看全部，决定落笔的节奏、词汇、腔调

刻意不放示范段落，只放抽象技法：喂样例会让模型往样例的具体情节上靠。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

STYLES_DIR = Path(__file__).resolve().parent.parent / "styles"

#: 强度只调"多大程度上按卡走"，不改卡的内容。
INTENSITIES = ("light", "medium", "strong")

_INTENSITY_HINT = {
    "light": "以上风味只作底色，淡淡透出来即可，不要抢戏。",
    "medium": "以上风味要贯彻到叙述、旁白与描写里，但不必句句紧扣。",
    "strong": "以上风味是本书的标识，每一段都要能认出是这个笔调。",
}


@dataclass(frozen=True)
class StyleCard:
    card_id: str
    label: str
    tagline: str = ""
    rhythm: str = ""
    lexicon: str = ""
    #: 供离线计分卡数命中的风味词。从 lexicon 那段散文里抽词只能抽出"偏爱苍茫"
    #: 这种半截词，所以让卡自己报准数。留空则退回抽词。
    lexicon_terms: tuple[str, ...] = ()
    voice: str = ""
    setpiece: str = ""
    hook: str = ""
    avoid: str = ""
    #: 给导演的场面调度指引。
    direction_scene: str = ""
    #: 只在第 1 章用的开篇运镜指引。
    direction_opening: str = ""

    # ------------------------------------------------------------------
    # 三层注入
    # ------------------------------------------------------------------

    def for_planner(self) -> str:
        parts = [f"本书风味：{self.label}——{self.tagline}" if self.tagline else ""]
        if self.setpiece:
            parts.append(f"大场面的顶法：{self.setpiece}")
        if self.hook:
            parts.append(f"章末钩子：{self.hook}")
        return "\n".join(p for p in parts if p)

    def for_director(self) -> str:
        parts = [f"本书风味：{self.label}"]
        if self.direction_scene:
            parts.append(f"场面调度：{self.direction_scene}")
        if self.avoid:
            parts.append(f"忌：{self.avoid}")
        return "\n".join(parts)

    def for_narrator(self, intensity: str = "medium", *, opening: bool = False) -> str:
        parts = [f"本书叙述风味：{self.label}"]
        for label, value in (
            ("总体", self.tagline),
            ("节奏", self.rhythm),
            ("用词", self.lexicon),
            ("腔调", self.voice),
            ("场面", self.setpiece),
            ("收束", self.hook),
            ("忌", self.avoid),
        ):
            if value:
                parts.append(f"{label}：{value}")
        if opening and self.direction_opening:
            parts.append(f"开篇起势：{self.direction_opening}")
        hint = _INTENSITY_HINT.get(intensity, _INTENSITY_HINT["medium"])
        parts.append(hint)
        return "\n".join(parts)


def load_style_card(card_id: str, styles_dir: Path | str | None = None) -> StyleCard:
    root = Path(styles_dir) if styles_dir else STYLES_DIR
    path = root / card_id / "card.json"
    if not path.exists():
        available = ", ".join(sorted(p.name for p in root.iterdir() if p.is_dir())) or "（无）"
        raise ValueError(f"没有风味卡 {card_id}。可用：{available}")
    data = json.loads(path.read_text(encoding="utf-8"))
    direction = data.get("direction") or {}
    return StyleCard(
        card_id=data.get("id", card_id),
        label=data.get("label", card_id),
        tagline=data.get("tagline", ""),
        rhythm=data.get("rhythm", ""),
        lexicon=data.get("lexicon", ""),
        lexicon_terms=tuple(data.get("lexicon_terms", [])),
        voice=data.get("voice", ""),
        setpiece=data.get("setpiece", ""),
        hook=data.get("hook", ""),
        avoid=data.get("avoid", ""),
        direction_scene=direction.get("scene", ""),
        direction_opening=direction.get("opening", ""),
    )


def available_cards(styles_dir: Path | str | None = None) -> tuple[str, ...]:
    root = Path(styles_dir) if styles_dir else STYLES_DIR
    if not root.exists():
        return ()
    return tuple(sorted(p.name for p in root.iterdir() if (p / "card.json").exists()))
