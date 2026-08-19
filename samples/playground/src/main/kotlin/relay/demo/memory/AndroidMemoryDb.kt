package relay.demo.memory

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import relay.memory.MEMORY_DROP
import relay.memory.MEMORY_SCHEMA
import relay.memory.MEMORY_SCHEMA_VERSION
import relay.memory.MemoryDb
import relay.memory.MemoryTx
import relay.memory.SqlRow

/**
 * Android's system SQLite often has no FTS5 (`no such module: fts5`).
 * Bundled SQLite matches the JVM tests.
 */
class AndroidMemoryDb(context: Context, name: String = "memory.db") : MemoryDb {
    private val connection: SQLiteConnection
    private val lock = Any()

    init {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        connection = BundledSQLiteDriver().open(file.absolutePath)
        migrate()
    }

    override fun <T> transaction(block: MemoryTx.() -> T): T = synchronized(lock) {
        execSql("BEGIN IMMEDIATE")
        try {
            val out = AndroidTx(connection).block()
            execSql("COMMIT")
            out
        } catch (t: Throwable) {
            runCatching { execSql("ROLLBACK") }
            throw t
        }
    }

    override fun close() {
        connection.close()
    }

    private fun migrate() {
        val version = connection.prepare("PRAGMA user_version").use { stmt ->
            stmt.step()
            stmt.getLong(0).toInt()
        }
        if (version == MEMORY_SCHEMA_VERSION) return
        execSql("BEGIN")
        try {
            if (version != 0) MEMORY_DROP.forEach(::execSql)
            MEMORY_SCHEMA.forEach(::execSql)
            execSql("PRAGMA user_version = $MEMORY_SCHEMA_VERSION")
            execSql("COMMIT")
        } catch (t: Throwable) {
            runCatching { execSql("ROLLBACK") }
            throw t
        }
    }

    private fun execSql(sql: String) {
        connection.prepare(sql).use { it.step() }
    }
}

private class AndroidTx(private val connection: SQLiteConnection) : MemoryTx {
    override fun exec(sql: String, vararg args: Any?) {
        connection.prepare(sql).use { stmt ->
            stmt.bind(args)
            stmt.step()
        }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        return connection.prepare(sql).use { stmt ->
            stmt.bind(args)
            val rows = mutableListOf<SqlRow>()
            while (stmt.step()) {
                val cols = linkedMapOf<String, Any?>()
                for (i in 0 until stmt.getColumnCount()) {
                    cols[stmt.getColumnName(i).lowercase()] = stmt.read(i)
                }
                rows += SqlRow(cols)
            }
            rows
        }
    }
}

private fun SQLiteStatement.bind(args: Array<out Any?>) {
    args.forEachIndexed { i, value ->
        val idx = i + 1
        when (value) {
            null -> bindNull(idx)
            is Boolean -> bindLong(idx, if (value) 1L else 0L)
            is Int -> bindLong(idx, value.toLong())
            is Long -> bindLong(idx, value)
            is Double -> bindDouble(idx, value)
            is Float -> bindDouble(idx, value.toDouble())
            else -> bindText(idx, value.toString())
        }
    }
}

private fun SQLiteStatement.read(index: Int): Any? = when {
    isNull(index) -> null
    getColumnType(index) == COLUMN_INTEGER -> getLong(index)
    getColumnType(index) == COLUMN_FLOAT -> getDouble(index)
    else -> getText(index)
}

private const val COLUMN_INTEGER = 1
private const val COLUMN_FLOAT = 2
