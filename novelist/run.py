"""小说生成器 CLI。

    python run.py --offline                    离线跑通，不花钱，验编排
    python run.py --chapters 3                 接真模型写三章
    python run.py --style gulong               换风味卡
    python run.py --inspect                    看记忆仓里都存了什么
    python run.py --verify                     离线计分卡，不调模型
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))

import verify as verifier
from crew.bible_io import dump_bible_summary, load_bible
from crew.clock import chapter_end, story_time
from crew.models import WORLD_OWNER
from crew.style import available_cards, load_style_card
from crew.studio import Studio, StudioConfig
from ledger.runtime import MemoryRuntime
from ledger.types import (
    ClockDomain,
    ClockStamp,
    MemoryKind,
    RecallRequest,
    StateReadRequest,
    StateSelector,
)
from llm.fake import build_fake_provider
from llm.provider import OpenAIProvider


def _load_env() -> None:
    try:
        from dotenv import load_dotenv
    except ImportError:
        return
    for candidate in (ROOT / ".env", ROOT.parent / ".env"):
        if candidate.exists():
            load_dotenv(candidate)
            return


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="多 Agent 小说生成器")
    parser.add_argument("--bible", default=str(ROOT / "bible" / "linwan.yaml"))
    # 默认留空，好区分"用户明确指定了路径"和"用的是默认路径"。
    # 离线跑走单独的目录，理由见 _resolve_paths。
    parser.add_argument("--db", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--chapters", type=int, default=None)
    parser.add_argument("--beats", type=int, default=2, help="每章 beat 数")
    parser.add_argument(
        "--length", type=int, default=220, help="每个动作的目标字数"
    )
    parser.add_argument(
        "--style", default=None, help=f"风味卡，覆盖设定里的；可选 {list(available_cards())}"
    )
    parser.add_argument(
        "--intensity", default=None, choices=("light", "medium", "strong")
    )
    parser.add_argument(
        "--review", type=int, default=None, help="成文后审校轮数，0 关掉"
    )
    parser.add_argument(
        "--offline", action="store_true", help="用假模型跑，只验编排不验文笔"
    )
    parser.add_argument(
        "--inspect", action="store_true", help="只看记忆仓内容，不生成"
    )
    parser.add_argument(
        "--verify", action="store_true", help="只跑离线计分卡，不调模型"
    )
    parser.add_argument(
        "--check", action="store_true", help="只验 key、模型名和 JSON 模式是否可用"
    )
    parser.add_argument("--trace", action="store_true", help="把 LLM 调用流水落盘")
    parser.add_argument(
        "--trace-report",
        action="store_true",
        help="把已落盘的 trace.jsonl 读成人话：耗时分布、截断和空产出",
    )
    parser.add_argument("--grep", default="", help="配合 --trace-report，只列匹配的调用")
    parser.add_argument("--fresh", action="store_true", help="先删掉旧库")
    return parser


def check_llm() -> int:
    """一次最便宜的往返，确认 key、base_url、模型名和 JSON 模式都对。"""
    import os

    from llm.provider import LLMError, Role

    base_url = os.getenv("NOVELIST_BASE_URL") or "(默认 OpenAI)"
    print(f"端点：{base_url}")

    try:
        llm = OpenAIProvider()
    except LLMError as exc:
        print(f"✗ {exc}")
        return 1

    failed = 0
    for role in (
        Role.PLANNER,
        Role.DIRECTOR,
        Role.ACTOR,
        Role.CRITIC,
        Role.NARRATOR,
        Role.REVIEWER,
        Role.TITLER,
        Role.CHRONICLER,
    ):
        model = llm._model_for(role)
        try:
            data = llm.complete_json(
                role,
                "你只输出 JSON，不要解释。",
                '返回这个 json：{"ok": true}',
                temperature=0,
            )
        except Exception as exc:
            print(f"✗ {role:<11} {model:<20} {type(exc).__name__}: {exc}")
            failed += 1
            continue
        flag = "✓" if data.get("ok") is True else "?"
        elapsed = llm.transcript.by_role(role)[-1].elapsed_ms
        print(f"{flag} {role:<11} {model:<20} {elapsed} ms  {data}")

    if failed:
        print(f"\n{failed} 个角色不可用。先修这些再跑生成。")
        return 1
    print("\n全部可用。")
    return 0


def inspect(runtime: MemoryRuntime, bible, chapters: int) -> None:
    print(dump_bible_summary(bible))
    print()

    health = runtime.index_health(bible.space_id)
    print(
        f"索引：FTS {health.fts_rows} 行，向量 {health.embedding_rows} 行，"
        f"待建 {health.pending_jobs}，向量可用={health.vector_available}"
    )
    print()

    print("== 世界线（客观） ==")
    world = runtime.recall(
        RecallRequest(
            space_id=bible.space_id,
            owner_id=WORLD_OWNER,
            query=bible.premise,
            at=ClockStamp(ClockDomain.STORY_TIME, chapter_end(chapters)),
            kinds=frozenset({MemoryKind.EPISODE}),
            budget_chars=4_000,
            limit_per_channel=50,
        )
    )
    for item in sorted(world.selected, key=lambda s: s.business_time or 0):
        print(f"  [{item.business_time}] {item.text}")
    print()

    for name in bible.playable_names:
        print(f"== {name} ==")
        states = runtime.get_states(
            StateReadRequest(
                space_id=bible.space_id,
                owner_id=name,
                selectors=tuple(
                    StateSelector(seed.field_id) for seed in bible.state_fields
                ),
                at=ClockStamp(ClockDomain.STORY_TIME, chapter_end(chapters)),
            )
        )
        for field_id, item in sorted(states.present.items()):
            print(f"  {field_id} = {item.payload.get('value')}")
        for issue in states.issues:
            print(f"  {issue.field_id}: {issue.status.value}")

        own = runtime.recall(
            RecallRequest(
                space_id=bible.space_id,
                owner_id=name,
                query=bible.premise,
                at=ClockStamp(ClockDomain.STORY_TIME, chapter_end(chapters)),
                kinds=frozenset({MemoryKind.EPISODE, MemoryKind.REFLECTION}),
                budget_chars=3_000,
                limit_per_channel=50,
            )
        )
        for item in sorted(own.selected, key=lambda s: s.business_time or 0):
            print(f"  [{item.business_time}] {item.kind.value} {item.text}")
        print()


_TITLE_LINE = "# 第 {n} 章"
_TITLED_LINE = "# 第 {n} 章　{title}"


def _read_chapters(out_dir: Path) -> tuple[tuple[int, str, str], ...]:
    """回读已产出的章节，拆成 (章号, 标题, 正文)。计分卡的输入。"""
    found = []
    for path in sorted(out_dir.glob("ch*.md")):
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        header = lines[0] if lines else ""
        body = "\n".join(lines[1:]).strip()
        number = int("".join(c for c in path.stem if c.isdigit()) or 0)
        title = ""
        if header.startswith("#") and "　" in header:
            title = header.split("　", 1)[1].strip()
        found.append((number, title, body))
    return tuple(found)


def run_verify(runtime: MemoryRuntime, bible, out_dir: Path) -> int:
    chapters = _read_chapters(out_dir)
    if not chapters:
        print(f"{out_dir} 下没有章节文件，先跑一次生成。")
        return 1
    proses = tuple((n, body) for n, _, body in chapters)
    titles = tuple((n, title) for n, title, _ in chapters if title)
    style = load_style_card(bible.style_card) if bible.style_card else None
    card = verifier.grade(
        bible,
        proses,
        titles,
        style,
        continuity=verifier.check_continuity(runtime, bible, proses),
    )
    print(verifier.render_report(card))
    (out_dir / "scorecard.md").write_text(
        verifier.render_report(card), encoding="utf-8"
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    _load_env()

    if args.check:
        return check_llm()

    db_path, out_dir = _resolve_paths(args)

    # 读流水不需要设定也不需要库，放在最前面，别被 --fresh 之类的副作用挡住。
    if args.trace_report:
        return run_trace_report(out_dir, args.grep)

    bible = load_bible(args.bible)
    if args.style:
        bible = dataclasses.replace(bible, style_card=args.style)
    if args.intensity:
        bible = dataclasses.replace(bible, style_intensity=args.intensity)
    chapters = args.chapters or bible.total_chapters

    out_dir.mkdir(parents=True, exist_ok=True)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if args.fresh and db_path.exists():
        db_path.unlink()

    runtime = MemoryRuntime(db_path)
    try:
        if args.inspect:
            inspect(runtime, bible, chapters)
            return 0

        if args.verify:
            return run_verify(runtime, bible, out_dir)

        if args.offline:
            llm = build_fake_provider(bible.playable_names, bible.total_chapters)
            print("离线模式：用假模型验编排，产出不代表文笔。\n")
        else:
            llm = OpenAIProvider()

        config = StudioConfig(
            beats_per_chapter=args.beats, chars_per_action=args.length
        )
        if args.review is not None:
            config.max_review_rounds = args.review

        studio = Studio(
            runtime=runtime,
            llm=llm,
            bible=bible,
            config=config,
            report=lambda message: print(f"  · {message}"),
        )
        if studio.style:
            print(f"风味：{studio.style.label}（{bible.style_intensity}）\n")

        outline = studio.prepare()

        for chapter in range(1, chapters + 1):
            print(f"\n=== 第 {chapter} 章 ===")
            result = studio.write_chapter(chapter, outline)
            if not result.prose:
                print("  （本章没有产出正文）")
                continue
            header = (
                _TITLED_LINE.format(n=chapter, title=result.title)
                if result.title
                else _TITLE_LINE.format(n=chapter)
            )
            path = out_dir / f"ch{chapter:02d}.md"
            path.write_text(f"{header}\n\n{result.prose}\n", encoding="utf-8")
            print(f"  → {path}")
            for note in result.rejected_proposals:
                print(f"  ! 被拒：{note}")

        if args.trace:
            _dump_trace(llm, out_dir)

        print(f"\n完成。看记忆仓：python run.py --inspect --db {db_path}")
        print(f"打分：python run.py --verify --db {db_path} --out {out_dir}")
        return 0
    finally:
        runtime.close()


def _resolve_paths(args) -> tuple[Path, Path]:
    """决定库和产出目录。

    离线跑默认写 out/offline/，不跟真跑共用目录。假模型的产出是烟雾测试的副产品，
    真跑的产出是成品；共用一个目录的话，一次 `--offline --fresh` 就会把辛苦跑出来
    的章节冲成"林晚把手里的东西放到桌上"这种占位文本——而且没有任何提示。

    显式给了 --out / --db 就照办，包括离线跑：想复现问题时需要能指到同一个库。
    """
    default_out = ROOT / "out" / "offline" if args.offline else ROOT / "out"
    out_dir = Path(args.out) if args.out else default_out
    db_path = Path(args.db) if args.db else out_dir / "novel.db"
    return db_path, out_dir


def read_trace(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def render_trace_report(calls: list[dict], grep: str = "") -> str:
    """把流水读成人话。

    trace.jsonl 是一次调用一行、单行几千字符的机器格式，肉眼没法读。而排查
    真跑问题几乎每次都要问同样几件事：谁慢、谁贵、谁被截断、谁返回了空。
    """
    if not calls:
        return "流水是空的。跑的时候加 --trace 才会落盘。"

    lines = [f"{len(calls)} 次调用，合计 {sum(c['elapsed_ms'] for c in calls) / 1000:.0f} 秒", ""]

    by_role: dict[str, list[dict]] = {}
    for call in calls:
        by_role.setdefault(call["role"], []).append(call)
    for role, group in sorted(by_role.items(), key=lambda kv: -sum(c["elapsed_ms"] for c in kv[1])):
        ms = sum(c["elapsed_ms"] for c in group)
        lines.append(f"  {role:<11} {len(group):>3} 次  {ms / 1000:>6.0f} 秒  均 {ms / len(group) / 1000:>5.1f} 秒")

    # 这两类是静默失效的信号，不单独拎出来就只会表现成"模型解析不出"。
    truncated = [c for c in calls if c.get("finish_reason") == "length"]
    empty = [c for c in calls if not c["response"].strip()]
    lines.append("")
    if truncated:
        lines.append(f"  ⚠ {len(truncated)} 次被 max_tokens 截断：" + "、".join(
            sorted({c["role"] for c in truncated})
        ) + "　→ 调 NOVELIST_MAX_TOKENS_<ROLE>")
    if empty:
        lines.append(f"  ⚠ {len(empty)} 次返回空产出：" + "、".join(sorted({c["role"] for c in empty})))
    if not truncated and not empty:
        lines.append("  没有截断，没有空产出。")

    lines.extend(["", f"{'#':>3}  {'角色':<11} {'耗时':>8}  {'响应':>6}  收尾"])
    for i, call in enumerate(calls, 1):
        if grep and grep not in call["role"] and grep not in call["response"]:
            continue
        mark = "" if call.get("finish_reason") in ("stop", "") else f"  ←{call['finish_reason']}"
        lines.append(
            f"{i:>3}  {call['role']:<11} {call['elapsed_ms'] / 1000:>7.1f}s  "
            f"{len(call['response']):>5}字{mark}"
        )
    return "\n".join(lines)


def run_trace_report(out_dir: Path, grep: str = "") -> int:
    path = out_dir / "trace.jsonl"
    if not path.exists():
        print(f"没有 {path}。跑的时候加 --trace 才会落盘。")
        return 1
    print(render_trace_report(read_trace(path), grep))
    return 0


def _dump_trace(llm, out_dir: Path) -> None:
    """把 LLM 流水落盘。出了问题要能翻出来是哪一环在胡说。"""
    transcript = getattr(llm, "transcript", None)
    if transcript is None:
        return
    path = out_dir / "trace.jsonl"
    with path.open("w", encoding="utf-8") as handle:
        for call in transcript.calls:
            handle.write(
                json.dumps(dataclasses.asdict(call), ensure_ascii=False) + "\n"
            )
    print(f"  → {path}（{len(transcript.calls)} 次调用，{transcript.total_ms()} ms）")


if __name__ == "__main__":
    raise SystemExit(main())
