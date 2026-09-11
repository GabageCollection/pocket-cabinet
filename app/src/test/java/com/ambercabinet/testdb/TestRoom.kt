package com.ambercabinet.testdb

import androidx.room.DatabaseConfiguration
import androidx.room.RoomDatabase
import com.ambercabinet.core.data.db.AppDatabase
import java.io.File
import java.util.concurrent.Executors

/** JVM 上创建真实 Room 数据库（底层为 sqlite-jdbc），支持文件库（迁移测试）与内存库 */
object TestRoom {

    fun inMemory(): AppDatabase = open(JdbcSQLiteOpenHelper.Factory(null))

    fun file(f: File): AppDatabase = open(JdbcSQLiteOpenHelper.Factory(f))

    private fun open(factory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory): AppDatabase {
        val container = RoomDatabase.MigrationContainer()
        container.addMigrations(*AppDatabase.ALL_MIGRATIONS)
        val config = DatabaseConfiguration(
            /* JournalMode.TRUNCATE 且禁用多实例后，Room 不会调用 Context 方法 */
            context = io.mockk.mockk(relaxed = true),
            name = "test",
            sqliteOpenHelperFactory = factory,
            migrationContainer = container,
            callbacks = null,
            allowMainThreadQueries = true,
            journalMode = RoomDatabase.JournalMode.TRUNCATE,
            queryExecutor = Executors.newCachedThreadPool(),
            transactionExecutor = Executors.newCachedThreadPool(),
            multiInstanceInvalidationServiceIntent = null,
            requireMigration = true,
            allowDestructiveMigrationOnDowngrade = false,
            migrationNotRequiredFrom = null,
            copyFromAssetPath = null,
            copyFromFile = null,
            copyFromInputStream = null,
            prepackagedDatabaseCallback = null,
            typeConverters = emptyList(),
            autoMigrationSpecs = emptyList()
        )
        val db = Class.forName("com.ambercabinet.core.data.db.AppDatabase_Impl")
            .getDeclaredConstructor().newInstance() as AppDatabase
        db.init(config)
        return db
    }

    /** v1 建表 SQL（与 app/schemas/.../1.json 一致），用于迁移测试 */
    val V1_DDL = listOf(
        "CREATE TABLE IF NOT EXISTS `bottles` (`id` TEXT NOT NULL, `ingredientId` TEXT NOT NULL, `brand` TEXT NOT NULL, `label` TEXT NOT NULL, `shape` TEXT NOT NULL, `liquid` TEXT NOT NULL, `initQty` REAL NOT NULL, `remaining` REAL NOT NULL, `unit` TEXT NOT NULL, `abv` REAL NOT NULL, `openedAt` INTEGER, `lowPct` INTEGER NOT NULL, `photoUri` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL, `bottleId` TEXT, `ingredientId` TEXT NOT NULL, `brand` TEXT NOT NULL, `delta` REAL NOT NULL, `unit` TEXT NOT NULL, `reason` TEXT NOT NULL, `detail` TEXT NOT NULL, `sessionId` TEXT, `undone` INTEGER NOT NULL, `time` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `mix_sessions` (`id` TEXT NOT NULL, `recipeId` TEXT NOT NULL, `servings` INTEGER NOT NULL, `status` TEXT NOT NULL, `undone` INTEGER NOT NULL, `currentStep` INTEGER NOT NULL, `chosenSubsJson` TEXT NOT NULL, `overridesJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `tasting_notes` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `recipeId` TEXT NOT NULL, `rating` REAL NOT NULL, `sweet` INTEGER, `sour` INTEGER, `bitter` INTEGER, `body` INTEGER, `text` TEXT NOT NULL, `photoUri` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `favorites` (`recipeId` TEXT NOT NULL, `time` INTEGER NOT NULL, PRIMARY KEY(`recipeId`))",
        "CREATE TABLE IF NOT EXISTS `custom_recipes` (`id` TEXT NOT NULL, `json` TEXT NOT NULL, `baseRecipeId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `kv` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'd56d99f493d2c3397b0d809017c5834e')",
        "PRAGMA user_version = 1"
    )

    /** 用 v1 结构+样例数据初始化一个文件数据库 */
    fun createV1File(f: File, vararg extraSql: String) {
        Class.forName("org.sqlite.JDBC")
        java.sql.DriverManager.getConnection("jdbc:sqlite:" + f.absolutePath).use { conn ->
            conn.createStatement().use { st ->
                (V1_DDL + extraSql).forEach { st.execute(it) }
            }
        }
    }
}
