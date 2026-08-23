package relay.memory.engine

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlinx.coroutines.Dispatchers

const val MEMORY_SCHEMA_VERSION: Int = 7

@Database(
    entities = [
        RawEventEntity::class,
        FactLogEntity::class,
        ClaimLogEntity::class,
        ExtractionRunEntity::class,
        NodeEntity::class,
        NodeAliasEntity::class,
        EdgeEntity::class,
        PendingReviewEntity::class,
        NodeFtsEntity::class,
        ClaimFtsEntity::class,
    ],
    version = MEMORY_SCHEMA_VERSION,
    exportSchema = false,
)
abstract class MemoryRoomDatabase : RoomDatabase() {
    abstract fun memory(): MemoryDao
}

object RoomMemoryDb {
    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `claim_log` (
                    `id` TEXT NOT NULL,
                    `graph_id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `run_id` TEXT NOT NULL,
                    `subject` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `raw_event_ids` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_log_graph_id_created_at` " +
                    "ON `claim_log` (`graph_id`, `created_at`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_log_run_id` ON `claim_log` (`run_id`)",
            )
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `extraction_run` (
                    `id` TEXT NOT NULL,
                    `graph_id` TEXT NOT NULL,
                    `session_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `event_ids` TEXT NOT NULL,
                    `context_event_ids` TEXT NOT NULL,
                    `started_at` INTEGER NOT NULL,
                    `finished_at` INTEGER,
                    `response_ref` TEXT NOT NULL,
                    `error` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_extraction_run_graph_id_started_at` " +
                    "ON `extraction_run` (`graph_id`, `started_at`)",
            )
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS `claim_fts`
                USING FTS5(`claim_id` UNINDEXED, `graph_id` UNINDEXED, `subject`, `text`, tokenize=`unicode61`)
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `raw_event` ADD COLUMN `task_scope_id` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("ALTER TABLE `fact_log` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'SESSION'")
            connection.execSQL("ALTER TABLE `fact_log` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'CANDIDATE'")
            connection.execSQL("ALTER TABLE `fact_log` ADD COLUMN `scope_id` TEXT NOT NULL DEFAULT 'legacy'")
            connection.execSQL("ALTER TABLE `claim_log` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'SESSION'")
            connection.execSQL("ALTER TABLE `claim_log` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'CANDIDATE'")
            connection.execSQL(
                "ALTER TABLE `claim_log` ADD COLUMN `scope_id` TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL("UPDATE `claim_log` SET `scope_id` = `session_id` WHERE `scope_id` = ''")
            connection.execSQL("ALTER TABLE `edge` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'SESSION'")
            connection.execSQL("ALTER TABLE `edge` ADD COLUMN `state` TEXT NOT NULL DEFAULT 'CANDIDATE'")
            connection.execSQL("ALTER TABLE `edge` ADD COLUMN `scope_id` TEXT NOT NULL DEFAULT 'legacy'")
            val durable = "'allergic_to','diet','born_in','alumni_of','knows_language'," +
                "'family_of','spouse_of','parent_of','child_of','sibling_of','has_pet','named','prefers'"
            connection.execSQL(
                "UPDATE `fact_log` SET `scope` = 'PROFILE', `state` = 'CONFIRMED', `scope_id` = '' " +
                    "WHERE `graph_id` LIKE 'novel:%' OR " +
                    "(`p` IN ($durable) AND lower(`s`) IN ('用户','user','我','本人'))",
            )
            connection.execSQL(
                "UPDATE `edge` SET `scope` = 'PROFILE', `state` = 'CONFIRMED', `scope_id` = '' " +
                    "WHERE `graph_id` LIKE 'novel:%' OR " +
                    "(`relation` IN ($durable) AND `src` IN (" +
                    "SELECT `id` FROM `node` WHERE lower(`canonical_name`) IN ('用户','user','我','本人')))",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_raw_event_graph_id_session_id_role` " +
                    "ON `raw_event` (`graph_id`, `session_id`, `role`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_raw_event_graph_id_task_scope_id` " +
                    "ON `raw_event` (`graph_id`, `task_scope_id`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_fact_log_graph_id_scope_scope_id_state` " +
                    "ON `fact_log` (`graph_id`, `scope`, `scope_id`, `state`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_claim_log_graph_id_scope_scope_id_state` " +
                    "ON `claim_log` (`graph_id`, `scope`, `scope_id`, `state`)",
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_edge_graph_id_scope_scope_id_state` " +
                    "ON `edge` (`graph_id`, `scope`, `scope_id`, `state`)",
            )
        }
    }

    fun inMemory(context: Context): MemoryRoomDatabase = open(context, inMemory = true, name = null)

    fun file(context: Context, name: String = "memory.db"): MemoryRoomDatabase =
        open(context, inMemory = false, name = name)

    fun file(context: Context, file: File): MemoryRoomDatabase {
        file.parentFile?.mkdirs()
        return open(context, inMemory = false, name = file.absolutePath)
    }

    private fun open(context: Context, inMemory: Boolean, name: String?): MemoryRoomDatabase {
        val app = context.applicationContext
        val builder = if (inMemory) {
            Room.inMemoryDatabaseBuilder(app, MemoryRoomDatabase::class.java)
        } else {
            Room.databaseBuilder(app, MemoryRoomDatabase::class.java, name!!)
        }
        return builder
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }
}
