package relay.demo.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import relay.memory.MEMORY_DROP
import relay.memory.MEMORY_SCHEMA
import relay.memory.MEMORY_SCHEMA_VERSION
import relay.memory.MemoryDb
import relay.memory.MemoryTx
import relay.memory.SqlRow

class AndroidMemoryDb(context: Context, name: String = "memory.db") : MemoryDb {
    private val helper = Helper(context, name)

    override fun <T> transaction(block: MemoryTx.() -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val out = AndroidTx(db).block()
            db.setTransactionSuccessful()
            return out
        } finally {
            db.endTransaction()
        }
    }

    override fun close() {
        helper.close()
    }

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, MEMORY_SCHEMA_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            MEMORY_SCHEMA.forEach { db.execSQL(it) }
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            MEMORY_DROP.forEach { db.execSQL(it) }
            onCreate(db)
        }
    }
}

private class AndroidTx(private val db: SQLiteDatabase) : MemoryTx {
    override fun exec(sql: String, vararg args: Any?) {
        db.execSQL(sql, Array(args.size) { args[it] })
    }

    override fun query(sql: String, vararg args: Any?): List<SqlRow> {
        val binds = args.map { it?.toString() }.toTypedArray()
        db.rawQuery(sql, binds).use { cursor ->
            val names = Array(cursor.columnCount) { cursor.getColumnName(it).lowercase() }
            val rows = mutableListOf<SqlRow>()
            while (cursor.moveToNext()) {
                val cols = linkedMapOf<String, Any?>()
                names.forEachIndexed { i, name ->
                    cols[name] = when (cursor.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                        else -> cursor.getString(i)
                    }
                }
                rows += SqlRow(cols)
            }
            return rows
        }
    }
}
