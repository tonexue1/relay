package relay.memory

val MEMORY_SCHEMA: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS raw_event (
      id TEXT PRIMARY KEY,
      graph_id TEXT NOT NULL,
      ts INTEGER NOT NULL,
      session_id TEXT NOT NULL,
      role TEXT NOT NULL,
      text_ref TEXT NOT NULL,
      source TEXT NOT NULL,
      consumed INTEGER NOT NULL,
      scope TEXT NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS fact_log (
      id TEXT PRIMARY KEY,
      graph_id TEXT NOT NULL,
      ts INTEGER NOT NULL,
      s TEXT NOT NULL,
      p TEXT NOT NULL,
      o TEXT NOT NULL,
      confidence REAL NOT NULL,
      raw_event_ids TEXT NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS node (
      id TEXT PRIMARY KEY,
      graph_id TEXT NOT NULL,
      type TEXT NOT NULL,
      canonical_name TEXT NOT NULL,
      UNIQUE (graph_id, canonical_name)
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS node_alias (
      graph_id TEXT NOT NULL,
      alias TEXT NOT NULL,
      node_id TEXT NOT NULL,
      PRIMARY KEY (graph_id, alias)
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS edge (
      id TEXT PRIMARY KEY,
      graph_id TEXT NOT NULL,
      src TEXT NOT NULL,
      dst TEXT NOT NULL,
      relation TEXT NOT NULL,
      confidence REAL NOT NULL,
      valid_from INTEGER NOT NULL,
      valid_to INTEGER,
      updated_at INTEGER NOT NULL,
      scope TEXT NOT NULL,
      provenance TEXT NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS pending_review (
      edge_id TEXT PRIMARY KEY,
      reason TEXT NOT NULL,
      confidence REAL NOT NULL,
      s TEXT NOT NULL,
      p TEXT NOT NULL,
      o TEXT NOT NULL
    )
    """.trimIndent(),
    """
    CREATE VIRTUAL TABLE IF NOT EXISTS node_fts USING fts5(
      node_id UNINDEXED,
      graph_id UNINDEXED,
      canonical_name,
      aliases,
      tokenize = 'unicode61'
    )
    """.trimIndent(),
)

const val MEMORY_SCHEMA_VERSION: Int = 2

val MEMORY_DROP: List<String> = listOf(
    "DROP TABLE IF EXISTS pending_review",
    "DROP TABLE IF EXISTS node_fts",
    "DROP TABLE IF EXISTS node_alias",
    "DROP TABLE IF EXISTS edge",
    "DROP TABLE IF EXISTS node",
    "DROP TABLE IF EXISTS fact_log",
    "DROP TABLE IF EXISTS raw_event",
)
