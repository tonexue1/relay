from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common


def main() -> None:
    parser = argparse.ArgumentParser(description="S1: chunk → GBNF extract")
    parser.add_argument("--samples", type=Path, default=common.SAMPLES_EXTRACT)
    parser.add_argument("--out", type=Path, default=common.OUT_DIR / "extract.jsonl")
    parser.add_argument("--ids", nargs="*", help="only these sample ids")
    parser.add_argument("--max-chars", type=int, default=common.MAX_CHUNK_CHARS)
    parser.add_argument("--dry-run", action="store_true", help="print chunks, no model")
    parser.add_argument("--no-fewshot", action="store_true")
    parser.add_argument("--no-retry", action="store_true")
    args = parser.parse_args()

    common.check_grammar_predicates()
    samples = common.load_json(args.samples)
    if args.ids:
        want = set(args.ids)
        samples = [s for s in samples if s["id"] in want]
    if not samples:
        raise SystemExit("no samples")

    jobs = []
    for sample in samples:
        chunks = common.chunk_turns(sample["turns"], max_chars=args.max_chars)
        for i, chunk in enumerate(chunks):
            jobs.append({"id": sample["id"], "chunk_index": i, "chunk_count": len(chunks), "text": chunk})

    print(f"{len(samples)} samples → {len(jobs)} chunks", flush=True)
    if args.dry_run:
        for job in jobs:
            print(f"\n## {job['id']} [{job['chunk_index'] + 1}/{job['chunk_count']}] ({len(job['text'])} chars)")
            print(job["text"])
        return

    llm, LlamaGrammar = common.load_llm()
    grammar = LlamaGrammar.from_string(common.grammar_text())
    args.out.parent.mkdir(parents=True, exist_ok=True)
    rows = []
    with args.out.open("w", encoding="utf-8") as fh:
        for i, job in enumerate(jobs, 1):
            t0 = time.perf_counter()
            raw = common.complete_chat(
                llm,
                common.extract_prompt(job["text"], fewshot=not args.no_fewshot),
                grammar=grammar,
                max_tokens=common.EXTRACT_MAX_TOKENS,
                temperature=0.0,
            )
            valid, triples = common.parse_triples(raw, job["text"])
            retried = False
            if (
                not args.no_retry
                and valid
                and not triples
                and not common.looks_like_chitchat(job["text"])
            ):
                retried = True
                messages = common.extract_prompt(job["text"], fewshot=not args.no_fewshot)
                messages[-1]["content"] += "\n\n这段含个人事实，禁止空列表。宾语用短名称。"
                raw = common.complete_chat(
                    llm,
                    messages,
                    grammar=grammar,
                    max_tokens=common.EXTRACT_MAX_TOKENS,
                    temperature=0.0,
                )
                valid, triples = common.parse_triples(raw, job["text"])
            ms = int((time.perf_counter() - t0) * 1000)
            row = {
                **job,
                "raw": raw,
                "valid": valid,
                "triples": triples,
                "retried": retried,
                "ms": ms,
            }
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            rows.append(row)
            n = len(triples)
            flag = "ok" if valid else "INVALID"
            extra = " retry" if retried else ""
            print(f"[{i}/{len(jobs)}] {job['id']}#{job['chunk_index']} {flag} {n} triples {ms}ms{extra}", flush=True)

    valid_n = sum(1 for r in rows if r["valid"])
    print(f"wrote {args.out}  schema_valid={valid_n}/{len(rows)}")


if __name__ == "__main__":
    main()
