package relay.memory.engine

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        SpaceEntity::class,
        StateFieldEntity::class,
        StateFieldAliasEntity::class,
        LedgerRawEntity::class,
        MemoryItemEntity::class,
        MemorySourceEntity::class,
        MemoryTagEntity::class,
        MemoryEvidenceEntity::class,
        EmbeddingModelEntity::class,
        MemoryEmbeddingEntity::class,
        IndexJobEntity::class,
        MemoryFtsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LedgerRoomDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
}

object LedgerDb {
    fun inMemory(context: Context): LedgerRoomDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, LedgerRoomDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .addCallback(Indexes)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()

    fun file(context: Context, file: File): LedgerRoomDatabase {
        file.parentFile?.mkdirs()
        return Room.databaseBuilder(
            context.applicationContext,
            LedgerRoomDatabase::class.java,
            file.absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .addCallback(Indexes)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    private object Indexes : RoomDatabase.Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            install(connection)
        }

        override suspend fun onOpen(connection: SQLiteConnection) {
            install(connection)
        }

        private fun install(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS ux_state_current
                ON memory_item (space_id, owner_id, scope, scope_id, field_id)
                WHERE kind = 'STATE' AND is_current = 1
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS ux_episode_idem
                ON memory_item (space_id, owner_id, idempotency_key)
                WHERE kind = 'EPISODE' AND idempotency_key IS NOT NULL
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS ux_reflection_current
                ON memory_item (space_id, owner_id, scope, scope_id, memory_key)
                WHERE kind = 'REFLECTION' AND is_current = 1
                """.trimIndent(),
            )
        }
    }
}
