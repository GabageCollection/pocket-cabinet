package com.ambercabinet.core.domain

import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units

/** 扣减计划行：同一目标材料的全部需求已聚合（含重复出现、替代、附加材料），跨瓶拆分不重复占用库存 */
data class PourLine(
    val targetId: String,
    val targetDef: Ingredient,
    val totalQty: Double,                        // 库存单位计的聚合总量
    val unit: String,
    val picks: List<Pair<Bottle, Double>>,       // 跨瓶拆分（按 §7.4 顺序）
    val via: SubstitutionRule?,                  // 非空表示整行来自替代
    val sources: List<String>                    // 来源说明，如「白朗姆 60ml×2」「替代搭配」
)

/** 可选/装饰材料不足时跳过（不挤占必需材料，不导致异常失败），并向用户提示 */
data class SkippedItem(val name: String, val reason: String)

data class PourPlan(
    val ok: Boolean,
    val lines: List<PourLine>,
    val skipped: List<SkippedItem> = emptyList(),
    val problems: List<String> = emptyList()
) {
    /** 计划指纹：确认页展示的计划与实际提交必须一致；库存变化导致指纹不同则需重新确认 */
    fun fingerprint(servings: Int): String =
        "n=" + servings + "|" + lines.sortedBy { it.targetId }.joinToString(";") { l ->
            l.targetId + ":" + (l.via?.let { it.fromId + ">" + it.toId } ?: "-") + ":" +
                l.picks.joinToString(",") { it.first.id + "=" + it.second }
        } + "|skip:" + skipped.joinToString(",") { it.name }
}

sealed class CommitResult {
    data class Success(val session: MixSession, val plan: PourPlan) : CommitResult()
    /** 同一草稿已提交过：幂等返回，不重复扣减 */
    data class AlreadyCommitted(val session: MixSession) : CommitResult()
    /** 库存已变化，实际计划与确认页不一致：必须重新确认 */
    data class PlanChanged(val freshPlan: PourPlan) : CommitResult()
    /** 材料不足或数据问题：未产生任何扣减 */
    data class Failed(val problems: List<String>) : CommitResult()
}

sealed class UndoResult {
    data class Done(val restored: Int) : UndoResult()
    object AlreadyUndone : UndoResult()
    object NotFound : UndoResult()
}

/** 扣减执行抽象（Room 事务在数据层实现）。deduct 返回实际应用的扣减量（统一精度规整后）。 */
interface InventoryWriter {
    /** 返回实际扣减量；库存不足抛异常触发整体回滚 */
    suspend fun deduct(bottleId: String, delta: Double): Double
    suspend fun restore(bottleId: String, delta: Double): Double
    suspend fun insertTxn(txn: InventoryTransaction)
    suspend fun insertSession(session: MixSession)
    suspend fun sessionById(sessionId: String): MixSession?
    suspend fun markSessionUndone(sessionId: String)
    suspend fun markTxnUndone(txnId: String)
    suspend fun txnsForSession(sessionId: String): List<InventoryTransaction>
    suspend fun setLastBottle(recipeId: String, ingredientId: String, bottleId: String)
}

class InventoryService(
    private val store: InventoryStore,
    private val writer: InventoryWriter,
    private val matchEngine: MatchEngine
) {

    companion object {
        /* 流水 reason 是历史数据的一部分（旧记录与备份里已是这些字符串），取值绝不能改；
           判定端（RecordsScreen 等）统一引用这里，不写魔法字符串 */
        const val REASON_MIX_DEDUCT = "调制扣减"
        const val REASON_UNDO_ROLLBACK = "撤销回滚"
    }

    /** §7.4 多瓶扣减顺序：用户指定 → 上次使用 → 已开瓶 → 剩余较少；不足时跨瓶 */
    suspend fun pickBottles(
        ingredientId: String,
        needStockUnits: Double,
        overrideBottleId: String?,
        recipeId: String?,
        store: InventoryStore = this.store
    ): List<Pair<Bottle, Double>>? {
        val bs = store.bottlesFor(ingredientId).toMutableList()
        if (bs.isEmpty()) return null
        val lastUsed = recipeId?.let { store.lastBottleFor(it, ingredientId) }
        /* 声明式比较器（满足传递性）：用户指定 → 上次使用 → 已开瓶 → 剩余较少 */
        bs.sortWith(
            compareByDescending<Bottle> { it.id == overrideBottleId }
                .thenByDescending { it.id == lastUsed }
                .thenByDescending { it.openedAt != null }
                .thenBy { it.remaining }
        )
        val plan = mutableListOf<Pair<Bottle, Double>>()
        var rest = needStockUnits
        for (b in bs) {
            if (rest <= Qty.EPS) break
            val take = Qty.round(minOf(b.remaining, rest))
            plan.add(b to take)
            rest = Qty.round(rest - take)
        }
        return if (rest > Qty.EPS) null else plan
    }

    private class Demand(
        val def: Ingredient,
        var qty: Double,                 // 库存单位聚合量
        var via: SubstitutionRule?,
        var required: Boolean,
        val sources: MutableList<String>,
        /* 选瓶覆盖：替代时按用户选的【原材料瓶 id】记录，扣减落在替代材料的目标瓶上 */
        var overrideBottleId: String? = null
    )

    /**
     * 生成完整扣减计划（§6.2/§7.4）：
     * 直接用量 + 用户确认的替代 + 附加材料 + 重复出现的材料统一聚合后，按目标材料一次性分瓶，
     * 不会每行独立判断「够用」而重复占用同一份库存。
     *
     * @param chosenSubs 用户明确确认的替代：原材料 ID → 替代材料 ID。未确认的替代绝不自动执行。
     * @param overrides 用户指定的用瓶：**原材料 ID** → 酒瓶 ID。替代生效时同样按原材料 ID 取，
     *   因此用户为「某材料」挑的瓶不会因为走了替代而被静默忽略。
     */
    suspend fun planPour(
        recipe: Recipe,
        servings: Int,
        ingredients: Map<String, Ingredient>,
        overrides: Map<String, String> = emptyMap(),
        chosenSubs: Map<String, String> = emptyMap(),
        store: InventoryStore = this.store
    ): PourPlan {
        val demands = LinkedHashMap<String, Demand>()
        val problems = mutableListOf<String>()
        val skippedUnit = mutableListOf<SkippedItem>()

        fun stockNeed(def: Ingredient, qty: Double, unit: String, bottles: List<Bottle>): Double? {
            val stockUnit = bottles.firstOrNull()?.unit ?: def.unit
            return Units.needInStockUnit(def, qty, unit, stockUnit)
        }

        suspend fun addDemand(targetId: String, def: Ingredient, qty: Double, unit: String, via: SubstitutionRule?, required: Boolean, label: String, overrideBottleId: String?): Boolean {
            val bottles = store.bottlesFor(targetId)
            val need = stockNeed(def, qty, unit, bottles)
            if (need == null) {
                /* 无法换算（跨维度或非计量单位如「撮/片皮」）：
                   必需材料才算问题；可选/装饰按「本次不加」跳过并告知用户，不进 problems */
                if (required) {
                    problems.add(def.zh + " 的单位「" + unit + "」无法换算成库存单位，请改用库存单位或删掉这一项")
                } else {
                    skippedUnit.add(SkippedItem(def.zh, "单位「" + unit + "」无法换算，本次不加"))
                }
                return false
            }
            val d = demands.getOrPut(targetId) { Demand(def, 0.0, via, required, mutableListOf()) }
            d.qty = Qty.round(d.qty + need)
            if (required) d.required = true
            if (via != null) d.via = via
            if (overrideBottleId != null) d.overrideBottleId = overrideBottleId
            d.sources.add(label)
            return true
        }

        for (ri in recipe.ingredients) {
            val def = ingredients[ri.ingredientId] ?: continue
            if (def.staple || ri.freeText != null) continue
            if (ri.qty <= 0) continue

            val required = ri.role == IngredientRole.REQUIRED
            val label = def.zh + " " + Units.fmt(ri.qty * servings) + " " + ri.unit
            /* 用户为这行原材料挑的瓶；替代生效时也要跟着走 */
            val overrideBottleId = overrides[ri.ingredientId]

            val chosenTo = chosenSubs[ri.ingredientId]
            val rule = if (chosenTo != null && ri.substitutable)
                matchEngine.subRulesFor(ri.ingredientId, recipe.method).firstOrNull { it.toId == chosenTo }
            else null

            if (rule != null) {
                /* 用户已确认的替代：按明确规则标识执行 */
                val toDef = ingredients[rule.toId]
                if (toDef == null) { if (required) problems.add("替代材料数据缺失：" + rule.toId); continue }
                if (!addDemand(rule.toId, toDef, ri.qty * rule.ratio * servings, ri.unit, rule, required, label + "（替代）", overrideBottleId)) continue
                if (rule.extraIngredientId != null && rule.extraQty > 0) {
                    val exDef = ingredients[rule.extraIngredientId]
                    if (exDef == null) { problems.add("替代搭配材料数据缺失"); continue }
                    addDemand(rule.extraIngredientId, exDef, rule.extraQty * servings, rule.extraUnit, null, required, "替代搭配", null)
                }
            } else {
                if (!addDemand(ri.ingredientId, def, ri.qty * servings, ri.unit, null, required, label, overrideBottleId)) continue
            }
        }

        val lines = mutableListOf<PourLine>()
        val skipped = skippedUnit.toMutableList()
        var ok = problems.isEmpty()

        for ((targetId, d) in demands) {
            /* 覆盖瓶只在它确实不属于目标材料时忽略（用户挑的是原材料瓶，替代后目标材料不同） */
            val override = d.overrideBottleId?.takeIf { oid -> store.bottlesFor(targetId).any { it.id == oid } }
            val plan = pickBottles(targetId, d.qty, override, recipe.id, store)
            if (plan == null) {
                if (d.required) {
                    problems.add(d.def.zh + " 库存不足（需要 " + Units.fmt(d.qty) + " " + (store.bottlesFor(targetId).firstOrNull()?.unit ?: d.def.unit) + "）")
                    ok = false
                } else {
                    /* 可选/装饰不足：按用户流程跳过并提示，不阻止调制 */
                    skipped.add(SkippedItem(d.def.zh, "库存不足，本次不加（影响成品呈现或风味）"))
                }
                continue
            }
            val unit = plan.first().first.unit
            lines.add(PourLine(targetId, d.def, d.qty, unit, plan, d.via, d.sources))
        }
        return PourPlan(ok, lines, skipped, problems)
    }

    /**
     * §6.3 事务扣减（在数据层 Room 事务内调用）：
     * 1. 幂等：同一 sessionId 已提交则直接返回 AlreadyCommitted，绝不二次扣减；
     * 2. 从事务内实时库存重新计算计划，与确认页指纹比对，不一致则要求重新确认；
     * 3. 任一写入失败抛异常 → Room 整体回滚，不存在「部分扣减后正常提交」路径。
     */
    suspend fun commitMix(
        sessionId: String,
        recipe: Recipe,
        servings: Int,
        ingredients: Map<String, Ingredient>,
        overrides: Map<String, String> = emptyMap(),
        chosenSubs: Map<String, String> = emptyMap(),
        expectedFingerprint: String? = null,
        startedAt: Long = System.currentTimeMillis()
    ): CommitResult {
        /* mix_sessions 只保存已完成的调制：记录存在即已提交 → 幂等返回 */
        writer.sessionById(sessionId)?.let { return CommitResult.AlreadyCommitted(it) }

        val plan = planPour(recipe, servings, ingredients, overrides, chosenSubs)
        if (!plan.ok) return CommitResult.Failed(plan.problems)
        val fp = plan.fingerprint(servings)
        if (expectedFingerprint != null && expectedFingerprint != fp) {
            return CommitResult.PlanChanged(plan)
        }

        val session = MixSession(
            id = sessionId,
            recipeId = recipe.id,
            servings = servings,
            chosenSubs = chosenSubs,
            bottleOverrides = overrides,
            /* 开始时间来自草稿创建时刻，不用默认 now：否则 startedAt 恒等于 finishedAt，调制时长永远为 0 */
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            recipeZh = recipe.zh,
            recipeEn = recipe.en,
            glass = recipe.glass,
            liquid = recipe.liquid
        )
        val now = System.currentTimeMillis()
        for (line in plan.lines) {
            for ((bottle, wantQty) in line.picks) {
                val applied = writer.deduct(bottle.id, wantQty)
                writer.insertTxn(
                    InventoryTransaction(
                        bottleId = bottle.id,
                        ingredientId = line.targetId,
                        brand = bottle.brand + if (line.via != null) "（替代 " + (ingredients[line.via.fromId]?.zh ?: line.via.fromId) + "）" else "",
                        delta = -applied,
                        unit = bottle.unit,
                        reason = REASON_MIX_DEDUCT,
                        detail = recipe.zh + " × " + servings,
                        sessionId = session.id,
                        time = now
                    )
                )
                writer.setLastBottle(recipe.id, line.targetId, bottle.id)
            }
        }
        writer.insertSession(session)
        return CommitResult.Success(session, plan)
    }

    /**
     * §6.3 撤销（在数据层 Room 事务内调用）：反向流水恢复库存，保留原始记录。
     * 幂等：已撤销的会话直接返回 AlreadyUndone，重复点击/并发/重试不会重复加回库存。
     */
    suspend fun undoSession(sessionId: String): UndoResult {
        val session = writer.sessionById(sessionId) ?: return UndoResult.NotFound
        if (session.undone) return UndoResult.AlreadyUndone
        val outTxns = writer.txnsForSession(sessionId).filter { it.delta < 0 && !it.undone }
        if (outTxns.isEmpty()) return UndoResult.NotFound
        for (t in outTxns) {
            t.bottleId?.let { writer.restore(it, -t.delta) }
            writer.markTxnUndone(t.id)
            writer.insertTxn(
                InventoryTransaction(
                    bottleId = t.bottleId, ingredientId = t.ingredientId, brand = t.brand,
                    delta = -t.delta, unit = t.unit, reason = REASON_UNDO_ROLLBACK,
                    detail = "撤销 " + t.detail, sessionId = sessionId
                )
            )
        }
        writer.markSessionUndone(sessionId)
        return UndoResult.Done(outTxns.size)
    }
}
