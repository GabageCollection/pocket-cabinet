package com.ambercabinet.core.data.repo

import androidx.room.withTransaction
import com.ambercabinet.core.data.db.*
import com.ambercabinet.core.data.seed.SeedCatalog
import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/* ── Entity ↔ Model 映射 ── */
fun BottleEntity.toModel() = Bottle(id, ingredientId, brand, label, shape, liquid, initQty, remaining, unit, abv, openedAt, lowPct, photoUri, createdAt, updatedAt, deleted)
fun Bottle.toEntity() = BottleEntity(id, ingredientId, brand, label, shape, liquid, initQty, remaining, unit, abv, openedAt, lowPct, photoUri, createdAt, updatedAt, deleted)
fun TxnEntity.toModel() = InventoryTransaction(id, bottleId, ingredientId, brand, delta, unit, reason, detail, sessionId, undone, time)
fun InventoryTransaction.toEntity() = TxnEntity(id, bottleId, ingredientId, brand, delta, unit, reason, detail, sessionId, undone, time)
fun NoteEntity.toModel() = TastingNote(id, sessionId, recipeId, rating, sweet, sour, bitter, body, text, photoUri, createdAt)

fun DraftEntity.toModel(): MixDraft = MixDraft(
    id, sessionId, recipeId, servings, currentStep,
    parseStringMap(overridesJson), parseSubMap(subsJson),
    timerEndAt, timerRemainingSec, timerRunning, createdAt, updatedAt
)
fun MixDraft.toEntity(): DraftEntity = DraftEntity(
    id, sessionId, recipeId, servings, currentStep,
    stringMapJson(bottleOverrides), stringMapJson(chosenSubs),
    timerEndAt, timerRemainingSec, timerRunning, createdAt, updatedAt
)

fun CustomIngredientEntity.toModel() = Ingredient(
    id = id, zh = zh, en = en, category = cat,
    dimension = when (dim) { "vol" -> UnitDimension.VOLUME; "mass" -> UnitDimension.MASS; else -> UnitDimension.COUNT },
    unit = unit, defaultAbv = abv,
    aliases = try {
        val a = JSONArray(aliasesJson); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() },
    isCustom = true
)

internal fun stringMapJson(map: Map<String, String>): String = JSONObject(map.mapValues { it.value }).toString()
internal fun parseStringMap(json: String): Map<String, String> = try {
    val o = JSONObject(json); o.keys().asSequence().associateWith { o.getString(it) }
} catch (e: Exception) { emptyMap() }

/** 替代选择解析：v2 为 from→to 字符串映射；兼容 v1 布尔映射（true → 空目标，仅供历史展示） */
internal fun parseSubMap(json: String): Map<String, String> = try {
    val o = JSONObject(json)
    o.keys().asSequence().mapNotNull { k ->
        val v = o.get(k)
        when (v) {
            is Boolean -> if (v) k to "" else null
            is String -> k to v
            else -> null
        }
    }.toMap()
} catch (e: Exception) { emptyMap() }

@Singleton
class CatalogRepository @Inject constructor(val catalog: SeedCatalog, db: AppDatabase) {
    private val customIngredientDao = db.customIngredientDao()

    val ingredients: Map<String, Ingredient> get() = catalog.ingredients
    val systemRecipes: List<Recipe> get() = catalog.recipes
    val substitutions: List<SubstitutionRule> get() = catalog.substitutions
    val brands: List<Brand> get() = catalog.brands

    val customIngredients: Flow<List<Ingredient>> =
        customIngredientDao.observeAll().map { list -> list.map { it.toModel() } }

    /** 系统材料 + 用户自定义材料（§四.1 自定义材料进入匹配/录入/备份） */
    val allIngredients: Flow<Map<String, Ingredient>> =
        customIngredients.map { custom -> catalog.ingredients + custom.associateBy { it.id } }

    suspend fun saveCustomIngredient(ing: Ingredient) {
        val now = System.currentTimeMillis()
        customIngredientDao.upsert(
            CustomIngredientEntity(
                ing.id, ing.zh, ing.en, ing.category,
                when (ing.dimension) { UnitDimension.VOLUME -> "vol"; UnitDimension.MASS -> "mass"; UnitDimension.COUNT -> "count" },
                ing.unit, ing.defaultAbv, JSONArray(ing.aliases).toString(), now, now
            )
        )
    }

    suspend fun deleteCustomIngredient(id: String) =
        customIngredientDao.softDelete(id, System.currentTimeMillis())
}

@Singleton
class CabinetRepository @Inject constructor(
    private val db: AppDatabase,
    private val catalog: SeedCatalog
) : InventoryStore, InventoryWriter {

    private val bottleDao = db.bottleDao()
    private val txnDao = db.txnDao()
    private val sessionDao = db.sessionDao()
    private val kvDao = db.kvDao()
    private val customRecipeDao = db.customRecipeDao()
    private val draftDao = db.draftDao()

    val bottles: Flow<List<Bottle>> = bottleDao.observeAll().map { list -> list.map { it.toModel() } }
    val transactions: Flow<List<InventoryTransaction>> = txnDao.observeAll().map { list -> list.map { it.toModel() } }
    val sessions: Flow<List<MixSession>> = sessionDao.observeAll().map { list -> list.map { it.toModel() } }
    val latestDraft: Flow<MixDraft?> = draftDao.observeLatest().map { it?.toModel() }

    /** 系统配方 + 私人配方（§9.1 RecipeVersion 以 JSON 存储私人版本） */
    val allRecipes: Flow<List<Recipe>> = customRecipeDao.observeAll().map { custom ->
        catalog.recipes + custom.mapNotNull { parseCustomRecipe(it.json) }
    }

    private fun parseCustomRecipe(json: String): Recipe? = try {
        CustomRecipeCodec.decode(JSONObject(json))
    } catch (e: Exception) { null }

    /* ── InventoryStore ── */
    override suspend fun bottlesFor(ingredientId: String) = bottleDao.getByIngredient(ingredientId).map { it.toModel() }
    override suspend fun allBottles() = bottleDao.getAll().map { it.toModel() }
    override suspend fun lastBottleFor(recipeId: String, ingredientId: String) =
        kvDao.get("lastBottle:" + recipeId + ":" + ingredientId)

    /** 内存快照（§五.10）：一次计算读入一致快照 */
    suspend fun snapshot(): SnapshotStore {
        val bottles = allBottles()
        val lastUsed = kvDao.getAll().filter { it.key.startsWith("lastBottle:") }
            .associate { e ->
                val parts = e.key.removePrefix("lastBottle:").split(":")
                if (parts.size == 2) (parts[0] to parts[1]) to e.value else ("" to "") to e.value
            }.filterKeys { it.first.isNotEmpty() }
        return SnapshotStore(bottles, lastUsed)
    }

    /* ── InventoryWriter（统一精度：Qty.round；delta = 实际应用的变化量）── */
    override suspend fun deduct(bottleId: String, delta: Double): Double {
        val b = bottleDao.getById(bottleId) ?: throw IllegalStateException("酒瓶不存在：" + bottleId)
        val want = Qty.round(delta)
        val next = Qty.round(b.remaining - want)
        require(next >= 0) { "「" + b.brand + "」库存不足，无法完成扣减" }   /* 抛错 → Room 事务整体回滚 */
        val applied = Qty.round(b.remaining - next)
        bottleDao.upsert(b.copy(remaining = next, updatedAt = System.currentTimeMillis()))
        return applied
    }

    override suspend fun restore(bottleId: String, delta: Double): Double {
        val b = bottleDao.getById(bottleId) ?: return 0.0
        val next = Qty.round(b.remaining + Qty.round(delta))
        bottleDao.upsert(b.copy(remaining = next, updatedAt = System.currentTimeMillis()))
        return Qty.round(next - b.remaining)
    }

    override suspend fun insertTxn(txn: InventoryTransaction) = txnDao.insert(txn.toEntity())
    override suspend fun insertSession(session: MixSession) = sessionDao.upsert(session.toEntity())
    override suspend fun sessionById(sessionId: String) = sessionDao.getById(sessionId)?.toModel()
    override suspend fun markSessionUndone(sessionId: String) {
        sessionDao.getById(sessionId)?.let { sessionDao.upsert(it.copy(undone = true)) }
    }
    override suspend fun markTxnUndone(txnId: String) = txnDao.markUndone(txnId)
    override suspend fun txnsForSession(sessionId: String) = txnDao.getBySession(sessionId).map { it.toModel() }
    override suspend fun setLastBottle(recipeId: String, ingredientId: String, bottleId: String) =
        kvDao.put(KvEntity("lastBottle:" + recipeId + ":" + ingredientId, bottleId))

    /* ── 原子扣减（§6.3：同一数据库事务，幂等，任一失败整体回滚）── */
    suspend fun commitMixAtomic(
        service: InventoryService,
        draft: MixDraft,
        recipe: Recipe,
        servings: Int,
        ingredients: Map<String, Ingredient>,
        expectedFingerprint: String?
    ): CommitResult = db.withTransaction {
        val result = service.commitMix(
            sessionId = draft.sessionId,
            recipe = recipe,
            servings = servings,
            ingredients = ingredients,
            overrides = draft.bottleOverrides,
            chosenSubs = draft.chosenSubs,
            expectedFingerprint = expectedFingerprint
        )
        /* 草稿只能成功提交一次：成功后删除草稿；失败/计划变化保留草稿供调整 */
        if (result is CommitResult.Success || result is CommitResult.AlreadyCommitted) {
            draftDao.delete(draft.id)
        }
        result
    }

    suspend fun undoSessionAtomic(service: InventoryService, sessionId: String): UndoResult =
        db.withTransaction { service.undoSession(sessionId) }

    /* ── 调酒草稿（§14 调酒中途退出：保存步骤与选择，重新进入后继续）── */
    suspend fun saveDraft(draft: MixDraft) = draftDao.upsert(draft.toEntity().copy(updatedAt = System.currentTimeMillis()))
    suspend fun getDraft(id: String) = draftDao.getById(id)?.toModel()
    suspend fun deleteDraft(id: String) = draftDao.delete(id)

    suspend fun createDraft(recipeId: String, servings: Int, overrides: Map<String, String>, subs: Map<String, String>): MixDraft {
        val d = MixDraft(
            sessionId = UUID.randomUUID().toString(),
            recipeId = recipeId, servings = servings,
            bottleOverrides = overrides, chosenSubs = subs
        )
        draftDao.upsert(d.toEntity())
        return d
    }

    /* ── 酒瓶录入与管理（§6.1 / §四.1）── */
    suspend fun addBottle(bottle: Bottle, reason: String, detail: String = "") {
        db.withTransaction {
            val b = bottle.copy(remaining = Qty.round(bottle.remaining), initQty = Qty.round(bottle.initQty))
            bottleDao.upsert(b.toEntity())
            txnDao.insert(
                InventoryTransaction(
                    bottleId = b.id, ingredientId = b.ingredientId, brand = b.brand,
                    delta = b.remaining, unit = b.unit, reason = reason, detail = detail
                ).toEntity()
            )
        }
    }

    /** 补充库存 / 盘点校正：显式指定目标酒瓶，delta 与库存变化一致 */
    suspend fun adjustStock(bottleId: String, newRemaining: Double, reason: String, detail: String = "") {
        db.withTransaction {
            val b = bottleDao.getById(bottleId) ?: throw IllegalArgumentException("酒瓶不存在")
            val next = Qty.round(newRemaining).coerceAtLeast(0.0)
            val delta = Qty.round(next - b.remaining)
            if (delta == 0.0) return@withTransaction
            bottleDao.upsert(b.copy(remaining = next, updatedAt = System.currentTimeMillis()))
            txnDao.insert(
                InventoryTransaction(
                    bottleId = b.id, ingredientId = b.ingredientId, brand = b.brand,
                    delta = delta, unit = b.unit, reason = reason, detail = detail
                ).toEntity()
            )
        }
    }

    suspend fun addStock(bottleId: String, delta: Double, reason: String) {
        val b = bottleDao.getById(bottleId) ?: throw IllegalArgumentException("酒瓶不存在")
        adjustStock(bottleId, b.remaining + Qty.round(delta), reason, "补充现有库存")
    }

    /** 编辑酒瓶信息（品牌、酒款、容量、酒精度、开瓶日期、低库存阈值） */
    suspend fun updateBottle(bottle: Bottle) =
        bottleDao.upsert(bottle.toEntity().copy(updatedAt = System.currentTimeMillis()))

    /** 归档（软删除）：历史记录中的品牌/流水仍保留可查 */
    suspend fun archiveBottle(bottleId: String) {
        bottleDao.getById(bottleId)?.let {
            bottleDao.upsert(it.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun getBottle(id: String) = bottleDao.getById(id)?.toModel()
}

fun MixSession.toEntity(): SessionEntity {
    return SessionEntity(
        id, recipeId, servings, status, undone, currentStep,
        stringMapJson(chosenSubs), stringMapJson(bottleOverrides),
        startedAt, finishedAt, recipeZh, recipeEn, glass, liquid
    )
}

fun SessionEntity.toModel(): MixSession {
    return MixSession(
        id, recipeId, servings, status, undone, currentStep,
        parseSubMap(chosenSubsJson), parseStringMap(overridesJson), startedAt, finishedAt,
        recipeZh, recipeEn, glass, liquid
    )
}

@Singleton
class RecordsRepository @Inject constructor(
    db: AppDatabase
) {
    private val noteDao = db.noteDao()
    private val favDao = db.favoriteDao()
    private val customDao = db.customRecipeDao()

    val notes: Flow<List<TastingNote>> = noteDao.observeAll().map { list -> list.map { it.toModel() } }
    val favorites: Flow<Set<String>> = favDao.observeAll().map { list -> list.map { it.recipeId }.toSet() }
    val favoriteTimes: Flow<Map<String, Long>> = favDao.observeAll().map { list -> list.associate { it.recipeId to it.time } }

    suspend fun toggleFavorite(recipeId: String, currentlyFav: Boolean) {
        if (currentlyFav) favDao.remove(recipeId) else favDao.add(FavoriteEntity(recipeId, System.currentTimeMillis()))
    }

    suspend fun saveNote(note: TastingNote) = noteDao.upsert(
        NoteEntity(note.id, note.sessionId, note.recipeId, note.rating, note.sweet, note.sour, note.bitter, note.body, note.text, note.photoUri, note.createdAt)
    )

    suspend fun noteForSession(sessionId: String) = noteDao.forSession(sessionId)?.toModel()

    suspend fun saveCustomRecipe(recipe: Recipe, baseRecipeId: String? = null) {
        customDao.upsert(CustomRecipeEntity(recipe.id, CustomRecipeCodec.encode(recipe).toString(), baseRecipeId, recipe.createdAt, System.currentTimeMillis()))
    }

    suspend fun deleteCustomRecipe(id: String) = customDao.softDelete(id, System.currentTimeMillis())

    suspend fun customRecipeById(id: String): Recipe? =
        customDao.getAll().firstOrNull { it.id == id && !it.deleted }?.let {
            try { CustomRecipeCodec.decode(JSONObject(it.json)) } catch (e: Exception) { null }
        }
}
