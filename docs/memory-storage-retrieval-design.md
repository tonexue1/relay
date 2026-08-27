# Relay Memory：无图存储与召回设计

> 状态：讨论稿，等待拷打；不是已接受架构，也不是实施计划。  
> 日期：2026-08-27  
> 目标：只回答两个问题——**记忆怎么存，记忆怎么取**。  
> 核心取舍：采用 Mem0 风格的简洁接口，但不把所有记忆压成“当前事实”；不建设知识图谱。  
> 适用场景：个人助手、陪伴 Agent、小说角色记忆。  
> 本文会替代当前 `Claim + 闭集 Triple + 图召回` 的主线设计；正式接受前，`memory-api.md` 与现有实现仍是真相源。

---

## 0. 决策摘要

Relay Memory 采用四类数据：

```text
RawEvent     证据，回答“当时实际发生/说了什么”
State        当前投影，回答“现在是什么”
Episode      一阶记忆，回答“过去经历过什么”
Reflection   二阶记忆，回答“这些经历说明了什么”
```

State 的存储和版本机制属于 Memory；State 的闭集 key、schema、校验规则和写入决策属于宿主。

RawEvent 是原始证据；State、Episode、Reflection 都必须能追溯来源。

不建设：

- Entity Node；
- Fact Edge；
- SDK 内置的通用关系闭集谓语；
- 邻居遍历；
- 节点合并；
- 多跳图查询；
- 图数据库。

召回采用：

```text
宿主指定的 required State
  +
FTS5/BM25 精确召回
  +
本地向量语义召回
  +
当前任务 / 最近记忆
  ↓
RRF 融合
  ↓
硬过滤、去重、State/Reflection 版本消解
  ↓
按类型预算组装上下文
```

关键语义：

```text
RawEvent           是不可损来源，不直接等于长期记忆
State              版本化更新，默认只读当前版本
Episode            只追加，不因当前业务状态变化而删除
Reflection         版本化更新，必须能追溯到支撑它的记忆
```

一句话：

> **State 负责现在，Episode 负责回忆，Reflection 负责成长；宿主定义 State 语义，Memory 负责可靠存取。**

---

# 第一部分：问题与边界

## 1. 为什么不再以图为中心

当前图设计解决了这些问题：

- `s + p` 功能边确定性覆盖；
- 双时钟；
- provenance；
- 一跳关系查询；
- 闭集谓语可测试。

但它没有解决当前最重要的问题：

- 用户换一种说法，如何找到记忆；
- 如何表达完整经历；
- 如何写“我记得那天……”；
- 如何表达“我是怎样的人”；
- 如何在小说里隔离角色视角；
- 如何稳定控制注入上下文的质量。

图还引入了额外负担：

- 谓语词表维护；
- 强行把自然语言压成 `s/p/o`；
- 同义节点合并；
- 图专用夜间工具；
- 召回必须先命中实体；
- schema 错误会损失原始含义。

当前没有真实召回 Eval 证明“多跳图查询”是核心产品需求。因此图不应继续作为主承重结构。

## 2. 本设计要解决什么

### 2.1 个人助手

- 用宿主定义的 State 保存用户当前资料、偏好和禁忌；
- 记住过去发生过的事情；
- 从多次经历中形成稳定认识；
- 用户改口后，State 当前值正确，历史经历仍可追溯；
- 离线从设备本地召回。

### 2.2 小说角色

- 保存客观发生的场景；
- 保存角色实际知道、相信和感受到的版本；
- 角色不能召回自己不知道的世界真相；
- 角色可以回忆过去；
- 角色的自我认识和对他人的认识会成长；
- 查询必须受故事时间约束，不能看到未来章节。

### 2.3 SDK

- Store 不调用 LLM；
- 存储语义确定、可测试；
- State schema 由 App/业务引擎提供；
- 模型只产生 Draft，不直接操作数据库 ID；
- App 可以替换抽取器、embedding 和召回策略；
- SQLite 是 Memory Core 的真相源；
- 索引可以损坏后重建；
- API 保持小而清楚。

## 3. 非目标

本阶段不做：

- 通用知识图谱；
- 多跳逻辑推理；
- 图可视化；
- 云端托管记忆；
- 端侧抽取模型；
- 本地 Cross-Encoder；
- 大规模 ANN 集群；
- 自动生成完整人物心理学模型；
- 在没有证据时自动覆盖旧记忆；
- 用一个总分证明记忆“正确”。

## 4. 设计原则

### 4.1 当前状态与历史经历分开

“角色现在位于临安”和“角色曾在杭州生活”是两类记忆：

- 当前地点由 State 直接读取；
- 历史经历由 Episode 检索；
- State 更新不能覆盖或删除 Episode。

小说引擎定义 `character.location` 的含义与合法值；Memory 不把它内置成通用谓语。

### 4.2 真相源与索引分开

SQLite 中的正文、版本、来源和时间是真相源。

以下都只是可重建索引：

- FTS5/BM25；
- embedding；
- 向量索引；
- tags。

### 4.3 关键内容不依赖概率召回

严重过敏、身份、当前故事状态等，如果每轮必须知道，宿主应该按 key 指定 required State，由 Memory 直接读取，而不是祈祷向量 Top-K 命中。

### 4.4 语义入口保留完整文本

向量和 Reranker 消费的是自然语言文本。不要只保存三元组后，再期待搜索器恢复被压掉的语义。

### 4.5 Store 不猜

Store 不做：

- 判断两句话是否同义；
- 判断新经历是否推翻旧经历；
- 猜测需要更新哪个数据库 ID；
- 从文本补充缺失事实。

Store 只执行明确命令：

- append；
- upsertState；
- supersedeReflection；
- retract；
- confirm；
- query。

### 4.6 先可解释，再智能

第一版每条召回结果必须能解释：

- 哪一路召回；
- 原始排名；
- 经过了什么过滤；
- 为什么进入上下文；
- 来源是什么。

---

# 第二部分：统一概念模型

## 5. Memory Space

每个记忆空间完全隔离：

```text
assistant:{userId}
novel:{novelId}
```

建议模型：

```kotlin
data class MemorySpace(
    val id: String,
    val kind: MemorySpaceKind,
)

enum class MemorySpaceKind {
    ASSISTANT,
    NOVEL,
}
```

所有读写必须携带 `spaceId`。禁止跨 Space 隐式 JOIN。

`spaceId` 替代当前带图含义的 `graphId`。

## 6. Owner 与 Perspective

同一个 Space 中，记忆属于某个观察者：

```text
个人助手：
  ownerId = user:{id}

小说：
  ownerId = world
  ownerId = character:{id}
```

`ownerId` 决定“谁有资格回忆这条内容”。

小说中同一场景可产生多个版本：

```text
world：
  沈砚因被官兵抓走而未能赴约。

character:linwan：
  沈砚没有赴约，他再次抛弃了我。

character:shenyan：
  我被官兵抓走，没能赶到约定地点。
```

这不是数据重复，而是不同认知主体拥有不同记忆。

## 7. 四类数据

### 7.1 RawEvent

原始消息、场景、工具结果或业务事件。

性质：

- 不可损；
- 不因抽取成功而删除；
- 可重新处理；
- 可全文搜索原话；
- 不默认进入长期记忆上下文。

### 7.2 State

State 是某个宿主定义的稳定 key 在当前时刻的值。

示例：

```text
profile.location = 上海
profile.allergies = ["花生"]
character.location = 临安
character.current_goal = 找到父亲
character.status = 受伤
```

性质：

- `key` 必填，且必须属于宿主传入或注册的 StateSchema；
- schema、合法值和更新权限归宿主；
- 同一范围内只有一个当前 Confirmed 版本；
- 新版本不会物理覆盖旧行；
- 默认召回只读当前版本；
- 历史查询可读旧版本；
- required State 按 key 直接读取，不参与 Top-K 竞争；
- Store 不理解 key 的业务语义。

### 7.3 Episode

一次完整经历或事件片段。

示例：

```text
用户上次面试时因为紧张，忘记介绍自己负责的项目。

十二岁的林晚在雨夜看见父亲离开，她相信自己被抛弃了。
```

性质：

- 只追加；
- 重复发生的相似经历仍然是不同 Episode；
- 必须带发生时间或写入时间；
- 可带人物、地点、情绪、显著度等 metadata；
- 不因 State 更新而失效；
- 可以被明确 retract，但不能被“新状态”自动抹掉。

### 7.4 Reflection

对多个 State/Episode 或其他可追溯证据的归纳认识。

示例：

```text
用户偏好先理解原理，再决定技术方案。

林晚很难信任别人，但接受一个人后会非常忠诚。
```

性质：

- `key` 必填，例如 `self_model`、`working_style`；
- 版本化更新；
- 必须保存 evidence memory IDs；
- 不能脱离支撑记忆成为不可解释结论；
- 当前版本用于回答，旧版本用于回看成长。

### 7.5 它们不是四个平级记忆层

依赖关系是：

```text
RawEvent（证据）
    ├── 抽取/投影 → State（当前值）
    └── 抽取      → Episode（一阶长期记忆）
                         ↓ 归纳
                    Reflection（二阶长期记忆）
```

State 与 Episode/Reflection 的抽象层次正交：State 是当前投影，不是比 Episode 更高的一层。一次事件可以同时更新 State 并追加 Episode；前者服务“现在”，后者保留“发生过”。

## 8. 为什么没有 Claim

当前 Open Claim 同时承担了几种不同职责：

- 当前 Profile；
- 开放知识；
- 经历摘要；
- 闭集 Triple 接不住的兜底。

这使更新和召回语义不清楚。

新模型要求写入方明确选择：

```text
现在是什么      → State
发生过什么      → Episode
这些说明什么    → Reflection
```

Extractor 可以输出 StateDraft，但只能使用宿主 StateSchema 中登记的 key。未登记或无法可靠分类的内容默认存为 Episode；Store 不从开放文本自行发明 key。

---

# 第三部分：物理存储

## 9. SQLite 是 Memory Core 的唯一真相源

这里的“唯一”只指 Memory Core。宿主 Profile 或小说世界状态可以有自己的真相源，Memory 不复制其业务所有权。

推荐表：

```text
memory_space
raw_event
memory_item
memory_source
memory_evidence
memory_tag
memory_embedding
embedding_model
index_job
```

不再需要：

```text
node
node_alias
edge
fact_log
relation
pending_review（图功能边专用部分）
node_fts
```

是否保留现有表由迁移计划决定，不在第一版直接 DROP。

## 10. raw_event

建议字段：

```sql
raw_event (
    id                TEXT PRIMARY KEY,
    space_id          TEXT NOT NULL,
    owner_id          TEXT NOT NULL,
    session_id        TEXT NOT NULL DEFAULT '',
    task_scope_id     TEXT NOT NULL DEFAULT '',
    role              TEXT NOT NULL,
    content           TEXT NOT NULL,
    occurred_at       INTEGER,
    captured_at       INTEGER NOT NULL,
    processing_state  TEXT NOT NULL,
    content_hash      TEXT NOT NULL,
    metadata_json     TEXT NOT NULL DEFAULT '{}'
)
```

`processing_state`：

```text
PENDING
PROCESSING
COMMITTED
RETRYABLE_ERROR
PERMANENT_ERROR
```

语义：

- 抽取返回空不代表事件应该消费；
- 只有成功提交 MemoryBatch 后才标记 `COMMITTED`；
- 相同幂等 key 的事件不得重复插入。

## 11. memory_item

统一保存 State、Episode、Reflection：

```sql
memory_item (
    id                TEXT PRIMARY KEY,
    space_id          TEXT NOT NULL,
    owner_id          TEXT NOT NULL,
    kind              TEXT NOT NULL,
    memory_key        TEXT,
    text              TEXT NOT NULL,
    payload_json      TEXT NOT NULL DEFAULT '{}',

    scope             TEXT NOT NULL,
    scope_id          TEXT NOT NULL DEFAULT '',
    lifecycle_state   TEXT NOT NULL,
    confidence        REAL NOT NULL,
    salience          REAL NOT NULL DEFAULT 0.5,

    occurred_at       INTEGER,
    valid_from        INTEGER,
    valid_to          INTEGER,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,

    supersedes_id     TEXT,
    retracted_at      INTEGER,
    expire_at         INTEGER,

    text_hash         TEXT NOT NULL,
    writer_run_id     TEXT NOT NULL,
    extractor_version TEXT NOT NULL,
    state_schema_version TEXT
)
```

### 11.1 kind

```text
STATE
EPISODE
REFLECTION
```

### 11.2 scope

```text
PROFILE   跨会话长期可见
TASK      仅当前 task scope
SESSION   仅当前 session
```

沿用当前有价值的 Scope 设计。

### 11.3 lifecycle_state

```text
CANDIDATE
CONFIRMED
SUPERSEDED
RETRACTED
EXPIRED
```

区分：

- `SUPERSEDED`：被新版本替代，但历史上曾成立；
- `RETRACTED`：被明确撤回或判错；
- `EXPIRED`：因生命周期策略不再进入默认召回。

### 11.4 时间语义

保留双时间思想，但不绑定图边：

```text
occurred_at   事件在世界中何时发生
valid_from    State/Reflection 从何时开始适用
valid_to      State/Reflection 到何时不再适用
created_at    系统何时写入这条编码
updated_at    metadata 最后何时变化
retracted_at  系统何时明确撤回
```

Episode 通常使用 `occurred_at`，不需要 `valid_to`。

State/Reflection 使用 `valid_from/valid_to`。

## 12. State 唯一性

State 的逻辑身份不是数据库 ID，而是：

```text
(space_id, owner_id, scope, scope_id, memory_key)
```

例如：

```text
(assistant:u1, user:u1, PROFILE, "", profile.location)
```

一次 `upsertState` 在 SQLite 事务中：

1. 校验 key 存在于宿主提供的 StateSchema；
2. 找到该 key 当前 `CONFIRMED` 版本；
3. 如果正文和 payload 完全相同，返回 No-op；
4. 旧版本改为 `SUPERSEDED`，写入 `valid_to`；
5. 新增新版本，`supersedes_id` 指向旧版本；
6. 新版本成为当前值；
7. 写入来源；
8. 提交后排队生成 embedding。

Store 不需要理解 `profile.location` 的业务含义，也不需要 LLM 猜旧 ID，因为宿主提供稳定 key 与 schema。

Candidate State 不能自动覆盖 Confirmed State。只有显式 `confirm` 或满足宿主定义的晋升策略后才能成为当前版本。

## 13. Episode 追加语义

Episode 不做语义去重。

两个文本很像，不代表是同一事件：

```text
周一用户面试时紧张。
周五用户面试时紧张。
```

允许的去重只有：

- 相同 raw event；
- 相同 writer run；
- 相同 draft index；
- 完全相同幂等 key。

建议幂等键：

```text
hash(spaceId + ownerId + writerRunId + draftIndex)
```

## 14. Reflection 版本与证据

Reflection 使用稳定 key：

```text
self_model
working_style
relationship:{otherOwnerId}
world_view
```

证据表：

```sql
memory_evidence (
    reflection_id   TEXT NOT NULL,
    evidence_id     TEXT NOT NULL,
    relation        TEXT NOT NULL,
    PRIMARY KEY (reflection_id, evidence_id)
)
```

`relation`：

```text
SUPPORTS
CONTRADICTS
MOTIVATES
```

这不是知识图谱：

- 只连接 Reflection 与直接证据；
- 不参与任意遍历；
- 不做实体关系推理；
- 作用是 provenance 和解释。

没有 evidence 的 Reflection 可以作为 Candidate 保存，但不能晋升为 Confirmed。

Reflection 的逻辑身份与 State 类似：

```text
(space_id, owner_id, scope, scope_id, memory_key)
```

提交新 Confirmed Reflection 时：

1. 校验 evidence 全部存在且对当前 owner 可见；
2. 校验 `expectedCurrentId`，避免后台 Worker 覆盖较新的用户编辑；
3. 将旧当前版本标记为 `SUPERSEDED` 并写入 `valid_to`；
4. 插入新版本和 evidence；
5. 排队生成新 embedding。

Reflection 不允许原地改正文。

## 15. memory_source

```sql
memory_source (
    memory_id       TEXT NOT NULL,
    source_type     TEXT NOT NULL,
    source_id       TEXT NOT NULL,
    metadata_json   TEXT NOT NULL DEFAULT '{}',
    PRIMARY KEY (memory_id, source_type, source_id)
)
```

所有 MemoryItem 必须至少满足一种来源条件：

- `source_type=RAW_EVENT`；
- `source_type=USER_EDIT`；
- `source_type=HOST_UPDATE`；
- `source_type=IMPORT`；或
- Reflection 通过 `memory_evidence` 形成可追溯到 RawEvent 的证据链。

`source_id` 由来源类型解释，例如 RawEvent ID、用户编辑操作 ID 或宿主事务 ID。State 可以来自 Extractor、用户编辑或宿主规则，但都必须通过宿主 StateSchema 校验。

## 16. memory_tag

Tag 是轻量精确索引，不是 Entity Node：

```sql
memory_tag (
    memory_id   TEXT NOT NULL,
    tag_type    TEXT NOT NULL,
    tag_value   TEXT NOT NULL,
    PRIMARY KEY (memory_id, tag_type, tag_value)
)
```

示例：

```text
PERSON=张三
PROJECT=Relay
TECHNOLOGY=Android
LOCATION=上海
EMOTION=恐惧
SCENE=chapter-12
```

Tag 用于：

- exact boost；
- filter；
- UI 展示；
- debug。

Tag 不负责：

- 实体合并；
- 图遍历；
- 关系推理。

## 17. FTS5/BM25

使用 SQLite FTS5 建立统一全文索引，并用 FTS5 内置的 BM25 对命中结果排序：

```text
memory_fts
- memory_id
- text
- key
- tags
```

要求：

- State、Episode、Reflection 都可检索；
- required State 另外按 key 直接读取，不依赖索引；
- RawEvent 使用独立 FTS5 索引，避免默认召回混入原始噪音；
- FTS5 命中后再按主表执行 Scope、状态和时间过滤；
- 中文第一版可沿用 NFKC + CJK 2/3-gram；
- BM25 原始分不与 cosine 直接相加。

查询形态：

```sql
SELECT memory_id, bm25(memory_fts) AS bm25_score
FROM memory_fts
WHERE memory_fts MATCH :query
ORDER BY bm25_score
LIMIT :limit;
```

注意：

- SQLite FTS5 的 `bm25()` 已做符号变换，数值越小排名越靠前；
- RecallEngine 只消费 BM25 的相对排名，不把原始分当概率；
- CJK 2/3-gram 是 BM25 的 token 输入，不提供语义理解；
- 专名、数字、原话和精确短语优先依赖此通道。

## 18. Embedding 存储

```sql
embedding_model (
    model_id            TEXT PRIMARY KEY,
    model_version       TEXT NOT NULL,
    dimensions          INTEGER NOT NULL,
    tokenizer_version   TEXT NOT NULL,
    query_prefix        TEXT NOT NULL,
    document_prefix     TEXT NOT NULL,
    normalization       TEXT NOT NULL,
    created_at          INTEGER NOT NULL,
    active              INTEGER NOT NULL
)
```

```sql
memory_embedding (
    memory_id       TEXT NOT NULL,
    model_id        TEXT NOT NULL,
    text_hash       TEXT NOT NULL,
    vector_blob     BLOB NOT NULL,
    indexed_at      INTEGER NOT NULL,
    PRIMARY KEY (memory_id, model_id)
)
```

必须记录：

- 模型；
- 模型版本；
- tokenizer；
- query/document prefix；
- normalize 方式；
- dimensions；
- 对应正文 hash。

只记录“模型名”不够。预处理变化同样会导致向量空间不兼容。

## 19. 向量索引第一版

第一版不直接引入 HNSW。

接口：

```kotlin
interface VectorIndex {
    suspend fun upsert(record: VectorRecord)
    suspend fun remove(memoryId: String, modelId: String)
    suspend fun search(
        query: FloatArray,
        filter: VectorFilter,
        limit: Int,
    ): List<VectorHit>
}
```

首个实现：

```text
SQLite metadata 预过滤
  → 读取候选 vector_blob
  → 批量 cosine
  → 本地 Top-K
```

是否引入 ANN 由真实 benchmark 决定，不提前承诺规模阈值。

未来 ANN 实现必须保持：

- SQLite 仍是真相源；
- ANN 可从 SQLite 重建；
- 删除和 supersede 可过滤；
- model ID 隔离；
- 索引版本可回滚。

---

# 第四部分：写入

## 20. 外部 API

目标是 Mem0 风格的小接口：

```kotlin
interface MemoryRuntime {
    suspend fun capture(event: RawEventDraft): RawEventId

    suspend fun commit(batch: MemoryBatch): CommitResult

    suspend fun recall(request: RecallRequest): RecallResult

    suspend fun retract(memoryId: String, reason: String): MutationResult

    suspend fun getStates(request: StateReadRequest): List<StateItem>
}
```

高级调用方可以直接提交明确 Draft：

```kotlin
data class StateSelector(
    val key: String,
    val scope: MemoryScope? = null,
    val scopeId: String = "",
)

data class StateReadRequest(
    val spaceId: String,
    val ownerId: String,
    val selectors: Set<StateSelector>,
    val at: Long = System.currentTimeMillis(),
)

sealed interface MemoryDraft

data class StateFieldSpec(
    val key: String,
    val valueSchema: JsonSchema,
    val allowedWriters: Set<MemoryWriterKind>,
)

data class StateSchema(
    val version: String,
    val fields: Map<String, StateFieldSpec>,
)

fun interface StateSchemaProvider {
    fun schemaFor(spaceId: String, ownerId: String): StateSchema?
}

data class StateDraft(
    val key: String,
    val text: String,
    val payload: JsonObject,
    val ownerId: String,
    val scope: MemoryScope,
    val scopeId: String,
    val sourceEventIds: List<String>,
    val confidence: Double,
    val expectedCurrentId: String? = null,
) : MemoryDraft

data class EpisodeDraft(
    val text: String,
    val ownerId: String,
    val occurredAt: Long?,
    val scope: MemoryScope,
    val scopeId: String,
    val sourceEventIds: List<String>,
    val tags: List<MemoryTag>,
    val salience: Double,
    val confidence: Double,
) : MemoryDraft

data class ReflectionDraft(
    val key: String,
    val text: String,
    val ownerId: String,
    val evidenceMemoryIds: List<String>,
    val scope: MemoryScope,
    val scopeId: String,
    val confidence: Double,
    val expectedCurrentId: String? = null,
) : MemoryDraft

data class MemoryBatch(
    val spaceId: String,
    val writerRunId: String,
    val extractorVersion: String,
    val stateSchemaVersion: String? = null,
    val sourceEventIds: List<String>,
    val drafts: List<MemoryDraft>,
    val commitMode: CommitMode = CommitMode.ATOMIC,
)
```

`StateSchema` 由宿主在创建 MemoryRuntime 时提供，或通过可插拔 `StateSchemaProvider` 按 Space 解析。SDK 不附带 `profile.*`、`character.*` 等内置词表；`stateSchemaVersion` 写入批次用于审计和重放。

包含 StateDraft 的批次必须提供 `stateSchemaVersion`；不含 StateDraft 的纯 Episode/Reflection 批次可以为 `null`。

## 21. 写入者与 Store 的职责

写入者可以是：

- 云端 Extractor；
- App 业务规则；
- 用户编辑；
- 后台 Reflection Worker；
- 导入器。

写入者负责：

- 选择 State/Episode/Reflection；
- StateDraft 只能使用宿主 StateSchema 中登记的 key；
- 为 State/Reflection 提供稳定 key；
- 提供 owner 和 scope；
- 提供来源；
- 提供发生时间；
- 明确是否请求覆盖当前 State；
- 为 Reflection 提供 evidence。

Store 负责：

- 按宿主提供的 StateSchema 校验 key 和 payload；
- Scope 校验；
- 事务；
- State/Reflection 版本切换；
- 幂等；
- provenance；
- 索引任务；
- 生命周期状态。

## 22. 写入事务

`commit(MemoryBatch)` 必须原子提交：

```text
校验全部 Draft
  ↓
检查 source 是否存在
  ↓
按宿主 StateSchema 校验 StateDraft
  ↓
执行 State 版本切换
  ↓
追加 Episode
  ↓
追加 Reflection 和 evidence
  ↓
写 tags
  ↓
标记 RawEvent 已提交
  ↓
创建 index_job
  ↓
COMMIT
```

同批坏稿如何处理由调用参数决定：

```text
ATOMIC      任一失败，整批回滚
BEST_EFFORT 合法稿提交，错误逐条返回
```

默认 `ATOMIC`，避免一段经历的 State、Episode 和 Reflection 只写一半。

## 23. Embedding 异步写入

正文事务不能等待 embedding：

```text
MemoryItem SQLite commit
  ↓
State/Episode/Reflection index_job=PENDING
  ↓
WorkManager 批量执行
  ↓
本地 tokenizer + embedding runtime
  ↓
memory_embedding
  ↓
index_job=COMPLETED
```

索引失败：

- 不回滚 MemoryItem；
- FTS5/BM25 仍可召回；
- 指数退避重试；
- 超过上限进入 `FAILED`；
- 对上层暴露 index health；
- 允许手动 rebuild。

## 24. 本地 Embedding Provider

```kotlin
interface EmbeddingProvider {
    val manifest: EmbeddingManifest

    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    suspend fun embedQuery(text: String): FloatArray
}
```

首版必须使用独立的 embedding 模型，不复用 3B 聊天模型：

- 聊天模型不等于 embedding 模型；
- 生成 hidden state 的接口和质量不稳定；
- 包体、耗时和能耗不合理。

候选模型不能仅按榜单决定，必须通过 Relay 中文召回集和目标 Android 设备 benchmark。

本文不提前锁定具体模型。

## 25. 本地向量化的真实缺陷

### 25.1 本地特有

- 模型下载和磁盘占用；
- Android ABI/runtime 兼容；
- tokenizer 和模型算子支持；
- 低端设备延迟；
- 批量索引发热和耗电；
- 后台任务可能被系统终止；
- 每台设备分别迁移；
- 设备可能长期不满足重建条件。

缓解：

- 模型按需下载，不打进基础 AAR；
- WorkManager；
- 批处理；
- 充电/空闲约束可配置；
- FTS5/BM25 永远兜底；
- 索引可断点恢复；
- 模型能力探测；
- App 可注入 CloudEmbeddingProvider。

### 25.2 所有向量系统共有

- 否定和数字容易误判；
- 短事实语义不稳定；
- 专有名词不如精确检索；
- 相似不等于有用；
- threshold 不能跨模型照抄；
- 模型或预处理不兼容升级需要重建；
- 旧新向量空间不能混搜；
- embedding 不能替代时间与 Scope 过滤。

这些不是本地独有缺陷，云端也存在；云端只是在集中算力和迁移控制上更容易。

## 26. Embedding 模型升级

采用双索引迁移：

```text
active model = v1
  ↓
注册 v2，状态 BUILDING
  ↓
新写入同时生成 v1/v2，或只生成 v2 并保留 FTS5/BM25
  ↓
后台为历史 MemoryItem 生成 v2
  ↓
跑召回 Eval
  ↓
原子切换 active model = v2
  ↓
观察窗口
  ↓
删除 v1 vectors
```

任何时候：

- 不用 v2 query 搜 v1 document vector；
- 召回结果必须标记 `modelId`；
- 切换失败可回滚；
- MemoryItem 正文无需迁移。

---

# 第五部分：召回

## 27. RecallRequest

```kotlin
data class RecallRequest(
    val spaceId: String,
    val ownerId: String,
    val query: String,
    val recentMessages: List<Message> = emptyList(),
    val sessionId: String = "",
    val taskScopeId: String = "",
    val at: Long = System.currentTimeMillis(),
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),
    val requiredStateKeys: Set<String> = emptySet(),
    val allowCrossTask: Boolean = false,
    val includeWorldMemory: Boolean = false,
    val budgetChars: Int = 2_000,
    val explain: Boolean = false,
)
```

## 28. Query 构造

当前只使用 Latest User 的方式不够。

第一版确定性 Query：

```text
latest user message
+ 最近一到三条用户消息
+ active task title（若有）
```

限制：

- 不拼全部聊天历史；
- 不默认加入 assistant 长回复；
- 不在 Store 内调用 LLM rewrite；
- App 可注入 `RecallQueryBuilder`；
- Query Builder 输出要进入 explain trace。

```kotlin
fun interface RecallQueryBuilder {
    fun build(request: RecallRequest): RecallQuery
}
```

## 29. 硬过滤

候选进入排名前必须满足：

```text
space_id 匹配
owner_id 匹配
kind 在请求范围
scope 当前可见
lifecycle_state 可召回
时间有效
小说 story_time 不超过当前 at
```

### 29.1 State/Reflection 当前值

默认：

```text
lifecycle_state = CONFIRMED
valid_from <= at
valid_to IS NULL OR valid_to > at
```

### 29.2 Episode

默认：

```text
lifecycle_state IN (CANDIDATE, CONFIRMED)
occurred_at IS NULL OR occurred_at <= at
retracted_at IS NULL
expire_at IS NULL OR expire_at > now
```

Candidate Episode 是否召回由产品配置决定。Candidate State 不允许进入 required State 上下文。

### 29.3 小说认知边界

角色请求默认只能读：

```text
owner_id = 当前角色
```

只有显式 `includeWorldMemory=true` 的叙述者或作者工具可以额外读取：

```text
owner_id = world
```

严禁因为文本相似而跨角色召回。

## 30. Required State

宿主按当前场景显式传入 `requiredStateKeys`。Memory 先校验这些 key 属于当前 Space 的 StateSchema，再按 key 读取目标时刻有效的 Confirmed State。

例如饮食助手传：

```text
profile.allergies
profile.diet
profile.medical_constraints
```

小说角色运行时传：

```text
character.location
character.current_goal
character.status
```

这些 key 是宿主的闭集，不是 SDK 内置谓语；这些 State 不参与向量、BM25 或 RRF 竞争。不存在、未确认或越权的 required key 必须在 RecallResult 中显式报错，不能静默忽略。

若同一 key 在多个可见 Scope 都有当前值，采用确定性覆盖顺序：

```text
SESSION > TASK > PROFILE
```

需要读取指定历史 Scope 时，调用 `getStates` 的精确 selector 版本，不依赖覆盖规则。

## 31. 候选生成

四路并行：

```text
FtsRetriever
VectorRetriever
RecentRetriever
TagRetriever
```

### 31.1 FTS5/BM25

输入 RecallQuery，返回：

```text
memoryId
rank
bm25/debug score
matched terms
```

负责：

- 人名；
- 项目名；
- 术语；
- 原话；
- 数字；
- embedding 不稳定的短文本。

### 31.2 Vector

输入 query embedding，返回：

```text
memoryId
rank
cosine
modelId
```

负责：

- 改写；
- 相似经历；
- 主题相关；
- Reflection 语义。

Vector 不是事实判断器。它只生成候选。

### 31.3 Recent

按当前 Scope 返回：

- 当前 session 最近 Episode；
- 当前 task 最近 Episode；
- 最近更新的 State/Reflection。

Recent 不代表相关，只给“刚刚发生”的内容进入候选池的机会。

### 31.4 Tag

从 query 中通过确定性规则或调用方提供的 tags 做 exact match。

第一版不强制集成中文 NER。可以使用：

- Extractor 已提供的 tags；
- App 词典；
- 技术标识符规则；
- 人工标签；
- 明确的 owner/project/location ID。

## 32. 候选池必须取并集

正确：

```text
candidates =
  fts hits
  ∪ vector hits
  ∪ recent hits
  ∪ tag hits
```

不采用 Mem0 OSS 当前“语义候选为主，BM25/实体只加分”的限制。

原因：

- 精确 ID 可能语义分很低；
- 中文短词可能只被 FTS5/BM25 找到；
- 新近事件可能还没有 embedding；
- 向量 threshold 不应提前杀死其他通道的独有结果。

## 33. RRF 融合

第一版：

```text
rrf(memory) = Σ 1 / (k + rank_channel(memory))
```

通道：

- FTS5/BM25；
- Vector；
- Recent；
- Tag。

要求：

- `k` 可配置；
- rank 从 1 开始；
- 缺失通道不贡献；
- 保存每一路贡献；
- 不把 RRF score 当概率；
- 不设置跨模型通用阈值。

为什么先用 RRF：

- BM25、cosine、recency 不同尺度；
- 避免过早发明复杂权重；
- 易解释；
- 易做消融实验。

## 34. 融合后的规则调整

RRF 后只允许少量可解释规则：

```text
当前 task exact match        小幅提升
exact tag match              小幅提升
CONFIRMED                    提升
CANDIDATE                    降低
非常低 confidence            降低或过滤
同 text_hash                 去重
已被 supersede               排除
未来 story_time              排除
```

禁止第一版使用：

- 十几个不可解释权重；
- LLM 自评相关度；
- 不可复现的“智能分”；
- 将 confidence、cosine、BM25 直接裸相加。

## 35. 去重与版本消解

顺序：

1. 排除不可见状态；
2. 同一 State key 只保留目标时刻有效版本；
3. 同一 Reflection key 只保留目标时刻有效版本；
4. 完全相同 `text_hash` 只保留最高排名；
5. 高度相似 Episode 不自动删除，只在当前上下文中做 diversity；
6. 已进入 required State 的内容，不再作为普通候选重复注入。

## 36. ContextAssembler

输入：

```text
required states
ranked states
ranked reflections
ranked episodes
budget
```

默认预算比例仅作为初始配置，不是产品真理：

```text
Required State    25%
State             15%
Reflection        20%
Episode           40%
```

规则：

- Required State 优先；
- 每类至少有独立上限；
- 单条超长 Episode 允许摘要字段，但原文仍可回查；
- 不因 State 很多而饿死 Episode；
- 不因 Episode 很多而挤掉关键 State；
- 输出标记 kind、时间和来源；
- Query 本身不能混入记忆正文。

建议格式：

```text
<memory_context>
  <current_state>
  - 用户目前居住在上海。
  </current_state>

  <reflections>
  - 用户习惯先验证关键风险，再开始完整实现。
  </reflections>

  <episodes>
  - [2026-08-18] 用户在一次 Android 方案讨论中先要求验证端侧模型。
  </episodes>
</memory_context>
```

## 37. RecallResult 与 explain

```kotlin
data class RecallResult(
    val context: String,
    val selected: List<SelectedMemory>,
    val trace: RecallTrace?,
)
```

`explain=true` 返回：

```text
query 构造结果
每路候选
每路排名
RRF 贡献
硬过滤原因
版本消解
预算淘汰
最终选择
embedding model ID
index health
```

没有 explain，就无法有效调 Recall。

---

# 第六部分：Use Cases

## 38. UC-A1：个人助手更新当前位置

输入：

```text
我已经从北京搬到上海了。
```

写入：

```text
RawEvent：
  原始用户消息

StateDraft：
  key=profile.location
  text=用户目前居住在上海
  schemaVersion=assistant-profile-v1

EpisodeDraft：
  text=用户从北京搬到上海
  occurredAt=消息时间
```

前提：

```text
宿主 StateSchema 已登记 profile.location
Extractor 或宿主规则显式提交 StateDraft
Store 只校验与执行，不自行发明 key
```

Store：

```text
旧 State“北京” → SUPERSEDED，valid_to=now
新 State“上海” → CONFIRMED，supersedes_id=旧 ID
Episode → APPEND
```

查询：

```text
“我现在住哪里？”
→ required State: profile.location
→ 上海

“我以前住哪里？”
→ Episode + State 历史
→ 北京
```

## 39. UC-A2：个人助手回忆面试

输入：

```text
上次面试我太紧张，忘记介绍自己负责的项目。
```

写入：

```text
Episode：
  用户上次面试时因为紧张，忘记介绍负责的项目。

tags：
  TOPIC=面试
  EMOTION=紧张
```

查询：

```text
“我之前面试有什么问题？”
```

召回：

```text
FTS5/BM25 命中“面试”
Vector 命中“面试问题”和“忘记介绍项目”的语义
RRF 合并
Episode 进入上下文
```

## 40. UC-A3：个人助手关键禁忌

State：

```text
profile.allergies = ["花生"]
```

用户问：

```text
今晚吃火锅行吗？
```

不能依赖“火锅”和“花生过敏”的 embedding 恰好相似。

饮食场景的 App/Agent 显式传：

```text
requiredStateKeys=[
  profile.allergies,
  profile.diet,
  profile.medical_constraints
]
```

结果：

```text
花生过敏作为 required State 确定性进入上下文，不参与 Memory Top-K 竞争。
```

## 41. UC-A4：形成用户工作方式 Reflection

已有 Episode：

```text
用户在三个方案讨论中都先要求验证核心假设。
用户拒绝在根因未知时直接修改代码。
```

后台 Reflection Writer 提交：

```text
key=working_style
text=用户偏好先查证关键假设和根因，再进入实现。
evidence=[episode-1, episode-2, episode-3]
```

以后讨论技术方案时：

```text
Reflection 语义召回
或宿主把 `working_style` 登记为 State key 后按 required State 注入
```

## 42. UC-N1：小说客观事件与角色记忆

场景：

```text
沈砚被官兵抓走，未能赴约。林晚只看到沈砚没有出现。
```

写入：

```text
RawEvent：
  场景原文

World Episode：
  owner=world
  沈砚因被官兵抓走而未能赴约。

林晚 Episode：
  owner=character:linwan
  沈砚没有赴约，林晚相信自己再次被抛弃。
  emotion=愤怒、恐惧

沈砚 Episode：
  owner=character:shenyan
  沈砚被抓，无法赶到约定地点。
  emotion=焦急、愧疚
```

运行林晚 Agent：

```text
owner=character:linwan
includeWorldMemory=false
```

她不能召回“沈砚被抓”。

## 43. UC-N2：小说人物当前状态

小说引擎提供 StateSchema：

```text
character.location
character.current_goal
character.status
```

Memory 保存当前 State：

```text
character.location = 临安
character.current_goal = 找到父亲
character.status = 受伤
```

场景生成前，小说引擎把这三个 key 作为 `requiredStateKeys` 传给 Recall。闭集由小说引擎定义，State 的版本存储和直接读取由 Memory 提供，因此人物不会因语义召回失败而突然出现在错误地点。

## 44. UC-N3：人物被场景触发回忆

当前场景：

```text
窗外开始下暴雨。
```

历史 Episode：

```text
十二岁的林晚在雨夜看见父亲离开，她认为自己被抛弃。
```

召回：

```text
Vector 命中“暴雨 / 雨夜”的经历语义
Tag EMOTION/WEATHER 可额外提升
Episode 高 salience
进入相关经历
```

模型可以写：

```text
雨声让她想起十二岁那一夜。
```

## 45. UC-N4：“我是怎样的人”

请求：

```text
owner=character:linwan
query=我是怎样的人
kinds=[REFLECTION, EPISODE, STATE]
```

召回：

```text
当前 self_model Reflection
  +
支撑它的高显著 Episode
  +
少量相关当前 State
```

输出上下文：

```text
认识：
- 林晚很难信任别人，但一旦接受一个人就会非常忠诚。

经历：
- 十二岁时她认为父亲抛弃了自己。
- 沈砚曾在雪夜冒险救她。
```

这使回答既有总结，也有回忆依据。

## 46. UC-N5：人物成长

前期 Reflection：

```text
self_model=v1
林晚相信依赖别人最终只会被抛弃。
```

新经历积累后：

```text
self_model=v1 → SUPERSEDED
self_model=v2 → CONFIRMED
林晚仍害怕被抛弃，但开始愿意相信经过行动证明的人。
```

旧 Reflection 保留，因此作者可以查询：

```text
林晚在第 20 章和第 80 章如何看待自己？
```

## 47. UC-N6：禁止未来记忆泄露

某 Episode：

```text
occurred_at=chapter-80
```

当前生成：

```text
at=chapter-30
```

硬过滤：

```text
occurred_at <= at
```

即使 embedding 完全命中，也不能进入候选。

---

# 第七部分：失败模式

## 48. 写入失败

### 48.1 抽取器把 Episode 错写成 State

风险：

- 一次情绪被当成长期当前状态；
- 新值错误覆盖稳定 Profile；
- 小说人物状态被未经确认的推测改写。

防线：

- StateDraft 的 key 必须存在于宿主 StateSchema；
- Candidate State 不覆盖 Confirmed State；
- 宿主可为敏感 key 限制允许的 writer 或要求显式 confirm；
- 未知记忆内容默认 Episode。

### 48.2 Reflection 没有证据

风险：

- 模型生成听起来合理但无来源的人格标签。

防线：

- Confirmed Reflection 必须有 evidence；
- RecallResult 展示 evidence；
- 用户可撤回 Reflection。

### 48.3 Episode 语义去重误删重复经历

风险：

- “多次发生”本身就是重要信号；
- 删除后无法形成 Reflection。

防线：

- Episode 不做语义去重；
- 只做写入幂等；
- 上下文阶段做 diversity，不修改存储。

## 49. 召回失败

### 49.1 Candidate Miss

目标记忆未进入任何通道。

诊断：

- FTS5 是否分词或 n-gram 索引失败；
- embedding 是否未完成；
- query 是否缺少上下文；
- scope 是否过早过滤；
- model 是否不适合中文。

### 49.2 Ranked Too Low

目标进入候选但排位太低。

诊断：

- 各路 rank；
- RRF 参数；
- exact tag；
- Candidate 状态降权；
- Recent 噪音。

### 49.3 Budget Dropped

目标排名足够，但被 ContextAssembler 丢弃。

诊断：

- 各 kind 配额；
- 单条长度；
- Required State 占用；
- 去重逻辑。

### 49.4 Wrong Scope

其他用户、角色、任务记忆进入结果。

这是数据隔离错误，不是“相关度不好”。必须作为 P0。

### 49.5 Injected But Unused

记忆已正确注入，但回答模型没有使用。

这是 Prompt/模型消费问题，不应继续调检索。

## 50. 本地索引失败

场景：

- 模型未下载；
- tokenizer 初始化失败；
- WorkManager 被杀；
- vector_blob 损坏；
- 模型版本切换中；
- 磁盘空间不足。

降级：

```text
State direct read
  +
FTS5/BM25
  +
Recent
```

State direct read 不依赖向量索引，因此 required State 仍可正常提供。

RecallResult 必须暴露：

```text
vectorIndexAvailable=false
reason=MODEL_NOT_READY
```

不允许静默伪装成完整召回。

---

# 第八部分：一致性、并发与生命周期

## 51. 事务边界

强一致：

- MemoryItem 正文；
- State/Reflection 版本切换；
- provenance；
- evidence；
- RawEvent consumed 状态；
- index job 创建。

最终一致：

- embedding；
- 向量索引；
- FTS5 rebuild；
- 后台 Reflection。

宿主拥有 StateSchema，不等于宿主必须再保存一份 State 值。以 Memory State 为当前值时，State 与 Episode 可以在同一 `MemoryBatch` 原子提交。若业务引擎已有不可替代的权威状态库，则应通过持久事件单向投影到 Memory，或直接作为外部上下文注入，不能让两个系统同时可写而没有同步协议。

## 52. 并发 State 更新

两个 writer 同时更新同一 State key：

1. SQLite 写事务串行化；
2. 后到事务重新读取当前版本；
3. 如果 expected current ID 不匹配，返回 conflict；
4. 调用方重新决定是否覆盖。

API：

```kotlin
StateDraft(
    key = "profile.location",
    expectedCurrentId = "state-v1",
    ...
)
```

不带 expected ID 时，可以配置：

```text
LAST_WRITE_WINS
REJECT_ON_EXISTING
```

Profile State 默认 `REJECT_ON_CONFLICT`，避免后台任务静默覆盖用户编辑。

## 53. 删除与遗忘

逻辑删除优先：

```text
retract(memoryId)
expire(memoryId)
supersede(memoryId)
```

物理删除仅用于：

- 用户明确要求彻底删除；
- 数据保留策略；
- 测试清理；
- 重建可派生索引。

物理删除必须级联：

- source/evidence 关联；
- tags；
- embeddings；
- FTS5；
- ANN。

RawEvent 是否删除由独立保留策略决定。

## 54. 用户纠错优先级

来源优先级建议：

```text
USER_EDIT
USER_EXPLICIT
APP_RULE
MODEL_EXTRACTED
MODEL_REFLECTION
```

低优先级 writer 不得静默覆盖高优先级 Confirmed State 或 Reflection。

来源优先级是存储策略，不是相关度分数。

---

# 第九部分：评测

## 55. 写入 Eval

分别评：

- Draft 类型是否正确；
- State key 是否存在于对应宿主 StateSchema；
- State payload 是否通过 schema 校验；
- Episode 是否保留完整经历；
- Reflection 是否有证据；
- Reflection key 是否稳定；
- Perspective 是否正确；
- story time 是否正确；
- 幂等；
- State/Reflection 版本切换；
- 用户纠错优先级。

## 56. 召回 Eval

### 56.1 Candidate Recall@K

目标记忆是否进入候选池。

### 56.2 MRR / nDCG

目标记忆是否排在前面。

### 56.3 Context Precision

最终注入内容中有多少真正有帮助。

### 56.4 Scope Safety

禁止：

- 跨用户；
- 跨角色；
- 跨任务；
- 未来章节；
- 已撤回记忆。

### 56.5 Current-State Accuracy

查询“现在”时，required State 必须读取目标时刻有效的当前版本，不能返回已 supersede 的 State。

### 56.6 Historical Recall

查询“以前”时能够召回目标 Episode，或通过显式历史查询读取目标时点有效的 State。

### 56.7 Answer Utility

正确记忆进入上下文后，最终回答是否真的改善。

不要把 Answer Utility 的失败全部归因于召回。

## 57. 失败分类

固定枚举：

```text
NOT_STORED
WRONG_KIND
WRONG_OWNER
WRONG_SCOPE
WRONG_TIME
STATE_SCHEMA_MISMATCH
MISSING_REQUIRED_STATE
NOT_INDEXED
QUERY_MISMATCH
CANDIDATE_MISS
RANKED_TOO_LOW
VERSION_FILTER_ERROR
BUDGET_DROPPED
INJECTED_BUT_UNUSED
```

每条 Eval 必须定位到一个阶段。

## 58. 本地设备 Benchmark

至少覆盖：

- 低端、中端、高端 Android 设备；
- 冷启动；
- 单条 embedding；
- 批量 embedding；
- 1k/10k/更大 MemoryItem 扫描；
- FTS5/BM25；
- 混合召回；
- 峰值内存；
- 磁盘；
- 电量和热；
- WorkManager 中断恢复。

ANN 是否需要，只由这组数据决定。

---

# 第十部分：迁移

## 59. 迁移原则

- 不原地破坏现有图表；
- 新旧实现可并存；
- 先影子召回，再切读取；
- RawEvent 永远保留；
- 索引可以重建；
- 不要求一次迁移就完美恢复语义。

## 60. 迁移阶段

### Phase 1：新表与接口

- 增加 MemoryItem、Source、Evidence、Tag、Embedding 表；
- 保留现有 MemoryStore；
- 新 RecallEngine 只用于测试。

### Phase 2：迁移现有 OpenClaim

保守策略：

```text
OpenClaim → Episode
```

原因：

- 文本无损；
- 不猜它一定是当前业务状态；
- 后续可以按宿主 StateSchema 重新生成 State/Reflection；
- 不会因分类错误覆盖 Profile。

### Phase 3：迁移 Triple

Triple 不按原 predicate 原样迁移成 State。

只有宿主 StateSchema 明确登记并提供映射的谓语，才迁移成 State：

```text
用户 lives_in 上海
→ State key=profile.location, value=上海

用户 allergic_to 花生
→ State key=profile.allergies, value=["花生"]
```

Memory SDK 不根据谓语名称自动猜映射。

无法可靠映射的 Triple 渲染成 Episode/Legacy Memory：

```text
张三 related_to 项目A
→ “张三与项目A有关。”
```

不保留通用关系的闭集 predicate registry；只保留各宿主显式提供的小型 StateSchema。

### Phase 4：Dual Recall

对真实 query 同时运行：

```text
old graph recall
new hybrid recall
```

只记录差异，不影响用户回答。

### Phase 5：切换

满足：

- Recall Eval 达标；
- Scope Safety 零违规；
- State 当前值与 required State 直接读取正确；
- 小说未来信息零泄露；
- Android benchmark 可接受；
- 回滚路径验证。

然后切换默认读取。

### Phase 6：删除旧图主线

移除：

- Triple 抽取要求；
- predicate registry；
- graph tools；
- neighborhood；
- merge nodes；
- graph dream。

旧表延迟一个版本周期后再决定是否删除。

---

# 第十一部分：API 使用草图

## 61. 个人助手写入

```kotlin
val eventId = memory.capture(
    RawEventDraft(
        spaceId = "assistant:u1",
        ownerId = "user:u1",
        role = "user",
        content = "我已经从北京搬到上海了",
        sessionId = "s1",
    ),
)

memory.commit(
    MemoryBatch(
        spaceId = "assistant:u1",
        writerRunId = "learn:s1:001",
        extractorVersion = "cloud-memory-v1",
        stateSchemaVersion = "assistant-profile-v1",
        sourceEventIds = listOf(eventId),
        drafts = listOf(
            StateDraft(
                key = "profile.location",
                text = "用户目前居住在上海",
                payload = buildJsonObject { put("city", "上海") },
                ownerId = "user:u1",
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sourceEventIds = listOf(eventId),
                confidence = 0.98,
            ),
            EpisodeDraft(
                text = "用户从北京搬到上海",
                ownerId = "user:u1",
                occurredAt = now,
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sourceEventIds = listOf(eventId),
                tags = listOf(
                    MemoryTag("LOCATION", "北京"),
                    MemoryTag("LOCATION", "上海"),
                ),
                salience = 0.7,
                confidence = 0.98,
            ),
        ),
    ),
)
```

## 62. 个人助手召回

```kotlin
val recalled = memory.recall(
    RecallRequest(
        spaceId = "assistant:u1",
        ownerId = "user:u1",
        query = "我现在住哪里？",
        requiredStateKeys = setOf("profile.location"),
        budgetChars = 1_500,
        explain = true,
    ),
)
```

## 63. 小说写入

```kotlin
memory.commit(
    MemoryBatch(
        spaceId = "novel:linwan",
        writerRunId = "scene:chapter-42",
        extractorVersion = "novel-memory-v1",
        sourceEventIds = listOf(sceneEventId),
        drafts = listOf(
            EpisodeDraft(
                ownerId = "world",
                text = "沈砚因被官兵抓走而未能赴约。",
                occurredAt = chapter42,
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sourceEventIds = listOf(sceneEventId),
                tags = listOf(MemoryTag("SCENE", "chapter-42")),
                salience = 0.8,
                confidence = 1.0,
            ),
            EpisodeDraft(
                ownerId = "character:linwan",
                text = "沈砚没有赴约，林晚相信自己再次被抛弃。",
                occurredAt = chapter42,
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sourceEventIds = listOf(sceneEventId),
                tags = listOf(
                    MemoryTag("PERSON", "沈砚"),
                    MemoryTag("EMOTION", "被抛弃"),
                ),
                salience = 0.95,
                confidence = 1.0,
            ),
        ),
    ),
)
```

## 64. 小说召回

```kotlin
val recalled = memory.recall(
    RecallRequest(
        spaceId = "novel:linwan",
        ownerId = "character:linwan",
        query = "沈砚为什么没有赴约？",
        at = chapter50,
        includeWorldMemory = false,
        kinds = setOf(MemoryKind.EPISODE, MemoryKind.REFLECTION),
        explain = true,
    ),
)
```

林晚只能看到自己的解释，不能看到 World Episode。

---

# 第十二部分：需要拷打的决策

## 65. 已明确建议

1. 不做图。
2. SQLite 是 Memory Core 的真相源。
3. 本地 FTS5/BM25 + 本地 embedding。
4. State、Episode、Reflection 分开。
5. State/Reflection 版本化，Episode 追加。
6. Perspective/owner 是小说硬边界。
7. 候选池取多路并集。
8. 第一版用 RRF。
9. State 的闭集 schema 由宿主提供；关键 State 按宿主指定的 key 直接读取。
10. Store 不调用 LLM。
11. Embedding 异步，FTS5/BM25 可独立工作。
12. ANN 是否引入由 benchmark 决定。

## 66. 最值得质疑的地方

### 66.1 三种 MemoryKind 是否足够

程序性经验是否属于 Reflection，还是未来需要独立 `PROCEDURE`？

当前建议：先放 Reflection，通过 key 区分；真实 Use Case 证明需要后再拆类型。

### 66.2 State key 谁定义

SDK 不定义 `profile.location`、`character.current_goal` 等字段。

当前边界：

- App/业务引擎提供 StateSchema；
- SDK 不内置大词表；
- Extractor 只能写 schema 中登记的 key；
- Store 校验 key、payload 和 writer 权限，但不解释业务含义；
- 未登记内容降级为 Episode；
- `character.location` 等闭集由小说引擎拥有，State 的通用版本存储机制由 Memory Core 拥有。

### 66.3 Reflection 谁生成、何时生成

当前建议：

- Store 不管；
- Runtime 提供可插拔 ReflectionWorker；
- 默认按 Episode 数量/空闲触发；
- 必须有 evidence；
- 首版可以不开启自动 Reflection。

### 66.4 Required State 谁决定

完全由宿主按场景决定。

- 宿主在 RecallRequest 中传 `requiredStateKeys`；
- SDK 校验 key 属于 StateSchema；
- SDK 按 key 确定性读取，不做意图猜测；
- required State 有独立预算和缺失错误；
- 后续再根据 Eval 决定是否增加本地规则路由。

### 66.5 小说时间使用毫秒还是故事坐标

小说可能需要：

```text
chapter
scene
world timestamp
叙事顺序
```

当前建议：

- `occurred_at` 只要求可排序的 `Long`；
- 小说适配层负责把 `chapter/scene` 编码成单调 story time；
- 额外展示值放 `payload_json`；
- 不把现实毫秒语义写死进核心 Store。

### 66.6 本地 embedding 模型

不能凭模型榜单拍板。

选择门槛：

- 中文 Recall@K；
- 否定、时间、专名测试；
- APK/下载体积；
- 目标设备延迟；
- 峰值内存；
- 电量；
- tokenizer/runtime 可维护性；
- license。

### 66.7 向量 brute force 能撑多大

本文不提供未经 benchmark 的阈值。

需要实测后决定：

- 继续 exact scan；
- 分区 scan；
- 量化；
- HNSW；
- 其他 ANN。

## 67. 对现有愿景文档的影响

如果本设计被接受，不能只改代码。以下现有表述将失效：

- `vision.md` 中“会复利的图谱”；
- `vision.md` 中“有图就有多跳”的价值层次；
- `vision.md` 中“原始日志 → 抽边 → 图更浓”的飞轮；
- `memory-api.md` 的 `graph_id`、闭集关系和图原语；
- `memory-engine.md` 的节点、边、合并与图整理设计；
- `multi-agent-memory.md` 中依赖图隔离或图工具的部分。

建议新的愿景表述：

> 设备保存可纠错、可追溯的当前状态、长期经历和认识；宿主定义 State 语义，Memory 通过直接读取与本地混合召回，把相关上下文交给任意云模型。

“连接”和“复利”不再由图结构自动保证，而由：

- Episode 积累；
- Reflection 归纳；
- State 更新；
- provenance；
- 混合召回；
- 反馈 Eval

共同实现。

在愿景文档同步修改前，这份设计只能是候选方案，不能与当前“图谱是楔子”的定位同时宣称为真。

## 68. 接受标准

这份设计只有同时满足以下条件才值得进入实施计划：

- 个人助手当前资料由 required State 确定性携带，不依赖概率召回；
- 个人助手能够回忆历史经历；
- 小说角色能够保存主观回忆；
- 小说角色不会读取其他角色或未来信息；
- State 的闭集 key 由宿主定义而非 SDK 内置；
- State 更新不会抹除 Episode；
- Reflection 有证据可追溯；
- embedding 不可用时系统仍能工作；
- 调试时能解释每条记忆为什么出现或消失；
- 从现有图实现可渐进迁移；
- 不依赖未经验证的 ANN 和模型性能假设。

