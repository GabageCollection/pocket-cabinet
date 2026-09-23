package com.ambercabinet.core.model

import java.util.UUID

/** 单位维度（规格 §9.2）：不同维度不自动互转 */
enum class UnitDimension { VOLUME, COUNT, MASS }

/** 配方材料角色（规格 §7.1） */
enum class IngredientRole { REQUIRED, OPTIONAL, GARNISH }

/** 配方状态（规格 §7.1，四色） */
enum class RecipeStatus { OK, SUBSTITUTABLE, MISSING, INSUFFICIENT }

data class Ingredient(
    val id: String,
    val zh: String,
    val en: String,
    val category: String,          // base / liqueur / mix / bitters / fresh
    val dimension: UnitDimension,
    val unit: String,              // ml / 个 / 片 / 枝 / 块 / dash / g
    val defaultAbv: Double = 0.0,
    val aliases: List<String> = emptyList(),
    val allergens: List<String> = emptyList(),
    val staple: Boolean = false,   // 常备（冰、盐），不参与杯数限制
    val yieldMl: Double? = null,   // 鲜果出汁估算换算：1 个 → yieldMl ml
    val isCustom: Boolean = false  // 用户自定义材料
)

data class RecipeIngredient(
    val ingredientId: String,
    val qty: Double,               // 单杯用量
    val unit: String,
    val role: IngredientRole,
    val substitutable: Boolean = true,
    val note: String? = null,
    val freeText: String? = null   // 如「适量」冰块
)

/** 步骤内声明的单杯用量（ingredientId, 数量, 单位） */
data class StepNeed(val ingredientId: String, val qty: Double, val unit: String)

data class RecipeStep(
    val title: String,
    val detail: String,
    val needs: List<StepNeed> = emptyList(),
    val timerSeconds: Int = 0,
    val timerLabel: String? = null,
    val visual: Int = 0
)

data class Recipe(
    val id: String,
    val zh: String,
    val en: String,
    val source: String,            // iba / classic / home / private
    val sourceNote: String,
    val flavors: List<String>,     // sweet / sour / bitter / fresh / strong
    val difficulty: Int,
    val method: String,
    val glass: String,
    val glassZh: String,
    val liquid: String,            // 本地视觉酒液色（§10.3）
    val abv: Double,
    val timeMin: Int,
    val allergens: List<String> = emptyList(),
    val ingredients: List<RecipeIngredient>,
    val steps: List<RecipeStep>,
    val isUser: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class Bottle(
    val id: String = UUID.randomUUID().toString(),
    val ingredientId: String,
    val brand: String,
    val label: String = "",
    val shape: String = "spirit",
    val liquid: String = "",
    val initQty: Double,
    val remaining: Double,
    val unit: String,
    val abv: Double = 0.0,
    val openedAt: Long? = null,
    val lowPct: Int = 20,
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false
)

/** 单层、有方向替代规则（§7.3） */
data class SubstitutionRule(
    val fromId: String,
    val toId: String,
    val ratio: Double,             // to 用量 = from 用量 × ratio
    val flavorImpact: String,
    val abvDelta: Double = 0.0,
    val methods: String = "全部方法",
    val priority: Int = 1,
    val extraIngredientId: String? = null,
    val extraQty: Double = 0.0,
    val extraUnit: String = "ml"
)

data class InventoryTransaction(
    val id: String = UUID.randomUUID().toString(),
    val bottleId: String?,
    val ingredientId: String,
    val brand: String,
    val delta: Double,
    val unit: String,
    val reason: String,            // 调制扣减 / 手动新增 / 补充库存 / 撤销回滚（历史数据可能含「拍照新增」，功能已下线）
    val detail: String = "",
    val sessionId: String? = null,
    val undone: Boolean = false,
    val time: Long = System.currentTimeMillis()
)

data class MixSession(
    val id: String = UUID.randomUUID().toString(),
    val recipeId: String,
    val servings: Int,
    /* 本表只保存已完成的调制（草稿在 mix_drafts），因此不再有 status 字段 */
    val undone: Boolean = false,
    /** 已确认的替代：原材料 ID → 替代材料 ID（明确规则标识，非布尔） */
    val chosenSubs: Map<String, String> = emptyMap(),
    val bottleOverrides: Map<String, String> = emptyMap(),
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /* 调制时的配方快照：配方后来修改或删除不影响历史展示（§9.1 历史不失真） */
    val recipeZh: String = "",
    val recipeEn: String = "",
    val glass: String = "rocks",
    val liquid: String = ""
)

/** 调酒草稿：开始调酒即创建，串联详情 → 步骤 → 确认 → 完成（§14 调酒中途退出） */
data class MixDraft(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,          // 预分配的调制记录 ID，保证提交幂等
    val recipeId: String,
    val servings: Int = 1,
    val currentStep: Int = 0,
    val bottleOverrides: Map<String, String> = emptyMap(),
    val chosenSubs: Map<String, String> = emptyMap(),
    val timerEndAt: Long? = null,   // 运行中：明确的到期时间基准（不依赖每秒减一）
    val timerRemainingSec: Int = 0, // 暂停/未启动：剩余秒数
    val timerRunning: Boolean = false,
    val timerStep: Int = -1,        // 计时所属步骤；-1 = 旧草稿未记录，按当前步处理
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class TastingNote(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val recipeId: String,
    val rating: Double,
    val sweet: Int? = null,
    val sour: Int? = null,
    val bitter: Int? = null,
    val body: Int? = null,
    val text: String = "",
    val photoUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class Brand(
    val ingredientId: String,
    val brand: String,
    val label: String,
    val abv: Double,
    val capacityMl: Int
)
