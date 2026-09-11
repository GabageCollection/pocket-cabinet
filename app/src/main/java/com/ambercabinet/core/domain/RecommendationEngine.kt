package com.ambercabinet.core.domain

import com.ambercabinet.core.model.*

data class Recommendation(
    val recipe: Recipe,
    val match: MatchResult,
    val score: Double,
    val reasons: List<String>  // 最多三条（§8 可解释）
)

data class UnlockRow(val ingredient: Ingredient, val gain: Int)

class RecommendationEngine(
    private val store: InventoryStore,
    private val matchEngine: MatchEngine
) {
    /** §8：确定性可解释排序 */
    suspend fun recommend(
        recipes: List<Recipe>,
        ingredients: Map<String, Ingredient>,
        favorites: Set<String>,
        recentRecipeIds: Set<String>
    ): List<Recommendation> {
        val agedThreshold = 25L * 86400000
        val now = System.currentTimeMillis()
        val aged = store.allBottles()
            .filter { it.openedAt != null && now - it.openedAt!! > agedThreshold }
            .associateBy { it.ingredientId }

        return recipes.mapNotNull { r ->
            val m = matchEngine.match(r, 1, ingredients)
            var score = 0.0
            val reasons = mutableListOf<String>()
            when (m.status) {
                RecipeStatus.OK -> { score += 1000; reasons.add("材料齐全，无需替代") }
                RecipeStatus.SUBSTITUTABLE -> { score += 600; reasons.add("使用已有替代材料可调") }
                else -> return@mapNotNull null
            }
            score += minOf(m.maxCups, 20) * 10
            reasons.add("当前库存最多可调 " + m.maxCups + " 杯")
            if (favorites.contains(r.id)) score += 120
            val agedHit = r.ingredients.firstOrNull { it.role == IngredientRole.REQUIRED && aged.containsKey(it.ingredientId) }
            if (agedHit != null) {
                score += 80
                val b = aged[agedHit.ingredientId]!!
                reasons.add(b.brand + "仅剩 " + (if (b.unit == "ml") Units0.fmt(b.remaining) + " ml" else Units0.fmt(b.remaining) + " " + b.unit) + "，优先消耗")
            }
            if (recentRecipeIds.contains(r.id)) score -= 200  // 重复抑制
            Recommendation(r, m, score, reasons.take(3))
        }.sortedByDescending { it.score }
    }

    /** §8「补一瓶酒」：对缺失核心材料逐项加入模拟，按新增解锁数排序 */
    suspend fun unlockRanking(
        recipes: List<Recipe>,
        ingredients: Map<String, Ingredient>
    ): List<UnlockRow> {
        val candidates = LinkedHashMap<String, Ingredient>()
        val simStore = SimStore(store.allBottles().toMutableList())
        val simEngine = MatchEngine(simStore, matchEngine.substitutionsAll())

        for (r in recipes) {
            val m = simEngine.match(r, 1, ingredients)
            if (m.status == RecipeStatus.OK) continue
            for (mi in m.missing) {
                if (!mi.def.staple && mi.ri.role == IngredientRole.REQUIRED) candidates[mi.def.id] = mi.def
            }
        }
        val baseOk = recipes.count { simEngine.match(it, 1, ingredients).status == RecipeStatus.OK }
        return candidates.values.map { def ->
            simStore.simulate(def)
            val gain = recipes.count { simEngine.match(it, 1, ingredients).status == RecipeStatus.OK } - baseOk
            simStore.clearSim()
            UnlockRow(def, gain)
        }.filter { it.gain > 0 }.sortedByDescending { it.gain }
    }

    /** 每瓶统计：可调的配方数与最佳杯数 */
    suspend fun bottleUsage(
        bottle: Bottle,
        recipes: List<Recipe>,
        ingredients: Map<String, Ingredient>
    ): Pair<Int, Int> {
        var n = 0
        var best = 0
        for (r in recipes) {
            val ri = r.ingredients.firstOrNull { it.ingredientId == bottle.ingredientId && it.role == IngredientRole.REQUIRED } ?: continue
            val m = matchEngine.match(r, 1, ingredients)
            if (m.status == RecipeStatus.OK || m.status == RecipeStatus.SUBSTITUTABLE) {
                n++
                val def = ingredients[bottle.ingredientId] ?: continue
                val need = Units0.needInStockUnit(def, ri.qty, ri.unit, bottle.unit)
                if (need != null) best = maxOf(best, Math.floor(bottle.remaining / need).toInt())
            }
        }
        return n to best
    }

    private class SimStore(val bottles: MutableList<Bottle>) : InventoryStore {
        override suspend fun bottlesFor(ingredientId: String) = bottles.filter { it.ingredientId == ingredientId }
        override suspend fun allBottles() = bottles.toList()
        override suspend fun lastBottleFor(recipeId: String, ingredientId: String): String? = null
        fun simulate(def: Ingredient) {
            bottles.add(Bottle(
                id = "__sim__" + def.id, ingredientId = def.id, brand = "", initQty = 9999.0,
                remaining = 9999.0, unit = if (def.dimension == UnitDimension.COUNT) def.unit else "ml"
            ))
        }
        fun clearSim() { bottles.removeAll { it.id.startsWith("__sim__") } }
    }
}

/** 内部别名，避免与 model 层混淆 */
private typealias Units0 = com.ambercabinet.core.units.Units
