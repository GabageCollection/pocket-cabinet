package com.ambercabinet.core.data.repo

import com.ambercabinet.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配方材料行 / 步骤的 JSON 编解码（种子数据与私人配方共用同一 schema）：
 * SeedCatalog（系统配方，加载即校验）与 CustomRecipeCodec（私人配方，容错读取）都基于这里。
 */
internal object RecipeJson {

    fun role(s: String): IngredientRole = when (s) {
        "optional" -> IngredientRole.OPTIONAL
        "garnish" -> IngredientRole.GARNISH
        else -> IngredientRole.REQUIRED
    }

    fun roleName(r: IngredientRole): String = when (r) {
        IngredientRole.OPTIONAL -> "optional"
        IngredientRole.GARNISH -> "garnish"
        else -> "required"
    }

    /* ── 材料行 ── */

    fun ingredient(o: JSONObject): RecipeIngredient = RecipeIngredient(
        ingredientId = o.getString("ing"),
        qty = o.optDouble("qty", 0.0),
        unit = o.getString("unit"),
        role = role(o.optString("role", "required")),
        substitutable = o.optBoolean("substitutable", true),
        note = if (o.has("note")) o.getString("note") else null,
        freeText = if (o.has("freeText")) o.getString("freeText") else null
    )

    fun ingredients(o: JSONObject): List<RecipeIngredient> {
        val arr = o.getJSONArray("ingredients")
        return (0 until arr.length()).map { ingredient(arr.getJSONObject(it)) }
    }

    fun ingredientJson(ri: RecipeIngredient): JSONObject {
        val o = JSONObject()
        o.put("ing", ri.ingredientId); o.put("qty", ri.qty); o.put("unit", ri.unit)
        o.put("role", roleName(ri.role))
        o.put("substitutable", ri.substitutable)
        ri.note?.let { o.put("note", it) }
        ri.freeText?.let { o.put("freeText", it) }
        return o
    }

    /* ── 步骤 ── */

    fun step(s: JSONObject): RecipeStep {
        val needArr = s.optJSONArray("need") ?: JSONArray()
        return RecipeStep(
            title = s.getString("t"),
            detail = s.getString("d"),
            needs = (0 until needArr.length()).map { k ->
                val n = needArr.getJSONArray(k)
                StepNeed(n.getString(0), n.optDouble(1, 0.0), n.getString(2))
            },
            timerSeconds = s.optInt("timer", 0),
            timerLabel = if (s.has("timerLabel")) s.getString("timerLabel") else null,
            visual = s.optInt("vis", 0)
        )
    }

    fun steps(o: JSONObject): List<RecipeStep> {
        val arr = o.getJSONArray("steps")
        return (0 until arr.length()).map { step(arr.getJSONObject(it)) }
    }

    fun stepJson(s: RecipeStep): JSONObject {
        val o = JSONObject()
        o.put("t", s.title); o.put("d", s.detail)
        val needs = JSONArray()
        s.needs.forEach { n -> needs.put(JSONArray().put(n.ingredientId).put(n.qty).put(n.unit)) }
        o.put("need", needs)
        if (s.timerSeconds > 0) { o.put("timer", s.timerSeconds); o.put("timerLabel", s.timerLabel) }
        o.put("vis", s.visual)
        return o
    }
}
