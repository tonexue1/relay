# Retrieval eval · stacked facts

第一闸只打 `searchMemories`：fact 已预置，不经过模型、不跑 `remember`。

公开集（LongMemEval / LoCoMo）当第二层，改编方法见 [data/README.md](../data/README.md)。抽取另桌。

## 语料

[stacked/](./stacked/) 同一 `(space=assistant, owner=user)` 叠仓：

| 文件 | 规模 |
|---|---|
| `facts.json` | 100 条。80 当前 + 10 对 supersede |
| `queries.json` | 48 条。lexical / paraphrase / update / none / distractor |

金标是 fact id，不是答案句子。

## 网格

冻结向量（`queryVector=null`），扫：

- 字面：`coverage`（默认）vs `bm25`（可见集上 min-max 到 0–1）
- 阈值：0 / 0.3 / 0.5（打在混合分 0.7 lexical + 0.3 recency 上）
- K：3 / 5 / 10

共 18 档。助手默认检索已切到评测较优档：`bm25`、阈值 0.5、K=3（`SearchMemories` 默认值）。网格仍显式扫全表，不受默认影响。

## 指标

| 列 | 含义 |
|---|---|
| R | 有金标的 query 上 Recall 宏平均 |
| P | 全部 query 的 Precision 宏平均（none 且返回空为 1） |
| chars | 按 `factPad` 格式估算的注入字符 |
| stale | 标了 `stale` 的题里，旧 id 仍出现的比例（`latestOnly` 应为 0） |
| none_n | 无关题平均返回条数 |

## 跑

```bash
./gradlew :relay:memory:testDebugUnitTest --tests relay.memory.eval.StackedRetrievalEval
```

表写到 `eval/memory/out/retrieval-grid.md`（gitignore）。
