# Relay · 端侧记忆引擎

> 状态:设计备忘(2026-08-17),不是实现 spec。
> 问题:记忆怎么入库、谁写、谁读、何时忘,端上用什么引擎。
> 承接:[multi-agent-memory.md](./multi-agent-memory.md) 的 scope 分层;做梦细节见 [dream-on-device.md](./dream-on-device.md)。

---

## 0. 结论先行

**别在端上造花哨的记忆引擎。** Android 上的答案很无聊:**SQLite(Room)+ FTS5 + filesDir**。新意不在存储技术,在**入库纪律**。谁一上来就想在手机里塞向量库 / 图数据库,基本都会翻车。

核心一句:**原始 append-only 日志 = 唯一真相;知识图谱 = 派生的物化视图(可丢弃、可重建)。**

这一条同时解决三个诉求:

- **交互不卡**:聊天时只 append 原文,绝不同步跑抽取。
- **可重做梦**:模型变强了,`DROP` 图谱,拿旧日志用新脑重建。
- **出处可溯**:每条边都指回它来自哪条 `raw_event`。

---

## 1. 两速写入

把"记住"拆成两件事,别混:

```
清醒/在线:  捕获(便宜、同步)   → 只追加原始日志,不理解
做梦/离线:  固化(昂贵、幂等)   → 从日志派生出图谱
```

### 1.1 快路径(每轮对话后,同步、毫秒级)

1. 原文落 `ArtifactStore`(filesDir,**内容寻址**=按 hash 存,天然去重 + 当出处 id)。
2. DB 里记一条 `raw_event`,标 `consumed=false`,进队列。

就这两步,**不调模型**。

### 1.2 慢路径(夜里充电,WorkManager 触发)

就是做梦:对每条未消费的 `raw_event` 跑微任务(3B + GBNF 约束吐三元组)→ 去重 / 连线 → **幂等 upsert 进图**,每条带 confidence + provenance → 标 `consumed=true`。

做梦本身就是 orchestra 的一条 `Pipeline`(Call):extract→dedup→link→upsert,每步一个即弃 Agent。

---

## 2. Schema 形状(全在一个 SQLite 里)

```sql
-- 真相:只追加
raw_event(id, ts, session_id, role, text_ref, source, consumed)

-- 派生视图:图
node(id, type, canonical_name, summary, confidence, updated_at, embedding?)
node_alias(node_id, alias)              -- 给去重/连线用
edge(id, src, dst, relation, confidence, valid_from, valid_to)
provenance(fact_id, raw_event_id)       -- 每个事实溯回原文

-- 给晨报用
pending_review(fact_id, reason, confidence)

-- 词法召回,day 1 就能用,不需要模型
CREATE VIRTUAL TABLE node_fts USING fts5(canonical_name, summary, content='node');
```

图**不用图数据库**,就是 `node` + `edge` 两张表;查邻居 = 一条 SQL join。别引嵌入式图库,手机上不值当。

---

## 3. 读路径

混合召回:**FTS5 词法 + 图遍历(命中节点的邻居)+(以后)向量**。

诚实的重点:**day 1 只用 FTS5 + 图遍历就能跑,一个模型都不用。** 向量是加分项,不是前置。

检索结果经现有 `transformContext` 钩子注进上下文,**不改 agent-core**。

---

## 4. 向量 / embedding 放哪

挂在架构里**后置的 `EmbeddingProvider`** 后面。落地时用 **`sqlite-vec`**(SQLite 扩展,和主库同进程,别单独起服务),或 ObjectBox 的端上向量检索。

**没 embedder 之前系统照样完整**——符合"embedding 后置"的决定,别为它阻塞。是否提前,由 spike S3(无 embedding 召回够不够)判定,别倒因为果。

### 4.1 S3 判定需要后的端侧选型

选型三条:**能不能复用你已有的 llama.cpp、够不够小能和 3B 共存、中文行不行**(内容是中文)。

**第一原则:优先复用 llama.cpp(GGUF)。** embedding 模型也能跑在 llama.cpp(`llama_embeddings`),复用你现成的下载 / 加载 / JNI / 量化基建,**别为 embedder 再引一套 ONNX Runtime / MediaPipe**。这基本把候选圈定在"有 GGUF 的多语 embedder"。

| 模型 | 参数/维度 | 中文 | 亮点 | 顾虑 |
|---|---|---|---|---|
| **EmbeddingGemma-300M** | 300M / 768(可截 512/256/128) | 100+ 语,好 | **专为端上 RAG 造**;Matryoshka 截维=向量更小 | 需确认 llama.cpp 版本支持 |
| **Qwen3-Embedding-0.6B** | 0.6B / 1024(可截) | SOTA 级 | 和 Qwen2.5-3B **同家族**、质量最好 | 偏大,~500MB@Q8 |
| **multilingual-e5-small** | 118M / 384 | 好 | **最小最稳**,久经考验 | 要加 `query:`/`passage:` 前缀 |
| **bge-m3** | 560M / 1024 | 很好 | **dense+sparse 一体**,sparse 与 FTS5 互补做 hybrid | 最大,~600MB |
| **bge-small-zh-v1.5** | 24M / 512 | 专攻中文 | 极小极快 | 只中文、纯 dense |

**起步拍板:先 `multilingual-e5-small`(118M)验 S3——先证明"语义召回真比 FTS5 强";过了再换 `EmbeddingGemma-300M`(截 256 维、Q8)上生产。** 想要最好质量且不在乎多 300MB,用 `Qwen3-Embedding-0.6B`(同家族省心)。

会踩的坑:

- **量化别太狠**:embedder 比生成模型对量化敏感,用 **Q8 / f16**,别 Q4(明显掉召回)。它小,Q8 也才几百 MB。
- **前缀**:e5 / bge 系列要区分 `query:` / `passage:`,忘了掉分;EmbeddingGemma 也有 prompt 模板。
- **维度 = 存储**:`10万节点 × 768维 × f32 ≈ 300MB`;截到 **256 维 ≈ 100MB**,再在 sqlite-vec 里存 **int8** 又砍 4 倍。**能截维的模型(Gemma / Qwen3)在端上是硬优势。**
- **RAM 不和 3B 打架**:embedder 只在**做梦(离线批处理)**时跑,那会儿 3B 不需在内存;聊天时可卸载。所以 0.6B 也不至于和 3B 抢内存。

落地:`EmbeddingProvider` 接口留在 `memory-api`(S3 前不实现);`memory-android` 用现有 llama.cpp JNI 跑 GGUF embedder,**做梦时批量算 → 写 sqlite-vec(int8 + 截维)**。

---

## 5. 矛盾与遗忘

- **矛盾**:不硬删。用 `valid_from / valid_to` 做时间边,或 `supersedes` 关系,把冲突留给晨报裁决。
- **遗忘**:衰减 + 归档——剪掉低置信、无人引用、陈旧的**边**;但 `raw_event` 保留(或冷存)。**图能忘,日志不忘,才能重建。**

---

## 6. 放哪个模块 / 怎么接现有代码

按已定的分层纪律(接口纯 Kotlin,Android 实现分开,像 `Provider` 那样):

```
relay/memory-api      纯 Kotlin: MemoryStore 接口(write/query/consolidate)
relay/memory-android  Room + FTS5 + filesDir + (可选)sqlite-vec 实现
```

接进 Agent **不改 agent-core**:

- **写**:每轮结束用现有 post-turn 位置 append `raw_event`。
- **读**:检索结果经 `transformContext` 注进上下文。
- **做梦**:orchestra 的一条 Pipeline(Call)。

依赖方向:`memory-android → memory-api`;orchestra / 业务依赖 `memory-api`。

---

## 7. 一句话收尾

**端上记忆引擎 = SQLite + FTS5 + 文件,append-only 日志当真相,图谱当可重建的派生视图,做梦是把日志物化成图的离线幂等作业。** 存储越无聊越好,聪明都放在"入库纪律"和"做梦"里。
