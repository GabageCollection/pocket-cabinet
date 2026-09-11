package com.ambercabinet

import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 领域引擎单元测试（规格 §15.1 + §15.2 数据集校验）。
 * 种子数据直接读取与 APK 内相同的 assets/seed JSON 文件。
 */
class EngineTest {

    private lateinit var ingredients: Map<String, Ingredient>
    private lateinit var recipes: List<Recipe>
    private lateinit var subs: List<SubstitutionRule>
    private lateinit var store: FakeStore
    private lateinit var writer: FakeWriter
    private lateinit var matchEngine: MatchEngine
    private lateinit var inventory: InventoryService
    private lateinit var recommender: RecommendationEngine

    class FakeStore(val bottles: MutableList<Bottle>) : InventoryStore {
        val lastUsed = mutableMapOf<String, String>()
        override suspend fun bottlesFor(ingredientId: String) = bottles.filter { it.ingredientId == ingredientId }
        override suspend fun allBottles() = bottles.toList()
        override suspend fun lastBottleFor(recipeId: String, ingredientId: String) = lastUsed[recipeId + ":" + ingredientId]
    }

    class FakeWriter(val store: FakeStore) : InventoryWriter {
        val txns = mutableListOf<InventoryTransaction>()
        val sessions = mutableListOf<MixSession>()
        override suspend fun deduct(bottleId: String, delta: Double): Double {
            val i = store.bottles.indexOfFirst { it.id == bottleId }
            val next = store.bottles[i].remaining - delta
            require(next >= 0) { "negative stock" }
            store.bottles[i] = store.bottles[i].copy(remaining = next)
            return delta
        }
        override suspend fun restore(bottleId: String, delta: Double): Double {
            val i = store.bottles.indexOfFirst { it.id == bottleId }
            store.bottles[i] = store.bottles[i].copy(remaining = store.bottles[i].remaining + delta)
            return delta
        }
        override suspend fun insertTxn(txn: InventoryTransaction) { txns.add(txn) }
        override suspend fun insertSession(session: MixSession) { sessions.add(session) }
        override suspend fun sessionById(sessionId: String) = sessions.firstOrNull { it.id == sessionId }
        override suspend fun markSessionUndone(sessionId: String) {
            val i = sessions.indexOfFirst { it.id == sessionId }
            sessions[i] = sessions[i].copy(undone = true)
        }
        override suspend fun markTxnUndone(txnId: String) {
            val i = txns.indexOfFirst { it.id == txnId }
            txns[i] = txns[i].copy(undone = true)
        }
        override suspend fun txnsForSession(sessionId: String) = txns.filter { it.sessionId == sessionId }
        override suspend fun setLastBottle(recipeId: String, ingredientId: String, bottleId: String) {
            store.lastUsed[recipeId + ":" + ingredientId] = bottleId
        }
    }

    private fun bottle(ing: String, brand: String, init: Double, remaining: Double, unit: String = "ml", opened: Boolean = true) =
        Bottle(
            id = "b-" + ing + "-" + brand, ingredientId = ing, brand = brand,
            initQty = init, remaining = remaining, unit = unit,
            openedAt = if (opened) System.currentTimeMillis() - 30L * 86400000 else null
        )

    @Before
    fun setup() {
        val dir = File("src/main/assets/seed")
        val parsed = com.ambercabinet.core.data.seed.SeedCatalog.parse(
            File(dir, "ingredients.json").readText(),
            File(dir, "substitutions.json").readText(),
            File(dir, "brands.json").readText(),
            File(dir, "recipes.json").readText()
        )
        ingredients = parsed.ingredients
        recipes = parsed.recipes
        subs = parsed.substitutions

        store = FakeStore(mutableListOf(
            bottle("gin", "添加利", 750.0, 165.0),
            bottle("gin", "必富达", 700.0, 490.0),
            bottle("bourbon", "占边", 750.0, 540.0),
            bottle("white_rum", "百加得", 750.0, 610.0),
            bottle("campari", "金巴利", 700.0, 430.0),
            bottle("dry_vermouth", "马天尼干", 1000.0, 760.0),
            bottle("simple_syrup", "莫林", 700.0, 420.0, opened = false),
            bottle("tonic", "屈臣氏汤力", 990.0, 990.0, opened = false),
            bottle("cola", "可口可乐", 660.0, 660.0, opened = false),
            bottle("soda", "屈臣氏苏打", 660.0, 660.0, opened = false),
            bottle("aromatic_bitters", "安高天娜", 200.0, 36.0),
            bottle("lime", "云南青柠", 7.0, 1.0, unit = "个", opened = false),
            bottle("lemon", "安岳柠檬", 6.0, 2.0, unit = "个", opened = false),
            bottle("sugar_cube", "太古方糖", 24.0, 5.0, unit = "块", opened = false)
        ))
        writer = FakeWriter(store)
        matchEngine = MatchEngine(store, subs)
        inventory = InventoryService(store, writer, matchEngine)
        recommender = RecommendationEngine(store, matchEngine)
    }

    private fun recipe(id: String) = recipes.first { it.id == id }

    @Test fun martiniOk10() = runTest {
        val m = matchEngine.match(recipe("dry-martini"), 1, ingredients)
        assertEquals(RecipeStatus.OK, m.status)
        assertEquals(10, m.maxCups)
    }

    @Test fun daiquiriSub3() = runTest {
        val m = matchEngine.match(recipe("daiquiri"), 1, ingredients)
        assertEquals(RecipeStatus.SUBSTITUTABLE, m.status)
        assertEquals(3, m.maxCups)
    }

    @Test fun gimletSub1() = runTest {
        val m = matchEngine.match(recipe("gimlet"), 1, ingredients)
        assertEquals(RecipeStatus.SUBSTITUTABLE, m.status)
        assertEquals(1, m.maxCups)
    }

    @Test fun mojitoMissingMint() = runTest {
        val m = matchEngine.match(recipe("mojito"), 1, ingredients)
        assertEquals(RecipeStatus.MISSING, m.status)
        assertEquals(listOf("mint"), m.missing.map { it.def.id })
    }

    @Test fun manhattanOnlySweetVermouth() = runTest {
        val m = matchEngine.match(recipe("manhattan"), 1, ingredients)
        assertEquals(RecipeStatus.MISSING, m.status)
        assertEquals(listOf("sweet_vermouth"), m.missing.map { it.def.id })
    }

    @Test fun trinidadInsufficient() = runTest {
        val m = matchEngine.match(recipe("trinidad-sour"), 1, ingredients)
        assertEquals(RecipeStatus.INSUFFICIENT, m.status)
    }

    @Test fun oldFashionedSugarLimits() = runTest {
        val m = matchEngine.match(recipe("old-fashioned"), 1, ingredients)
        assertEquals(RecipeStatus.OK, m.status)
        assertEquals(5, m.maxCups)
    }

    @Test fun optionalEggWhiteNotBlocking() = runTest {
        val m = matchEngine.match(recipe("whiskey-sour"), 1, ingredients)
        assertEquals(RecipeStatus.OK, m.status)
        assertEquals(3, m.maxCups)
    }

    @Test fun stapleIceNotLimiting() = runTest {
        assertTrue(ingredients.getValue("ice").staple)
    }

    @Test fun crossBottlePlan() = runTest {
        val plan = inventory.planPour(recipe("dry-martini"), 3, ingredients)
        assertTrue(plan.ok)
        val gin = plan.lines.first { it.targetId == "gin" }
        assertEquals(2, gin.picks.size)
        assertEquals(165.0, gin.picks[0].second, 0.001)
        assertEquals(15.0, gin.picks[1].second, 0.001)
    }

    @Test fun userOverrideFirst() = runTest {
        val beefeater = store.bottles.first { it.brand == "必富达" }
        val plan = inventory.planPour(recipe("dry-martini"), 1, ingredients, overrides = mapOf("gin" to beefeater.id))
        val gin = plan.lines.first { it.targetId == "gin" }
        assertEquals(beefeater.id, gin.picks[0].first.id)
    }

    @Test fun lastUsedPreferred() = runTest {
        val bf = store.bottles.first { it.brand == "必富达" }
        store.lastUsed["gin-tonic:gin"] = bf.id
        val plan = inventory.planPour(recipe("gin-tonic"), 1, ingredients)
        assertEquals(bf.id, plan.lines.first { it.targetId == "gin" }.picks[0].first.id)
    }

    @Test fun commitAndUndo() = runTest {
        val before = store.bottles.filter { it.ingredientId == "gin" }.map { it.remaining }
        val result = inventory.commitMix("s-commit-1", recipe("dry-martini"), 3, ingredients)
        assertTrue(result is CommitResult.Success)
        assertEquals(0.0, store.bottles.first { it.brand == "添加利" }.remaining, 0.001)
        assertEquals(475.0, store.bottles.first { it.brand == "必富达" }.remaining, 0.001)
        assertTrue(inventory.undoSession("s-commit-1") is UndoResult.Done)
        assertEquals(before, store.bottles.filter { it.ingredientId == "gin" }.map { it.remaining })
        assertTrue(store.bottles.all { it.remaining >= 0 })
    }

    @Test fun insufficientRollback() = runTest {
        val txnCount = writer.txns.size
        val result = inventory.commitMix("s-fail-1", recipe("trinidad-sour"), 1, ingredients)
        assertTrue(result is CommitResult.Failed)
        assertEquals(txnCount, writer.txns.size)
        assertEquals(0, writer.sessions.size)
    }

    @Test fun substitutionDeductsSubstitute() = runTest {
        val result = inventory.commitMix("s-sub-1", recipe("daiquiri"), 1, ingredients, chosenSubs = mapOf("lime" to "lemon"))
        assertTrue(result is CommitResult.Success)
        assertEquals(1.4, store.bottles.first { it.ingredientId == "lemon" }.remaining, 0.001)
    }

    /* ── 以下为针对审查发现缺陷新增的用例（§八）── */

    /** 指定 B 瓶后，实际扣减（不仅是计划）优先使用 B 瓶 */
    @Test fun commitHonorsUserPickedBottle() = runTest {
        val beefeater = store.bottles.first { it.brand == "必富达" }
        val result = inventory.commitMix("s-override", recipe("dry-martini"), 1, ingredients, overrides = mapOf("gin" to beefeater.id))
        assertTrue(result is CommitResult.Success)
        assertEquals(430.0, beefeater.let { b -> store.bottles.first { it.id == b.id }.remaining }, 0.001)
        assertEquals(165.0, store.bottles.first { it.brand == "添加利" }.remaining, 0.001)
        assertEquals(beefeater.id, writer.txns.first { it.reason == "调制扣减" && it.ingredientId == "gin" }.bottleId)
    }

    /** 未确认的替代方案不得自动执行 */
    @Test fun unconfirmedSubNeverExecuted() = runTest {
        val lemonBefore = store.bottles.first { it.ingredientId == "lemon" }.remaining
        val result = inventory.commitMix("s-nosub", recipe("daiquiri"), 1, ingredients)  // 未确认 lime→lemon
        assertTrue(result is CommitResult.Failed)
        assertEquals(lemonBefore, store.bottles.first { it.ingredientId == "lemon" }.remaining, 0.001)
        assertEquals(0, writer.txns.size)
        assertEquals(0, writer.sessions.size)
    }

    /** 替代材料本身不足时，部分扣减不允许残留（附加材料路径也不能部分提交） */
    @Test fun extraIngredientShortageNoPartialCommit() = runTest {
        /* gin→vodka 规则不含附加材料；构造含附加材料的规则直接验证计划失败 */
        val rules = listOf(
            SubstitutionRule("gin", "vodka", 1.0, "风味更中性", extraIngredientId = "lemon", extraQty = 5.0, extraUnit = "个")
        )
        val engine = MatchEngine(store, rules)
        val svc = InventoryService(store, writer, engine)
        /* lemon 只有 2 个，需求 5 个 → 计划失败，不产生任何扣减 */
        val before = store.bottles.map { it.id to it.remaining }.toMap()
        val plan = svc.planPour(recipe("dry-martini"), 1, ingredients, chosenSubs = mapOf("gin" to "vodka"))
        assertFalse(plan.ok)
        assertTrue(before.all { (id, v) -> store.bottles.first { it.id == id }.remaining == v })
    }

    /** 同材料重复需求 + 附加材料：聚合后统一占用库存，不重复占用 */
    @Test fun duplicateIngredientAggregated() = runTest {
        val base = recipe("dry-martini")
        /* 构造：同一必需材料 gin 出现两次（60+15=75/杯），2 杯需 150；若按行独立判断会各自「够用」而重复占用 */
        val dup = base.copy(ingredients = base.ingredients + base.ingredients.first { it.ingredientId == "gin" }.copy(qty = 15.0))
        val plan = inventory.planPour(dup, 2, ingredients)
        assertTrue(plan.ok)
        assertEquals(1, plan.lines.count { it.targetId == "gin" })
        assertEquals(150.0, plan.lines.first { it.targetId == "gin" }.totalQty, 0.001)
        /* 用单瓶验证聚合：去掉第二瓶后，3 杯需要 225 > 165 → 失败；2 杯 150 ≤ 165 → 成功 */
        store.bottles.removeIf { it.brand == "必富达" }
        val plan3 = inventory.planPour(dup, 3, ingredients)
        assertFalse(plan3.ok)
        val plan2 = inventory.planPour(dup, 2, ingredients)
        assertTrue(plan2.ok)
    }

    /** 可选/装饰材料不足：跳过并提示，不阻止调制、不挤占必需材料 */
    @Test fun optionalSkippedWithNotice() = runTest {
        val base = recipe("old-fashioned")
        val withOptional = base.copy(ingredients = base.ingredients + RecipeIngredient("lemon", 0.25, "个", IngredientRole.OPTIONAL, substitutable = false))
        store.bottles.removeIf { it.ingredientId == "lemon" }
        val plan = inventory.planPour(withOptional, 1, ingredients)
        assertTrue(plan.ok)
        assertTrue(plan.skipped.any { it.name.contains("柠檬") })
        assertTrue(plan.lines.none { it.targetId == "lemon" })
    }

    /** 分数数量（0.125 个）多次扣减、撤销后库存与流水一致 */
    @Test fun fractionalQtyPrecisionRoundTrip() = runTest {
        val lemon = store.bottles.first { it.ingredientId == "lemon" }
        val base = recipe("dry-martini")
        val frac = base.copy(ingredients = listOf(RecipeIngredient("lemon", 1.0, "片", IngredientRole.REQUIRED, substitutable = false)))
        /* 1 片 = 1/8 个 = 0.125 个；3 杯 = 0.375 */
        val r1 = inventory.commitMix("s-frac-1", frac, 3, ingredients)
        assertTrue(r1 is CommitResult.Success)
        val after1 = store.bottles.first { it.id == lemon.id }.remaining
        assertEquals(2.0 - 0.375, after1, 0.0001)
        assertEquals(-0.375, writer.txns.last().delta, 0.0001)
        assertEquals(after1 - 2.0, writer.txns.last().delta, 0.0001)  /* 流水 delta == 实际库存变化 */
        inventory.commitMix("s-frac-2", frac, 1, ingredients)
        assertEquals(2.0 - 0.5, store.bottles.first { it.id == lemon.id }.remaining, 0.0001)
        assertTrue(inventory.undoSession("s-frac-1") is UndoResult.Done)
        assertEquals(2.0 - 0.125, store.bottles.first { it.id == lemon.id }.remaining, 0.0001)
        assertTrue(inventory.undoSession("s-frac-2") is UndoResult.Done)
        assertEquals(2.0, store.bottles.first { it.id == lemon.id }.remaining, 0.0001)
    }

    /** 连续提交同一草稿只扣减一次（幂等） */
    @Test fun doubleCommitIdempotent() = runTest {
        val ginBefore = store.bottles.filter { it.ingredientId == "gin" }.sumOf { it.remaining }
        val r1 = inventory.commitMix("s-dup", recipe("dry-martini"), 1, ingredients)
        assertTrue(r1 is CommitResult.Success)
        val r2 = inventory.commitMix("s-dup", recipe("dry-martini"), 1, ingredients)
        assertTrue(r2 is CommitResult.AlreadyCommitted)
        val ginAfter = store.bottles.filter { it.ingredientId == "gin" }.sumOf { it.remaining }
        assertEquals(ginBefore - 60.0, ginAfter, 0.001)   /* dry-martini 1 杯 = 60 ml gin */
        assertEquals(1, writer.sessions.size)
    }

    /** 连续撤销只恢复一次 */
    @Test fun doubleUndoRestoresOnce() = runTest {
        inventory.commitMix("s-undo", recipe("dry-martini"), 1, ingredients)
        val ginAfterCommit = store.bottles.filter { it.ingredientId == "gin" }.sumOf { it.remaining }
        assertTrue(inventory.undoSession("s-undo") is UndoResult.Done)
        val ginAfterUndo = store.bottles.filter { it.ingredientId == "gin" }.sumOf { it.remaining }
        assertTrue(inventory.undoSession("s-undo") is UndoResult.AlreadyUndone)
        assertEquals(ginAfterUndo, store.bottles.filter { it.ingredientId == "gin" }.sumOf { it.remaining }, 0.001)
        assertEquals(ginAfterCommit + 60.0, ginAfterUndo, 0.001)
    }

    /** 确认后库存变化导致计划指纹变化 → 拒绝提交，要求重新确认 */
    @Test fun planFingerprintMismatchRequiresReconfirm() = runTest {
        val plan = inventory.planPour(recipe("dry-martini"), 1, ingredients)
        val fp = plan.fingerprint(1)
        /* 用户看着确认页时，另一处消耗了 165 ml（用尽第一瓶 → 分瓶变化） */
        writer.deduct(store.bottles.first { it.brand == "添加利" }.id, 165.0)
        val result = inventory.commitMix("s-fp", recipe("dry-martini"), 1, ingredients, expectedFingerprint = fp)
        assertTrue(result is CommitResult.PlanChanged)
        assertEquals(0, writer.txns.size)
    }

    @Test fun unlockRankingTopIsSweetVermouth() = runTest {
        val rows = recommender.unlockRanking(recipes, ingredients)
        assertEquals("sweet_vermouth", rows.first().ingredient.id)
        assertTrue(rows.first().gain >= 3)
    }

    @Test fun recommendationExplainable() = runTest {
        val recs = recommender.recommend(recipes, ingredients, setOf("old-fashioned"), emptySet())
        assertTrue(recs.isNotEmpty())
        assertTrue(recs.first().reasons.size in 1..3)
        assertEquals(RecipeStatus.OK, recs.first().match.status)
    }

    @Test fun recentSuppression() = runTest {
        val a = recommender.recommend(recipes, ingredients, emptySet(), emptySet())
        val b = recommender.recommend(recipes, ingredients, emptySet(), setOf(a.first().recipe.id))
        assertTrue(b.none { it.recipe.id == a.first().recipe.id } || b.first().recipe.id != a.first().recipe.id || a.size == 1)
    }

    @Test fun datasetIntegrity() {
        val ids = recipes.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        recipes.forEach { r ->
            assertTrue(r.zh.isNotBlank() && r.en.isNotBlank())
            assertTrue(r.ingredients.any { it.role == IngredientRole.REQUIRED })
            assertTrue(r.steps.isNotEmpty())
            r.ingredients.forEach { ri -> assertTrue(ingredients.containsKey(ri.ingredientId)) }
        }
        subs.forEach { s ->
            assertTrue(ingredients.containsKey(s.fromId))
            assertTrue(ingredients.containsKey(s.toId))
        }
    }

    @Test fun crossDimensionNotConverted() {
        val lemon = ingredients.getValue("lemon")
        assertNull(Units.needInStockUnit(lemon, 1.0, "片", "ml"))
        assertNotNull(Units.needInStockUnit(lemon, 30.0, "ml", "个"))
        assertEquals(1.0, Units.needInStockUnit(lemon, 2.0, "dash", "ml")!!, 0.001)
    }

    @Test fun volumeUnitConversions() {
        val gin = ingredients.getValue("gin")
        assertEquals(29.574, Units.needInStockUnit(gin, 1.0, "oz", "ml")!!, 0.001)
        assertEquals(15.0, Units.needInStockUnit(gin, 1.5, "cl", "ml")!!, 0.001)
        assertEquals(5.0, Units.needInStockUnit(gin, 1.0, "茶匙", "ml")!!, 0.001)
        assertEquals(2.029, Units.needInStockUnit(gin, 60.0, "ml", "oz")!!, 0.001)
        assertNull(Units.needInStockUnit(gin, 1.0, "g", "ml"))
    }
}
