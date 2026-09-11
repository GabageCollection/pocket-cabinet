package com.ambercabinet.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bottles")
data class BottleEntity(
    @PrimaryKey val id: String,
    val ingredientId: String,
    val brand: String,
    val label: String,
    val shape: String,
    val liquid: String,
    val initQty: Double,
    val remaining: Double,
    val unit: String,
    val abv: Double,
    val openedAt: Long?,
    val lowPct: Int,
    val photoUri: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "transactions")
data class TxnEntity(
    @PrimaryKey val id: String,
    val bottleId: String?,
    val ingredientId: String,
    val brand: String,
    val delta: Double,
    val unit: String,
    val reason: String,
    val detail: String,
    val sessionId: String?,
    val undone: Boolean,
    val time: Long
)

@Entity(tableName = "mix_sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val servings: Int,
    val status: String,
    val undone: Boolean,
    val currentStep: Int,
    val chosenSubsJson: String,
    val overridesJson: String,
    val startedAt: Long,
    val finishedAt: Long?,
    /* v2：调制时的配方快照，配方修改/删除后历史仍完整 */
    val recipeZh: String = "",
    val recipeEn: String = "",
    val glass: String = "",
    val liquid: String = ""
)

/** v2：调酒草稿（开始调酒即创建，步骤/计时持久化，提交后删除） */
@Entity(tableName = "mix_drafts")
data class DraftEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val recipeId: String,
    val servings: Int,
    val currentStep: Int,
    val overridesJson: String,
    val subsJson: String,
    val timerEndAt: Long?,
    val timerRemainingSec: Int,
    val timerRunning: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** v2：用户自定义材料（中英文名、分类、单位维度，持久化） */
@Entity(tableName = "custom_ingredients")
data class CustomIngredientEntity(
    @PrimaryKey val id: String,
    val zh: String,
    val en: String,
    val cat: String,
    val dim: String,        // vol / mass / count
    val unit: String,
    val abv: Double,
    val aliasesJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false
)

@Entity(tableName = "tasting_notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val recipeId: String,
    val rating: Double,
    val sweet: Int?,
    val sour: Int?,
    val bitter: Int?,
    val body: Int?,
    val text: String,
    val photoUri: String?,
    val createdAt: Long
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val recipeId: String,
    val time: Long
)

/** 私人配方 / 个人版本：内容以 JSON 存储，系统配方升级不覆盖（§10.3） */
@Entity(tableName = "custom_recipes")
data class CustomRecipeEntity(
    @PrimaryKey val id: String,
    val json: String,
    val baseRecipeId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false
)

/** 轻量键值：上次用瓶（§7.4）等 */
@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String
)
