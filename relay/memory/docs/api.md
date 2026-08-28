# 接口

宿主只碰 `MemoryRuntime`。类型以落地代码为准，本文是评审合同。

---

## MemoryRuntime

```kotlin
interface MemoryRuntime {
    suspend fun capture(event: RawEventDraft): RawEventId
    suspend fun registerStateSchema(snapshot: StateSchemaSnapshot): SchemaRegistration
    suspend fun ensureStateField(spec: StateFieldSpec): FieldRegistration
    suspend fun putFieldAlias(spaceId: String, alias: String, canonicalFieldId: String)
    suspend fun commit(batch: MemoryBatch): CommitResult
    suspend fun recall(request: RecallRequest): RecallResult
    suspend fun getStates(request: StateReadRequest): StateReadResult
    suspend fun getStateHistory(request: StateHistoryRequest): List<StateVersion>
    suspend fun indexHealth(spaceId: String): IndexHealth
}
```

`putFieldAlias`：`canonicalFieldId` 必须已在 `state_field`。`alias` 不得占用另一个 `field_id`。不合并两条值。

---

## 字段目录

```kotlin
enum class OverwritePolicy {
    EXTRACTOR_CAN_CURRENT,
    EXTRACTOR_CANDIDATE_ONLY,
    USER_LOCK,
}

data class StateFieldSpec(
    val fieldId: String,
    val contract: ValueContract,
    val authorityMode: AuthorityMode,
    val projectionMode: ProjectionMode,
    val riskTier: RiskTier,
    val allowedWriters: Set<MemoryWriterKind>,
    val overwritePolicy: OverwritePolicy,
)
```

`ensureStateField`：已有复用，没有建槽。入参若命中别名，按规范名复用，不新建第二个槽。

SDK 不内置 `profile.*`。助手建议种子：`allergies`（`USER_LOCK`）。小说建议种子：`location`、`current_goal`。

---

## 写入

抽取器只出 Proposal，不能 `commit`。`suggestedFieldId` 可新可旧，也可是别名。

```kotlin
data class StateCommand(
    val fieldId: String,                 // 规范名或别名，commit 时解析
    val payload: JsonObject,
    val rendered: RenderedText,
    val sources: List<SourceRef>,
    val expectedCurrentId: String?,
    val sourceRevision: Long?,
    val validFrom: ClockStamp,
    val targetLifecycle: TargetLifecycle,
    val overrideUserEdit: Boolean = false,
    ...
)

data class EpisodeCommand(
    val idempotencyKey: String,          // 唯一：(space, owner, key)
    val occurredAt: ClockStamp?,         // 小说必填
    ...
)
```

当前 `(space, owner, fieldId)` 任一 scope 的当前值是 `USER_EDIT` 且字段 `USER_LOCK`：抽取器在任何 scope 写 `CURRENT` → `USER_LOCK`。只能 CANDIDATE。`overrideUserEdit=true` 仅 USER_EDIT / HOST。

小说 State / Reflection 缺 `validFrom` → 拒。Episode 缺 `occurredAt` → 拒。

`ClockStamp.domain` 必须等于 space。否则 `CLOCK_DOMAIN_MISMATCH`。

错误码至少包括：`UNKNOWN_FIELD`、`AMBIGUOUS_FIELD`、`SOURCE_NOT_FOUND`、`CAS_CONFLICT`、`WRITER_NOT_ALLOWED`、`USER_LOCK`、`IDEMPOTENT_REPLAY`。

---

## 读取

```kotlin
data class RecallRequest(
    val spaceId: String,
    val ownerId: String,
    val includeOwners: List<String> = emptyList(),  // 世界仓等，默认不加
    val query: String,
    val recentMessages: List<Message>,
    val sessionId: String,
    val taskScopeId: String,
    val at: ClockStamp,
    val requiredFields: List<RequiredField>,        // 别名或规范名
    val contextContractId: String?,
    val contextContractVersion: String?,
    val budgetChars: Int = 2_000,
    val explain: Boolean = false,
)

data class StateReadRequest(
    val spaceId: String,
    val ownerId: String,
    val includeOwners: List<String> = emptyList(),
    val selectors: Set<StateSelector>,
    val at: ClockStamp,
)
```

必带与 `getStates`：别名 → 规范名，再按 `at` 点时取 ACTIVE 版本。不要 `WHERE is_current=1`。

`includeOwners` 空 = 只读 `ownerId`。跨 owner 泄漏是 bug，不是配置。

传了 `requiredFields` 必须带契约 ID/版本。默认 `onMissing=BLOCK`。

`getStateHistory`：版本链，含 `is_current=0`。可按时间窗过滤。

---

## 隔离

- 跨 `space_id` 恒为空
- 默认跨 `owner_id` 恒为空；只有 `includeOwners` 列出的才加进来
- SESSION / TASK 必须匹配请求里的 `scope_id`
- 四路 STATE / Reflection 与必带同一套 `at` + `valid_*`，不用 `is_current`
- 小说 `occurred_at > at` 或 `valid_from > at` 的行不出现
- 硬过滤在 SQL `LIMIT` 之前
- 最近 `ORDER BY` 业务时间
