package com.ambercabinet.core.domain

import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units

/** 库存查询抽象：领域层不依赖 Room（§10.2 业务规则不写入 DAO） */
interface InventoryStore {
    suspend fun bottlesFor(ingredientId: String): List<Bottle>
    suspend fun allBottles(): List<Bottle>
    suspend fun lastBottleFor(recipeId: String, ingredientId: String): String?
}

/** 内存库存快照：一次计算读入一致快照，避免按配方/材料反复查库（§五.10） */
class SnapshotStore(
    bottles: List<Bottle>,
    private val lastUsed: Map<Pair<String, String>, String> = emptyMap()
) : InventoryStore {
    private val byIngredient = bottles.filter { !it.deleted }.groupBy { it.ingredientId }
    private val all = bottles.filter { !it.deleted }
    override suspend fun bottlesFor(ingredientId: String) = byIngredient[ingredientId] ?: emptyList()
    override suspend fun allBottles() = all
    override suspend fun lastBottleFor(recipeId: String, ingredientId: String) = lastUsed[recipeId to ingredientId]
}

data class MissingItem(val ri: RecipeIngredient, val def: Ingredient)
data class InsufficientItem(val ri: RecipeIngredient, val def: Ingredient, val have: Double, val need: Double, val unit: String)
data class SubChoice(val ri: RecipeIngredient, val rule: SubstitutionRule, val toDef: Ingredient, val maxCups: Int)

data class MatchResult(
    val status: RecipeStatus,
    val maxCups: Int,
    val missing: List<MissingItem>,
    val insufficient: List<InsufficientItem>,
    val subs: List<SubChoice>,
    val limiting: String?
)

class MatchEngine(
    private val store: InventoryStore,
    private val substitutions: List<SubstitutionRule>
) {
    fun substitutionsAll(): List<SubstitutionRule> = substitutions

    /**
     * 单层替代查询（§7.3）：方向性（from→to）+ 适用方法 + 优先级排序。
     * 返回全部适用方案供用户确认，不只取第一条。
     */
    fun subRulesFor(fromId: String, method: String? = null): List<SubstitutionRule> =
        substitutions
            .filter { it.fromId == fromId }
            .filter { method == null || it.methods == "全部方法" || it.methods.split('、', ',', ' ').contains(method) }
            .sortedBy { it.priority }

    /** 兼容旧调用：最高优先级方案 */
    fun subRule(fromId: String): SubstitutionRule? = subRulesFor(fromId).firstOrNull()

    private data class Avail(val total: Double, val unit: String?, val bottles: List<Bottle>)

    private suspend fun available(ingredientId: String): Avail {
        val bs = store.bottlesFor(ingredientId).filter { !it.deleted }
        if (bs.isEmpty()) return Avail(0.0, null, emptyList())
        val unit = bs[0].unit
        /* 同种材料多瓶统一到标准单位后汇总（§五.9）；单位不一致的瓶不混入 */
        return Avail(Qty.round(bs.filter { it.unit == unit }.sumOf { it.remaining }), unit, bs)
    }

    /** §7.1 状态判定 + §7.2 最大杯数（必需材料向下取整取最小，常备不参与） */
    suspend fun match(recipe: Recipe, servings: Int = 1, ingredients: Map<String, Ingredient>): MatchResult {
        val missing = mutableListOf<MissingItem>()
        val insufficient = mutableListOf<InsufficientItem>()
        val subs = mutableListOf<SubChoice>()
        var maxCups = Int.MAX_VALUE
        var limiting: String? = null

        for (ri in recipe.ingredients) {
            val def = ingredients[ri.ingredientId] ?: continue
            if (ri.role != IngredientRole.REQUIRED) continue
            if (def.staple) continue

            val av = available(ri.ingredientId)
            val needOne = av.unit?.let { Units.needInStockUnit(def, ri.qty, ri.unit, it) }
            val owned = av.bottles.isNotEmpty()

            if (owned && needOne != null && needOne > 0 && av.total >= needOne * servings - Qty.EPS) {
                val cups = Math.floor(av.total / needOne + Qty.EPS).toInt()
                if (cups < maxCups) { maxCups = cups; limiting = def.zh + "总量" }
                continue
            }
            if (ri.substitutable) {
                /* 收集全部可用替代方案（方向 + 方法 + 库存校验），供用户确认 */
                val working = mutableListOf<SubChoice>()
                for (rule in subRulesFor(ri.ingredientId, recipe.method)) {
                    val toDef = ingredients[rule.toId] ?: continue
                    val avSub = available(rule.toId)
                    if (avSub.bottles.isEmpty()) continue
                    val subNeedOne = avSub.unit?.let { Units.needInStockUnit(toDef, ri.qty * rule.ratio, ri.unit, it) }
                        ?: continue
                    var extraCups = Int.MAX_VALUE
                    var extraOk = true
                    if (rule.extraIngredientId != null) {
                        val exDef = ingredients[rule.extraIngredientId]
                        val avEx = available(rule.extraIngredientId)
                        val exNeed = if (exDef != null && avEx.unit != null)
                            Units.needInStockUnit(exDef, rule.extraQty, rule.extraUnit, avEx.unit) else null
                        extraOk = avEx.bottles.isNotEmpty() && exNeed != null && avEx.total >= exNeed * servings - Qty.EPS
                        if (extraOk && exNeed != null && exNeed > 0) extraCups = Math.floor(avEx.total / exNeed + Qty.EPS).toInt()
                    }
                    if (subNeedOne > 0 && avSub.total >= subNeedOne * servings - Qty.EPS && extraOk) {
                        val cups2 = minOf(Math.floor(avSub.total / subNeedOne + Qty.EPS).toInt(), extraCups)
                        working.add(SubChoice(ri, rule, toDef, cups2))
                    }
                }
                if (working.isNotEmpty()) {
                    subs.addAll(working)
                    val best = working.maxOf { it.maxCups }
                    if (best < maxCups) { maxCups = best; limiting = working.first().toDef.zh + "（替代）" }
                    continue
                }
            }
            if (owned && needOne != null && needOne > 0 && av.total > 0) {
                insufficient.add(InsufficientItem(ri, def, av.total, needOne * servings, av.unit!!))
                maxCups = maxOf(0, minOf(maxCups, Math.floor(av.total / needOne + Qty.EPS).toInt()))
                limiting = def.zh + "剩余量"
            } else {
                missing.add(MissingItem(ri, def))
                maxCups = 0
            }
        }

        if (maxCups == Int.MAX_VALUE) maxCups = 0
        val status = when {
            missing.isNotEmpty() -> RecipeStatus.MISSING
            insufficient.isNotEmpty() -> RecipeStatus.INSUFFICIENT
            subs.isNotEmpty() -> RecipeStatus.SUBSTITUTABLE
            else -> RecipeStatus.OK
        }
        return MatchResult(status, maxOf(0, maxCups), missing, insufficient, subs, limiting)
    }
}
