"""离线计分卡：不调 LLM 的回归度量。

定位是"这一稿比上一稿好还是坏"。单测能保证编排不坏，但保证不了文本质量的走向——
改一句 prompt 之后文风是不是飘了、章标题是不是开始撞车、叙述者是不是又开始加戏，
这些只有量出来才看得见。

多数指标是【相对信号】而不是绝对真值，阈值只用于粗判。

其中三项是别人做不了的：死人开口、道具串门、隔空对话。因为它们要拿正文去比对
账本里【某个故事时刻的】事实，而不是比对一份摊平的当前状态——只有点时读取的
账本答得上"第 2 章那会儿这只镯子在谁手里"。
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

from crew.models import Bible, WORLD_OWNER
from crew import clock
from crew.style import StyleCard
from ledger.runtime import MemoryRuntime
from ledger.types import ClockDomain, ClockStamp, StateReadRequest, StateSelector

_PUNCT = re.compile(r"[\s\W_]+", re.UNICODE)
_SPLIT = re.compile(r"[\s、，,。；;：:！!？?—\-·.()（）「」『』【】/|]+")
_SENTENCE = re.compile(r"[。！？!?\n]+")
#: 各式引号里的台词。判断"人在不在场"时要先剥掉——对白里提到一个人，
#: 说明的是别人在谈论他，不是他本人在场。
_DIALOGUE = re.compile(r"[「『“\"']([^」』”\"']*)[」』”\"']")
_CJK_ONLY = re.compile(r"^[\u4e00-\u9fff]+$")
_ONOMATOPOEIA = re.compile(r"[轰砰噗咚嗡铮咔嘭霍]")

#: 从风味卡 lexicon 抽词时要滤掉的说明性用词——它们描述技法，本身不是风味词。
_LEXICON_STOP = frozenset(
    {
        "偏爱", "意象", "一类", "自然", "融入", "点到", "即止", "切忌", "通篇",
        "堆砌", "成套", "套话", "但要", "常以", "善用", "相间", "而不", "以及",
        "或者", "各种", "一些", "这种", "那种", "要有", "不写", "忌用", "能省",
        "尤其", "落到", "实处", "具体", "读者", "人物", "叙述", "句子", "段落",
    }
)

#: 通用"网文腔"负向词。命中越多，说明文本越滑向放之四海皆准的套话。
GENERIC_TERMS = (
    "心如刀绞", "五味杂陈", "百感交集", "不禁", "不由得", "仿佛", "似乎",
    "浑身一震", "瞳孔一缩", "深吸一口气", "苦涩地笑了笑", "眼中闪过一丝",
)


def normalize(text: str) -> str:
    return _PUNCT.sub("", unicodedata.normalize("NFKC", text or "").lower())


@dataclass(frozen=True)
class Coverage:
    total: int = 0
    hit: int = 0
    missed: tuple[str, ...] = ()

    @property
    def ratio(self) -> float:
        return self.hit / self.total if self.total else 0.0


def term_coverage(text: str, terms: tuple[str, ...]) -> Coverage:
    """词表覆盖率：直接数每个词有没有出现过。

    刻意不做分词切片。词表本来就该是短专名，切了反而会把"手""门""针"
    这类单字风味词全丢掉——白描卡的词表整个是单字。
    """
    uniq = tuple(dict.fromkeys(t.strip() for t in terms if t and t.strip()))
    missed = tuple(t for t in uniq if t not in text)
    return Coverage(len(uniq), len(uniq) - len(missed), missed)


def lexicon_terms(style: StyleCard | None) -> tuple[str, ...]:
    """风味词表。卡自己声明的优先，没声明才退回从描述里抽。"""
    if style is None:
        return ()
    return style.lexicon_terms or extract_lexicon_terms(style.lexicon)


def strip_dialogue(text: str) -> str:
    return _DIALOGUE.sub("", text or "")


def extract_lexicon_terms(lexicon: str) -> tuple[str, ...]:
    """兜底：从风味卡的自然语描述里抠候选词。

    只对"苍茫、亘古、纪元"这种顿号分隔的词表管用；描述写成流畅句子时会抽出
    "偏爱苍茫"这类半截词。所以是兜底，卡应该显式声明 lexicon_terms。
    """
    found = []
    for raw in _SPLIT.split(lexicon or ""):
        term = raw.strip()
        if not 2 <= len(term) <= 4:
            continue
        if not _CJK_ONLY.match(term) or term in _LEXICON_STOP:
            continue
        if term not in found:
            found.append(term)
    return tuple(found)


def short_sentence_ratio(text: str, max_len: int = 15) -> float:
    sentences = [s.strip() for s in _SENTENCE.split(text or "") if s.strip()]
    if not sentences:
        return 0.0
    return sum(1 for s in sentences if len(s) <= max_len) / len(sentences)


def count_generic(text: str) -> tuple[tuple[str, int], ...]:
    hits = [(term, (text or "").count(term)) for term in GENERIC_TERMS]
    return tuple((term, n) for term, n in hits if n)


def find_duplicate_titles(titles: tuple[tuple[int, str], ...]) -> tuple[tuple[int, int, str], ...]:
    """归一化后与在前某章撞车的标题。返回 (本章号, 撞上的章号, 标题)。"""
    seen: dict[str, int] = {}
    dupes = []
    for chapter, title in titles:
        key = normalize(title)
        if not key:
            continue
        if key in seen:
            dupes.append((chapter, seen[key], title))
        else:
            seen[key] = chapter
    return tuple(dupes)


# ---------------------------------------------------------------------------
# 拿账本对正文——这几项要点时读取，摊平的当前状态答不上来
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ContinuityHit:
    chapter: int
    kind: str
    detail: str


def _states_at(runtime: MemoryRuntime, bible: Bible, at: int) -> dict[str, dict]:
    selectors = tuple(StateSelector(s.field_id) for s in bible.state_fields)
    out = {}
    for owner in bible.playable_names:
        read = runtime.get_states(
            StateReadRequest(
                space_id=bible.space_id,
                owner_id=owner,
                selectors=selectors,
                at=ClockStamp(ClockDomain.STORY_TIME, at),
            )
        )
        out[owner] = {fid: item.payload.get("value") for fid, item in read.present.items()}
    return out


def check_continuity(
    runtime: MemoryRuntime, bible: Bible, proses: tuple[tuple[int, str], ...]
) -> tuple[ContinuityHit, ...]:
    """拿每一章的正文去对账本的事实。

    三类命中：
    * 死人开口——某人在正文里现身，但账本说本章开始前他已经死了；
    * 道具串门——一件东西同时记在两个人名下，而它出现在正文里；
    * 隔空同框——两人同时现身，但账本说他们这一章从头到尾都在两个地方。

    两条降噪规则，都是拿真产出试出来的：
    * 先剥掉对白再找人名。周娘子说「那沈砚要是真心」，沈砚人在洛阳，
      按名字直接匹配就会报一条"隔空同框"，而那只是被人提起。
    * 隔空同框要求章首章末都不同地。有人在本章赶路的话，章首必然两地——
      那正是这一章要写的事，不是硬伤。
    """
    hits: list[ContinuityHit] = []
    for chapter, prose in proses:
        if not prose.strip():
            continue
        narration = strip_dialogue(prose)
        opening = _states_at(runtime, bible, clock.story_time(chapter, 0))
        closing = _states_at(runtime, bible, clock.chapter_end(chapter))
        present = [name for name in bible.playable_names if name in narration]

        for name in present:
            if opening.get(name, {}).get("status") in bible.death_states:
                hits.append(
                    ContinuityHit(chapter, "死人开口", f"{name} 已故却在正文里现身")
                )

        for field_id in bible.prop_fields:
            owners_of: dict[str, list[str]] = {}
            for owner, values in opening.items():
                for item in values.get(field_id) or []:
                    owners_of.setdefault(str(item), []).append(owner)
            for item, holders in owners_of.items():
                if len(holders) > 1 and item in prose:
                    hits.append(
                        ContinuityHit(
                            chapter,
                            "道具串门",
                            f"「{item}」同时记在 {'、'.join(holders)} 名下",
                        )
                    )

        for i, left in enumerate(present):
            for right in present[i + 1 :]:
                if _apart(opening, left, right) and _apart(closing, left, right):
                    here = closing.get(left, {}).get("location")
                    there = closing.get(right, {}).get("location")
                    hits.append(
                        ContinuityHit(
                            chapter,
                            "隔空同框",
                            f"{left}（{here}）与 {right}（{there}）自始至终两地却同现",
                        )
                    )
    return tuple(hits)


def _apart(states: dict[str, dict], left: str, right: str) -> bool:
    here = states.get(left, {}).get("location")
    there = states.get(right, {}).get("location")
    return bool(here and there and here != there)


# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Scorecard:
    title: str
    chapters: int
    style_label: str
    duplicate_titles: tuple[tuple[int, int, str], ...] = ()
    worldview: Coverage = field(default_factory=Coverage)
    lexicon: Coverage = field(default_factory=Coverage)
    short_ratio: float = 0.0
    onomatopoeia: int = 0
    generic: tuple[tuple[str, int], ...] = ()
    continuity: tuple[ContinuityHit, ...] = ()
    scores: dict[str, float] = field(default_factory=dict)
    overall: float = 0.0


def world_entries(bible: Bible) -> tuple[str, ...]:
    """世界标志词。

    刻意不拿 world_rules 来数：那是成句的规则（"临安到洛阳需走一个多月"），
    整句拿去 in 判断永远不命中，切碎又全是"需走""一个"这种噪音。
    也不拿 affiliation：那是身份描述（"临安绣坊东家"），正文不会照着写。
    用人名、地名和设定里显式声明的 world_terms——短、专有、命中即有意义。
    """
    entries = list(bible.playable_names)
    for spec in bible.characters:
        location = spec.initial_state.get("location")
        if location:
            entries.append(str(location))
    entries.extend(bible.world_terms)
    return tuple(dict.fromkeys(e for e in entries if e))


def grade(
    bible: Bible,
    proses: tuple[tuple[int, str], ...],
    titles: tuple[tuple[int, str], ...] = (),
    style: StyleCard | None = None,
    continuity: tuple[ContinuityHit, ...] = (),
) -> Scorecard:
    full = "\n".join(text for _, text in proses)
    total = max(1, len(proses))

    dupes = find_duplicate_titles(titles)
    worldview = term_coverage(full, world_entries(bible))
    lex_terms = lexicon_terms(style)
    lexicon = term_coverage(full, lex_terms)
    generic = count_generic(full)
    generic_total = sum(n for _, n in generic)

    scores = {
        # 标题撞车按章数摊。
        "标题不重复": _clamp(1 - len(dupes) / total),
        "世界观落地": _clamp(worldview.ratio),
        "风味贴合": _clamp(lexicon.ratio) if lex_terms else 0.0,
        # 每章容许一次套话，超了线性扣。
        "去套话": _clamp(1 - generic_total / max(4, total)),
        # 连贯性命中零容忍：一条就扣掉四分之一。
        "账本连贯": _clamp(1 - len(continuity) / 4),
    }
    weights = {
        "账本连贯": 0.35,
        "世界观落地": 0.2,
        "风味贴合": 0.2,
        "标题不重复": 0.15,
        "去套话": 0.1,
    }
    overall = sum(scores[k] * w for k, w in weights.items())

    return Scorecard(
        title=bible.title,
        chapters=len(proses),
        style_label=style.label if style else "（未挂风味卡）",
        duplicate_titles=dupes,
        worldview=worldview,
        lexicon=lexicon,
        short_ratio=short_sentence_ratio(full),
        onomatopoeia=len(_ONOMATOPOEIA.findall(full)),
        generic=generic,
        continuity=continuity,
        scores=scores,
        overall=overall,
    )


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def render_report(card: Scorecard) -> str:
    lines = [
        f"《{card.title}》计分卡　{card.chapters} 章　风味：{card.style_label}",
        f"总分 {card.overall:.2f}",
        "",
    ]
    for name, score in card.scores.items():
        lines.append(f"  {name:<6} {score:.2f}")
    lines.append("")
    lines.append(
        f"  世界观 {card.worldview.hit}/{card.worldview.total}"
        f"　风味词 {card.lexicon.hit}/{card.lexicon.total}"
        f"　短句占比 {card.short_ratio:.0%}"
        f"　象声词 {card.onomatopoeia}"
    )
    if card.duplicate_titles:
        lines.append("\n  标题撞车：")
        lines.extend(
            f"    第 {n} 章《{t}》撞上第 {m} 章" for n, m, t in card.duplicate_titles
        )
    if card.generic:
        top = "、".join(f"{term}×{n}" for term, n in card.generic)
        lines.append(f"\n  套话命中：{top}")
    if card.continuity:
        lines.append("\n  账本连贯性命中：")
        lines.extend(
            f"    第 {h.chapter} 章 [{h.kind}] {h.detail}" for h in card.continuity
        )
    if card.worldview.missed:
        lines.append(f"\n  世界观未落地：{'、'.join(card.worldview.missed[:6])}")
    return "\n".join(lines)
