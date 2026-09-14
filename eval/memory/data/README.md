# Memory public eval data (local only)

Downloaded artifacts. Gitignored. Do not commit.

检索第一闸用手编叠仓 [../retrieval/README.md](../retrieval/README.md)，不要把下面的 QA 集直接当 `searchMemories` 金标。公开集是对话 session + 答案句；要改编需从 `has_answer` 轮抽出 fact 句并人工过 id，最多先做 20 题。优先下 **oracle**（只有 evidence session）。

## LongMemEval (cleaned)

Source: [xiaowu0162/longmemeval-cleaned](https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned)

| File | Role |
|---|---|
| `longmemeval/longmemeval_oracle.json` | evidence sessions only (upper bound) |
| `longmemeval/longmemeval_s_cleaned.json` | ~115k-token haystack |

500 questions each. Types: `single-session-user`, `single-session-assistant`, `single-session-preference`, `knowledge-update`, `temporal-reasoning`, `multi-session`. IDs ending in `_abs` are abstention.

Not downloaded: `longmemeval_m_cleaned.json` (~2.6 GB, ~500 sessions/item).

```bash
mkdir -p eval/memory/data/longmemeval
cd eval/memory/data/longmemeval
curl -L --fail -O https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_oracle.json
curl -L --fail -O https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_s_cleaned.json
# 可选大 haystack
curl -L --fail -O https://hf-mirror.com/datasets/xiaowu0162/longmemeval-cleaned/resolve/main/longmemeval_m_cleaned.json
```

## LoCoMo

Source: [snap-research/locomo](https://github.com/snap-research/locomo) `data/locomo10.json`
License: CC BY-NC 4.0

| File | Role |
|---|---|
| `locomo/locomo10.json` | 10 long conversations + QA |

```bash
mkdir -p eval/memory/data/locomo
curl -L --fail -o eval/memory/data/locomo/locomo10.json \
  https://raw.githubusercontent.com/snap-research/locomo/main/data/locomo10.json
```
