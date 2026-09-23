package com.ambercabinet.core.domain

import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units

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
    /**
     * §8：确定性可解释排序。
     * @param matched 调用方已算好的 (配方, match) 列表，避免在同一快照上重复计算
     * @param notesByRecipe 品鉴笔记按配方分组：均分偏离 3 星线性加减分；全部笔记的甜/酸/苦
     *        均值 ≥3.5 视为口味倾向，带对应风味标签的配方小幅加分（清爽/浓烈不参与）
     */
    suspend fun recommend(
        matched: List<Pair<Recipe, MatchResult>>,
        favorites: Set<String>,
        recentRecipeIds: Set<String>,
        notesByRecipe: Map<String, List<TastingNote>> = emptyMap()
    ): List<Recommendation> {
        val agedThreshold = 25L * 86400000
        val now = System.currentTimeMillis()
        val aged = store.allBottles()
            .filter { it.openedAt != null && now - it.openedAt!! > agedThreshold }
            .associateBy { it.ingredientId }

        val allNotes = notesByRecipe.values.flatten()
        fun dimMean(extract: (TastingNote) -> Int?): Double? {
            val vals = allNotes.mapNotNull(extract)
            return if (vals.isEmpty()) null else vals.average()
        }
        /* 口味倾向：甜/酸/苦各自非空均值 ≥3.5 的维度（5 分制） */
        val AFFINITY_ZH = mapOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦")
        val affinity = listOf(
            "sweet" to dimMean { it.sweet },
            "sour" to dimMean { it.sour },
            "bitter" to dimMean { it.bitter }
        ).filter { it.second != null && it.second!! >= 3.5 }

        return matched.mapNotNull { (r, m) ->
            var score = 0.0
            val reasons = mutableListOf<String>()
            when (m.status) {
                RecipeStatus.OK -> { score += 1000; reasons.add("材料齐全，无需替代") }
                RecipeStatus.SUBSTITUTABLE -> { score += 600; reasons.add("使用已有替代材料可调") }
                else -> return@mapNotNull null
            }
            /* 品鉴评分反哺：均分每偏离 3 星 1 分 ±120，高分/低分都要让用户在推荐理由里看到 */
            val notes = notesByRecipe[r.id]
            if (notes != null && notes.isNotEmpty()) {
                val avg = notes.map { it.rating }.average()
                score += ((avg - 3.0) * 120).toInt()
                if (avg >= 4.0 || avg <= 2.0) reasons.add("你给它打过 " + String.format("%.1f", avg) + " 星")
            }
            /* 风味亲和：口味倾向维度命中配方风味标签 +40 */
            for ((dim, _) in affinity) {
                if (r.flavors.contains(dim)) {
                    score += 40
                    reasons.add("合你偏" + AFFINITY_ZH[dim] + "的口味")
                }
            }
            score += minOf(m.maxCups, 20) * 10
            reasons.add("当前库存最多可调 " + m.maxCups + " 杯")
            if (favorites.contains(r.id)) score += 120
            val agedHit = r.ingredients.firstOrNull { it.role == IngredientRole.REQUIRED && aged.containsKey(it.ingredientId) }
            if (agedHit != null) {
                score += 80
                val b = aged[agedHit.ingredientId]!!
                reasons.add(b.brand + "仅剩 " + Units.fmt(b.remaining) + " " + b.unit + "，优先消耗")
            }
            if (recentRecipeIds.contains(r.id)) score -= 200  // 重复抑制
            Recommendation(r, m, score, reasons.take(3))
        }.sortedByDescending { it.score }
    }

    /**
     * §8「补一瓶酒」：对缺失核心材料逐项加入模拟，按新增解锁数排序。
     * baseOk 只算一次；每个候选只重测「当前不可调且确实引用该材料」的配方。
     */
    suspend fun unlockRanking(
        recipes: List<Recipe>,
        ingredients: Map<String, Ingredient>
    ): List<UnlockRow> {
        val candidates = LinkedHashMap<String, Ingredient>()
        val simStore = SimStore(store.allBottles().toMutableList())
        val simEngine = MatchEngine(simStore, matchEngine.substitutionsAll())

        /* 一次全量匹配：得到 baseOk 集合 + 收集缺失候选 */
        val notOk = mutableListOf<Recipe>()
        for (r in recipes) {
            val m = simEngine.match(r, 1, ingredients)
            if (m.status == RecipeStatus.OK) continue
            notOk.add(r)
            for (mi in m.missing) {
                if (!mi.def.staple && mi.ri.role == IngredientRole.REQUIRED) candidates[mi.def.id] = mi.def
            }
        }
        return candidates.values.map { def ->
            simStore.simulate(def)
            /* 候选材料不在配方里，其状态不可能从非 OK 变 OK：只测受影响的配方 */
            val affected = notOk.filter { r -> r.ingredients.any { it.ingredientId == def.id } }
            val gain = affected.count { simEngine.match(it, 1, ingredients).status == RecipeStatus.OK }
            simStore.clearSim()
            UnlockRow(def, gain)
        }.filter { it.gain > 0 }.sortedByDescending { it.gain }
    }

    /**
     * 「只差一种材料」补齐后可调杯数：在库存副本上加入模拟瓶重测。
     * 模拟瓶必须按材料自身默认单位入库（同 SimStore.simulate 的注释）：
     * 非 COUNT 一律给 "ml" 会让 MASS（g）材料的换算返回 null，杯数恒为 0。
     */
    suspend fun cupsIfRestocked(recipe: Recipe, def: Ingredient, ingredients: Map<String, Ingredient>): Int {
        val simStore = SimStore(store.allBottles().toMutableList())
        simStore.simulate(def)
        return MatchEngine(simStore, matchEngine.substitutionsAll()).match(recipe, 1, ingredients).maxCups
    }

    private class SimStore(val bottles: MutableList<Bottle>) : InventoryStore {
        override suspend fun bottlesFor(ingredientId: String) = bottles.filter { it.ingredientId == ingredientId && !it.deleted }
        override suspend fun allBottles() = bottles.filter { !it.deleted }
        override suspend fun lastBottleFor(recipeId: String, ingredientId: String): String? = null
        fun simulate(def: Ingredient) {
            /* 模拟瓶按材料自己的默认单位入库：这样 needInStockUnit 的换算路径与真实瓶一致。
               此前非 COUNT 一律给 "ml"，导致 MASS（g）材料换算必然返回 null、永远进不了补货建议 */
            bottles.add(Bottle(
                id = "__sim__" + def.id, ingredientId = def.id, brand = "", initQty = 9999.0,
                remaining = 9999.0, unit = def.unit
            ))
        }
        fun clearSim() { bottles.removeAll { it.id.startsWith("__sim__") } }
    }
}
