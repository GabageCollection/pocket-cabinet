package com.ambercabinet.core.data.repo

import com.ambercabinet.core.model.*
import org.json.JSONArray
import org.json.JSONObject

/** 私人配方 / 个人版本与系统配方同 schema（§10.3 系统升级不覆盖私人版本） */
object CustomRecipeCodec {

    fun encode(r: Recipe): JSONObject {
        val o = JSONObject()
        o.put("id", r.id); o.put("zh", r.zh); o.put("en", r.en)
        o.put("source", r.source); o.put("sourceNote", r.sourceNote)
        o.put("flavors", JSONArray(r.flavors))
        o.put("difficulty", r.difficulty); o.put("method", r.method)
        o.put("glass", r.glass); o.put("glassZh", r.glassZh)
        o.put("liquid", r.liquid); o.put("abv", r.abv); o.put("timeMin", r.timeMin)
        val ings = JSONArray()
        r.ingredients.forEach { ri ->
            val io = JSONObject()
            io.put("ing", ri.ingredientId); io.put("qty", ri.qty); io.put("unit", ri.unit)
            io.put("role", when (ri.role) {
                IngredientRole.OPTIONAL -> "optional"
                IngredientRole.GARNISH -> "garnish"
                else -> "required"
            })
            io.put("substitutable", ri.substitutable)
            ri.note?.let { io.put("note", it) }
            ri.freeText?.let { io.put("freeText", it) }
            ings.put(io)
        }
        o.put("ingredients", ings)
        val steps = JSONArray()
        r.steps.forEach { s ->
            val so = JSONObject()
            so.put("t", s.title); so.put("d", s.detail)
            val needs = JSONArray()
            s.needs.forEach { n -> needs.put(JSONArray().put(n.first).put(n.second).put(n.third)) }
            so.put("need", needs)
            if (s.timerSeconds > 0) { so.put("timer", s.timerSeconds); so.put("timerLabel", s.timerLabel) }
            so.put("vis", s.visual)
            steps.put(so)
        }
        o.put("steps", steps)
        o.put("createdAt", r.createdAt); o.put("updatedAt", r.updatedAt)
        return o
    }

    fun decode(o: JSONObject): Recipe {
        fun optStrings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        val ingArr = o.getJSONArray("ingredients")
        val ings = (0 until ingArr.length()).map { j ->
            val ri = ingArr.getJSONObject(j)
            RecipeIngredient(
                ingredientId = ri.getString("ing"),
                qty = ri.optDouble("qty", 0.0),
                unit = ri.getString("unit"),
                role = when (ri.getString("role")) {
                    "optional" -> IngredientRole.OPTIONAL
                    "garnish" -> IngredientRole.GARNISH
                    else -> IngredientRole.REQUIRED
                },
                substitutable = ri.optBoolean("substitutable", true),
                note = if (ri.has("note")) ri.getString("note") else null,
                freeText = if (ri.has("freeText")) ri.getString("freeText") else null
            )
        }
        val stepArr = o.getJSONArray("steps")
        val steps = (0 until stepArr.length()).map { j ->
            val s = stepArr.getJSONObject(j)
            val needArr = s.optJSONArray("need") ?: JSONArray()
            RecipeStep(
                title = s.getString("t"),
                detail = s.getString("d"),
                needs = (0 until needArr.length()).map { k ->
                    val n = needArr.getJSONArray(k)
                    Triple(n.getString(0), n.optDouble(1, 0.0), n.getString(2))
                },
                timerSeconds = s.optInt("timer", 0),
                timerLabel = if (s.has("timerLabel")) s.getString("timerLabel") else null,
                visual = s.optInt("vis", 0)
            )
        }
        return Recipe(
            id = o.getString("id"), zh = o.getString("zh"), en = o.getString("en"),
            source = o.optString("source", "private"), sourceNote = o.optString("sourceNote", "私人配方"),
            flavors = optStrings(o.optJSONArray("flavors")),
            difficulty = o.optInt("difficulty", 1), method = o.optString("method", "直调"),
            glass = o.optString("glass", "rocks"), glassZh = o.optString("glassZh", "古典杯"),
            liquid = o.optString("liquid", "oklch(0.62 0.13 60)"),
            abv = o.optDouble("abv", 0.0), timeMin = o.optInt("timeMin", 3),
            ingredients = ings, steps = steps, isUser = true,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
