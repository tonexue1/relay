"""Shared bits for the dream eval harness. Not a memory engine."""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parent
GRAMMAR_PATH = ROOT / "grammar" / "triple.gbnf"
SAMPLES_EXTRACT = ROOT / "samples" / "extract.json"
SAMPLES_INJECT = ROOT / "samples" / "inject.json"
OUT_DIR = ROOT / "out"
MODELS_DIR = ROOT / "models"

# Same checkpoint the Android on-device provider downloads.
GGUF_NAME = "Qwen2.5-3B-Instruct-Q4_K_M.gguf"
GGUF_URL = (
    "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/"
    + GGUF_NAME
)
GGUF_SHA256 = "9c9f56a391a3abbd5b89d0245bf6106081bcc3173119d4229235dd9d23253f94"
GGUF_BYTES = 1_929_903_264

# Closed set. Chinese labels are display-only; store English p.
PREDICATES = (
    "allergic_to",
    "likes",
    "dislikes",
    "prefers",
    "diet",
    "lives_in",
    "work_location",
    "born_in",
    "works_at",
    "works_as",
    "alumni_of",
    "member_of",
    "skilled_in",
    "knows_language",
    "colleague_of",
    "friend_of",
    "family_of",
    "spouse_of",
    "parent_of",
    "child_of",
    "sibling_of",
    "has_pet",
    "named",
    "owns",
    "takes",
    "attends",
    "plans",
    "has_task",
    "work_years",
    "located_in",
)

PREDICATE_ZH = {
    "allergic_to": "过敏",
    "likes": "喜欢",
    "dislikes": "不喜欢",
    "prefers": "更倾向",
    "diet": "饮食",
    "lives_in": "住在",
    "work_location": "办公地",
    "born_in": "出生于",
    "works_at": "就职于",
    "works_as": "职位是",
    "alumni_of": "毕业于",
    "member_of": "属于",
    "skilled_in": "擅长",
    "knows_language": "会说",
    "colleague_of": "同事是",
    "friend_of": "朋友是",
    "family_of": "家人是",
    "spouse_of": "配偶是",
    "parent_of": "子女是",
    "child_of": "父母是",
    "sibling_of": "兄弟姐妹是",
    "has_pet": "养宠物",
    "named": "名叫",
    "owns": "拥有",
    "takes": "在服用",
    "attends": "参加",
    "plans": "打算",
    "has_task": "待办",
    "work_years": "工龄",
    "located_in": "位于",
}

# New edge supersedes old when src+p collide on a different o.
FUNCTIONAL_PREDICATES = {
    "lives_in",
    "work_location",
    "born_in",
    "works_at",
    "works_as",
    "spouse_of",
    "diet",
    "work_years",
}

# Few-shot + a chunk needs more than the 2048 micro-task window.
N_CTX = 4096
MAX_CHUNK_CHARS = 140
EXTRACT_MAX_TOKENS = 256
INJECT_MAX_TOKENS = 128

DEFAULT_ALIASES = {
    "美式咖啡": "美式",
    "坐地铁": "地铁",
    "花生酱": "花生",
    "花生米": "花生",
    "杭州市": "杭州",
    "离职": "跳槽",
    "换工作": "跳槽",
    "吃素": "素食",
    "素食主义": "素食",
    "我妈": "妈妈",
    "我爸": "爸爸",
    "功课": "作业",
    "两年了": "两年",
    "2年": "两年",
    "英文": "英语",
}

EXTRACT_SYSTEM = """你是知识抽取器，不是助理。只抽已经说出口的个人事实。
主语：说话的人用「用户」，其他人用姓名或称呼。不要抽「助理」实体。
named 只用于宠物名。过敏史「要报 X」= allergic_to X，不要写成 likes/dislikes。
宾语用短名称：地铁不是坐地铁，美式不是美式咖啡。
不要抽：机票、提醒、周末、过年、设备型号。技术能力用 skilled_in，不要 likes。
plans 可以是地点，也可以是意向：跳槽、休息。不要把下周三/今晚当宾语。
located_in 只用于地点→地点。work_location 是办公地，lives_in 是住地，born_in 是出生地。
家人优先 child_of/parent_of/spouse_of/sibling_of；说不清再用 family_of。
朋友用 friend_of，同事用 colleague_of。学校 alumni_of，组织 member_of。
素食/清真 = diet。拥有物 owns。参加的活动 attends。会的语言 knows_language。
想换工作、离职、说拜拜 = plans 跳槽。想歇一阵 = plans 休息。
作业/功课没做完、还要交X = has_task X。
工作N年了、工龄N年 = work_years N年（两年、三年），不要写成 works_at。
吃素/素食/清真 = diet；英语/日语 = knows_language，不要写成 skilled_in。
有个人事实就必须抽；空列表只用于纯闲聊。"""

# Held-out from samples/extract.json. Last shot is a positive extract (not empty).
FEWSHOT: list[dict[str, Any]] = [
    {
        "dialogue": "用户: 今天雨好大，随便聊聊，没什么要记的。\n助理: 那就聊。",
        "triples": [],
    },
    {
        "dialogue": "用户: 体检表过敏史把头孢报上去，小时候起过疹。\n助理: 记下头孢。",
        "triples": [{"s": "用户", "p": "allergic_to", "o": "头孢"}],
    },
    {
        "dialogue": "用户: 我爸住成都，过年我回去。机票还没买，提醒我周五。\n助理: 成都单独算。",
        "triples": [
            {"s": "用户", "p": "family_of", "o": "爸爸"},
            {"s": "爸爸", "p": "lives_in", "o": "成都"},
        ],
    },
    {
        "dialogue": "用户: 我在腾讯做设计师，最近在填一台 ThinkPad。\n助理: 设计师记下了。",
        "triples": [
            {"s": "用户", "p": "works_at", "o": "腾讯"},
            {"s": "用户", "p": "works_as", "o": "设计师"},
        ],
    },
    {
        "dialogue": "用户: 家里狗叫旺财。医生让我吃钙片，提醒我晚上。\n助理: 旺财、钙片。",
        "triples": [
            {"s": "用户", "p": "has_pet", "o": "狗"},
            {"s": "狗", "p": "named", "o": "旺财"},
            {"s": "用户", "p": "takes", "o": "钙片"},
        ],
    },
    {
        "dialogue": "用户: 咖啡我喝拿铁。通勤宁可坐地铁，不爱开车。下周去南京住鼓楼，机票还在犹豫。明天找李娜，她是我同事，比我熟 JNI。\n助理: 拿铁、地铁、南京鼓楼、李娜。",
        "triples": [
            {"s": "用户", "p": "likes", "o": "拿铁"},
            {"s": "用户", "p": "prefers", "o": "地铁"},
            {"s": "用户", "p": "dislikes", "o": "开车"},
            {"s": "用户", "p": "plans", "o": "南京"},
            {"s": "南京", "p": "located_in", "o": "鼓楼"},
            {"s": "用户", "p": "colleague_of", "o": "李娜"},
        ],
    },
    {
        "dialogue": "用户: 我打算离职，先歇两个月，别的先不谈。\n助理: 记下了。",
        "triples": [
            {"s": "用户", "p": "plans", "o": "跳槽"},
            {"s": "用户", "p": "plans", "o": "休息"},
        ],
    },
]

CHITCHAT_RE = re.compile(r"没什么要记|随便聊|开个玩笑")
PLAN_NOISE_RE = re.compile(r"机票|提醒|周末|过年|今晚|晚上|明天|下周|这周|下午|上午|有空|猫粮|清单|三点")
PETS = {"猫", "狗", "宠物"}
LANGUAGES = {"英语", "中文", "汉语", "日语", "法语", "德语", "韩语", "西班牙语"}
DIET_OBJECTS = {"吃素", "素食", "素食主义", "素", "清真"}
OTHER_SUBJECT_PREDICATES = {
    "named",
    "lives_in",
    "located_in",
    "born_in",
    "work_location",
    "family_of",
    "spouse_of",
    "parent_of",
    "child_of",
    "sibling_of",
    "friend_of",
    "likes",
    "dislikes",
    "prefers",
    "allergic_to",
    "diet",
    "takes",
    "has_task",
    "work_years",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def dump_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def grammar_text() -> str:
    return GRAMMAR_PATH.read_text(encoding="utf-8")


def check_grammar_predicates() -> None:
    listed = []
    for line in grammar_text().splitlines():
        if line.startswith("pred-name"):
            listed = re.findall(r'"([a-z_]+)"', line)
    missing = [p for p in PREDICATES if p not in listed]
    extra = [p for p in listed if p not in PREDICATES]
    if missing or extra:
        raise SystemExit(f"grammar predicates drift: missing={missing} extra={extra}")


def format_turns(turns: list[dict[str, str]]) -> str:
    lines = []
    for turn in turns:
        role = turn.get("role", "user")
        label = "用户" if role == "user" else "助理"
        lines.append(f"{label}: {turn['text'].strip()}")
    return "\n".join(lines)


def _split_sentences(text: str, max_chars: int) -> list[str]:
    parts = re.split(r"(?<=[。！？；\n])", text)
    out: list[str] = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if len(part) <= max_chars:
            out.append(part)
            continue
        bits = [b.strip() for b in re.split(r"(?<=[，、])", part) if b.strip()]
        buf: list[str] = []
        size = 0
        for bit in bits:
            if buf and size + len(bit) > max_chars:
                out.append("".join(buf))
                buf = [bit]
                size = len(bit)
            else:
                buf.append(bit)
                size += len(bit)
        if buf:
            out.append("".join(buf))
    return out


def chunk_turns(
    turns: list[dict[str, str]],
    max_chars: int = MAX_CHUNK_CHARS,
) -> list[str]:
    """Split by turn, then sentence. Code decides cuts, not the model."""
    chunks: list[str] = []
    buf: list[str] = []
    size = 0

    def flush() -> None:
        nonlocal buf, size
        if buf:
            chunks.append("\n".join(buf))
            buf = []
            size = 0

    for turn in turns:
        role = turn.get("role", "user")
        label = "用户" if role == "user" else "助理"
        text = turn["text"].strip()
        piece = f"{label}: {text}"
        if len(piece) <= max_chars:
            extra = len(piece) + (1 if buf else 0)
            if buf and size + extra > max_chars:
                flush()
            buf.append(piece)
            size += extra
            continue
        # Single turn longer than the budget: split sentences, keep the label.
        flush()
        sentences = _split_sentences(text, max_chars) or [text]
        sent_buf: list[str] = []
        sent_size = 0
        prefix = f"{label}: "
        for sent in sentences:
            add = len(sent) + (0 if sent_buf else len(prefix))
            if sent_buf and sent_size + add + 1 > max_chars:
                chunks.append(prefix + "".join(sent_buf))
                sent_buf = [sent]
                sent_size = len(prefix) + len(sent)
            else:
                sent_buf.append(sent)
                sent_size += add if sent_buf[:-1] else add
        if sent_buf:
            chunks.append(prefix + "".join(sent_buf))

    flush()
    return chunks or [format_turns(turns)]


def merged_aliases(extra: dict[str, str] | None = None) -> dict[str, str]:
    out = dict(DEFAULT_ALIASES)
    if extra:
        out.update(extra)
    return out


def normalize_text(text: str, aliases: dict[str, str] | None = None) -> str:
    value = unicodedata.normalize("NFKC", text).strip().lower()
    value = re.sub(r"\s+", "", value)
    aliases = merged_aliases(aliases)
    # Longest alias first so 花生酱 wins over 花生.
    for src, dst in sorted(aliases.items(), key=lambda kv: len(kv[0]), reverse=True):
        src_n = unicodedata.normalize("NFKC", src).strip().lower()
        dst_n = unicodedata.normalize("NFKC", dst).strip().lower()
        value = value.replace(src_n, dst_n)
    return value


def normalize_triple(triple: dict[str, str], aliases: dict[str, str] | None = None) -> tuple[str, str, str]:
    return (
        normalize_text(triple.get("s", ""), aliases),
        normalize_text(triple.get("p", "")),
        normalize_text(triple.get("o", ""), aliases),
    )


def triple_set(triples: Iterable[dict[str, str]], aliases: dict[str, str] | None = None) -> set[tuple[str, str, str]]:
    out: set[tuple[str, str, str]] = set()
    for triple in triples:
        key = normalize_triple(triple, aliases)
        if all(key):
            out.add(key)
    return out


def score_sets(
    gold: set[tuple[str, str, str]],
    pred: set[tuple[str, str, str]],
) -> dict[str, float | int]:
    tp = gold & pred
    fp = pred - gold
    fn = gold - pred
    precision = len(tp) / len(pred) if pred else 1.0
    recall = len(tp) / len(gold) if gold else 1.0
    return {
        "tp": len(tp),
        "fp": len(fp),
        "fn": len(fn),
        "precision": precision,
        "recall": recall,
    }


def extract_prompt(chunk: str, fewshot: bool = True) -> list[dict[str, str]]:
    rels = "、".join(PREDICATES)
    messages: list[dict[str, str]] = [
        {"role": "system", "content": EXTRACT_SYSTEM + "\n关系词表：" + rels},
    ]
    if fewshot:
        for ex in FEWSHOT:
            messages.append({"role": "user", "content": "对话：\n" + ex["dialogue"]})
            messages.append(
                {
                    "role": "assistant",
                    "content": json.dumps(
                        {"triples": ex["triples"]},
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                }
            )
    messages.append({"role": "user", "content": "对话：\n" + chunk})
    return messages


DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
DEEPSEEK_MODEL = "deepseek-chat"
CLOUD_MAX_CHUNK_CHARS = 4000
CLOUD_MAX_TOKENS = 512


def load_deepseek_key() -> str:
    for name in ("RELAY_DEEPSEEK_API_KEY", "DEEPSEEK_API_KEY"):
        env = os.environ.get(name)
        if env:
            return env.strip()
    props = ROOT.parent.parent / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("relay.deepseek.apiKey="):
                return line.split("=", 1)[1].strip()
    raise SystemExit(
        "No DeepSeek key. Set RELAY_DEEPSEEK_API_KEY or relay.deepseek.apiKey in local.properties"
    )


def _strip_json_fence(text: str) -> str:
    body = text.strip()
    if body.startswith("```"):
        body = re.sub(r"^```(?:json)?\s*", "", body)
        body = re.sub(r"\s*```$", "", body)
    return body.strip()


def complete_cloud(
    messages: list[dict[str, str]],
    *,
    max_tokens: int = CLOUD_MAX_TOKENS,
    temperature: float = 0.0,
    api_key: str | None = None,
) -> str:
    key = api_key or load_deepseek_key()
    chat = [dict(m) for m in messages]
    if chat:
        chat[-1]["content"] = chat[-1]["content"] + "\n\n只输出 JSON。"
    payload = {
        "model": DEEPSEEK_MODEL,
        "messages": chat,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "response_format": {"type": "json_object"},
    }
    req = urllib.request.Request(
        DEEPSEEK_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:400]
        raise SystemExit(f"DeepSeek HTTP {exc.code}: {detail}") from exc
    content = data["choices"][0]["message"]["content"] or ""
    return _strip_json_fence(content)


def facts_block(facts: list[dict[str, str]]) -> str:
    lines = ["已记住的事实（必须遵守）："]
    for fact in facts:
        lines.append(f"- {fact['s']} {fact['p']} {fact['o']}")
    return "\n".join(lines)


def inject_messages(question: str, facts: list[dict[str, str]] | None) -> list[dict[str, str]]:
    if facts:
        system = (
            "你是手机上的个人助理。根据已记住的事实回答，简短直接。"
            "事实与问题冲突时以事实为准。不知道就说不知道。\n\n"
            + facts_block(facts)
        )
    else:
        system = (
            "你是手机上的个人助理。只根据当前问题回答，简短直接。"
            "没有依据的个人事实不要编。不知道就说不知道。"
        )
    return [
        {"role": "system", "content": system},
        {"role": "user", "content": question},
    ]


def answer_hits(text: str, needles: list[str]) -> bool:
    body = normalize_text(text)
    return any(normalize_text(n) in body for n in needles)


def resolve_gguf() -> Path:
    env = os.environ.get("RELAY_GGUF")
    if env:
        path = Path(env).expanduser()
        if not path.is_file():
            raise SystemExit(f"RELAY_GGUF not found: {path}")
        return path
    local = MODELS_DIR / GGUF_NAME
    if local.is_file():
        return local
    raise SystemExit(
        "No GGUF. Run: python download.py\n"
        f"or set RELAY_GGUF=/path/to/{GGUF_NAME}"
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_gguf(dest: Path | None = None) -> Path:
    dest = dest or (MODELS_DIR / GGUF_NAME)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.is_file() and dest.stat().st_size == GGUF_BYTES:
        actual = _sha256(dest)
        if actual == GGUF_SHA256:
            print(f"already have {dest}")
            return dest
        print("checksum mismatch, re-downloading")
        dest.unlink()
    tmp = dest.with_suffix(dest.suffix + ".partial")
    print(f"downloading {GGUF_URL}\n -> {dest}")
    with urllib.request.urlopen(GGUF_URL) as resp, tmp.open("wb") as out:
        total = int(resp.headers.get("Content-Length") or 0)
        done = 0
        while True:
            block = resp.read(1024 * 1024)
            if not block:
                break
            out.write(block)
            done += len(block)
            if total:
                pct = 100.0 * done / total
                print(f"\r{done / 1e6:.0f}/{total / 1e6:.0f} MB ({pct:.0f}%)", end="", file=sys.stderr)
    print(file=sys.stderr)
    actual = _sha256(tmp)
    if actual != GGUF_SHA256:
        tmp.unlink(missing_ok=True)
        raise SystemExit(f"sha256 mismatch: {actual} != {GGUF_SHA256}")
    tmp.replace(dest)
    return dest


def load_llm(n_ctx: int = N_CTX):
    try:
        from llama_cpp import Llama, LlamaGrammar
    except ImportError as exc:
        raise SystemExit("pip install -r requirements.txt") from exc
    path = resolve_gguf()
    n_gpu = int(os.environ.get("RELAY_N_GPU_LAYERS", "-1" if sys.platform == "darwin" else "0"))
    print(f"loading {path} n_ctx={n_ctx} n_gpu_layers={n_gpu}", file=sys.stderr)
    llm = Llama(
        model_path=str(path),
        n_ctx=n_ctx,
        n_gpu_layers=n_gpu,
        n_batch=512,
        verbose=False,
    )
    return llm, LlamaGrammar


def complete_chat(
    llm: Any,
    messages: list[dict[str, str]],
    *,
    grammar: Any | None = None,
    max_tokens: int,
    temperature: float,
) -> str:
    kwargs: dict[str, Any] = {
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    if grammar is not None:
        kwargs["grammar"] = grammar
    result = llm.create_chat_completion(**kwargs)
    return result["choices"][0]["message"]["content"] or ""


def mentioned(name: str, chunk: str) -> bool:
    if not chunk or name == "用户":
        return True
    hay = normalize_text(chunk)
    needle = normalize_text(name)
    if needle and needle in hay:
        return True
    if name in {"妈妈", "妈"} and ("妈" in chunk or "母亲" in chunk):
        return True
    if name in {"爸爸", "爸"} and ("爸" in chunk or "父亲" in chunk):
        return True
    return False


def looks_like_chitchat(chunk: str) -> bool:
    return bool(CHITCHAT_RE.search(chunk))


def looks_like_year_span(value: str) -> bool:
    return bool(re.fullmatch(r"(两|三|四|五|六|七|八|九|十|\d+)年", value))


def _ascii_heavy(text: str) -> bool:
    if not text:
        return False
    ascii_n = sum(1 for c in text if ord(c) < 128 and c.isalnum())
    return ascii_n >= 3 and ascii_n / max(len(text), 1) >= 0.5


def clean_triples(triples: list[dict[str, str]], chunk: str = "") -> list[dict[str, str]]:
    """Code-side filters. 3B is the sensor; this is the cortex."""
    kept: list[dict[str, str]] = []
    for triple in triples:
        s, p, o = triple["s"].strip(), triple["p"].strip(), triple["o"].strip()
        if s == "助理" or o == "助理":
            continue
        if o.endswith("过敏") and p in {"likes", "dislikes", "prefers", "allergic_to"}:
            stem = o[: -len("过敏")].strip()
            if stem:
                o, p = stem, "allergic_to"
        if s == "用户" and p == "located_in":
            p = "lives_in"
        if p in {"colleague_of", "friend_of", "spouse_of", "sibling_of"} and o == "用户" and s != "用户":
            s, o = "用户", s
        if p == "parent_of" and o == "用户" and s != "用户":
            s, p, o = "用户", "child_of", s
        if p == "child_of" and o == "用户" and s != "用户":
            s, p, o = "用户", "parent_of", s
        if o in DIET_OBJECTS or o.endswith("清真"):
            p = "diet"
            o = "清真" if "清真" in o else "素食"
        if p in {"likes", "prefers"} and o[:1] in "坐喝吃":
            o = o[1:].strip()
        if p == "likes" and o.endswith("咖啡") and len(o) > 2:
            o = o[: -len("咖啡")].strip()
        if o.endswith("没做完") or o.endswith("没写完") or o.endswith("没交"):
            stem = o.removesuffix("没做完").removesuffix("没写完").removesuffix("没交").strip()
            if stem:
                o = stem
            p = "has_task"
        if p in {"plans", "likes"} and (o == "作业" or o == "功课" or o.endswith("作业")) and (
            "没做" in chunk or "没写" in chunk or "没交" in chunk or not chunk
        ):
            p = "has_task"
        s = normalize_text(s)
        o = normalize_text(o)
        if looks_like_year_span(o) and (
            p in {"works_at", "works_as", "work_years", "plans", "likes"} or "工作" in chunk or not chunk
        ):
            p = "work_years"
        if o in LANGUAGES and p in {"skilled_in", "knows_language", "likes"}:
            p = "knows_language"
        if not s or not o or s == o:
            continue
        if p == "named" and o in PETS and s not in PETS:
            s, o = o, s
        if p == "named" and s not in PETS:
            continue
        if p == "named" and o in PETS:
            continue
        if p == "has_pet" and (s != "用户" or o not in PETS):
            continue
        if s != "用户" and p not in OTHER_SUBJECT_PREDICATES:
            continue
        if p == "plans" and PLAN_NOISE_RE.search(o):
            continue
        if p in {"likes", "prefers", "dislikes", "takes"} and _ascii_heavy(o):
            continue
        if p in {"likes", "prefers", "dislikes"} and len(o) > 4:
            continue
        if p in {"located_in", "lives_in"} and len(o) <= 1:
            continue
        if chunk and not (mentioned(s, chunk) and mentioned(o, chunk)):
            continue
        kept.append({"s": s, "p": p, "o": o})

    allergens = {item["o"] for item in kept if item["p"] == "allergic_to"}
    out: list[dict[str, str]] = []
    seen: set[tuple[str, str, str]] = set()
    for item in kept:
        if item["p"] in {"likes", "prefers", "dislikes"}:
            if any(item["o"] == a or item["o"].startswith(a) or a.startswith(item["o"]) for a in allergens):
                continue
        key = (item["s"], item["p"], item["o"])
        if key in seen:
            continue
        seen.add(key)
        out.append(item)
    pets = {item["s"] for item in out if item["p"] == "named" and item["s"] in PETS}
    for pet in pets:
        implied = {"s": "用户", "p": "has_pet", "o": pet}
        key = (implied["s"], implied["p"], implied["o"])
        if key not in seen:
            seen.add(key)
            out.append(implied)
    return out


def parse_triples(raw: str, chunk: str = "") -> tuple[bool, list[dict[str, str]]]:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return False, []
    triples = data.get("triples") if isinstance(data, dict) else None
    if not isinstance(triples, list):
        return False, []
    clean: list[dict[str, str]] = []
    for item in triples:
        if not isinstance(item, dict):
            return False, []
        s, p, o = item.get("s"), item.get("p"), item.get("o")
        if not all(isinstance(x, str) and x.strip() for x in (s, p, o)):
            return False, []
        if p not in PREDICATES:
            return False, []
        clean.append({"s": s.strip(), "p": p.strip(), "o": o.strip()})
    return True, clean_triples(clean, chunk)
