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
        r.ingredients.forEach { ings.put(RecipeJson.ingredientJson(it)) }
        o.put("ingredients", ings)
        val steps = JSONArray()
        r.steps.forEach { steps.put(RecipeJson.stepJson(it)) }
        o.put("steps", steps)
        o.put("createdAt", r.createdAt); o.put("updatedAt", r.updatedAt)
        return o
    }

    fun decode(o: JSONObject): Recipe {
        fun optStrings(arr: JSONArray?): List<String> =
            if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
        return Recipe(
            id = o.getString("id"), zh = o.getString("zh"), en = o.getString("en"),
            source = o.optString("source", "private"), sourceNote = o.optString("sourceNote", "私人配方"),
            flavors = optStrings(o.optJSONArray("flavors")),
            difficulty = o.optInt("difficulty", 1), method = o.optString("method", "直调"),
            glass = o.optString("glass", "rocks"), glassZh = o.optString("glassZh", "古典杯"),
            liquid = o.optString("liquid", "oklch(0.62 0.13 60)"),
            abv = o.optDouble("abv", 0.0), timeMin = o.optInt("timeMin", 3),
            ingredients = RecipeJson.ingredients(o), steps = RecipeJson.steps(o), isUser = true,
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }
}
