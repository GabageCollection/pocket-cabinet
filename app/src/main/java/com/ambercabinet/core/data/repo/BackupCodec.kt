package com.ambercabinet.core.data.repo

import com.ambercabinet.core.data.db.*
import com.ambercabinet.core.units.Units
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份编解码（§12.1，纯 JVM 可测）：
 * - v2：带版本号与校验信息的 ZIP（manifest.json + backup.json + photos/）
 * - 兼容 v1 纯 JSON 备份（旧格式只读导入）
 * - 完整校验：应用标识、schemaVersion、校验值、记录 ID、关联关系、单位、数量范围、私人配方结构
 */
object BackupCodec {
    const val APP_ID = "amber-cabinet"
    const val SCHEMA_VERSION = 2
    private const val MAX_ENTRIES = 2000
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_QTY = 10_000_000.0

    class Invalid(message: String) : Exception(message)

    data class Bundle(
        val bottles: List<BottleEntity> = emptyList(),
        val txns: List<TxnEntity> = emptyList(),
        val sessions: List<SessionEntity> = emptyList(),
        val notes: List<NoteEntity> = emptyList(),
        val favorites: List<FavoriteEntity> = emptyList(),
        val customRecipes: List<CustomRecipeEntity> = emptyList(),
        val customIngredients: List<CustomIngredientEntity> = emptyList(),
        val kv: List<KvEntity> = emptyList(),
        /** 备份内照片：zip 路径（photos/xxx）→ 内容。photoUri 以 "backup-photo:photos/xxx" 标记 */
        val photos: Map<String, ByteArray> = emptyMap(),
        val exportedAt: Long = 0L,
        val schemaVersion: Int = SCHEMA_VERSION
    ) {
        val totalRecords: Int get() = bottles.size + txns.size + sessions.size + notes.size + favorites.size + customRecipes.size + customIngredients.size
    }

    data class Summary(
        val exportedAt: Long,
        val schemaVersion: Int,
        val counts: Map<String, Int>,
        val photoCount: Int
    )

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /* ── 编码 ── */

    fun encode(bundle: Bundle): ByteArray {
        val data = dataJson(bundle)
        val dataBytes = data.toString().toByteArray(Charsets.UTF_8)
        val manifest = JSONObject()
            .put("app", APP_ID)
            .put("format", "amber-backup-zip")
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", bundle.exportedAt)
            .put("counts", JSONObject(mapOf(
                "bottles" to bundle.bottles.size, "txns" to bundle.txns.size,
                "sessions" to bundle.sessions.size, "notes" to bundle.notes.size,
                "favorites" to bundle.favorites.size, "customRecipes" to bundle.customRecipes.size,
                "customIngredients" to bundle.customIngredients.size, "photos" to bundle.photos.size
            )))
            .put("checksum", sha256(dataBytes))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
            zip.putNextEntry(ZipEntry("backup.json")); zip.write(data.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            bundle.photos.forEach { (path, bytes) ->
                require(!path.contains("..")) { "非法照片路径" }
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /* ── 解码 + 校验 ── */

    fun decode(bytes: ByteArray): Bundle {
        require(bytes.size <= MAX_TOTAL_BYTES + 1024 * 1024) { "文件过大，不是有效备份" }
        return if (isZip(bytes)) decodeZip(bytes) else decodeLegacyJson(String(bytes, Charsets.UTF_8))
    }

    fun summarize(bytes: ByteArray): Summary {
        val b = decode(bytes)
        return Summary(
            exportedAt = b.exportedAt,
            schemaVersion = b.schemaVersion,
            counts = mapOf(
                "bottles" to b.bottles.size, "txns" to b.txns.size, "sessions" to b.sessions.size,
                "notes" to b.notes.size, "favorites" to b.favorites.size,
                "customRecipes" to b.customRecipes.size, "customIngredients" to b.customIngredients.size
            ),
            photoCount = b.photos.size
        )
    }

    private fun isZip(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun decodeZip(bytes: ByteArray): Bundle {
        var manifest: JSONObject? = null
        var dataJson: JSONObject? = null
        val photos = mutableMapOf<String, ByteArray>()
        var total = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entries++
                if (entries > MAX_ENTRIES) throw Invalid("备份文件条目过多")
                val name = e.name
                if (name.contains("..") || name.startsWith("/")) throw Invalid("备份文件路径非法")
                val content = zip.readBytes()
                total += content.size
                if (total > MAX_TOTAL_BYTES) throw Invalid("备份文件解压后过大")
                when (name) {
                    "manifest.json" -> manifest = JSONObject(String(content, Charsets.UTF_8))
                    "backup.json" -> dataJson = JSONObject(String(content, Charsets.UTF_8))
                    else -> if (name.startsWith("photos/")) photos[name] = content
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        val m = manifest ?: throw Invalid("缺少 manifest.json，不是有效的备份文件")
        if (m.optString("app") != APP_ID) throw Invalid("不是口袋酒柜的备份文件")
        val schema = m.optInt("schemaVersion", -1)
        if (schema < 1 || schema > SCHEMA_VERSION) throw Invalid("备份版本（$schema）与当前应用不兼容")
        val d = dataJson ?: throw Invalid("备份数据缺失")
        if (m.optString("checksum") != sha256(d.toString().toByteArray(Charsets.UTF_8)))
            throw Invalid("校验失败：备份文件已损坏")
        val bundle = parseData(d, photos, m.optLong("exportedAt"), schema)
        validate(bundle)
        return bundle
    }

    /** v1 兼容：旧版纯 JSON 备份（UUID 覆盖式合并时代），现按全量替换语义导入 */
    private fun decodeLegacyJson(text: String): Bundle {
        val root = try { JSONObject(text) } catch (e: Exception) { throw Invalid("不是有效的备份文件") }
        if (root.optString("app") != APP_ID) throw Invalid("不是口袋酒柜的备份文件")
        val schema = root.optInt("schemaVersion", 1)
        if (schema > SCHEMA_VERSION) throw Invalid("备份版本（$schema）比当前应用新，无法恢复")
        val data = root.optJSONObject("data") ?: throw Invalid("备份数据缺失")
        if (root.optString("checksum") != sha256(data.toString().toByteArray(Charsets.UTF_8)))
            throw Invalid("校验失败：备份文件已损坏")
        val bundle = parseData(data, emptyMap(), root.optLong("exportedAt"), schema)
        validate(bundle)
        return bundle
    }

    /* ── 数据 JSON ── */

    private fun dataJson(b: Bundle) = JSONObject()
        .put("bottles", JSONArray(b.bottles.map(::bottleJson)))
        .put("txns", JSONArray(b.txns.map(::txnJson)))
        .put("sessions", JSONArray(b.sessions.map(::sessionJson)))
        .put("notes", JSONArray(b.notes.map(::noteJson)))
        .put("favorites", JSONArray(b.favorites.map(::favJson)))
        .put("customRecipes", JSONArray(b.customRecipes.map(::customJson)))
        .put("customIngredients", JSONArray(b.customIngredients.map(::customIngJson)))
        .put("kv", JSONArray(b.kv.map(::kvJson)))

    private fun parseData(d: JSONObject, photos: Map<String, ByteArray>, exportedAt: Long, schema: Int) = Bundle(
        bottles = parseBottles(d.optJSONArray("bottles") ?: JSONArray()),
        txns = parseTxns(d.optJSONArray("txns") ?: JSONArray()),
        sessions = parseSessions(d.optJSONArray("sessions") ?: JSONArray()),
        notes = parseNotes(d.optJSONArray("notes") ?: JSONArray()),
        favorites = parseFavs(d.optJSONArray("favorites") ?: JSONArray()),
        customRecipes = parseCustoms(d.optJSONArray("customRecipes") ?: JSONArray()),
        customIngredients = parseCustomIngs(d.optJSONArray("customIngredients") ?: JSONArray()),
        kv = parseKvs(d.optJSONArray("kv") ?: JSONArray()),
        photos = photos,
        exportedAt = exportedAt,
        schemaVersion = schema
    )

    /** 全量校验（§三.5）：任何一项不通过都拒绝恢复 */
    private fun validate(b: Bundle) {
        fun requireIds(kind: String, ids: List<String>) {
            if (ids.any { it.isBlank() }) throw Invalid(kind + "存在空 ID")
            if (ids.size != ids.distinct().size) throw Invalid(kind + "存在重复 ID")
        }
        requireIds("酒瓶", b.bottles.map { it.id })
        requireIds("流水", b.txns.map { it.id })
        requireIds("调制记录", b.sessions.map { it.id })
        requireIds("品鉴笔记", b.notes.map { it.id })
        requireIds("私人配方", b.customRecipes.map { it.id })
        requireIds("自定义材料", b.customIngredients.map { it.id })

        val bottleIds = b.bottles.map { it.id }.toSet()
        val sessionIds = b.sessions.map { it.id }.toSet()
        for (t in b.txns) {
            if (t.bottleId != null && t.bottleId !in bottleIds) throw Invalid("流水关联了不存在的酒瓶")
            if (t.sessionId != null && t.sessionId !in sessionIds) throw Invalid("流水关联了不存在的调制记录")
            if (t.unit !in Units.ALL_UNITS) throw Invalid("流水包含未知单位：" + t.unit)
            if (Math.abs(t.delta) > MAX_QTY) throw Invalid("流水数量超出合理范围")
        }
        for (bo in b.bottles) {
            if (bo.unit !in Units.ALL_UNITS) throw Invalid("酒瓶包含未知单位：" + bo.unit)
            if (bo.remaining < 0 || bo.initQty <= 0 || bo.remaining > MAX_QTY || bo.initQty > MAX_QTY)
                throw Invalid("酒瓶「" + bo.brand + "」数量超出合理范围")
            if (bo.lowPct !in 0..100) throw Invalid("酒瓶「" + bo.brand + "」低库存阈值非法")
        }
        for (s in b.sessions) {
            if (s.recipeId.isBlank()) throw Invalid("调制记录缺少配方 ID")
            if (s.servings !in 1..100) throw Invalid("调制记录杯数超出合理范围")
        }
        for (n in b.notes) {
            if (n.sessionId !in sessionIds) throw Invalid("品鉴笔记关联了不存在的调制记录")
            if (n.rating < 0 || n.rating > 5) throw Invalid("品鉴评分超出 0–5 范围")
        }
        for (c in b.customRecipes) {
            try { CustomRecipeCodec.decode(JSONObject(c.json)) }
            catch (e: Exception) { throw Invalid("私人配方「" + c.id + "」结构不完整：" + e.message) }
        }
        for (ci in b.customIngredients) {
            if (ci.dim !in listOf("vol", "mass", "count")) throw Invalid("自定义材料「" + ci.zh + "」单位维度非法")
            if (ci.unit !in Units.ALL_UNITS) throw Invalid("自定义材料「" + ci.zh + "」单位非法")
        }
    }

    /* ── 序列化 ── */
    private fun bottleJson(b: BottleEntity) = JSONObject()
        .put("id", b.id).put("ingredientId", b.ingredientId).put("brand", b.brand)
        .put("label", b.label).put("shape", b.shape).put("liquid", b.liquid)
        .put("initQty", b.initQty).put("remaining", b.remaining).put("unit", b.unit)
        .put("abv", b.abv).put("openedAt", b.openedAt ?: JSONObject.NULL)
        .put("lowPct", b.lowPct).put("photoUri", b.photoUri ?: JSONObject.NULL)
        .put("createdAt", b.createdAt).put("updatedAt", b.updatedAt).put("deleted", b.deleted)

    private fun txnJson(t: TxnEntity) = JSONObject()
        .put("id", t.id).put("bottleId", t.bottleId ?: JSONObject.NULL)
        .put("ingredientId", t.ingredientId).put("brand", t.brand)
        .put("delta", t.delta).put("unit", t.unit).put("reason", t.reason)
        .put("detail", t.detail).put("sessionId", t.sessionId ?: JSONObject.NULL)
        .put("undone", t.undone).put("time", t.time)

    private fun sessionJson(s: SessionEntity) = JSONObject()
        .put("id", s.id).put("recipeId", s.recipeId).put("servings", s.servings)
        .put("status", s.status).put("undone", s.undone).put("currentStep", s.currentStep)
        .put("chosenSubsJson", s.chosenSubsJson).put("overridesJson", s.overridesJson)
        .put("startedAt", s.startedAt).put("finishedAt", s.finishedAt ?: JSONObject.NULL)
        .put("recipeZh", s.recipeZh).put("recipeEn", s.recipeEn)
        .put("glass", s.glass).put("liquid", s.liquid)

    private fun noteJson(n: NoteEntity) = JSONObject()
        .put("id", n.id).put("sessionId", n.sessionId).put("recipeId", n.recipeId)
        .put("rating", n.rating).put("sweet", n.sweet ?: JSONObject.NULL)
        .put("sour", n.sour ?: JSONObject.NULL).put("bitter", n.bitter ?: JSONObject.NULL)
        .put("body", n.body ?: JSONObject.NULL).put("text", n.text)
        .put("photoUri", n.photoUri ?: JSONObject.NULL).put("createdAt", n.createdAt)

    private fun favJson(f: FavoriteEntity) = JSONObject().put("recipeId", f.recipeId).put("time", f.time)
    private fun customJson(c: CustomRecipeEntity) = JSONObject()
        .put("id", c.id).put("json", c.json).put("baseRecipeId", c.baseRecipeId ?: JSONObject.NULL)
        .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt).put("deleted", c.deleted)
    private fun customIngJson(c: CustomIngredientEntity) = JSONObject()
        .put("id", c.id).put("zh", c.zh).put("en", c.en).put("cat", c.cat).put("dim", c.dim)
        .put("unit", c.unit).put("abv", c.abv).put("aliasesJson", c.aliasesJson)
        .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt).put("deleted", c.deleted)
    private fun kvJson(k: KvEntity) = JSONObject().put("key", k.key).put("value", k.value)

    /* ── 解析 ── */
    private fun JSONObject.optStr(key: String): String? = if (isNull(key) || !has(key)) null else getString(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key) || !has(key)) null else getLong(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)

    private fun parseBottles(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        BottleEntity(o.getString("id"), o.getString("ingredientId"), o.getString("brand"),
            o.optString("label"), o.optString("shape", "spirit"), o.optString("liquid"),
            o.getDouble("initQty"), o.getDouble("remaining"), o.getString("unit"),
            o.optDouble("abv"), o.optLongOrNull("openedAt"), o.optInt("lowPct", 20),
            o.optStr("photoUri"), o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"))
    }
    private fun parseTxns(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        TxnEntity(o.getString("id"), o.optStr("bottleId"), o.getString("ingredientId"),
            o.getString("brand"), o.getDouble("delta"), o.getString("unit"), o.getString("reason"),
            o.optString("detail"), o.optStr("sessionId"), o.optBoolean("undone"), o.getLong("time"))
    }
    private fun parseSessions(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        SessionEntity(o.getString("id"), o.getString("recipeId"), o.getInt("servings"),
            o.optString("status", "done"), o.optBoolean("undone"), o.optInt("currentStep"),
            o.optString("chosenSubsJson", "{}"), o.optString("overridesJson", "{}"),
            o.getLong("startedAt"), o.optLongOrNull("finishedAt"),
            o.optString("recipeZh", ""), o.optString("recipeEn", ""),
            o.optString("glass", ""), o.optString("liquid", ""))
    }
    private fun parseNotes(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        NoteEntity(o.getString("id"), o.getString("sessionId"), o.getString("recipeId"),
            o.getDouble("rating"), o.optIntOrNull("sweet"), o.optIntOrNull("sour"),
            o.optIntOrNull("bitter"), o.optIntOrNull("body"), o.optString("text"),
            o.optStr("photoUri"), o.getLong("createdAt"))
    }
    private fun parseFavs(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        FavoriteEntity(o.getString("recipeId"), o.getLong("time"))
    }
    private fun parseCustoms(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        CustomRecipeEntity(o.getString("id"), o.getString("json"), o.optStr("baseRecipeId"),
            o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"))
    }
    private fun parseCustomIngs(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        CustomIngredientEntity(o.getString("id"), o.getString("zh"), o.optString("en"),
            o.getString("cat"), o.getString("dim"), o.getString("unit"),
            o.optDouble("abv"), o.optString("aliasesJson", "[]"),
            o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("deleted"))
    }
    private fun parseKvs(a: JSONArray) = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        KvEntity(o.getString("key"), o.getString("value"))
    }
}
