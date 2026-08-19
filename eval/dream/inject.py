from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common


def judge(answer: str, sample: dict) -> bool:
    if sample.get("reject") and common.answer_hits(answer, sample["reject"]) and not common.answer_hits(
        answer, sample.get("accept") or []
    ):
        return False
    return common.answer_hits(answer, sample.get("accept") or [])


def main() -> None:
    parser = argparse.ArgumentParser(description="S2: same question ± injected facts")
    parser.add_argument("--samples", type=Path, default=common.SAMPLES_INJECT)
    parser.add_argument("--out", type=Path, default=common.OUT_DIR / "inject.jsonl")
    parser.add_argument("--ids", nargs="*")
    args = parser.parse_args()

    samples = common.load_json(args.samples)
    if args.ids:
        want = set(args.ids)
        samples = [s for s in samples if s["id"] in want]

    llm, _ = common.load_llm()
    args.out.parent.mkdir(parents=True, exist_ok=True)

    rows = []
    with args.out.open("w", encoding="utf-8") as fh:
        for i, sample in enumerate(samples, 1):
            variants = [("baseline", None), ("injected", sample.get("facts") or [])]
            answers = {}
            for label, facts in variants:
                t0 = time.perf_counter()
                text = common.complete_chat(
                    llm,
                    common.inject_messages(sample["question"], facts),
                    max_tokens=common.INJECT_MAX_TOKENS,
                    temperature=0.0,
                )
                ms = int((time.perf_counter() - t0) * 1000)
                ok = judge(text, sample)
                answers[label] = {"text": text, "ok": ok, "ms": ms}
                print(f"[{i}/{len(samples)}] {sample['id']} {label} {'HIT' if ok else 'miss'} {ms}ms", flush=True)
                print(f"    {text.replace(chr(10), ' / ')[:160]}", flush=True)
            row = {
                "id": sample["id"],
                "kind": sample.get("kind", "memory"),
                "question": sample["question"],
                "baseline": answers["baseline"],
                "injected": answers["injected"],
            }
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
            fh.flush()
            rows.append(row)

    def rate(kind: str | None, field: str) -> tuple[int, int]:
        picked = [r for r in rows if kind is None or r["kind"] == kind]
        hits = sum(1 for r in picked if r[field]["ok"])
        return hits, len(picked)

    print()
    for kind in ("memory", "control"):
        b_hit, b_n = rate(kind, "baseline")
        i_hit, i_n = rate(kind, "injected")
        if b_n == 0:
            continue
        print(f"{kind:8} baseline {b_hit}/{b_n} ({b_hit / b_n:.0%})  injected {i_hit}/{i_n} ({i_hit / i_n:.0%})")

    b_hit, b_n = rate("memory", "baseline")
    i_hit, i_n = rate("memory", "injected")
    if i_n == 0:
        return
    delta = i_hit / i_n - (b_hit / b_n if b_n else 0)
    print(f"memory delta: {delta:+.0%}")
    if i_hit / i_n >= 0.80 and b_hit / b_n <= 0.40:
        print("S2: PASS (injected ≥80% and baseline ≤40%)")
    elif delta >= 0.40 and i_hit / i_n >= 0.70:
        print("S2: PASS-ish (clear win, a bit under the 80/20 example)")
    else:
        print("S2: FAIL / HOLD — 注入没有明显赢, 价值命题未过闸")
    if len([s for s in samples if s.get("kind", "memory") == "memory"]) < 20:
        print("note: seed set is small; spike asks for 20–30 memory questions.")


if __name__ == "__main__":
    main()
