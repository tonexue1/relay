"""章节标题。

放在章末而不是章首：标题要扣住这一章真正写出来的东西，而章首那会儿只有 beat 目标，
还没有正文。规划阶段起的标题往往和成文对不上。

跟已有标题一起送进去，是为了避免全书标题互相撞车——这是离线评分卡里"连续性"那
一轴最常见的扣分项。
"""

from __future__ import annotations

from llm.provider import LLMProvider, Role

_SYSTEM = """你给一章小说起标题。

要求：
1. 一个短语，三到八个字。不要"第X章"之类编号，不要"标题："之类前缀。
2. 必须扣住这一章特有的情节、人物或意象，别人看了标题能想起是哪一章。
3. 不许与"已用过的标题"里任何一个雷同或近义。
4. 不要用套路词（风云、抉择、宿命、启程、终章之类）。
5. 只输出这个短语本身，不要引号、不要解释。"""

_USER = """《{title}》第 {chapter} 章正文：

{prose}

已用过的标题（不得雷同）：
{used}

给这一章起一个标题。"""

#: 标题最长几个字。超了就是模型没听话，截断不如丢掉重来，这里直接判无效。
MAX_TITLE_CHARS = 12


class Titler:
    def __init__(self, llm: LLMProvider) -> None:
        self._llm = llm

    def title_for(
        self, book: str, chapter: int, prose: str, used: tuple[str, ...] = ()
    ) -> str:
        """起标题。起不出来就返回空串，由调用方退回"第 N 章"。"""
        if not prose.strip():
            return ""
        raw = self._llm.complete_text(
            Role.TITLER,
            _SYSTEM,
            _USER.format(
                title=book,
                chapter=chapter,
                prose=prose[:2_000],
                used="\n".join(f"- {t}" for t in used) or "-（无）",
            ),
            temperature=0.8,
        )
        return _clean(raw, used)


def _clean(raw: str, used: tuple[str, ...]) -> str:
    text = (raw or "").strip().strip("《》「」\"'“”‘’ 。，、").splitlines()
    if not text:
        return ""
    candidate = text[0].strip().strip("《》「」\"'“”‘’ 。，、")
    if not candidate or len(candidate) > MAX_TITLE_CHARS:
        return ""
    if candidate in used:
        return ""
    return candidate
