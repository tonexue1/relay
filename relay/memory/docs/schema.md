# 表结构

SQLite 是真相源。FTS 与向量都可从这些表重建。

---

## 一览

| 表 | 作用 |
|---|---|
| `memory_space` | 仓：助手一张、小说一本 |
| `state_field` | `fieldId` 目录。新字段先占槽 |
| `state_field_alias` | 别名 → 规范名 |
| `raw_event` | 原文 |
| `memory_item` | State / Episode / Reflection |
| `memory_source` | 记忆 → 来源 |
| `memory_evidence` | Reflection → 证据，不做图遍历 |
| `memory_tag` | 标签 |
| `memory_fts` | FTS5 |
| `embedding_model` | 向量模型清单 |
| `memory_embedding` | 向量 |
| `index_job` | 异步索引进度 |

不做：`node` / `edge` / 闭集谓语表。

---

## memory_space

```sql
memory_space (
    id              TEXT PRIMARY KEY,
    clock_domain    TEXT NOT NULL,   -- WALL_CLOCK | STORY_TIME
    created_at      INTEGER NOT NULL
)
```

同一 Space 业务时间只用一个 domain。

---

## state_field

有哪些 State 字段。没有这行，`getStates(fieldId)` 失败。

```sql
state_field (
    space_id        TEXT NOT NULL,
    field_id        TEXT NOT NULL,   -- 字段名，如 allergies、巴拉巴拉
    created_by      TEXT NOT NULL,   -- HOST_SEED | EXTRACTOR | USER_EDIT
    value_contract  TEXT NOT NULL,   -- JSON，形状
    allowed_writers TEXT NOT NULL,   -- JSON array
    risk_tier       TEXT NOT NULL,   -- LOW | HIGH
    authority_mode  TEXT NOT NULL,   -- MEMORY_AUTHORITATIVE | HOST_AUTHORITATIVE
    projection_mode TEXT NOT NULL,   -- NONE | MEMORY_MIRROR
    overwrite_policy TEXT NOT NULL,  -- EXTRACTOR_CAN_CURRENT | EXTRACTOR_CANDIDATE_ONLY | USER_LOCK
    created_at      INTEGER NOT NULL,
    deprecated      INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (space_id, field_id)
)
```

`ensureStateField`：已有则复用，没有则插入。改值不改这行。删字段把 `deprecated=1`，不删历史 `memory_item`。

过敏等种子：`overwrite_policy=USER_LOCK`。锁 `(space, owner, fieldId)` 全部 scope：任一当前值是 `USER_EDIT`，抽取器不得在任何 scope 写 CURRENT。

---

## state_field_alias

```sql
state_field_alias (
    space_id             TEXT NOT NULL,
    alias                TEXT NOT NULL,   -- 如 过敏
    canonical_field_id   TEXT NOT NULL,   -- 如 allergies，必须已在 state_field
    PRIMARY KEY (space_id, alias)
)
```

宿主挂映射。`alias` 不得等于已有 `field_id`（除非指向它自己）。Memory 不自动并值。`getStates` / 必带先解析成 `canonical_field_id`。两规范名都有当前值且 payload 不同 → `AMBIGUOUS_FIELD`。

---

## raw_event

```sql
raw_event (
    id                 TEXT PRIMARY KEY,
    space_id           TEXT NOT NULL,
    owner_id           TEXT NOT NULL,
    session_id         TEXT NOT NULL DEFAULT '',
    task_scope_id      TEXT NOT NULL DEFAULT '',
    role               TEXT NOT NULL,
    content            TEXT NOT NULL,
    clock_domain       TEXT NOT NULL,
    occurred_at        INTEGER,
    captured_at        INTEGER NOT NULL,   -- 恒 WALL_CLOCK
    processing_state   TEXT NOT NULL,      -- PENDING | PROCESSING | COMMITTED
                                           -- RETRYABLE_ERROR | PERMANENT_ERROR
    content_hash       TEXT NOT NULL,
    idempotency_key    TEXT,
    metadata_json      TEXT NOT NULL DEFAULT '{}'
)
```

`idempotency_key` 非空时唯一。只有 commit 成功或宿主整批拒绝才 `COMMITTED`。

---

## memory_item

```sql
memory_item (
    id                TEXT PRIMARY KEY,
    space_id          TEXT NOT NULL,
    owner_id          TEXT NOT NULL,
    kind              TEXT NOT NULL,       -- STATE | EPISODE | REFLECTION

    field_id          TEXT,                -- STATE 必填，= state_field.field_id
    memory_key        TEXT,                -- REFLECTION 必填

    payload_json      TEXT NOT NULL DEFAULT '{}',
    text              TEXT NOT NULL,
    renderer_id       TEXT NOT NULL DEFAULT '',
    renderer_version  TEXT NOT NULL DEFAULT '',

    scope             TEXT NOT NULL,       -- PROFILE | TASK | SESSION
    scope_id          TEXT NOT NULL DEFAULT '',

    is_current        INTEGER NOT NULL DEFAULT 0,
    lifecycle_state   TEXT NOT NULL,       -- CANDIDATE | ACTIVE | RETRACTED

    confidence        REAL NOT NULL,
    salience          REAL NOT NULL DEFAULT 0.5,

    clock_domain      TEXT NOT NULL,
    occurred_at       INTEGER,
    valid_from        INTEGER,
    valid_to          INTEGER,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,

    supersedes_id     TEXT,
    retracted_at      INTEGER,

    writer_kind       TEXT NOT NULL,
    writer_id         TEXT NOT NULL,
    writer_run_id     TEXT NOT NULL,

    mirrored_source_revision INTEGER,
    payload_hash      TEXT NOT NULL,
    text_hash         TEXT NOT NULL,
    idempotency_key   TEXT
)
```

合法组合：

```text
is_current=1, ACTIVE      当前值
is_current=0, ACTIVE      历史，getStateHistory 可读
is_current=0, CANDIDATE   待确认
is_current=0, RETRACTED   已撤回
is_current=1 且非 ACTIVE  非法
```

```sql
CREATE UNIQUE INDEX ux_state_current
    ON memory_item (space_id, owner_id, scope, scope_id, field_id)
    WHERE kind = 'STATE' AND is_current = 1;

CREATE UNIQUE INDEX ux_reflection_current
    ON memory_item (space_id, owner_id, scope, scope_id, memory_key)
    WHERE kind = 'REFLECTION' AND is_current = 1;

CREATE UNIQUE INDEX ux_episode_idem
    ON memory_item (space_id, owner_id, idempotency_key)
    WHERE kind = 'EPISODE' AND idempotency_key IS NOT NULL;
```

`clock_domain` 必须等于所属 `memory_space`。小说：Episode 的 `occurred_at`、State / Reflection 的 `valid_from` 应用层 NOT NULL。覆盖 State 时旧行写 `valid_to`，新行写 `valid_from`。

---

## memory_source / memory_evidence / memory_tag

```sql
memory_source (
    memory_id    TEXT NOT NULL,
    source_type  TEXT NOT NULL,   -- RAW_EVENT | USER_EDIT | HOST_TXN | IMPORT
    source_id    TEXT NOT NULL,
    PRIMARY KEY (memory_id, source_type, source_id)
)

memory_evidence (
    reflection_id  TEXT NOT NULL,
    evidence_id    TEXT NOT NULL,  -- 另一条 memory_item.id
    relation       TEXT NOT NULL,  -- SUPPORTS | CONTRADICTS | MOTIVATES
    PRIMARY KEY (reflection_id, evidence_id)
)

memory_tag (
    memory_id  TEXT NOT NULL,
    tag        TEXT NOT NULL,
    PRIMARY KEY (memory_id, tag)
)
```

每条 `memory_item` 至少一条 `memory_source`。Reflection 成为当前值前，`memory_evidence` 非空。

---

## 检索

```sql
-- FTS5，写入事务内同步更新
memory_fts (memory_id, text)

embedding_model (
    model_id          TEXT PRIMARY KEY,
    model_version     TEXT NOT NULL,
    dimensions        INTEGER NOT NULL,
    tokenizer_version TEXT NOT NULL,
    query_prefix      TEXT NOT NULL,
    document_prefix   TEXT NOT NULL,
    normalization     TEXT NOT NULL,
    active            INTEGER NOT NULL
)

memory_embedding (
    memory_id    TEXT NOT NULL,
    model_id     TEXT NOT NULL,
    text_hash    TEXT NOT NULL,
    vector_blob  BLOB NOT NULL,
    indexed_at   INTEGER NOT NULL,
    PRIMARY KEY (memory_id, model_id)
)

index_job (
    id          TEXT PRIMARY KEY,
    memory_id   TEXT NOT NULL,
    kind        TEXT NOT NULL,   -- EMBEDDING
    status      TEXT NOT NULL,   -- PENDING | COMPLETED | FAILED
    updated_at  INTEGER NOT NULL
)
```

向量检索：先用与 FTS 相同的硬过滤，再对 `vector_blob` 算 cosine。禁止先 ANN 再过滤。

默认搜索硬过滤（LIMIT 之前）：

```text
space_id = :spaceId
owner_id IN (:ownerId + :includeOwners)
SESSION → scope_id = :sessionId
TASK    → scope_id = :taskScopeId
PROFILE 可入
STATE / REFLECTION → lifecycle_state = 'ACTIVE'
                     AND valid_from IS NOT NULL AND valid_from <= :atBusiness
                     AND (valid_to IS NULL OR valid_to > :atBusiness)
EPISODE → occurred_at IS NOT NULL AND occurred_at <= :atBusiness
不用 is_current；不用 created_at / updated_at / captured_at
最近 ORDER BY occurred_at 或 valid_from
```

点时直读（必带 / `getStates`）：同一套 `valid_*` 谓词，按 scope 覆盖顺序选一条。不是 `WHERE is_current=1`。

