from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common


def load_preds(path: Path) -> dict[str, dict]:
    by_id: dict[str, dict] = {}
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            bucket = by_id.setdefault(
                row["id"],
                {"valid": True, "triples": [], "chunks": 0, "invalid_chunks": 0, "ms": 0},
            )
            bucket["chunks"] += 1
            bucket["ms"] += row.get("ms", 0)
            if not row.get("valid"):
                bucket["valid"] = False
                bucket["invalid_chunks"] += 1
            bucket["triples"].extend(row.get("triples") or [])
    return by_id


def self_test() -> None:
    common.check_grammar_predicates()
    turns = [
        {"role": "user", "text": "我花生过敏。"},
        {"role": "assistant", "text": "记下了。"},
        {"role": "user", "text": "另外我住杭州。"},
    ]
    chunks = common.chunk_turns(turns, max_chars=20)
    assert len(chunks) >= 2, chunks
    gold = common.triple_set(
        [{"s": "用户", "p": "allergic_to", "o": "花生酱"}],
        aliases={"花生酱": "花生"},
    )
    pred = common.triple_set([{"s": "用户", "p": "allergic_to", "o": "花生"}])
    got = common.score_sets(gold, pred)
    assert got["tp"] == 1 and got["fp"] == 0 and got["fn"] == 0, got
    valid, triples = common.parse_triples('{"triples":[{"s":"用户","p":"likes","o":"咖啡"}]}')
    assert valid and triples[0]["p"] == "likes"
    invalid, _ = common.parse_triples('{"triples":[{"s":"用户","p":"invented","o":"x"}]}')
    assert not invalid
    cleaned = common.clean_triples(
        [
            {"s": "用户", "p": "dislikes", "o": "青霉素过敏"},
            {"s": "用户", "p": "likes", "o": "青霉素"},
            {"s": "用户", "p": "plans", "o": "机票"},
            {"s": "用户", "p": "likes", "o": "JNI"},
            {"s": "用户", "p": "prefers", "o": "坐地铁"},
            {"s": "助理", "p": "works_at", "o": "阿里"},
            {"s": "王磊", "p": "colleague_of", "o": "用户"},
            {"s": "杭州", "p": "located_in", "o": "鼓楼"},
        ],
        chunk="用户: 我青霉素过敏。通勤宁可坐地铁。王磊是我同事。住杭州。",
    )
    keys = {(t["s"], t["p"], t["o"]) for t in cleaned}
    assert ("用户", "allergic_to", "青霉素") in keys
    assert ("用户", "likes", "青霉素") not in keys
    assert ("用户", "plans", "机票") not in keys
    assert ("用户", "likes", "JNI") not in keys
    assert ("用户", "prefers", "地铁") in keys
    assert ("用户", "colleague_of", "王磊") in keys
    assert ("杭州", "located_in", "鼓楼") not in keys
    pets = common.clean_triples(
        [{"s": "芝麻", "p": "named", "o": "猫"}],
        chunk="家里那只猫叫芝麻",
    )
    pet_keys = {(t["s"], t["p"], t["o"]) for t in pets}
    assert ("猫", "named", "芝麻") in pet_keys
    assert ("用户", "has_pet", "猫") in pet_keys
    print("self-test ok")


def main() -> None:
    parser = argparse.ArgumentParser(description="S1: precision / recall vs gold")
    parser.add_argument("--gold", type=Path, default=common.SAMPLES_EXTRACT)
    parser.add_argument("--pred", type=Path, default=common.OUT_DIR / "extract.jsonl")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.pred.is_file():
        raise SystemExit(f"missing predictions: {args.pred}  (run extract.py first)")

    gold_samples = common.load_json(args.gold)
    preds = load_preds(args.pred)
    rows = []
    tp = fp = fn = 0
    valid_chunks = 0
    total_chunks = 0
    for sample in gold_samples:
        sid = sample["id"]
        aliases = sample.get("aliases") or {}
        gold = common.triple_set(sample.get("gold") or [], aliases)
        pred_row = preds.get(sid)
        if pred_row is None:
            print(f"missing pred for {sid}")
            pred = set()
            valid = False
            chunks = 0
        else:
            pred = common.triple_set(pred_row["triples"], aliases)
            valid = pred_row["valid"]
            chunks = pred_row["chunks"]
            total_chunks += chunks
            valid_chunks += chunks - pred_row["invalid_chunks"]
        stats = common.score_sets(gold, pred)
        tp += stats["tp"]
        fp += stats["fp"]
        fn += stats["fn"]
        rows.append((sid, valid, chunks, stats, gold, pred))

    precision = tp / (tp + fp) if (tp + fp) else 1.0
    recall = tp / (tp + fn) if (tp + fn) else 1.0
    schema = valid_chunks / total_chunks if total_chunks else 0.0
    print(f"{'id':<28} {'ok':<5} {'P':>5} {'R':>5}  gold pred")
    for sid, valid, chunks, stats, gold, pred in rows:
        print(
            f"{sid:<28} {str(valid):<5} {stats['precision']:5.2f} {stats['recall']:5.2f}  "
            f"{len(gold):4d} {len(pred):4d}  ({chunks} chunks)"
        )
        extra = pred - gold
        missing = gold - pred
        if extra:
            print(f"  fp {sorted(extra)}")
        if missing:
            print(f"  fn {sorted(missing)}")
    print()
    print(f"schema_valid: {valid_chunks}/{total_chunks} ({schema:.0%})")
    print(f"micro precision: {precision:.2%}  ({tp}/{tp + fp})")
    print(f"micro recall:    {recall:.2%}  ({tp}/{tp + fn})")
    p_gate, r_gate = 0.70, 0.50
    kill = 0.50
    if schema < 1.0:
        gate = "FAIL schema (S0/S1: GBNF must be 100% valid)"
    elif precision < kill:
        gate = "FAIL precision <50% — 负复利, 改路线"
    elif precision >= p_gate and recall >= r_gate:
        gate = "PASS S1 gate (P≥70% and R≥50%)"
    else:
        gate = f"HOLD below gate (need P≥{p_gate:.0%} R≥{r_gate:.0%})"
    print(f"S1: {gate}")
    if len(gold_samples) < 30:
        print(f"note: {len(gold_samples)} seed samples; spike asks for 30–50 real dialogues.")


if __name__ == "__main__":
    main()
