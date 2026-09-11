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
    version = 2,
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

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}
