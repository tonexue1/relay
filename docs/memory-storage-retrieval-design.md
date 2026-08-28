# Relay Memory：无图存储与召回设计

> 状态：**候选架构上限 + 可实施 MVP**。本文的完整能力集是演进候选，不是已承诺的目标；只有 §69 的 MVP 部分是当前打算实施的范围。  
> 日期：2026-08-28  
> 目标：只回答两个问题——**记忆怎么存，记忆怎么取**。  
> 核心取舍：采用 Mem0 风格的简洁接口，但不把所有记忆压成“当前事实”；不建设知识图谱。  
> 适用场景：个人助手、陪伴 Agent、小说角色记忆。  
> 本文会替代当前 `Claim + 闭集 Triple + 图召回` 的主线设计；正式接受前，`memory-api.md` 与现有实现仍是真相源。

## 阅读方式

本文分三层，混读会得到错误结论：

```text
候选层    完整能力集：Reflection 自动生成、端侧 embedding、RRF、ANN、
          多时钟认知模型、完整 schema 迁移。
          这些是"如果证据支持才做"，不是路线图承诺。

MVP 层    §69 划定的首版范围，验收清单在 §72。

不变量层  §70 列出的机械保证，任何层都不许违反。
```

每个候选能力都必须在 §71 找到明确的启用触发条件。没有触发条件的机制不进入实施。

正文里凡是标注“候选能力”的章节（§18、§19、§23、§24、§26、§31.2、§33）都不在首版范围内，但它们的接口位置是预留好的，启用时不需要改动已有的存储契约与不变量。

---

## 0. 决策摘要

Relay Memory 采用四类数据：

```text
RawEvent     证据，回答“当时实际发生/说了什么”
State        当前投影，回答“现在是什么”
Episode      一阶记忆，回答“过去经历过什么”
Reflection   二阶记忆，回答“这些经历说明了什么”
```

RawEvent 是原始证据；State、Episode、Reflection 都必须能追溯来源。

### 0.1 State 的两个正交维度

State 的**通用存取机制**属于 Memory；State 的**业务语义**属于宿主。这不是一句原则，而是两个必须逐字段声明的枚举：

```text
authorityMode      谁有资格裁决这个字段的当前值
  MEMORY_AUTHORITATIVE   Memory 就是当前值的裁决者
  HOST_AUTHORITATIVE     宿主业务系统裁决，Memory 无权自行改变

projectionMode     Memory 是否保存一份可读副本
  NONE                   Memory 不保存，宿主每轮作为外部上下文注入
  MEMORY_MIRROR          Memory 保存只读镜像，按宿主 revision 单向推进
```

因此禁止的不是“多个 writer”，而是**双权威**：

```text
允许：MEMORY_AUTHORITATIVE + 多个授权 writer
      通过 allowedWriters、优先级和 CAS 协调

允许：HOST_AUTHORITATIVE + MEMORY_MIRROR
      Memory 接受带宿主 revision 的单向投影

禁止：宿主状态库与 Memory 各自独立决定同一字段的当前值
禁止：Extractor 直接写 HOST_AUTHORITATIVE 字段
```

### 0.2 三方职责

```text
LLM / Extractor    只产出 Proposal：候选 kind、key、payload、证据、置信度
宿主策略层         把 Proposal 裁决成 AuthorizedCommand：
                   principal、writer kind、decision、CAS、来源、有效时间
Memory Engine      只验证并执行 AuthorizedCommand：
                   schema shape、scope、事务、版本链、幂等、provenance、隔离
```

模型没有数据库最终决定权；Memory 不判断事实是否可信。

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
宿主展开的 required State（确定性直接读取，不参与竞争）
  ‖
  ‖   各检索通道（owner/scope/time 硬过滤在查询内完成）
  ‖     FTS5/BM25 精确召回
  ‖     当前任务 / 最近记忆
  ‖     Tag 精确匹配
  ‖     本地向量语义召回（候选能力）
  ‖       ↓
  ‖   候选池取并集
  ‖       ↓
  ‖   排名融合（MVP 为固定规则，RRF 是候选能力）
  ‖       ↓
  ‖   去重、State/Reflection 版本消解
  ↓       ↓
不可截断 + 可截断两段预算组装上下文
```

关键语义：

```text
RawEvent           是不可损来源，不直接等于长期记忆
State              版本化更新，默认只读当前版本
Episode            只追加，不因当前业务状态变化而删除
Reflection         版本化更新，必须能追溯到支撑它的记忆
```

一句话：

> **State 负责现在，Episode 负责回忆，Reflection 负责成长；宿主裁决写什么，Memory 保证怎么存怎么取。**

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
- 模型只产生 Proposal，由宿主裁决成 AuthorizedCommand，不直接操作数据库 ID；
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

## 职责边界与端到端数据流

### 三方角色

```text
┌─────────────────────────────────────────────────────────────┐
│ LLM / Extractor（云端或端侧）                                │
│   输入：RawEvent、最近上下文、宿主给的 key 白名单            │
│   输出：Proposal —— 建议的 kind、key、payload、证据、置信度  │
│   无权：决定覆盖、指定数据库 ID、写 HOST_AUTHORITATIVE 字段  │
└──────────────────────────┬──────────────────────────────────┘
                           │ Proposal
┌──────────────────────────▼──────────────────────────────────┐
│ 宿主策略层（App / 小说引擎 / 业务规则）                      │
│   拥有：StateSchema、ContextContract、writer 权限、优先级     │
│   裁决：接受 / 拒绝 / 降级为 Episode / 要求用户确认           │
│   输出：AuthorizedCommand —— 带 principal、policyVersion、    │
│         decision、CAS、来源、有效时间、canonical payload      │
│         和确定性渲染的 retrieval text                        │
└──────────────────────────┬──────────────────────────────────┘
                           │ AuthorizedCommand
┌──────────────────────────▼──────────────────────────────────┐
│ Memory Engine（本 SDK）                                      │
│   验证：schema shape、writer 权限、CAS、scope、owner、时间    │
│   执行：事务、版本链切换、幂等、provenance、索引任务          │
│   不做：判断事实真假、判断是否同义、猜业务含义、发明 key      │
└─────────────────────────────────────────────────────────────┘
```

### 一次写入的完整链路

```text
1. capture(RawEventDraft)
     → RawEvent 落库，processing_state=PENDING

2. Extractor 消费 RawEvent
     → List<Proposal>

3. 宿主策略层裁决
     → 每个 Proposal 得到一个结果：
        ACCEPT_AS_STATE       生成 StateCommand（含 CAS 与 decision）
        ACCEPT_AS_EPISODE     生成 EpisodeCommand
        ACCEPT_AS_REFLECTION  生成 ReflectionCommand（含 evidence）
        NEEDS_USER_CONFIRM    生成 CANDIDATE，等用户确认
        REJECT                只留在 RawEvent 里，不进长期记忆

4. commit(MemoryBatch)
     → Memory 逐条验证；ATOMIC 模式下任一失败整批回滚

5. RawEvent processing_state=COMMITTED
     → 索引任务入队（最终一致）
```

### 谁拥有什么

| 关注点 | 宿主 | Memory Engine |
| --- | --- | --- |
| State key 闭集与语义 | 拥有 | 只按快照校验 shape |
| State 值的业务合法性 | 拥有 | 不判断 |
| 哪个 key 每轮必带 | 拥有（ContextContract） | 按展开结果直接读取 |
| writer 身份与权限策略 | 拥有 | 执行 allowedWriters 校验 |
| 覆盖 / 冲突裁决意图 | 拥有（decision + CAS） | 执行并在不匹配时报冲突 |
| 当前值唯一性 | 不负责 | 拥有（唯一约束 + 事务） |
| 历史版本可读 | 不负责 | 拥有（版本链） |
| 来源可追溯 | 提供来源引用 | 拥有（provenance 表与强制校验） |
| owner / scope / time 隔离 | 传入参数 | 拥有（硬过滤，不可绕过） |
| 检索与排名 | 可注入策略 | 拥有默认实现与 explain |

### 边界的反面：本 SDK 明确不做的判断

```text
"这句话是不是在说同一件事"        → 宿主 / Extractor
"新说法是否推翻旧说法"            → 宿主 decision
"这个值合不合理"                  → 宿主语义校验
"用户现在需要哪些 key"            → 宿主 ContextContract
"这条记忆重不重要"                → 宿主给 salience
```

Memory 只回答：这条命令是否合法、能否原子执行、执行后当前值是什么、历史还在不在、为什么这条被召回。

## 4. 设计原则

### 4.1 当前状态与历史经历分开

“角色现在位于临安”和“角色曾在杭州生活”是两类记忆：

- 当前地点由 State 直接读取；
- 历史经历由 Episode 检索；
- State 更新不能覆盖或删除 Episode。

小说引擎定义 `character.location` 的含义与合法值；Memory 不把它内置成通用谓语。

### 4.2 真相源与索引分开

SQLite 中的 canonical payload、版本、来源和时间是真相源。

以下都只是可重建派生物：

- 用于检索的 `text`（由宿主确定性渲染 canonical payload 得到）；
- FTS5/BM25；
- embedding；
- 向量索引；
- tags。

`text` 是派生物这一点很重要：payload 与 text 冲突时，payload 赢；renderer 升级后可以整体重渲染并重建索引，不需要迁移真相。

### 4.3 每个当前值只有一个裁决者

同一个 `(space, owner, scope, key)` 的当前值只能有一个权威来源。`authorityMode` 声明它是谁；`projectionMode` 声明 Memory 是否保存副本。

被禁止的是“两个系统各自认为自己说的是现在”，不是“多个 writer”。多 writer 在 `MEMORY_AUTHORITATIVE` 下是正常情况，通过 `allowedWriters`、来源优先级和 CAS 协调。

### 4.4 关键内容不依赖概率召回

严重过敏、身份、当前故事状态等，如果每轮必须知道，宿主应该在 ContextContract 里声明，展开为 required State 由 Memory 按 key 直接读取，而不是祈祷向量 Top-K 命中。

配套要求：required key 缺失、过期、越权或 schema 不匹配时必须**fail-closed**——逐 key 返回结构化 issue 并阻断，不能静默降级，也不能被预算截断。

### 4.5 语义入口保留完整文本

向量和 Reranker 消费的是自然语言文本。不要只保存三元组后，再期待搜索器恢复被压掉的语义。

### 4.6 Store 不猜

Store 不做：

- 判断两句话是否同义；
- 判断新经历是否推翻旧经历；
- 猜测需要更新哪个数据库 ID；
- 从文本补充缺失事实；
- 把 Proposal 自行升级为写入。

Store 只执行明确的 AuthorizedCommand：

```text
registerStateSchema   注册宿主契约快照
capture               追加 RawEvent
commit                原子提交一批 Command
  StateCommand              PUT / CONFIRM / MIRROR
  EpisodeCommand            追加
  ReflectionCommand         PUT / CONFIRM
  RetractCommand            撤回任意 MemoryItem
getStates             按 selector 读取当前 State
getStateHistory       按版本链读取历史
recall                混合召回
```

### 4.7 先可解释，再智能

MVP 起，每条召回结果必须能解释：

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

State 是某个宿主定义的稳定字段在当前时刻的值。

示例：

```text
profile.location = 上海
profile.allergies = ["花生"]
character.location = 临安
character.current_goal = 找到父亲
character.status = 受伤
```

性质：

- 逻辑身份由稳定 `fieldId` 决定，`key` 只是当前版本的可读名（见 §12.1）；
- `key` 必填，且必须属于宿主提供的 StateSchema 快照；
- schema、合法值和更新权限归宿主；
- 同一范围内只有一个当前版本，由数据库唯一约束保证；
- 新版本不会物理覆盖旧行；
- 默认召回只读当前版本；
- 历史查询可读旧版本，且不受 `CONFIRMED-only` 过滤影响（见 §11.3）；
- required State 按 key 直接读取，不参与 Top-K 竞争；
- Store 不理解 key 的业务语义。

每个字段必须声明权威与投影：

```text
authorityMode = MEMORY_AUTHORITATIVE
  Memory 保存的就是当前值。
  例：profile.location、profile.allergies、working_style。
  允许多个授权 writer，通过 allowedWriters + 优先级 + CAS 协调。

authorityMode = HOST_AUTHORITATIVE
  宿主业务系统（小说引擎的世界状态机、账号系统）裁决当前值。
  projectionMode = NONE
    Memory 不存；宿主每轮把值作为外部上下文自行注入。
  projectionMode = MEMORY_MIRROR
    Memory 存只读镜像。写入必须携带宿主 sourceRevision，
    且只接受严格单调递增的 revision；Extractor 与用户编辑一律拒绝。
```

镜像字段的额外语义：

```text
镜像不是权威，召回时必须带 freshness：
  mirroredSourceRevision
  mirroredAt

超过宿主声明的 stalenessBudgetMs 即视为 STALE。
required State 命中 STALE 时按 fail-closed 处理，不当作有效值使用。
```

这条分裂是为了让“宿主已有权威状态库”和“Memory 就是权威”两种真实部署都能表达，而不必二选一，也不会产生两个系统同时可写同一当前值。

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

Extractor 可以提出 State Proposal，但最终写入必须由宿主裁决成 `StateCommand`，且只能落在宿主 StateSchema 快照中登记、且允许该 writer 写入的字段上。未登记、越权或无法可靠分类的内容默认降级为 Episode；Store 不从开放文本自行发明 key。

---

# 第三部分：物理存储

## 9. SQLite 是 Memory Core 的唯一真相源

这里的“唯一”只指 Memory Core 自己拥有的数据：RawEvent、`MEMORY_AUTHORITATIVE` State、Episode、Reflection、provenance 与版本链。

`HOST_AUTHORITATIVE` 字段的真相源在宿主。Memory 对它们只有两种合法姿态：

```text
projectionMode = NONE            不存，不假装知道
projectionMode = MEMORY_MIRROR   存只读镜像，标注 sourceRevision 与新鲜度
```

Memory 不复制宿主的业务所有权，也不允许自己写回镜像字段。

推荐表：

```text
memory_space
state_schema_snapshot
text_renderer
raw_event
memory_item
memory_source
memory_evidence
memory_tag
memory_embedding
embedding_model
index_job
```

`text_renderer` 登记 `(renderer_id, renderer_version)`，让派生 text 可追溯、可整体重渲染。

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
    clock_domain      TEXT NOT NULL,
    occurred_at       INTEGER,
    captured_at       INTEGER NOT NULL,
    processing_state  TEXT NOT NULL,
    content_hash      TEXT NOT NULL,
    idempotency_key   TEXT,
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
- 宿主裁决为全部 REJECT 时标记 `COMMITTED` 并记录零写入原因，不能留在 `PENDING` 无限重试；
- 相同 `idempotency_key` 的事件不得重复插入（唯一索引强制）；
- `occurred_at` 属于 `clock_domain` 声明的时钟域，`captured_at` 恒为 `WALL_CLOCK`。

## 11. memory_item

统一保存 State、Episode、Reflection：

```sql
memory_item (
    id                TEXT PRIMARY KEY,
    space_id          TEXT NOT NULL,
    owner_id          TEXT NOT NULL,
    kind              TEXT NOT NULL,

    -- 逻辑身份：State 用 field_id，Reflection 用 memory_key
    field_id          TEXT,
    memory_key        TEXT,

    payload_json      TEXT NOT NULL DEFAULT '{}',   -- canonical，真相
    text              TEXT NOT NULL,                -- 派生，供检索
    renderer_id       TEXT NOT NULL DEFAULT '',
    renderer_version  TEXT NOT NULL DEFAULT '',

    scope             TEXT NOT NULL,
    scope_id          TEXT NOT NULL DEFAULT '',

    -- 当前头与历史生命周期分离
    is_current        INTEGER NOT NULL DEFAULT 0,
    lifecycle_state   TEXT NOT NULL,

    confidence        REAL NOT NULL,
    salience          REAL NOT NULL DEFAULT 0.5,

    -- 时间：每个字段绑定一个 clock domain
    clock_domain      TEXT NOT NULL,
    occurred_at       INTEGER,
    valid_from        INTEGER,
    valid_to          INTEGER,
    created_at        INTEGER NOT NULL,   -- 恒为 WALL_CLOCK
    updated_at        INTEGER NOT NULL,   -- 恒为 WALL_CLOCK

    supersedes_id     TEXT,
    retracted_at      INTEGER,
    expire_at         INTEGER,

    -- 写入身份与契约
    writer_kind       TEXT NOT NULL,
    writer_id         TEXT NOT NULL,
    policy_version    TEXT NOT NULL,
    writer_run_id     TEXT NOT NULL,
    extractor_version TEXT NOT NULL,
    state_schema_hash TEXT,
    semantic_checker  TEXT,

    -- 镜像字段专用
    mirrored_source_revision  INTEGER,
    mirrored_at               INTEGER,

    payload_hash      TEXT NOT NULL,
    text_hash         TEXT NOT NULL,
    idempotency_key   TEXT
)
```

必需约束：

```sql
-- 同一 State 字段在同一范围内只能有一个当前头
CREATE UNIQUE INDEX ux_state_current
    ON memory_item (space_id, owner_id, scope, scope_id, field_id)
    WHERE kind = 'STATE' AND is_current = 1;

-- 同一 Reflection key 同理
CREATE UNIQUE INDEX ux_reflection_current
    ON memory_item (space_id, owner_id, scope, scope_id, memory_key)
    WHERE kind = 'REFLECTION' AND is_current = 1;

-- CANDIDATE 不占当前头
CREATE UNIQUE INDEX ux_state_candidate
    ON memory_item (space_id, owner_id, scope, scope_id, field_id)
    WHERE kind = 'STATE' AND lifecycle_state = 'CANDIDATE';

-- Episode 写入幂等
CREATE UNIQUE INDEX ux_episode_idem
    ON memory_item (space_id, idempotency_key)
    WHERE kind = 'EPISODE' AND idempotency_key IS NOT NULL;
```

唯一约束比应用层检查重要：并发或代码 bug 导致“两个当前值”是最难发现、后果最严重的一类错误，必须让数据库直接拒绝。

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

### 11.3 当前头与生命周期是两个维度

早期版本把两者混在一个 `lifecycle_state` 里，于是“默认只读 CONFIRMED”和“历史版本可读”直接矛盾：历史版本一旦被标记为 SUPERSEDED，就被 CONFIRMED-only 的过滤条件排除了。

正确的分解：

```text
is_current        这一行是不是"现在"
                  0 / 1，由唯一约束保证每组最多一个 1

lifecycle_state   这一行的真值状态
                  CANDIDATE    待确认，永不是当前头
                  ACTIVE       内容有效
                  RETRACTED    被明确撤回或判错
                  EXPIRED      因保留策略退出默认召回
```

两者的合法组合：

```text
is_current=1, ACTIVE       当前值
is_current=0, ACTIVE       历史版本，曾经成立，历史查询可读
is_current=0, CANDIDATE    待确认提案
is_current=0, RETRACTED    已撤回，默认所有查询都不返回
is_current=0, EXPIRED      退出默认召回，显式历史查询可读
is_current=1, RETRACTED    非法，撤回当前值必须同时清空当前头
```

因此：

```text
默认召回        is_current=1 AND lifecycle_state='ACTIVE'
历史查询        按 field_id 走版本链，接受 is_current=0 AND ACTIVE
                （getStateHistory，不复用召回过滤器）
required State  is_current=1 AND ACTIVE，永不接受 CANDIDATE
```

“被替代”不再是一种 lifecycle 状态，而是 `is_current` 从 1 变 0 加上 `valid_to` 落值。语义没丢，矛盾消失了。

### 11.4 时间：Clock Domain 是必填的

裸 `Long` 时间戳是 bug 温床：小说的第 42 章和 `1756...` 毫秒都能塞进同一个字段，比较出来的结果毫无意义。因此每个时间值必须绑定时钟域。

```kotlin
enum class ClockDomain {
    WALL_CLOCK,      // 真实毫秒
    STORY_TIME,      // 小说内事件发生顺序（单调编码）
}

data class ClockStamp(
    val domain: ClockDomain,
    val value: Long,
)
```

规则：

```text
- 同一 Space 的业务时间只能用一个 domain，写在 memory_space 上；
- 跨 domain 比较直接抛错（CLOCK_DOMAIN_MISMATCH），不做隐式换算；
- created_at / updated_at / retracted_at / mirrored_at 恒为 WALL_CLOCK，
  它们是系统记账时间，与业务时间无关；
- occurred_at / valid_from / valid_to / RecallRequest.at 使用
  memory_item.clock_domain 声明的业务时间。
```

字段含义：

```text
occurred_at   事件在世界中何时发生（业务时钟）
valid_from    State/Reflection 从何时开始适用（业务时钟）
valid_to      State/Reflection 到何时不再适用（业务时钟）
created_at    系统何时写入这条编码（WALL_CLOCK）
updated_at    metadata 最后何时变化（WALL_CLOCK）
retracted_at  系统何时明确撤回（WALL_CLOCK）
```

Episode 通常使用 `occurred_at`，不需要 `valid_to`。State/Reflection 使用 `valid_from/valid_to`。

MVP 到此为止：两个 domain，每个 Space 选一个。小说真正需要的更细认知时间（事件发生 / 角色获知 / 分支可见性）是候选能力，见 §71.4。原因是三时钟会立刻要求分支存储与认知传播规则，而当前没有证据说明单一 story time 撑不住第一批小说场景。

## 12. State 身份、契约与写入授权

### 12.1 稳定 fieldId 与可读 key

State 的逻辑身份不是数据库 ID，也不是可读 key 字符串，而是：

```text
(space_id, owner_id, scope, scope_id, field_id)
```

`field_id` 由宿主在 StateSchema 中分配，一旦分配永不复用、永不改写含义。`memory_key` 是该字段当前的可读名，用于 FTS、UI 和日志。

为什么要分两层：

```text
只有 key            重命名 = 丢历史，或者历史被错误地当成同一字段
fieldId + key       重命名只改显示名，版本链和历史查询不断
```

例如：

```text
field_id = fld_profile_location
memory_key = profile.location        （v1）
memory_key = profile.residence_city  （v2 重命名后）

两者是同一字段，历史值仍然可查。
```

### 12.2 ValueContract：不是完整 JSON Schema

用完整 JSON Schema 描述 State 的 value 会有两个相反的失败：太松则 payload 形状不可预测，太紧则每加一个业务约束都要改 Memory。

因此拆成两层：

```text
Memory 强制层：ValueContract
  只保证"形状稳定、可存储、可渲染、可比较"。
  Memory 必须能机械校验，且失败必须是确定性的。

宿主强制层：语义校验
  枚举合法值、跨字段一致性、业务不变量。
  Memory 不执行，但对高风险字段必须要求宿主已执行（见 12.3）。
```

`ValueContract` 的封闭集合：

```text
TEXT        { value: String }                 带 maxLength
ENUM        { value: String }                 带 allowedValues（闭集，Memory 可校验）
NUMBER      { value: Double }                 带 min/max
BOOL        { value: Boolean }
INSTANT     { value: Long, clock: ClockDomain }
TEXT_LIST   { value: List<String> }           带 maxItems、itemMaxLength
ENUM_LIST   { value: List<String> }           带 allowedValues、maxItems
RECORD      固定字段名 → 上述标量类型          带 requiredFields、无嵌套
OPAQUE_JSON { value: JsonObject }             只校验大小上限，仅镜像字段可用
```

规则：

```text
- 不支持任意嵌套、oneOf、条件 schema、递归引用；
- ENUM/ENUM_LIST 的闭集由宿主给出，Memory 只做集合成员检查，
  不理解"临安"是地名；
- OPAQUE_JSON 只允许 HOST_AUTHORITATIVE + MEMORY_MIRROR 字段使用，
  因为它放弃了 shape 保证，只适合"照搬宿主真相"的场景；
- 校验失败返回 SCHEMA_MISMATCH，附字段、期望契约和实际值摘要。
```

这样“限制过死”的担忧落在正确的位置：形状必须死，语义可以活。

### 12.3 语义准入不是可选项

只对形状校验，会让“过敏原写成一段散文”这种错误顺利入库。因此字段声明风险等级：

```text
riskTier = LOW
  形状校验通过即可写入。
  例：character.mood、working_style。

riskTier = HIGH
  写入命令必须携带 semanticCheck：
    checkerId + checkerVersion + verdict=PASS
  Memory 不做语义判断，但缺少 PASS 一律拒绝，
  错误码 SEMANTIC_CHECK_REQUIRED。
  例：profile.allergies、profile.medical_constraints、身份类字段。
```

Memory 因此仍然“不猜”，但不再给出“我校验过了”的假安全感：它校验的是**宿主是否声明校验过**。

### 12.4 canonical payload 与检索 text

```text
payload_json    canonical，真相
text            派生，供 FTS/embedding/上下文使用
```

要求：

```text
- text 由宿主提供的确定性 renderer 生成，禁止 LLM 现场编写；
- 命令必须携带 textRendererId + textRendererVersion；
- 同一 payload + 同一 renderer 版本必须渲染出同一 text；
- renderer 升级不改变真相，只触发 text 重渲染与索引重建；
- 两者不一致时以 payload 为准，且必须能检出（renderer 版本比对）。
```

### 12.5 写入路径

一次 `StateCommand(PUT)` 在同一 SQLite 事务中：

```text
1.  解析宿主 StateSchema 快照（按 schemaHash 精确匹配，见 12.6）
2.  校验 field 存在、未 deprecated
3.  校验 authorityMode 与命令类型相容
      HOST_AUTHORITATIVE 且 projectionMode=NONE   → 直接拒绝
      HOST_AUTHORITATIVE 且 MEMORY_MIRROR         → 只接受 MIRROR 命令
4.  校验 writerPrincipal ∈ allowedWriters
5.  校验 ValueContract；riskTier=HIGH 时校验 semanticCheck=PASS
6.  校验 textRenderer 版本已登记
7.  读取当前版本（唯一约束保证只有一个）
8.  CAS：
      expectedCurrentId 给出时必须严格相等，否则 CONFLICT
      未给出时按字段的 conflictPolicy 处理
      MIRROR 命令改用 sourceRevision 单调性判定
9.  幂等：payload_hash + renderer 版本相同 → No-op，返回既有 ID
10. 旧当前版本 → is_current=0，写入 valid_to，lifecycle_state 保持 ACTIVE
11. 插入新版本，supersedes_id 指向旧版本，is_current=1
12. 写 provenance（principal、policyVersion、来源引用、schemaHash）
13. 排队索引任务
```

Store 不需要理解 `profile.location` 的业务含义，也不需要 LLM 猜旧 ID：宿主提供稳定 `fieldId`、契约与 CAS。

`CANDIDATE` State 永远不是当前值。它只占据“待确认”位置，必须通过显式 `CONFIRM` 命令或宿主晋升策略才能成为当前值；required State 只读当前值，因此永远读不到 Candidate。

### 12.6 Schema 快照与演进

宿主给的不是“一个 schema 对象”，而是**可定位的快照**：

```sql
state_schema_snapshot (
    space_id        TEXT NOT NULL,
    schema_version  TEXT NOT NULL,
    schema_hash     TEXT NOT NULL,
    fields_json     TEXT NOT NULL,
    registered_at   INTEGER NOT NULL,
    PRIMARY KEY (space_id, schema_hash)
)
```

理由：只记版本字符串挡不住“宿主改了字段但没改版本号”。写入时按 `schema_hash` 匹配，历史行保留写入时的 `schema_hash`，因此任何旧数据都能解释成“当时按哪套契约写的”。

变更分类与 MVP 允许范围：

| 变更 | 分类 | MVP |
| --- | --- | --- |
| 新增字段 | 兼容 | 允许 |
| ENUM 扩大 allowedValues | 兼容 | 允许 |
| maxLength / maxItems 放宽 | 兼容 | 允许 |
| 修改可读 key（fieldId 不变） | 兼容 | 允许 |
| 字段标记 deprecated | 兼容 | 允许，停止新写入，历史仍可读 |
| ENUM 缩小 allowedValues | 破坏 | 禁止 |
| ValueContract 换类型 | 破坏 | 禁止；必须新建 fieldId |
| 删除字段 | 破坏 | 禁止；只允许 deprecated |
| 复用旧 fieldId 表达新含义 | 破坏 | 永久禁止 |

破坏性变更的唯一合法形式：

```text
新建 fieldId
  → 新旧字段共存
  → 宿主自行迁移或回填
  → 旧字段 deprecated
```

Memory 在 MVP 不提供 schema migration DSL。它只提供三件机械保证：兼容性变更检测、破坏性变更拒绝、历史行按写入时 `schema_hash` 可解释。

已存在的历史数据不因新快照失效：读取时用行上记录的 `schema_hash`，不用当前快照。若旧数据在当前契约下已不合法，`getStates` 返回 `SCHEMA_MISMATCH` 而不是静默丢弃。

### 12.7 写入身份与优先级

```text
data class WriterPrincipal(
    val kind: MemoryWriterKind,   // USER_EDIT / HOST_RULE / EXTRACTOR /
                                  // REFLECTION_WORKER / IMPORTER / HOST_MIRROR
    val id: String,               // 具体 writer 标识，用于审计
    val policyVersion: String,    // 宿主裁决策略版本
)
```

字段级授权：

```text
allowedWriters   哪些 writer kind 可以写这个字段
conflictPolicy   未给 CAS 时的行为
                   REJECT_ON_EXISTING     已有当前值即拒绝（默认给敏感字段）
                   LAST_WRITE_WINS        允许覆盖
                   PRIORITY_ORDER         按 §54 来源优先级裁决
```

低优先级 writer 不得静默覆盖高优先级来源写出的当前值。这条由 Memory 机械执行：新命令的 `principal.kind` 优先级低于当前版本来源时，`PRIORITY_ORDER` 下返回 `PRIORITY_BLOCKED`，不是悄悄写入。

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

幂等键由写入方提供，Memory 用唯一索引强制执行（见 §11 `ux_episode_idem`）。缺失即拒绝，错误码 `IDEMPOTENCY_KEY_MISSING`——“忘了传”不能退化成“允许重复写”。

推荐构造：

```text
hash(spaceId + ownerId + writerRunId + commandIndex)
```

这保证同一次 writer run 重试不会产生重复 Episode，而不同时间真实发生两次相似经历仍然是两条。

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

没有 evidence 的 Reflection 只能作为 `CANDIDATE` 保存，不能成为当前值。

Reflection 的逻辑身份与 State 类似：

```text
(space_id, owner_id, scope, scope_id, memory_key)
```

提交新的当前 Reflection 时：

1. 校验 evidence 全部存在且对当前 owner 可见（跨 owner evidence 返回 `EVIDENCE_NOT_VISIBLE`）；
2. 校验 `expectedCurrentId`，避免后台 Worker 覆盖较新的用户编辑；
3. 旧当前版本 `is_current=0` 并写入 `valid_to`，`lifecycle_state` 保持 `ACTIVE`；
4. 插入新版本（`is_current=1`）和 evidence；
5. 排队生成新 embedding。

Reflection 不允许原地改正文。

证据失效的处理：MVP 里 evidence 被 retract **不会**自动让 Reflection 失效，只在 `getStates`/召回结果里标注 `evidenceRetractedCount>0`，由宿主或人工决定是否重算。自动级联失效是候选能力（§71.2），因为它需要一套“重算触发与去抖”机制，且误判会让人格结论反复抖动。

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

`source_type` 与 API 的 `SourceRef.type` 一致：

```text
RAW_EVENT   source_id = RawEvent ID
USER_EDIT   source_id = 用户编辑操作 ID
HOST_TXN    source_id = 宿主事务/状态机 revision 标识
IMPORT      source_id = 导入批次 ID
```

所有 MemoryItem 必须至少满足一种来源条件：

- 至少一条上述来源记录；或
- Reflection 通过 `memory_evidence` 形成可追溯到 RawEvent 的证据链。

这条由提交事务强制校验，缺来源返回 `SOURCE_NOT_FOUND`。写入身份（`writer_kind`、`writer_id`、`policy_version`）记在 `memory_item` 上，与“来源引用”是两件事：前者回答“谁授权写的”，后者回答“依据是什么”。

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
memory_fts（contentless / external content 表）
- memory_id
- text
- key
- tags
```

要求：

- State、Episode、Reflection 都可检索；
- required State 另外按 key 直接读取，不依赖索引；
- RawEvent 使用独立 FTS5 索引，避免默认召回混入原始噪音；
- 中文第一版可沿用 NFKC + CJK 2/3-gram；
- BM25 原始分不与 cosine 直接相加。

### 17.1 过滤必须在 LIMIT 之前

先 `LIMIT` 再过滤是隔离与召回的双重错误：Top-N 可能全是别人的记忆或未来章节，过滤后剩零条，看起来像“召回不到”，实际是查询写错了。

错误写法：

```sql
-- 反例：先取 50 条，再在应用层过滤 owner / scope / time
SELECT memory_id FROM memory_fts
WHERE memory_fts MATCH :query
ORDER BY bm25(memory_fts) LIMIT 50;
```

正确写法：过滤条件与 MATCH 在同一查询内，`LIMIT` 只在过滤之后生效。

```sql
SELECT m.id,
       bm25(memory_fts) AS bm25_score
FROM memory_fts
JOIN memory_item AS m ON m.id = memory_fts.memory_id
WHERE memory_fts MATCH :query
  AND m.space_id = :spaceId
  AND m.owner_id IN (:visibleOwners)
  AND m.kind IN (:kinds)
  AND m.scope IN (:visibleScopes)
  AND m.lifecycle_state = 'ACTIVE'
  AND (m.kind = 'EPISODE' OR m.is_current = 1)
  AND (m.occurred_at IS NULL OR m.occurred_at <= :atBusiness)
  AND (m.valid_from IS NULL OR m.valid_from <= :atBusiness)
  AND (m.valid_to IS NULL OR m.valid_to > :atBusiness)
  AND m.retracted_at IS NULL
  AND (m.expire_at IS NULL OR m.expire_at > :nowWall)
ORDER BY bm25_score
LIMIT :limit;
```

同一规则适用于向量通道：metadata 预过滤先做，再算 Top-K；不允许“先 ANN Top-K 再过滤”。

注意：

- SQLite FTS5 的 `bm25()` 已做符号变换，数值越小排名越靠前；
- RecallEngine 只消费 BM25 的相对排名，不把原始分当概率；
- CJK 2/3-gram 是 BM25 的 token 输入，不提供语义理解；
- 专名、数字、原话和精确短语优先依赖此通道；
- `:atBusiness` 与 `:nowWall` 是两个不同时钟域的参数，不可混用（见 §11.4）。

## 18. Embedding 存储（候选能力，§71.3）

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

## 19. 向量索引（候选能力，§71.3）

启用向量通道时不直接引入 HNSW。

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

目标是 Mem0 风格的小接口，但每个写入必须能表达“谁授权的、覆盖谁、按哪套契约”：

```kotlin
interface MemoryRuntime {
    suspend fun capture(event: RawEventDraft): RawEventId

    suspend fun registerStateSchema(snapshot: StateSchemaSnapshot): SchemaRegistration

    suspend fun commit(batch: MemoryBatch): CommitResult

    suspend fun recall(request: RecallRequest): RecallResult

    suspend fun getStates(request: StateReadRequest): StateReadResult

    suspend fun getStateHistory(request: StateHistoryRequest): List<StateVersion>

    suspend fun indexHealth(spaceId: String): IndexHealth
}
```

### 20.1 Schema 与契约

```kotlin
data class StateFieldSpec(
    val fieldId: String,
    val key: String,
    val contract: ValueContract,
    val authorityMode: AuthorityMode,
    val projectionMode: ProjectionMode,
    val riskTier: RiskTier,
    val allowedWriters: Set<MemoryWriterKind>,
    val conflictPolicy: ConflictPolicy,
    val allowedScopes: Set<MemoryScope>,
    val textRendererId: String,
    val stalenessBudgetMs: Long? = null,   // 仅 MEMORY_MIRROR
    val deprecated: Boolean = false,
)

sealed interface ValueContract {
    data class Text(val maxLength: Int) : ValueContract
    data class Enum(val allowedValues: Set<String>) : ValueContract
    data class Number(val min: Double, val max: Double) : ValueContract
    data object Bool : ValueContract
    data class Instant(val clock: ClockDomain) : ValueContract
    data class TextList(val maxItems: Int, val itemMaxLength: Int) : ValueContract
    data class EnumList(val allowedValues: Set<String>, val maxItems: Int) : ValueContract
    data class Record(
        val fields: Map<String, ValueContract>,   // 值只能是标量契约
        val requiredFields: Set<String>,
    ) : ValueContract
    data class OpaqueJson(val maxBytes: Int) : ValueContract   // 仅镜像字段
}

enum class AuthorityMode { MEMORY_AUTHORITATIVE, HOST_AUTHORITATIVE }
enum class ProjectionMode { NONE, MEMORY_MIRROR }
enum class RiskTier { LOW, HIGH }
enum class ConflictPolicy { REJECT_ON_EXISTING, LAST_WRITE_WINS, PRIORITY_ORDER }

data class StateSchemaSnapshot(
    val spaceId: String,
    val schemaVersion: String,
    val fields: List<StateFieldSpec>,
) {
    val schemaHash: String get() = /* 对规范化 fields 求稳定 hash */ TODO()
}

fun interface StateSchemaProvider {
    fun snapshotFor(spaceId: String): StateSchemaSnapshot?
}
```

`registerStateSchema` 返回兼容性判定：

```kotlin
sealed interface SchemaRegistration {
    data class Accepted(val schemaHash: String, val changes: List<SchemaChange>) : SchemaRegistration
    data class Rejected(val breakingChanges: List<SchemaChange>) : SchemaRegistration
}

data class SchemaChange(
    val fieldId: String,
    val kind: SchemaChangeKind,   // FIELD_ADDED / ENUM_WIDENED / LIMIT_RELAXED /
                                  // KEY_RENAMED / DEPRECATED / ENUM_NARROWED /
                                  // CONTRACT_TYPE_CHANGED / FIELD_REMOVED /
                                  // FIELD_ID_REUSED
    val breaking: Boolean,
)
```

SDK 不附带 `profile.*`、`character.*` 等内置词表。

### 20.2 Proposal：模型能表达的全部

```kotlin
data class MemoryProposal(
    val suggestedKind: MemoryKind,
    val suggestedFieldId: String? = null,
    val text: String,
    val payload: JsonObject,
    val ownerId: String,
    val sourceEventIds: List<String>,
    val confidence: Double,
    val rationale: String? = null,
)
```

Proposal 不是写入。它没有 principal、没有 CAS、没有 decision，因此在类型层面无法提交给 `commit`。

### 20.3 AuthorizedCommand：宿主裁决的结果

```kotlin
data class WriterPrincipal(
    val kind: MemoryWriterKind,
    val id: String,
    val policyVersion: String,
)

enum class MemoryWriterKind {
    USER_EDIT, HOST_RULE, EXTRACTOR, REFLECTION_WORKER, IMPORTER, HOST_MIRROR,
}

data class SemanticCheck(
    val checkerId: String,
    val checkerVersion: String,
    val verdict: SemanticVerdict,   // PASS / FAIL
)

data class RenderedText(
    val text: String,
    val rendererId: String,
    val rendererVersion: String,
)

data class SourceRef(
    val type: SourceType,   // RAW_EVENT / USER_EDIT / HOST_TXN / IMPORT
    val id: String,
)

sealed interface MemoryCommand {
    val principal: WriterPrincipal
    val ownerId: String
    val scope: MemoryScope
    val scopeId: String
    val sources: List<SourceRef>
}

data class StateCommand(
    override val principal: WriterPrincipal,
    override val ownerId: String,
    override val scope: MemoryScope,
    override val scopeId: String,
    override val sources: List<SourceRef>,
    val fieldId: String,
    val op: StateOp,                       // PUT / CONFIRM / MIRROR
    val payload: JsonObject,
    val rendered: RenderedText,
    val validFrom: ClockStamp,
    val expectedCurrentId: String?,        // CAS；null 时走 conflictPolicy
    val sourceRevision: Long? = null,      // MIRROR 必填，且必须单调递增
    val semanticCheck: SemanticCheck? = null,   // riskTier=HIGH 必填
    val confidence: Double,
    val targetLifecycle: TargetLifecycle = TargetLifecycle.CURRENT,
) : MemoryCommand

data class EpisodeCommand(
    override val principal: WriterPrincipal,
    override val ownerId: String,
    override val scope: MemoryScope,
    override val scopeId: String,
    override val sources: List<SourceRef>,
    val rendered: RenderedText,
    val payload: JsonObject = JsonObject(emptyMap()),
    val occurredAt: ClockStamp?,
    val tags: List<MemoryTag>,
    val salience: Double,
    val confidence: Double,
    val idempotencyKey: String,
    val targetLifecycle: TargetLifecycle = TargetLifecycle.CURRENT,
) : MemoryCommand

data class ReflectionCommand(
    override val principal: WriterPrincipal,
    override val ownerId: String,
    override val scope: MemoryScope,
    override val scopeId: String,
    override val sources: List<SourceRef>,
    val reflectionKey: String,
    val rendered: RenderedText,
    val evidence: List<EvidenceRef>,
    val validFrom: ClockStamp,
    val expectedCurrentId: String?,
    val confidence: Double,
    val targetLifecycle: TargetLifecycle = TargetLifecycle.CURRENT,
) : MemoryCommand

enum class TargetLifecycle { CANDIDATE, CURRENT }

data class RetractCommand(
    override val principal: WriterPrincipal,
    override val ownerId: String,
    override val scope: MemoryScope,
    override val scopeId: String,
    override val sources: List<SourceRef>,
    val memoryId: String,
    val reason: String,
) : MemoryCommand

data class MemoryBatch(
    val spaceId: String,
    val writerRunId: String,
    val extractorVersion: String,
    val stateSchemaHash: String?,   // 含 StateCommand 时必填
    val commands: List<MemoryCommand>,
    val commitMode: CommitMode = CommitMode.ATOMIC,
)
```

含 `StateCommand` 的批次必须提供 `stateSchemaHash`，且必须精确匹配已注册快照；纯 Episode 批次可为 `null`。`stateSchemaHash` 落到每一行，用于事后解释与重放。

### 20.4 读取

```kotlin
data class StateSelector(
    val fieldId: String,
    val scope: MemoryScope? = null,   // null 表示按 §30 覆盖顺序解析
    val scopeId: String = "",
)

data class StateReadRequest(
    val spaceId: String,
    val ownerId: String,
    val selectors: Set<StateSelector>,
    val at: ClockStamp,
)

data class StateReadResult(
    val present: Map<String, StateItem>,
    val issues: List<StateIssue>,
)

data class StateHistoryRequest(
    val spaceId: String,
    val ownerId: String,
    val fieldId: String,
    val scope: MemoryScope? = null,
    val fromInclusive: ClockStamp? = null,
    val toExclusive: ClockStamp? = null,
    val includeRetracted: Boolean = false,
)
```

`getStateHistory` 是独立入口，不与召回共用过滤器；它按 `field_id` 走版本链，因此 `is_current=0` 的历史版本可读（见 §11.3）。

### 20.5 提交结果

```kotlin
sealed interface CommitResult {
    data class Committed(
        val writes: List<CommittedWrite>,
        val noOps: List<NoOpWrite>,
    ) : CommitResult

    data class Rejected(val failures: List<CommandFailure>) : CommitResult

    data class PartiallyCommitted(          // 仅 BEST_EFFORT
        val writes: List<CommittedWrite>,
        val failures: List<CommandFailure>,
    ) : CommitResult
}

data class CommandFailure(
    val commandIndex: Int,
    val code: CommitErrorCode,
    val detail: String,
)

enum class CommitErrorCode {
    UNKNOWN_FIELD,
    FIELD_DEPRECATED,
    SCHEMA_HASH_UNKNOWN,
    SCHEMA_MISMATCH,
    SEMANTIC_CHECK_REQUIRED,
    WRITER_NOT_ALLOWED,
    AUTHORITY_VIOLATION,          // 写 HOST_AUTHORITATIVE / 非 MIRROR 命令
    STALE_SOURCE_REVISION,        // 镜像 revision 回退
    CAS_CONFLICT,
    PRIORITY_BLOCKED,
    SCOPE_NOT_ALLOWED,
    SOURCE_NOT_FOUND,
    EVIDENCE_NOT_VISIBLE,
    RENDERER_UNKNOWN,
    CLOCK_DOMAIN_MISMATCH,
    IDEMPOTENCY_KEY_MISSING,
}
```

## 21. 写入者与 Store 的职责

写入者可以是：

- 云端 Extractor（只产 Proposal）；
- App 业务规则；
- 用户编辑；
- 后台 Reflection Worker；
- 宿主状态镜像器；
- 导入器。

宿主策略层负责：

- 把 Proposal 裁决成 State / Episode / Reflection 命令，或拒绝；
- 只使用 StateSchema 快照中登记且允许该 writer 的 `fieldId`；
- 提供 `WriterPrincipal` 与 `policyVersion`；
- 提供 owner、scope、来源与时间戳（含 clock domain）；
- 明确覆盖意图：`expectedCurrentId` 或依赖字段 `conflictPolicy`；
- 为高风险字段提供 `SemanticCheck`；
- 用登记过的 renderer 确定性渲染 `text`；
- 为 Reflection 提供 evidence；
- 为 Episode 提供幂等键。

Store 负责：

- 按 `schemaHash` 匹配快照，校验字段存在、未废弃、契约形状；
- 校验 authority/projection 相容性与 writer 权限；
- 校验 scope 合法性与 owner 隔离；
- CAS、优先级与镜像 revision 单调性；
- 事务与版本链切换；
- 幂等；
- provenance 强制；
- 索引任务；
- 生命周期状态。

Store **不**负责判断值是否合理、是否同义、是否推翻旧结论。

## 22. 写入事务

`commit(MemoryBatch)` 必须原子提交：

```text
解析 stateSchemaHash → 快照（未注册直接整批拒绝）
  ↓
逐条校验命令：字段 / 契约 / 权威 / writer / scope / renderer / clock
  ↓
校验来源存在（RawEvent、宿主事务、用户编辑）
  ↓
State：CAS 或 conflictPolicy 或 sourceRevision 判定 → 版本链切换
  ↓
Episode：按幂等键追加
  ↓
Reflection：校验 evidence 可见性 → 版本链切换 → 写 evidence
  ↓
写 tags
  ↓
标记 RawEvent processing_state=COMMITTED
  ↓
创建 index_job
  ↓
COMMIT
```

同批坏稿如何处理由调用参数决定：

```text
ATOMIC      任一失败，整批回滚
BEST_EFFORT 合法命令提交，错误逐条返回
```

默认 `ATOMIC`，避免一段经历的 State、Episode 和 Reflection 只写一半。

`BEST_EFFORT` 有一个硬约束：同一 `fieldId` 的多条 State 命令必须整组成功或整组失败，不允许只应用其中一条造成中间态当前值。

## 23. Embedding 异步写入（候选能力，§71.3）

FTS5 索引在写入事务内同步更新，因此 MVP 没有“写完搜不到”的窗口。只有 embedding 是异步的：

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

## 24. 本地 Embedding Provider（候选能力，§71.3）

```kotlin
interface EmbeddingProvider {
    val manifest: EmbeddingManifest

    suspend fun embedDocuments(texts: List<String>): List<FloatArray>

    suspend fun embedQuery(text: String): FloatArray
}
```

启用向量通道时必须使用独立的 embedding 模型，不复用 3B 聊天模型：

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

## 26. Embedding 模型升级（候选能力，§71.3）

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
    val at: ClockStamp,
    val kinds: Set<MemoryKind> = MemoryKind.entries.toSet(),

    // required State 来自宿主 ContextContract 的展开结果
    val requiredFields: List<RequiredField> = emptyList(),
    val contextContractId: String? = null,
    val contextContractVersion: String? = null,

    val allowCrossTask: Boolean = false,
    val includeWorldMemory: Boolean = false,
    val budgetChars: Int = 2_000,
    val explain: Boolean = false,
)
```

`at` 是 `ClockStamp` 而不是裸 `Long`（见 §11.4）：小说场景传故事时间，助手场景传真实时间，两者不能互相误用。

传了 `requiredFields` 就必须同时传 `contextContractId/Version`，否则无法回答“这轮为什么必须带这些字段”。缺失时返回 `MISSING_CONTRACT_REF`。

## 28. Query 构造

当前只使用 Latest User 的方式不够。

MVP 的确定性 Query：

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

硬过滤必须在数据库层与 MATCH / 向量查询同批执行，不是应用层的后置清理（见 §17.1）。

候选进入排名前必须满足：

```text
space_id 匹配
owner_id 在可见集合内
kind 在请求范围
scope 当前可见
lifecycle_state = ACTIVE
时间有效（业务时钟）
小说 story time 不超过当前 at
```

### 29.1 State/Reflection 当前值

默认：

```text
is_current = 1
lifecycle_state = 'ACTIVE'
valid_from IS NULL OR valid_from <= at
valid_to IS NULL OR valid_to > at
```

历史版本（`is_current=0 AND ACTIVE`）不进入默认召回，只能通过 `getStateHistory` 读取。这条把 §11.3 的两个维度落到查询上：历史仍然存在且可读，但不会污染“现在”。

### 29.2 Episode

默认：

```text
lifecycle_state = 'ACTIVE'
occurred_at IS NULL OR occurred_at <= at
retracted_at IS NULL
expire_at IS NULL OR expire_at > nowWall
```

Episode 没有当前头概念，因此不参与 `is_current` 过滤。

`CANDIDATE` Episode 是否召回由产品配置决定，默认不召回。`CANDIDATE` State 永远不进入 required State 上下文，且因为 `is_current=0` 也不会进入默认召回。

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

### 30.1 它来自宿主的 ContextContract

required State 不是调用方随手填的一串 key，而是宿主**场景契约**的展开结果：

```text
宿主侧（Memory 不实现）：
  ContextContract(
      contractId = "diet-advice",
      contractVersion = "v3",
      requiredFields = [fld_profile_allergies, fld_profile_diet,
                        fld_profile_medical_constraints],
      optionalFields = [fld_profile_cuisine_pref],
      onMissing = BLOCK,
  )

Memory 侧：
  只接收展开后的 requiredFieldIds + contractId/contractVersion，
  记录到 explain trace 与召回审计，用于回答
  "这轮为什么必须带这三个字段"。
```

这样做的边界理由：判断“点外卖场景需要过敏原”是业务知识，属于宿主；判断“要求的字段有没有拿到”是机械保证，属于 Memory。MVP 不在 SDK 内实现 contract 求值器，只要求调用方把 `contractId/contractVersion` 一起传进来。

例如饮食助手展开出：

```text
profile.allergies
profile.diet
profile.medical_constraints
```

小说角色运行时展开出：

```text
character.location
character.current_goal
character.status
```

这些字段是宿主的闭集，不是 SDK 内置谓语；它们不参与向量、BM25 或排名融合竞争。

### 30.2 逐字段结果，不是“有或没有”

Memory 对每个 required field 返回一个明确状态：

```kotlin
enum class RequiredStateStatus {
    PRESENT,            // 当前值存在、契约匹配、新鲜
    MISSING,            // 该 owner/scope 下没有当前值
    CANDIDATE_ONLY,     // 只有未确认版本，不可作为当前值使用
    STALE,              // 镜像字段超过 stalenessBudgetMs
    UNAUTHORIZED,       // 调用方 owner 无权读取该字段
    SCHEMA_MISMATCH,    // 历史行按写入时契约已不兼容当前契约
    UNKNOWN_FIELD,      // fieldId 不在当前 schema 快照中
    NOT_PROJECTED,      // HOST_AUTHORITATIVE + projectionMode=NONE
}

data class StateIssue(
    val fieldId: String,
    val status: RequiredStateStatus,
    val detail: String,
    val mirroredAt: Long? = null,
    val mirroredSourceRevision: Long? = null,
)
```

`NOT_PROJECTED` 是一条重要的显式失败：宿主把“自己保管、不投影”的字段写进 required 列表，说明调用点搞错了责任，Memory 必须报错而不是返回空。

### 30.3 fail-closed 闭环

```text
所有 required field = PRESENT
  → RecallResult.Ready

存在任一非 PRESENT
  → RecallResult.Blocked(issues)
  → 不返回 context 字符串
  → 宿主必须处理：向用户追问、走安全兜底话术、或拒绝该轮请求
```

允许宿主显式放宽单个字段：

```kotlin
data class RequiredField(
    val fieldId: String,
    val onMissing: OnMissing = OnMissing.BLOCK,   // BLOCK / WARN
)
```

`WARN` 的语义是“缺了也继续，但必须出现在 warnings 里”。默认是 `BLOCK`：过敏原这类字段静默缺失比报错危险得多。

三条不可协商的规则：

```text
1. required State 永不被预算截断。
   预算不足时截断的是 Episode 与 Reflection，
   全部可截断内容清空后仍不够，返回 BUDGET_EXCEEDED，
   而不是丢掉过敏原。

2. required State 只读当前值，永不读 CANDIDATE。

3. required State 走 getStates 同一条直接读取路径，
   不经过 FTS、向量或排名，因此向量索引损坏时仍然可用。
```

### 30.4 Scope 解析

若同一字段在多个可见 Scope 都有当前值，采用确定性覆盖顺序：

```text
SESSION > TASK > PROFILE
```

覆盖顺序只在 selector 未指定 scope 时生效，且必须记入 explain trace（选中了哪个 scope、屏蔽了哪些）。需要读取指定 Scope 时，用 `StateSelector` 显式给出 scope；需要读取历史时点，用 `getStateHistory`。

## 31. 候选生成

候选层四路并行；MVP 只启用其中三路：

```text
FtsRetriever      MVP
RecentRetriever   MVP
TagRetriever      MVP
VectorRetriever   候选能力（§71.3）
```

通道接口从一开始就按多路设计，因此增删一路不改变融合、去重和 explain 结构。

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

### 31.2 Vector（候选能力）

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

MVP 不集成中文 NER。可以使用：

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

## 33. RRF 融合（候选能力）

MVP 不用 RRF：只有 FTS + Recent + Tag 时，“BM25 主序 + §34 的少量规则调整”与 RRF 差别很小，却要多引入一个不可解释参数。启用条件见 §71.5。

启用后的形式：

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

为什么在多通道时选 RRF 而不是加权求和：

- BM25、cosine、recency 不同尺度；
- 避免过早发明复杂权重；
- 易解释；
- 易做消融实验。

## 34. 融合后的规则调整

MVP 的排名就是“BM25 主序 + 本节规则”；RRF 启用后本节规则作用在 RRF 结果之上。两种情况下都只允许少量可解释规则：

```text
当前 task exact match        小幅提升
exact tag match              小幅提升
CANDIDATE                    降低（若产品允许召回）
非常低 confidence            降低或过滤
同 text_hash                 去重
```

`is_current=0`、`RETRACTED`、`EXPIRED` 和未来 story time 不在这里“降权”，它们在 §29 硬过滤阶段就已被排除。排名阶段不承担隔离责任。

MVP 禁止使用：

- 十几个不可解释权重；
- LLM 自评相关度；
- 不可复现的“智能分”；
- 将 confidence、cosine、BM25 直接裸相加。

## 35. 去重与版本消解

顺序：

1. 排除不可见状态（已在硬过滤完成，这里只做断言校验）；
2. 同一 `field_id` 只保留一条 State（唯一约束已保证，出现多条即 P0 数据错误，必须报警而非静默取一条）；
3. 同一 Reflection key 同理；
4. 完全相同 `text_hash` 只保留最高排名；
5. 高度相似 Episode 不自动删除，只在当前上下文中做 diversity；
6. 已进入 required State 的字段，不再作为普通候选重复注入。

## 36. ContextAssembler

输入：

```text
required states
ranked states
ranked reflections
ranked episodes
budget
```

预算分两类，这个区分比具体比例重要：

```text
不可截断部分    required State（onMissing=BLOCK 的字段）
可截断部分      ranked State、Reflection、Episode
```

分配顺序：

```text
1. 先无条件放入全部不可截断内容
2. 若不可截断内容已超预算
     → 不裁剪，返回 RecallResult.Blocked(BUDGET_EXCEEDED)
     → 由宿主决定缩减 contract 或提高预算
3. 剩余预算按比例分给可截断部分
```

剩余预算的默认比例仅作为初始配置，不是产品真理：

```text
State（非 required）   20%
Reflection             25%
Episode                55%
```

规则：

- 不可截断内容永远优先，且不参与比例分配；
- 可截断的每类至少有独立上限，避免互相饿死；
- 单条超长 Episode 允许摘要字段，但原文仍可回查；
- 输出标记 kind、时间、来源和是否 required；
- 已进入 required 的字段不再作为普通候选重复注入；
- Query 本身不能混入记忆正文。

建议格式：

```text
<memory_context>
  <required_state>
  - 过敏原：花生。（宿主权威，contract diet-advice/v3）
  </required_state>

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

结果是密封类型，因为“上下文拿到了”和“关键字段缺失”必须在类型层面区分，不能靠调用方记得检查一个布尔值：

```kotlin
sealed interface RecallResult {
    val trace: RecallTrace?

    data class Ready(
        val context: String,
        val requiredStates: Map<String, StateItem>,
        val selected: List<SelectedMemory>,
        val warnings: List<StateIssue>,       // onMissing=WARN 的缺失
        val degradations: List<Degradation>,  // 向量索引不可用等
        override val trace: RecallTrace?,
    ) : RecallResult

    data class Blocked(
        val reason: BlockedReason,            // REQUIRED_STATE_UNAVAILABLE /
                                              // BUDGET_EXCEEDED /
                                              // MISSING_CONTRACT_REF
        val issues: List<StateIssue>,
        override val trace: RecallTrace?,
    ) : RecallResult
}

data class Degradation(
    val component: String,        // VECTOR_INDEX / FTS / EMBEDDING_MODEL
    val reason: String,           // MODEL_NOT_READY / INDEX_CORRUPT / ...
)
```

`Blocked` 不返回 `context`：如果允许它同时返回一个“少了过敏原的上下文”，调用方就会直接用它。

`explain=true` 返回：

```text
query 构造结果
required field 解析（选中 scope、屏蔽的 scope、逐字段状态）
contextContractId / contractVersion
每路候选
每路排名
融合贡献（MVP 为固定规则贡献，RRF 启用后为各路 RRF 贡献）
硬过滤原因
版本消解
预算淘汰（区分可截断与不可截断）
最终选择
embedding model ID
index health 与降级项
```

没有 explain，就无法有效调 Recall。

---

# 第六部分：Use Cases

## 38. UC-A1：个人助手更新当前位置

输入：

```text
我已经从北京搬到上海了。
```

链路：

```text
1. capture(RawEvent)
     原始用户消息

2. Extractor 产出 Proposal
     suggestedKind=STATE
     suggestedFieldId=fld_profile_location
     payload={"value":"上海"}
     confidence=0.98

3. 宿主策略层裁决
     字段存在、未 deprecated
     authorityMode=MEMORY_AUTHORITATIVE
     EXTRACTOR ∈ allowedWriters
     riskTier=LOW，无需 SemanticCheck
     → ACCEPT_AS_STATE + 同时 ACCEPT_AS_EPISODE

4. commit(MemoryBatch)
     stateSchemaHash=<assistant-profile-v1 的 hash>

     StateCommand(
       fieldId=fld_profile_location, op=PUT,
       payload={"value":"上海"},
       rendered=("用户目前居住在上海", renderer=profile-zh, v2),
       expectedCurrentId=<旧 State ID>,
       principal=(EXTRACTOR, cloud-mem-v1, policy-7))

     EpisodeCommand(
       rendered=("用户从北京搬到上海", ...),
       occurredAt=ClockStamp(WALL_CLOCK, 消息时间),
       idempotencyKey=hash(...))
```

Store 执行：

```text
旧 State“北京” → is_current=0，valid_to=now，lifecycle 仍为 ACTIVE
新 State“上海” → is_current=1，ACTIVE，supersedes_id=旧 ID
Episode → APPEND（幂等键去重）
两者在同一事务内原子提交
```

查询：

```text
“我现在住哪里？”
→ required field: fld_profile_location
→ 上海（PRESENT）

“我以前住哪里？”
→ Episode 召回 + getStateHistory(fld_profile_location)
→ 北京（is_current=0 的历史版本，仍然 ACTIVE）
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

宿主的 ContextContract 展开后传入：

```text
contextContractId=diet-advice
contextContractVersion=v3
requiredFields=[
  (fld_profile_allergies,           onMissing=BLOCK),
  (fld_profile_diet,                onMissing=WARN),
  (fld_profile_medical_constraints, onMissing=BLOCK),
]
```

字段声明（宿主 schema）：

```text
fld_profile_allergies
  contract=ENUM_LIST(allowedValues=<过敏原词表>, maxItems=32)
  riskTier=HIGH
  allowedWriters={USER_EDIT, HOST_RULE}
  conflictPolicy=PRIORITY_ORDER
```

结果分两种，都是确定的：

```text
全部 PRESENT
  → Ready，花生过敏进入不可截断预算段，不参与 Top-K 竞争

fld_profile_allergies = MISSING
  → Blocked(REQUIRED_STATE_UNAVAILABLE)
  → 宿主必须先问“你有食物过敏吗”，
    而不是拿着不完整上下文给出自信建议
```

注意 `allowedWriters` 不含 `EXTRACTOR`：过敏原只能由用户编辑或宿主规则写入，模型的推测最多变成一条 Episode 或 CANDIDATE。

## 41. UC-A4：形成用户工作方式 Reflection

已有 Episode：

```text
用户在三个方案讨论中都先要求验证核心假设。
用户拒绝在根因未知时直接修改代码。
```

MVP 里没有自动生成器（§71.1），因此这条 Reflection 来自宿主或人工触发的 `ReflectionCommand`：

```text
ReflectionCommand(
  reflectionKey=working_style,
  rendered=("用户偏好先查证关键假设和根因，再进入实现。", renderer=reflection-zh, v1),
  evidence=[episode-1, episode-2, episode-3],
  principal=(REFLECTION_WORKER, style-worker, policy-3),
  expectedCurrentId=<旧 working_style ID 或 null>)
```

evidence 为空时 Memory 只接受 `targetLifecycle=CANDIDATE`，不能成为当前值。

以后讨论技术方案时：

```text
MVP：BM25 命中“假设/根因/验证”等词，或 Recent 通道带出
候选：向量语义召回（§71.3）
另一条路：宿主把 working_style 也登记为 State 字段，
          按 required State 每轮确定性注入
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

这个用例的关键是先回答一个问题：小说引擎自己有没有权威状态机？两种部署都合法，但必须选一种。

情况一：Memory 就是权威（轻量小说 App）

```text
fld_character_location
  contract=ENUM(allowedValues=<地点表>)
  authorityMode=MEMORY_AUTHORITATIVE
  projectionMode=NONE
  allowedWriters={HOST_RULE, USER_EDIT}
  clockDomain=STORY_TIME
```

引擎在每个场景结算后提交 `StateCommand(PUT)`，Memory 保存当前值与历史。

情况二：引擎有自己的世界状态机（成熟叙事引擎）

```text
fld_character_location
  authorityMode=HOST_AUTHORITATIVE
  projectionMode=MEMORY_MIRROR
  allowedWriters={HOST_MIRROR}
  stalenessBudgetMs=<一个场景的时长>
```

引擎每次状态变化后投影：

```text
StateCommand(op=MIRROR, sourceRevision=<状态机 revision>, ...)
```

Memory 只接受严格递增 revision，且召回时带上 `mirroredAt`。超出 staleness 判 `STALE`，按 fail-closed 阻断——宁可让引擎重新投影，也不让角色拿着过期地点写场景。

两种情况共同的部分：

```text
character.location = 临安
character.current_goal = 找到父亲
character.status = 受伤

场景生成前，引擎展开 ContextContract("scene-generation", v2)，
把这三个字段作为 requiredFields 传给 recall。
```

闭集由小说引擎定义，版本存储与直接读取由 Memory 提供，因此人物不会因语义召回失败而突然出现在错误地点。被禁止的只有第三种：引擎和 Memory 各自独立改这三个字段。

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
self_model v1 → is_current=0，valid_to=chapter-62，lifecycle 仍为 ACTIVE
self_model v2 → is_current=1，ACTIVE，valid_from=chapter-62
林晚仍害怕被抛弃，但开始愿意相信经过行动证明的人。
```

旧 Reflection 保留且**仍然可读**（这正是 §11.3 分离两个维度的目的），因此作者可以查询：

```text
林晚在第 20 章和第 80 章如何看待自己？
→ 按 STORY_TIME 走版本链，取 valid_from <= at < valid_to 的版本
```

## 47. UC-N6：禁止未来记忆泄露

某 Episode：

```text
clock_domain=STORY_TIME
occurred_at=chapter-80
```

当前生成：

```text
at=ClockStamp(STORY_TIME, chapter-30)
```

硬过滤在 SQL 层与 MATCH 同批执行（§17.1）：

```text
occurred_at <= :atBusiness
```

即使检索完全命中，也不能进入候选。关键点是这个条件**不在应用层后置**：先 LIMIT 再过滤会让未来章节挤掉合法结果，表现成“召回不到”，而真正的 bug 是查询写错了。

---

# 第七部分：失败模式

## 48. 写入失败

### 48.1 抽取器把 Episode 错写成 State

风险：

- 一次情绪被当成长期当前状态；
- 新值错误覆盖稳定 Profile；
- 小说人物状态被未经确认的推测改写。

防线（逐层机械可查）：

- 抽取器只能产 Proposal，类型上无法直接提交；
- 写入必须来自宿主裁决出的 `StateCommand`，带 principal 与 policyVersion；
- `fieldId` 必须存在于匹配 `schemaHash` 的快照中且未 deprecated；
- `EXTRACTOR` 不在敏感字段的 `allowedWriters` 里时直接 `WRITER_NOT_ALLOWED`；
- `riskTier=HIGH` 字段缺 `SemanticCheck(PASS)` 直接 `SEMANTIC_CHECK_REQUIRED`；
- `conflictPolicy=PRIORITY_ORDER` 下低优先级 writer 拿不到覆盖权；
- `CANDIDATE` 永不是当前值，required State 读不到它；
- 未登记或分类不确定的内容降级为 Episode。

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

### 48.4 宿主镜像字段与 Memory 各写一份

风险：

- 小说引擎的世界状态机和 Memory 都认为自己保存“角色现在在哪”；
- 两边不一致时无从判断谁对。

防线：

- 字段必须声明 `authorityMode`；
- `HOST_AUTHORITATIVE` 字段拒绝一切非 `MIRROR` 命令（`AUTHORITY_VIOLATION`）；
- 镜像只接受严格递增 `sourceRevision`（`STALE_SOURCE_REVISION`）；
- 镜像值带 `mirroredAt`，超 `stalenessBudgetMs` 判 `STALE`；
- required State 命中 `STALE` 按 fail-closed 处理。

### 48.5 renderer 漂移让检索文本与真相不符

风险：

- payload 是 `["花生"]`，text 却还是旧渲染的“无已知过敏原”；
- 检索命中错误文本，或上下文注入错误表述。

防线：

- `text` 必须由登记过的 renderer 生成，命令携带 renderer 版本；
- payload 与 text 冲突时 payload 为准；
- renderer 版本升级触发批量重渲染与索引重建，可检测、可重放；
- 禁止 LLM 现场撰写 `text`。

### 48.6 required 字段声明成不投影却被要求读取

风险：

- 宿主把 `projectionMode=NONE` 的字段写进 required 列表；
- Memory 返回空，调用方以为“用户没有过敏原”。

防线：

- `getStates` 与 recall 对该字段返回 `NOT_PROJECTED`，不是空值；
- `onMissing=BLOCK` 时整轮 `Blocked`；
- 空集合与“我无权知道”在 API 上永不同形。

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

State direct read 不依赖向量索引，因此 required State 仍可正常提供。这是把关键内容放在直接读取路径上的直接回报：端侧模型没就绪时，过敏原依然确定性进入上下文。

`RecallResult.Ready` 必须带上降级项：

```kotlin
degradations = listOf(
    Degradation(component = "VECTOR_INDEX", reason = "MODEL_NOT_READY"),
)
```

不允许静默伪装成完整召回。FTS 也不可用时（索引损坏、重建中），只剩 required State + Recent，此时 `degradations` 必须同时列出 `FTS`，让上层知道这轮只有确定性内容。

---

# 第八部分：一致性、并发与生命周期

## 51. 事务边界

强一致：

- MemoryItem canonical payload 与派生 text；
- State/Reflection 当前头切换与 `valid_to`；
- provenance；
- evidence；
- tags；
- FTS5 索引更新（写入事务内，因此不存在“写完搜不到”的窗口）；
- RawEvent processing_state；
- index job 创建。

最终一致：

- embedding；
- 向量索引；
- FTS5 全量 rebuild（灾后恢复路径，不是常规写入路径）；
- renderer 升级后的批量重渲染；
- 后台 Reflection（候选能力）。

宿主拥有 StateSchema，不等于宿主必须再保存一份 State 值。字段声明 `MEMORY_AUTHORITATIVE` 时，State 与 Episode 可以在同一 `MemoryBatch` 原子提交，Memory 就是当前值。字段声明 `HOST_AUTHORITATIVE` 时，只有两条合法路径：`MEMORY_MIRROR` 单向投影（带 `sourceRevision`），或 `NONE` 由宿主每轮注入。没有第三种。

## 52. 并发 State 更新

两个 writer 同时更新同一字段：

1. SQLite 写事务串行化；
2. 后到事务重新读取当前版本；
3. `expectedCurrentId` 不匹配返回 `CAS_CONFLICT`；
4. 调用方重新读取当前值后决定是否覆盖。

命令形态：

```kotlin
StateCommand(
    fieldId = "fld_profile_location",
    op = StateOp.PUT,
    expectedCurrentId = "state-v1",
    principal = WriterPrincipal(MemoryWriterKind.EXTRACTOR, "cloud-mem-v1", "policy-7"),
    ...
)
```

未提供 `expectedCurrentId` 时按字段声明的 `conflictPolicy` 处理：

```text
REJECT_ON_EXISTING   已有当前值即拒绝
LAST_WRITE_WINS      允许覆盖
PRIORITY_ORDER       按 §54 来源优先级裁决，低优先级返回 PRIORITY_BLOCKED
```

建议：PROFILE scope 的敏感字段默认 `REJECT_ON_EXISTING` 或 `PRIORITY_ORDER`，避免后台任务静默覆盖用户编辑。

镜像字段不走 CAS，走单调性：`sourceRevision` 必须严格大于当前值的 `mirrored_source_revision`，否则返回 `STALE_SOURCE_REVISION`。这让宿主重放或乱序投递不会把旧值写回。

## 53. 删除与遗忘

逻辑删除优先：

```text
RetractCommand           lifecycle_state=RETRACTED，若是当前头则同时 is_current=0
保留策略过期              lifecycle_state=EXPIRED
被新版本替代              is_current 1→0，lifecycle_state 保持 ACTIVE
```

撤回当前 State 会留下“没有当前值”的空位。这是允许的状态，且 required State 读到它时返回 `MISSING`，不会自动回退到上一版本——静默复活旧值比缺失更危险。宿主若想回退，必须显式写一条新命令。

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

来源优先级（高到低），与 `MemoryWriterKind` 一一对应：

```text
USER_EDIT            用户直接编辑记忆
HOST_MIRROR          宿主权威状态投影（仅镜像字段）
HOST_RULE            宿主业务规则
IMPORTER             显式导入
EXTRACTOR            模型抽取
REFLECTION_WORKER    后台归纳
```

执行方式不是建议，而是机械规则：字段 `conflictPolicy=PRIORITY_ORDER` 且未给 CAS 时，若新命令 `principal.kind` 优先级低于当前版本的写入者，返回 `PRIORITY_BLOCKED`；命令带正确 `expectedCurrentId` 时视为调用方已看到当前值并明确决定覆盖，允许通过。

来源优先级是存储策略，不是相关度分数。

---

# 第九部分：评测

## 55. 写入 Eval

分为两组，因为责任方不同。

宿主裁决质量（Proposal → Command）：

```text
Kind 选择是否正确（State / Episode / Reflection / 拒绝）
fieldId 是否选对
payload 是否表达了原意
高风险字段是否触发了语义校验
是否该降级为 Episode 而错误升级为 State
覆盖意图是否正确（该 CAS 的有没有带 CAS）
```

Memory 执行正确性（Command → 存储）：

```text
契约形状校验（含错误码准确性）
writer 权限与 authority 校验
CAS / 优先级 / 镜像 revision 判定
Episode 幂等
State / Reflection 版本链切换与当前头唯一
provenance 完整
clock domain 一致性
scope 合法性
```

把两组分开评的理由：抽取质量差和存储执行错误的修复方式完全不同，混成一个“写入准确率”会让人去调错的东西。

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

查询“现在”时，required State 必须读取 `is_current=1 AND ACTIVE` 的版本，不能返回已被替代的历史版本。

### 56.6 Historical Recall

查询“以前”时能够召回目标 Episode，或通过 `getStateHistory` 读取目标时点有效的历史版本。这条与 56.5 是一对：当前值正确不等于历史可读，两者必须分别验证。

### 56.7 Required Closure

required 字段的逐字段状态是否正确，以及缺失时是否真的阻断：

```text
PRESENT 判定正确率
MISSING / STALE / NOT_PROJECTED 是否被正确区分
onMissing=BLOCK 缺失时是否返回 Blocked 而非降级上下文
required 内容是否从未被预算截断
```

### 56.8 Answer Utility

正确记忆进入上下文后，最终回答是否真的改善。

不要把 Answer Utility 的失败全部归因于召回。

## 57. 失败分类

固定枚举：

```text
NOT_STORED
WRONG_KIND
WRONG_FIELD
WRONG_OWNER
WRONG_SCOPE
WRONG_TIME
CLOCK_DOMAIN_MISMATCH
STATE_SCHEMA_MISMATCH
SEMANTIC_CHECK_MISSING
AUTHORITY_VIOLATION
WRITER_NOT_ALLOWED
CAS_CONFLICT_UNHANDLED
MIRROR_STALE
MISSING_REQUIRED_STATE
REQUIRED_NOT_BLOCKED
CURRENT_HEAD_DUPLICATED
HISTORY_UNREADABLE
NOT_INDEXED
QUERY_MISMATCH
CANDIDATE_MISS
RANKED_TOO_LOW
VERSION_FILTER_ERROR
BUDGET_DROPPED
INJECTED_BUT_UNUSED
```

每条 Eval 必须定位到一个阶段。`CURRENT_HEAD_DUPLICATED` 和 `REQUIRED_NOT_BLOCKED` 是 P0：前者说明唯一约束或事务有漏洞，后者说明 fail-closed 被绕过。

## 58. 本地设备 Benchmark

MVP 必须测（决定首版能否上线）：

- 低端、中端、高端 Android 设备；
- 冷启动；
- 1k/10k/更大 MemoryItem 的 FTS5/BM25 查询延迟；
- 含全部硬过滤条件的联表查询延迟（这是真实形态，单表 MATCH 不算）；
- 写入事务延迟（含同步 FTS 更新）；
- 峰值内存；
- 磁盘占用。

启用向量通道前必须测（§71.3 的前置门槛）：

- 单条 embedding；
- 批量 embedding；
- 暴力 cosine 扫描延迟；
- 电量和热；
- WorkManager 中断恢复。

ANN 是否需要，只由后一组数据决定。

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

迁移阶段与 §69 的 MVP 范围对应：Phase 1–3 交付 MVP，Phase 4–6 才涉及候选能力的启用与旧图下线。

### Phase 1：新表与接口

- 增加 SchemaSnapshot、Renderer、MemoryItem、Source、Evidence、Tag 表与全部唯一索引；
- 实现 commit / getStates / getStateHistory / FTS 召回；
- 保留现有 MemoryStore；
- 新 RecallEngine 只用于测试；
- Embedding 相关表可以先建但不启用（§71.3）。

### Phase 2：迁移现有 OpenClaim

保守策略：

```text
OpenClaim → Episode
```

原因：

- 文本无损；
- 不猜它一定是当前业务状态；
- 后续可以按宿主 schema 重新生成 State/Reflection；
- 不会因分类错误覆盖 Profile。

迁移写入的 Episode 使用 `IMPORTER` principal 与 `source_type=IMPORT`，因此可以整批识别、整批回滚。

### Phase 3：迁移 Triple

Triple 不按原 predicate 原样迁移成 State。

只有宿主提供显式映射表（predicate → fieldId）的谓语才迁移成 State：

```text
用户 lives_in 上海
→ StateCommand(fieldId=fld_profile_location, payload={"value":"上海"})

用户 allergic_to 花生
→ StateCommand(fieldId=fld_profile_allergies, payload={"value":["花生"]})
```

约束：

```text
- 映射表由宿主提供，Memory 不按谓语名称猜；
- 迁移命令的 principal=IMPORTER；
- 目标字段 riskTier=HIGH 时，迁移必须带 SemanticCheck，
  否则只能落 CANDIDATE 等人工确认；
- 同一 fieldId 有多条冲突 Triple 时全部降级为 Episode，
  不选一条当当前值。
```

无法可靠映射的 Triple 渲染成 Episode：

```text
张三 related_to 项目A
→ “张三与项目A有关。”
```

不保留通用关系的闭集 predicate registry；只保留各宿主显式提供的小型 StateSchema 快照。

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

宿主启动时注册一次 schema 快照：

```kotlin
val registration = memory.registerStateSchema(
    StateSchemaSnapshot(
        spaceId = "assistant:u1",
        schemaVersion = "assistant-profile-v1",
        fields = listOf(
            StateFieldSpec(
                fieldId = "fld_profile_location",
                key = "profile.location",
                contract = ValueContract.Text(maxLength = 64),
                authorityMode = AuthorityMode.MEMORY_AUTHORITATIVE,
                projectionMode = ProjectionMode.NONE,
                riskTier = RiskTier.LOW,
                allowedWriters = setOf(
                    MemoryWriterKind.USER_EDIT,
                    MemoryWriterKind.HOST_RULE,
                    MemoryWriterKind.EXTRACTOR,
                ),
                conflictPolicy = ConflictPolicy.PRIORITY_ORDER,
                allowedScopes = setOf(MemoryScope.PROFILE),
                textRendererId = "profile-zh",
            ),
        ),
    ),
)
val schemaHash = (registration as SchemaRegistration.Accepted).schemaHash
```

写入：

```kotlin
val eventId = memory.capture(
    RawEventDraft(
        spaceId = "assistant:u1",
        ownerId = "user:u1",
        role = "user",
        content = "我已经从北京搬到上海了",
        sessionId = "s1",
        clockDomain = ClockDomain.WALL_CLOCK,
        idempotencyKey = "msg:s1:17",
    ),
)

val principal = WriterPrincipal(
    kind = MemoryWriterKind.EXTRACTOR,
    id = "cloud-memory-v1",
    policyVersion = "assistant-policy-7",
)

memory.commit(
    MemoryBatch(
        spaceId = "assistant:u1",
        writerRunId = "learn:s1:001",
        extractorVersion = "cloud-memory-v1",
        stateSchemaHash = schemaHash,
        commands = listOf(
            StateCommand(
                principal = principal,
                ownerId = "user:u1",
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sources = listOf(SourceRef(SourceType.RAW_EVENT, eventId)),
                fieldId = "fld_profile_location",
                op = StateOp.PUT,
                payload = buildJsonObject { put("value", "上海") },
                rendered = RenderedText("用户目前居住在上海", "profile-zh", "v2"),
                validFrom = ClockStamp(ClockDomain.WALL_CLOCK, now),
                expectedCurrentId = currentLocationId,   // 读到什么就覆盖什么
                confidence = 0.98,
            ),
            EpisodeCommand(
                principal = principal,
                ownerId = "user:u1",
                scope = MemoryScope.PROFILE,
                scopeId = "",
                sources = listOf(SourceRef(SourceType.RAW_EVENT, eventId)),
                rendered = RenderedText("用户从北京搬到上海", "episode-zh", "v1"),
                occurredAt = ClockStamp(ClockDomain.WALL_CLOCK, now),
                tags = listOf(
                    MemoryTag("LOCATION", "北京"),
                    MemoryTag("LOCATION", "上海"),
                ),
                salience = 0.7,
                confidence = 0.98,
                idempotencyKey = "learn:s1:001#1",
            ),
        ),
    ),
)
```

## 62. 个人助手召回

```kotlin
val result = memory.recall(
    RecallRequest(
        spaceId = "assistant:u1",
        ownerId = "user:u1",
        query = "我现在住哪里？",
        at = ClockStamp(ClockDomain.WALL_CLOCK, now),
        requiredFields = listOf(
            RequiredField("fld_profile_location", OnMissing.BLOCK),
        ),
        contextContractId = "location-answer",
        contextContractVersion = "v1",
        budgetChars = 1_500,
        explain = true,
    ),
)

when (result) {
    is RecallResult.Ready -> promptBuilder.append(result.context)
    is RecallResult.Blocked -> askUserForMissingFields(result.issues)
}
```

调用方无法“忘记”处理缺失分支：`Blocked` 里没有 `context` 可用。

## 63. 小说写入

```kotlin
val principal = WriterPrincipal(
    kind = MemoryWriterKind.HOST_RULE,
    id = "novel-engine",
    policyVersion = "novel-policy-2",
)

fun sceneEpisode(
    owner: String,
    text: String,
    tags: List<MemoryTag>,
    salience: Double,
    index: Int,
) = EpisodeCommand(
    principal = principal,
    ownerId = owner,
    scope = MemoryScope.PROFILE,
    scopeId = "",
    sources = listOf(SourceRef(SourceType.RAW_EVENT, sceneEventId)),
    rendered = RenderedText(text, "scene-zh", "v1"),
    occurredAt = ClockStamp(ClockDomain.STORY_TIME, chapter42),
    tags = tags,
    salience = salience,
    confidence = 1.0,
    idempotencyKey = "scene:chapter-42#$index",
)

memory.commit(
    MemoryBatch(
        spaceId = "novel:linwan",
        writerRunId = "scene:chapter-42",
        extractorVersion = "novel-memory-v1",
        stateSchemaHash = null,   // 本批只有 Episode
        commands = listOf(
            sceneEpisode(
                owner = "world",
                text = "沈砚因被官兵抓走而未能赴约。",
                tags = listOf(MemoryTag("SCENE", "chapter-42")),
                salience = 0.8,
                index = 1,
            ),
            sceneEpisode(
                owner = "character:linwan",
                text = "沈砚没有赴约，林晚相信自己再次被抛弃。",
                tags = listOf(
                    MemoryTag("PERSON", "沈砚"),
                    MemoryTag("EMOTION", "被抛弃"),
                ),
                salience = 0.95,
                index = 2,
            ),
        ),
    ),
)
```

## 64. 小说召回

```kotlin
val result = memory.recall(
    RecallRequest(
        spaceId = "novel:linwan",
        ownerId = "character:linwan",
        query = "沈砚为什么没有赴约？",
        at = ClockStamp(ClockDomain.STORY_TIME, chapter50),
        includeWorldMemory = false,
        kinds = setOf(MemoryKind.EPISODE, MemoryKind.REFLECTION),
        explain = true,
    ),
)
```

林晚只能看到自己的解释，不能看到 World Episode。`at` 用 `STORY_TIME` 而不是裸 `Long`，所以把真实毫秒误传进来会在类型/校验层就被挡住，而不是静默放行整本书的未来章节。

---

# 第十二部分：需要拷打的决策

## 65. 已明确建议

1. 不做图。
2. SQLite 是 Memory Core 自有数据的真相源；宿主权威字段的真相在宿主。
3. MVP 检索为本地 FTS5/BM25 + Recent + Tag；向量是候选能力。
4. State、Episode、Reflection 分开。
5. State/Reflection 版本化，Episode 追加；当前头与生命周期是两个维度。
6. Perspective/owner 是小说硬边界，由数据库层硬过滤保证。
7. 候选池取多路并集。
8. MVP 用固定可解释排名规则；RRF 在通道数增加后启用。
9. State 契约由宿主提供：key 闭集、形状受限、语义准入按风险要求宿主背书。
10. 每个字段唯一裁决者，`authorityMode` + `projectionMode` 显式声明。
11. Store 不调用 LLM；模型只产 Proposal。
12. FTS 同步更新；embedding 异步且可缺失。
13. ANN 是否引入由 benchmark 决定。

## 66. 最值得质疑的地方

### 66.1 三种 MemoryKind 是否足够

程序性经验是否属于 Reflection，还是未来需要独立 `PROCEDURE`？

当前建议：先放 Reflection，通过 key 区分；真实 Use Case 证明需要后再拆类型。

### 66.2 State 字段谁定义

SDK 不定义 `profile.location`、`character.current_goal` 等字段。

当前边界：

- App/业务引擎注册 `StateSchemaSnapshot`，Memory 按 `schemaHash` 精确匹配；
- SDK 不内置大词表；
- 写入必须来自宿主裁决的 Command，且字段允许该 writer；
- Store 校验 `fieldId`、`ValueContract` 形状、writer 权限、authority 相容性，但不解释业务含义；
- `riskTier=HIGH` 字段要求宿主提供 `SemanticCheck(PASS)`，Memory 只验证“宿主声明校验过”；
- 未登记内容降级为 Episode；
- `character.location` 等闭集由小说引擎拥有；通用版本存储、当前值唯一性与历史可读由 Memory Core 拥有。

### 66.3 Reflection 谁生成、何时生成

当前决定：

- Store 不管；
- MVP **不带**自动生成器，Reflection 由宿主或人工写入（§69.2）；
- 候选阶段提供可插拔 ReflectionWorker，触发条件见 §71.1；
- 当前值必须有 evidence，无 evidence 只能落 `CANDIDATE`；
- evidence 撤回在 MVP 只做标注，不自动失效（§71.2）。

### 66.4 Required State 谁决定

完全由宿主按场景决定，但形式比“传一串 key”更严格：

- 宿主维护 ContextContract，展开成 `requiredFields`；
- 调用方必须同时传 `contextContractId/Version`，否则 `MISSING_CONTRACT_REF`；
- SDK 按 `fieldId` 确定性读取，不做意图猜测；
- 逐字段返回 `PRESENT/MISSING/CANDIDATE_ONLY/STALE/UNAUTHORIZED/SCHEMA_MISMATCH/UNKNOWN_FIELD/NOT_PROJECTED`；
- `onMissing=BLOCK` 缺失时返回 `Blocked`，不返回残缺上下文；
- required 内容永不被预算截断；
- SDK 内置 contract 求值器是候选能力（§71.9）。

### 66.5 小说时间使用毫秒还是故事坐标

小说可能需要：

```text
chapter
scene
world timestamp
叙事顺序
角色何时获知
分支可见性
```

当前决定：

- 时间值一律是 `ClockStamp(domain, value)`，不是裸 `Long`；
- MVP 只有 `WALL_CLOCK` 与 `STORY_TIME`，每个 Space 选一个业务时钟；
- 小说适配层负责把 `chapter/scene` 编码成单调 `STORY_TIME`；
- 跨域比较抛 `CLOCK_DOMAIN_MISMATCH`，不做隐式换算；
- 额外展示值放 `payload_json`；
- “角色何时获知”与“分支可见性”用 Episode 文本表达，独立时钟是候选能力（§71.4）。

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

> 设备保存可纠错、可追溯的当前状态、长期经历和认识；宿主裁决写什么，Memory 保证当前值唯一、历史不丢、隔离不破，并通过直接读取与本地检索把相关上下文交给任意云模型。

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

这份设计（作为候选架构上限）只有同时满足以下条件才值得进入实施计划：

- 个人助手当前资料由 required State 确定性携带，不依赖概率召回；
- 个人助手能够回忆历史经历；
- 小说角色能够保存主观回忆；
- 小说角色不会读取其他角色或未来信息；
- State 的闭集字段由宿主定义而非 SDK 内置；
- 每个字段有唯一裁决者，不存在双权威；
- State 更新不会抹除 Episode，历史版本仍可读；
- Reflection 有证据可追溯；
- embedding 不可用时系统仍能工作；
- 调试时能解释每条记忆为什么出现或消失；
- 从现有图实现可渐进迁移；
- 不依赖未经验证的 ANN 和模型性能假设。

MVP 的可验收标准另见 §72，两者不是同一份清单：本节判断“这个方向值不值得做”，§72 判断“首版做完了没有”。

---

# 第十三部分：MVP 与候选升级

## 69. MVP 范围

MVP 的判定原则：**只包含“少了就不能安全上线”的机制**。任何能靠宿主临时绕过、或者缺少证据证明必要的能力，一律推到 §71。

### 69.1 MVP 必须包含

存储与写入：

```text
RawEvent 与 processing_state 闭环（含全 REJECT 的终态）
memory_item：State / Episode / Reflection 三类
稳定 fieldId + 可读 key（§12.1）
ValueContract 全部封闭类型的形状校验（§12.2）
riskTier=HIGH 的 SemanticCheck 准入门（§12.3）
canonical payload + 登记 renderer 渲染 text（§12.4）
StateSchemaSnapshot 注册 + schemaHash 精确匹配 + 兼容性判定（§12.6）
authorityMode / projectionMode 两枚举与其校验（§0.1、§12.5）
WriterPrincipal + allowedWriters + conflictPolicy（§12.7）
CAS（expectedCurrentId）与镜像 sourceRevision 单调性
Episode 幂等键强制
is_current 与 lifecycle_state 分离 + 四条唯一索引（§11）
两个 ClockDomain 及跨域拒绝（§11.4）
provenance 强制校验
ATOMIC / BEST_EFFORT 提交语义
```

召回：

```text
required State 直接读取 + 逐字段状态 + fail-closed（§30）
getStates / getStateHistory 两条独立读取路径
同步 FTS5/BM25（写入事务内更新索引）
Recent 通道
Tag 通道（只用已有 tags，不做 NER）
硬过滤在 LIMIT 之前（§17.1）
固定可解释排名规则（§34）
不可截断 / 可截断两段预算（§36）
RecallResult.Ready / Blocked 密封结果 + explain trace
```

### 69.2 MVP 明确不做

```text
自动 Reflection 生成                → §71.1
evidence retract 级联失效           → §71.2
端侧 embedding 与向量通道           → §71.3
细粒度认知时钟（获知/分支可见性）    → §71.4
RRF 融合                            → §71.5
ANN / HNSW / 量化                   → §71.6
schema migration DSL 与自动回填     → §71.7
本地 Cross-Encoder Reranker         → §71.8
SDK 内的 ContextContract 求值器     → §71.9
```

MVP 里 Reflection **类型存在、可写、可召回**，但没有自动生成器：宿主或人工可以写，Memory 不自己产。这样保留数据形状，不背上归纳质量的风险。

MVP 里检索只有 FTS5/BM25 + Recent + Tag 三路。这意味着首版对“换一种说法”的召回能力弱于终局设计——这是有意的取舍：先把确定性部分做对，再用 Eval 数据证明向量通道值多少。

### 69.3 为什么这些必须在首版

| 机制 | 缺了会发生什么 |
| --- | --- |
| 唯一索引 | 出现两个当前值，且没人发现 |
| is_current / lifecycle 分离 | 历史查询查不到已被替代的值 |
| CAS 与优先级 | 后台任务静默覆盖用户亲手改的过敏原 |
| authority / projection | 宿主与 Memory 各自宣称“现在” |
| schemaHash | 宿主改了契约但没改版本号，历史无法解释 |
| SemanticCheck 门 | 高风险字段写进散文，形状合法但语义有害 |
| required fail-closed | 缺过敏原时给出自信的错误建议 |
| provenance 强制 | 记忆无法追溯，用户纠错无处下手 |
| 硬过滤在 LIMIT 前 | 跨 owner 泄漏，且表现为“召回不到” |
| Episode 幂等 | 重试把一次经历写成五条，污染后续归纳 |

## 70. 不可违反的不变量

这些是任何层、任何优化、任何降级都不能破坏的机械保证。每条都必须有对应测试。

```text
I1  同一 (space, owner, scope, scopeId, fieldId) 最多一个 is_current=1
I2  is_current=1 的行 lifecycle_state 必须为 ACTIVE
I3  CANDIDATE 行永不 is_current=1
I4  每条 MemoryItem 至少有一条来源，或有可追溯到 RawEvent 的证据链
I5  跨 space 查询恒为空；跨 owner 需显式授权且记入 trace
I6  业务时间比较必须同 clock domain，跨域抛错
I7  召回结果中不存在 occurred_at / valid_from 晚于请求 at 的条目
I8  required State 只读 is_current=1 且 ACTIVE 的行
I9  required State 永不被预算截断
I10 HOST_AUTHORITATIVE 字段只接受 MIRROR 命令
I11 镜像 sourceRevision 严格单调递增
I12 State 历史版本在 getStateHistory 中可读，不被当前值过滤器排除
I13 payload 与 text 冲突时 payload 为准，且 renderer 版本可比对
I14 schemaHash 未注册的批次整批拒绝
I15 索引（FTS、embedding、向量、tag）可从 SQLite 完全重建
I16 向量索引不可用时 required State + FTS + Recent 仍可服务
I17 Episode 不因 State 更新或 Reflection 生成而被删除或修改
I18 空结果与"无权限 / 未投影 / schema 不匹配"在 API 上不同形
```

## 71. 证据触发的候选升级

每项候选能力必须写明：**什么指标达到什么值才启用**。没有触发条件的能力不进入排期。

### 71.1 自动 Reflection 生成

```text
触发：Episode 累积后，"我是怎样的人"类查询的 Answer Utility
      低于人工 Reflection 基线 15% 以上，且宿主/人工写入
      跟不上 Episode 增长速度。
启用后仍必须：evidence 非空、可撤回、版本化、不自动覆盖用户编辑。
风险：人格结论抖动。先做只产 CANDIDATE，由用户确认。
```

### 71.2 evidence 撤回级联失效

```text
触发：真实数据中出现"支撑证据已撤回但结论仍在使用"的案例，
      且 evidenceRetractedCount 标注不足以让宿主及时处理。
启用后必须带去抖：撤回比例超阈值才降级为 CANDIDATE，
不是撤一条就翻盘。
```

### 71.3 端侧 embedding 与向量通道

```text
触发：FTS5/BM25 + Recent + Tag 的 Candidate Recall@20
      在 Relay 中文召回集上低于目标线（例如 0.85），
      且失败样本经人工确认主要是"换一种说法"类，
      不是分词或 query 构造问题。
前置门槛：目标低端设备上单条 embedding 延迟、批量索引耗电、
          冷启动内存均通过 §58 benchmark。
```

先修 query 构造和 n-gram，再考虑加向量：换一种说法失败常常是 query 只取了最后一句。

### 71.4 细粒度认知时钟

```text
触发：小说场景出现"角色何时获知"与"事件何时发生"必须分离
      才能正确生成的真实案例（例如伏笔揭示、多分支）。
候选模型：worldOccurredAt / ownerLearnedAt / narrativeVisibleAt
启用代价：分支存储、认知传播规则、三倍时间过滤组合。
在此之前用单一 story time + Episode 文本表达"她此刻才知道"。
```

### 71.5 RRF 融合

```text
触发：候选通道数 ≥ 3 且固定规则排名的 nDCG@10
      明显低于离线 RRF 重放结果。
在只有 FTS + Recent + Tag 时，RRF 与"FTS 主序 + 小幅规则调整"
差别很小，不值得引入一个新的不可解释参数 k。
```

### 71.6 ANN / 量化

```text
触发：暴力 cosine 在目标设备上 p95 超过延迟预算，
      且候选集无法通过 metadata 预过滤缩小。
必须保持：SQLite 为真相源、可重建、可回滚、按 modelId 隔离。
```

### 71.7 Schema migration DSL

```text
触发：出现 3 次以上真实的破坏性 schema 变更需求，
      且"新建 fieldId + 宿主回填"的成本被证明不可接受。
在此之前只支持兼容性新增与 deprecated（§12.6）。
```

### 71.8 本地 Reranker

```text
触发：Candidate Recall 达标但 nDCG / Context Precision 不达标，
      即"找得到、排不对"成为主要失败模式。
```

### 71.9 SDK 内 ContextContract 求值器

```text
触发：3 个以上宿主重复实现了几乎相同的 contract 展开逻辑，
      且展开错误成为线上问题来源。
在此之前 Memory 只接收展开结果 + contractId/Version 用于审计。
```

## 72. MVP 验收清单

每条都必须是可自动化的测试，而不是“看起来对”。

写入正确性：

```text
A1  并发写同一字段：只产生一个当前值，另一方拿到 CAS_CONFLICT
A2  重放同一 writer run：Episode 不重复，State 返回 No-op
A3  EXTRACTOR 写敏感字段：WRITER_NOT_ALLOWED
A4  riskTier=HIGH 缺 SemanticCheck：SEMANTIC_CHECK_REQUIRED
A5  payload 形状不符契约：SCHEMA_MISMATCH，且错误指向具体字段
A6  未注册 schemaHash：整批拒绝，无部分写入
A7  破坏性 schema 变更：registerStateSchema 返回 Rejected
A8  HOST_AUTHORITATIVE 字段收到 PUT：AUTHORITY_VIOLATION
A9  镜像 revision 回退：STALE_SOURCE_REVISION，当前值不变
A10 缺来源的命令：SOURCE_NOT_FOUND
A11 ATOMIC 批次任一失败：数据库无任何变化
A12 全部 Proposal 被拒：RawEvent 进入 COMMITTED 终态，不重试
```

读取正确性：

```text
B1  更新三次后：当前值为第三次，历史三个版本均可通过
    getStateHistory 读到
B2  撤回当前 State：required 返回 MISSING，不自动复活上一版本
B3  CANDIDATE 存在时：required 不返回它
B4  镜像超 stalenessBudget：返回 STALE，onMissing=BLOCK 时整轮 Blocked
B5  projectionMode=NONE 字段被 required：NOT_PROJECTED
B6  跨 owner / 跨 space 查询：零条泄漏，含 FTS 与 Recent 通道
B7  小说 at=chapter-30：chapter-80 的 Episode 不出现在任何通道
B8  跨 clock domain 比较：抛 CLOCK_DOMAIN_MISMATCH
B9  预算极小：required 全部保留，Episode/Reflection 被截断
B10 required 内容超预算：Blocked(BUDGET_EXCEEDED)，不裁剪 required
```

降级与恢复：

```text
C1  删除 embedding 表：required + FTS + Recent 正常，
    degradations 列出 VECTOR_INDEX
C2  FTS 索引损坏：required + Recent 正常，degradations 列出 FTS
C3  从 SQLite 完整重建 FTS 与 tag 索引后：召回结果与重建前一致
C4  renderer 版本升级：text 全量重渲染，payload 不变，
    重建后检索命中新表述
```

可解释性：

```text
D1  explain 能回答：为什么这条进上下文、走哪路、被哪条规则调整
D2  explain 能回答：required 字段选中了哪个 scope、屏蔽了哪些
D3  explain 能回答：哪些候选被硬过滤、原因枚举
D4  每条召回结果可追溯到来源 RawEvent 或 evidence 链
```

MVP 只有 A、B、C、D 四组全绿才算完成。任何一条以“后续再补”通过，都会把风险留在最难修的位置。

