-- Ledger 表结构。对应 relay/memory/docs/schema.md。
-- SQLite 是真相源；FTS 与向量都可从这些表重建。

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS memory_space (
    id           TEXT PRIMARY KEY,
    clock_domain TEXT NOT NULL CHECK (clock_domain IN ('WALL_CLOCK', 'STORY_TIME')),
    created_at   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS state_field (
    space_id         TEXT NOT NULL,
    field_id         TEXT NOT NULL,
    created_by       TEXT NOT NULL,
    value_contract   TEXT NOT NULL,
    allowed_writers  TEXT NOT NULL,
    risk_tier        TEXT NOT NULL,
    authority_mode   TEXT NOT NULL,
    projection_mode  TEXT NOT NULL,
    overwrite_policy TEXT NOT NULL,
    created_at       INTEGER NOT NULL,
    deprecated       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (space_id, field_id)
);

CREATE TABLE IF NOT EXISTS state_field_alias (
    space_id           TEXT NOT NULL,
    alias              TEXT NOT NULL,
    canonical_field_id TEXT NOT NULL,
    PRIMARY KEY (space_id, alias)
);

CREATE TABLE IF NOT EXISTS raw_event (
    id               TEXT PRIMARY KEY,
    space_id         TEXT NOT NULL,
    owner_id         TEXT NOT NULL,
    session_id       TEXT NOT NULL DEFAULT '',
    task_scope_id    TEXT NOT NULL DEFAULT '',
    role             TEXT NOT NULL,
    content          TEXT NOT NULL,
    clock_domain     TEXT NOT NULL,
    occurred_at      INTEGER,
    captured_at      INTEGER NOT NULL,
    processing_state TEXT NOT NULL,
    content_hash     TEXT NOT NULL,
    idempotency_key  TEXT,
    metadata_json    TEXT NOT NULL DEFAULT '{}'
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_raw_event_idem
    ON raw_event (space_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS memory_item (
    id                TEXT PRIMARY KEY,
    space_id          TEXT NOT NULL,
    owner_id          TEXT NOT NULL,
    kind              TEXT NOT NULL CHECK (kind IN ('STATE', 'EPISODE', 'REFLECTION')),

    field_id          TEXT,
    memory_key        TEXT,

    payload_json      TEXT NOT NULL DEFAULT '{}',
    text              TEXT NOT NULL,
    renderer_id       TEXT NOT NULL DEFAULT '',
    renderer_version  TEXT NOT NULL DEFAULT '',

    scope             TEXT NOT NULL CHECK (scope IN ('PROFILE', 'TASK', 'SESSION')),
    scope_id          TEXT NOT NULL DEFAULT '',

    is_current        INTEGER NOT NULL DEFAULT 0,
    lifecycle_state   TEXT NOT NULL CHECK (lifecycle_state IN ('CANDIDATE', 'ACTIVE', 'RETRACTED')),

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
    policy_version    TEXT NOT NULL DEFAULT '',

    mirrored_source_revision INTEGER,
    payload_hash      TEXT NOT NULL,
    text_hash         TEXT NOT NULL,
    idempotency_key   TEXT,

    -- is_current=1 且非 ACTIVE 是非法组合。
    CHECK (is_current = 0 OR lifecycle_state = 'ACTIVE'),
    -- STATE 必须挂 field_id，REFLECTION 必须挂 memory_key。
    CHECK (kind <> 'STATE' OR field_id IS NOT NULL),
    CHECK (kind <> 'REFLECTION' OR memory_key IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_state_current
    ON memory_item (space_id, owner_id, scope, scope_id, field_id)
    WHERE kind = 'STATE' AND is_current = 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_reflection_current
    ON memory_item (space_id, owner_id, scope, scope_id, memory_key)
    WHERE kind = 'REFLECTION' AND is_current = 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_episode_idem
    ON memory_item (space_id, owner_id, idempotency_key)
    WHERE kind = 'EPISODE' AND idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_item_pit
    ON memory_item (space_id, owner_id, kind, lifecycle_state, valid_from, valid_to);

CREATE INDEX IF NOT EXISTS ix_item_episode_time
    ON memory_item (space_id, owner_id, kind, occurred_at);

CREATE INDEX IF NOT EXISTS ix_item_field
    ON memory_item (space_id, owner_id, field_id, scope, scope_id);

CREATE TABLE IF NOT EXISTS memory_source (
    memory_id   TEXT NOT NULL,
    source_type TEXT NOT NULL,
    source_id   TEXT NOT NULL,
    PRIMARY KEY (memory_id, source_type, source_id)
);

CREATE TABLE IF NOT EXISTS memory_evidence (
    reflection_id TEXT NOT NULL,
    evidence_id   TEXT NOT NULL,
    relation      TEXT NOT NULL,
    PRIMARY KEY (reflection_id, evidence_id)
);

CREATE TABLE IF NOT EXISTS memory_tag (
    memory_id TEXT NOT NULL,
    tag       TEXT NOT NULL,
    PRIMARY KEY (memory_id, tag)
);

CREATE INDEX IF NOT EXISTS ix_tag_lookup ON memory_tag (tag, memory_id);

-- 中文用应用层 NFKC + CJK 2/3-gram 展开后喂 unicode61，查询侧同样展开。
CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5 (
    memory_id UNINDEXED,
    ngram_text
);

CREATE TABLE IF NOT EXISTS embedding_model (
    model_id          TEXT PRIMARY KEY,
    model_version     TEXT NOT NULL,
    dimensions        INTEGER NOT NULL,
    tokenizer_version TEXT NOT NULL,
    query_prefix      TEXT NOT NULL DEFAULT '',
    document_prefix   TEXT NOT NULL DEFAULT '',
    normalization     TEXT NOT NULL DEFAULT 'L2',
    active            INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS memory_embedding (
    memory_id   TEXT NOT NULL,
    model_id    TEXT NOT NULL,
    text_hash   TEXT NOT NULL,
    vector_blob BLOB NOT NULL,
    indexed_at  INTEGER NOT NULL,
    PRIMARY KEY (memory_id, model_id)
);

CREATE TABLE IF NOT EXISTS index_job (
    id         TEXT PRIMARY KEY,
    memory_id  TEXT NOT NULL,
    kind       TEXT NOT NULL,
    status     TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_job_status ON index_job (status, kind);
