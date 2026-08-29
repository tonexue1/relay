"""检索文本归一与渲染。

中文分词不依赖外部库：NFKC 归一后把 CJK 连续段展开成 2-gram，
非 CJK 段按词切。写入和查询用同一套展开，保证 FTS5 对中文可用。
"""

from __future__ import annotations

import hashlib
import unicodedata

_CJK_RANGES = (
    (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF),
    (0xF900, 0xFAFF),
    (0x20000, 0x2FA1F),
    (0x3040, 0x30FF),
    (0xAC00, 0xD7AF),
)

def _is_cjk(ch: str) -> bool:
    code = ord(ch)
    return any(lo <= code <= hi for lo, hi in _CJK_RANGES)


def normalize(text: str) -> str:
    return unicodedata.normalize("NFKC", text).casefold()


def tokenize(text: str) -> list[str]:
    """展开成 FTS 词元：CJK 段出 2-gram（单字段落出单字），其余按词切。"""
    normalized = normalize(text)
    tokens: list[str] = []
    i = 0
    size = len(normalized)

    while i < size:
        ch = normalized[i]
        if _is_cjk(ch):
            j = i
            while j < size and _is_cjk(normalized[j]):
                j += 1
            run = normalized[i:j]
            if len(run) == 1:
                tokens.append(run)
            else:
                tokens.extend(run[k : k + 2] for k in range(len(run) - 1))
            i = j
        elif ch.isalnum():
            j = i
            while j < size and normalized[j].isalnum() and not _is_cjk(normalized[j]):
                j += 1
            tokens.append(normalized[i:j])
            i = j
        else:
            i += 1

    return tokens


def index_text(text: str) -> str:
    """写入 memory_fts.ngram_text 的形式。"""
    return " ".join(tokenize(text))


def match_query(text: str) -> str:
    """FTS5 MATCH 表达式。OR 语义配合 bm25 排序，命中越多排越前。"""
    tokens = {t for t in tokenize(text) if len(t) >= 1}
    if not tokens:
        return ""
    quoted = [f'"{t}"' for t in sorted(tokens)]
    return " OR ".join(quoted)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------
# 渲染器：payload → 检索文本。确定性，带版本。
# --------------------------------------------------------------------------

RENDERER_VERSION = "1"


def render_state(field_id: str, payload: dict) -> tuple[str, str, str]:
    """返回 (text, renderer_id, renderer_version)。"""
    value = payload.get("value", payload)
    if isinstance(value, list):
        body = "、".join(str(v) for v in value)
    elif isinstance(value, dict):
        body = "；".join(f"{k}={v}" for k, v in sorted(value.items()))
    else:
        body = str(value)
    return f"{field_id}：{body}", "state.kv", RENDERER_VERSION


def render_episode(summary: str) -> tuple[str, str, str]:
    return summary.strip(), "episode.summary", RENDERER_VERSION


def render_reflection(memory_key: str, summary: str) -> tuple[str, str, str]:
    return f"{memory_key}：{summary.strip()}", "reflection.kv", RENDERER_VERSION
