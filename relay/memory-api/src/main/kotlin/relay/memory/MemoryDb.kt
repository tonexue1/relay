package relay.memory

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

class SqlRow(private val cols: Map<String, Any?>) {
    fun str(name: String): String = cols[name]?.toString().orEmpty()
    fun long(name: String): Long = (cols[name] as Number).toLong()
    fun longOrNull(name: String): Long? = (cols[name] as? Number)?.toLong()
    fun double(name: String): Double = (cols[name] as Number).toDouble()
    fun int(name: String): Int = (cols[name] as Number).toInt()
}

interface MemoryTx {
    fun exec(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<SqlRow>
}

interface MemoryDb {
    fun <T> transaction(block: MemoryTx.() -> T): T
    fun close()
}

class JdbcMemoryDb(private val conn: Connection) : MemoryDb {
    override fun <T> transaction(block: MemoryTx.() -> T): T {
        synchronized(conn) {
            val prev = conn.autoCommit
            conn.autoCommit = false
            try {
                val out = JdbcTx(conn).block()
                conn.commit()
                return out
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = prev
            }
        }
    }

    override fun close() {
        conn.close()
    }

    companion object {
        fun inMemory(): JdbcMemoryDb = open("jdbc:sqlite::memory:")

        fun file(file: File): JdbcMemoryDb {
            file.parentFile?.mkdirs()
            return open("jdbc:sqlite:${file.absolutePath}")
        }

        private fun open(url: String): JdbcMemoryDb {
            Class.forName("org.sqlite.JDBC")
            val conn = DriverManager.getConnection(url)
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            return JdbcMemoryDb(conn)
        }
    }
}

private class JdbcTx(private val conn: Connection) : MemoryTx {
    override fun exec(sql: String, vararg args: Any?) {
        bind(conn.prepareStatement(sql), args).use { it.executeUpdate() }
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        bind(conn.prepareStatement(sql), args).use { stmt ->
            stmt.executeQuery().use { rs ->
                val meta = rs.metaData
                val names = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
                val rows = mutableListOf<SqlRow>()
                while (rs.next()) {
                    val cols = linkedMapOf<String, Any?>()
                    names.forEachIndexed { i, name ->
                        cols[name] = rs.getObject(i + 1)
                    }
                    rows += SqlRow(cols)
                }
                return rows
            }
        }
    }

    private fun bind(stmt: PreparedStatement, args: Array<out Any?>): PreparedStatement {
        args.forEachIndexed { i, value ->
            when (value) {
                null -> stmt.setObject(i + 1, null)
                is Boolean -> stmt.setInt(i + 1, if (value) 1 else 0)
                else -> stmt.setObject(i + 1, value)
            }
        }
        return stmt
    }
}
