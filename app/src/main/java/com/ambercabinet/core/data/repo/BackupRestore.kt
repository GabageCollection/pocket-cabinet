package com.ambercabinet.core.data.repo

import androidx.room.withTransaction
import com.ambercabinet.core.data.db.AppDatabase

/**
 * 全量替换式恢复（§三.2，独立于 Android Context，可在 JVM 用真实 Room 测试）：
 * 同一事务清空全部表并写入备份内容；任一写入失败整体回滚，保持恢复前状态。
 */
object BackupRestore {
    suspend fun apply(db: AppDatabase, bundle: BackupCodec.Bundle, photoRemap: (String?) -> String? = { it }) {
        db.withTransaction {
            db.bottleDao().clear(); db.txnDao().clear(); db.sessionDao().clear()
            db.noteDao().clear(); db.favoriteDao().clear(); db.customRecipeDao().clear()
            db.customIngredientDao().clear(); db.kvDao().clear(); db.draftDao().clear()

            db.bottleDao().upsertAll(bundle.bottles.map { it.copy(photoUri = photoRemap(it.photoUri)) })
            db.txnDao().insertAll(bundle.txns)
            db.sessionDao().upsertAll(bundle.sessions)
            db.noteDao().upsertAll(bundle.notes.map { it.copy(photoUri = photoRemap(it.photoUri)) })
            bundle.favorites.forEach { db.favoriteDao().add(it) }
            db.customRecipeDao().upsertAll(bundle.customRecipes)
            db.customIngredientDao().upsertAll(bundle.customIngredients)
            db.kvDao().putAll(bundle.kv)
        }
    }
}
