package com.ambercabinet.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BottleEntity::class, TxnEntity::class, SessionEntity::class,
        NoteEntity::class, FavoriteEntity::class, CustomRecipeEntity::class,
        KvEntity::class, DraftEntity::class, CustomIngredientEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bottleDao(): BottleDao
    abstract fun txnDao(): TxnDao
    abstract fun sessionDao(): SessionDao
    abstract fun noteDao(): NoteDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun customRecipeDao(): CustomRecipeDao
    abstract fun kvDao(): KvDao
    abstract fun draftDao(): DraftDao
    abstract fun customIngredientDao(): CustomIngredientDao

    companion object {
        /**
         * v1 → v2（保留全部用户数据）：
         * - mix_sessions 增加配方快照四列（默认空串，历史记录自动兼容）
         * - 新增 mix_drafts（调酒草稿）与 custom_ingredients（自定义材料）
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mix_sessions ADD COLUMN recipeZh TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mix_sessions ADD COLUMN recipeEn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mix_sessions ADD COLUMN glass TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mix_sessions ADD COLUMN liquid TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mix_drafts` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, `sessionId` TEXT NOT NULL, `recipeId` TEXT NOT NULL, " +
                        "`servings` INTEGER NOT NULL, `currentStep` INTEGER NOT NULL, " +
                        "`overridesJson` TEXT NOT NULL, `subsJson` TEXT NOT NULL, " +
                        "`timerEndAt` INTEGER, `timerRemainingSec` INTEGER NOT NULL, `timerRunning` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_ingredients` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, `zh` TEXT NOT NULL, `en` TEXT NOT NULL, " +
                        "`cat` TEXT NOT NULL, `dim` TEXT NOT NULL, `unit` TEXT NOT NULL, `abv` REAL NOT NULL, " +
                        "`aliasesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /**
         * v2 → v3（保留全部用户数据）：
         * - mix_sessions 移除恒为 "done" 的 status 与零读取的 currentStep。
         *   minSdk 26 对应 SQLite 3.21，不支持 DROP COLUMN，故采用表重建（复制 → 删旧表 → 改名）。
         * - mix_drafts 增加 timerStep（计时所属步骤，默认 -1 表示未记录）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mix_sessions_new` (" +
                        "`id` TEXT NOT NULL PRIMARY KEY, `recipeId` TEXT NOT NULL, `servings` INTEGER NOT NULL, " +
                        "`undone` INTEGER NOT NULL, `chosenSubsJson` TEXT NOT NULL, `overridesJson` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, " +
                        "`recipeZh` TEXT NOT NULL DEFAULT '', `recipeEn` TEXT NOT NULL DEFAULT '', " +
                        "`glass` TEXT NOT NULL DEFAULT '', `liquid` TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL(
                    "INSERT INTO `mix_sessions_new` (`id`, `recipeId`, `servings`, `undone`, " +
                        "`chosenSubsJson`, `overridesJson`, `startedAt`, `finishedAt`, `recipeZh`, `recipeEn`, `glass`, `liquid`) " +
                        "SELECT `id`, `recipeId`, `servings`, `undone`, " +
                        "`chosenSubsJson`, `overridesJson`, `startedAt`, `finishedAt`, `recipeZh`, `recipeEn`, `glass`, `liquid` " +
                        "FROM `mix_sessions`"
                )
                db.execSQL("DROP TABLE `mix_sessions`")
                db.execSQL("ALTER TABLE `mix_sessions_new` RENAME TO `mix_sessions`")
                db.execSQL("ALTER TABLE `mix_drafts` ADD COLUMN `timerStep` INTEGER NOT NULL DEFAULT -1")
            }
        }

        /**
         * v3 → v4：为热路径查询补索引（纯新增，不动数据）。
         * - bottles(ingredientId)：选瓶/匹配按材料查瓶
         * - transactions(sessionId)：撤销按会话取原始流水
         * - transactions(time)：进出记录按时间倒序（该表是唯一持续增长的表）
         * - tasting_notes(sessionId)：按会话取品鉴笔记
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bottles_ingredientId` ON `bottles` (`ingredientId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_sessionId` ON `transactions` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_time` ON `transactions` (`time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasting_notes_sessionId` ON `tasting_notes` (`sessionId`)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
