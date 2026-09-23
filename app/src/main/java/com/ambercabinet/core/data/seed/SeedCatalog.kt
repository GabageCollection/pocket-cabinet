package com.ambercabinet.core.data.seed

import android.content.Context
import com.ambercabinet.core.data.repo.RecipeJson
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import org.json.JSONObject

/**
 * 初始配方数据（规格 §10.3）：版本化 JSON 随 APK 发布。
 * 加载时校验：唯一 ID、必需字段、材料关联存在；任何一款失败则整体失败（不回写）。
 */
class SeedCatalog(
    val version: String,
    val ingredients: Map<String, Ingredient>,
    val recipes: List<Recipe>,
    val substitutions: List<SubstitutionRule>,
    val brands: List<Brand>
) {
    companion object {
        /** 步骤里允许出现的说明性用量（无数量意义，不参与换算与扣减） */
        private val DESCRIPTIVE_UNITS = setOf("适量", "少许", "可省")

        /** 步骤用量单位是否已知：允许「dash（可省）」这类在已知单位后附注的写法 */
        private fun isKnownNeedUnit(raw: String): Boolean {
            if (raw in Units.ALL_UNITS || raw in DESCRIPTIVE_UNITS) return true
            val base = raw.substringBefore('（').trim()
            return base in Units.ALL_UNITS || base in DESCRIPTIVE_UNITS
        }

        /** 不依赖 Context 的解析入口（供 JVM 单元测试与导入校验使用） */
        fun parse(ingredientsJson: String, substitutionsJson: String, brandsJson: String, recipesJson: String): SeedCatalog {
            val ingredients = parseIngredients(JSONObject(ingredientsJson))
            val substitutions = parseSubstitutions(JSONObject(substitutionsJson))
            val brands = parseBrands(JSONObject(brandsJson))
            val (version, recipes) = parseRecipes(JSONObject(recipesJson), ingredients)
            return SeedCatalog(version, ingredients, recipes, substitutions, brands)
        }

        fun load(context: Context): SeedCatalog {
            val ingredients = parseIngredients(readAsset(context, "seed/ingredients.json"))
            val substitutions = parseSubstitutions(readAsset(context, "seed/substitutions.json"))
            val brands = parseBrands(readAsset(context, "seed/brands.json"))
            val (version, recipes) = parseRecipes(readAsset(context, "seed/recipes.json"), ingredients)
            return SeedCatalog(version, ingredients, recipes, substitutions, brands)
        }

        private fun readAsset(context: Context, path: String): JSONObject =
            context.assets.open(path).bufferedReader().use { JSONObject(it.readText()) }

        private fun optStringList(obj: JSONObject, key: String): List<String> {
            val arr = obj.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { arr.getString(it) }
        }

        private fun parseIngredients(root: JSONObject): Map<String, Ingredient> {
            val arr = root.getJSONArray("ingredients")
            val map = LinkedHashMap<String, Ingredient>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ing = Ingredient(
                    id = o.getString("id"),
                    zh = o.getString("zh"),
                    en = o.getString("en"),
                    category = o.getString("cat"),
                    dimension = when (o.getString("dim")) {
                        "vol" -> UnitDimension.VOLUME
                        "mass" -> UnitDimension.MASS
                        else -> UnitDimension.COUNT
                    },
                    unit = o.getString("unit"),
                    defaultAbv = o.optDouble("abv", 0.0),
                    aliases = optStringList(o, "aliases"),
                    allergens = optStringList(o, "allergens"),
                    staple = o.optBoolean("staple", false),
                    yieldMl = if (o.has("yieldMl")) o.getDouble("yieldMl") else null
                )
                require(!map.containsKey(ing.id)) { "重复材料 ID: " + ing.id }
                map[ing.id] = ing
            }
            return map
        }

        private fun parseSubstitutions(root: JSONObject): List<SubstitutionRule> {
            val arr = root.getJSONArray("substitutions")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val extra = o.optJSONObject("extra")
                SubstitutionRule(
                    fromId = o.getString("from"),
                    toId = o.getString("to"),
                    ratio = o.getDouble("ratio"),
                    flavorImpact = o.getString("flavor"),
                    abvDelta = o.optDouble("abvDelta", 0.0),
                    methods = o.optString("methods", "全部方法"),
                    priority = o.optInt("priority", 1),
                    extraIngredientId = extra?.getString("ing"),
                    extraQty = extra?.optDouble("qty", 0.0) ?: 0.0,
                    extraUnit = extra?.optString("unit", "ml") ?: "ml"
                )
            }
        }

        private fun parseBrands(root: JSONObject): List<Brand> {
            val arr = root.getJSONArray("brands")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Brand(
                    ingredientId = o.getString("ingredient"),
                    brand = o.getString("brand"),
                    label = o.getString("label"),
                    abv = o.optDouble("abv", 0.0),
                    capacityMl = o.getInt("cap")
                )
            }
        }

        private fun parseRecipes(
            root: JSONObject,
            ingredients: Map<String, Ingredient>
        ): Pair<String, List<Recipe>> {
            val version = root.getString("version")
            val arr = root.getJSONArray("recipes")
            val ids = mutableSetOf<String>()
            val recipes = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                require(ids.add(id)) { "重复配方 ID: " + id }
                /* 材料行/步骤解析与私人配方共用 RecipeJson（同 schema），这里额外做引用完整性校验 */
                val ingArr = o.getJSONArray("ingredients")
                for (j in 0 until ingArr.length()) {
                    val ingId = ingArr.getJSONObject(j).getString("ing")
                    require(ingredients.containsKey(ingId)) { "配方 " + id + " 引用了未知材料: " + ingId }
                }
                val recipeIngredients = RecipeJson.ingredients(o)
                require(recipeIngredients.any { it.role == IngredientRole.REQUIRED }) {
                    "配方 " + id + " 缺少必需材料"
                }
                val steps = RecipeJson.steps(o)
                /* 单位白名单校验：材料行的单位必须落在已知单位里（否则匹配/扣减会静默失败） */
                recipeIngredients.forEach { ri ->
                    require(ri.unit in Units.ALL_UNITS) {
                        "配方 " + id + " 的材料「" + ri.ingredientId + "」使用了未知单位：" + ri.unit
                    }
                }
                /* 步骤内的用量单位：允许「适量/少许」等说明性用量，以及「dash（可省）」这类带备注写法 */
                steps.forEach { st ->
                    st.needs.forEach { n ->
                        require(isKnownNeedUnit(n.unit)) {
                            "配方 " + id + " 的步骤用量使用了未知单位：" + n.unit
                        }
                    }
                }
                /* 过敏原自动聚合（§13） */
                val allergens = recipeIngredients
                    .mapNotNull { ingredients[it.ingredientId] }
                    .flatMap { it.allergens }
                    .distinct()
                Recipe(
                    id = id,
                    zh = o.getString("zh"),
                    en = o.getString("en"),
                    source = o.getString("source"),
                    sourceNote = o.getString("sourceNote"),
                    flavors = optStringList(o, "flavors"),
                    difficulty = o.getInt("difficulty"),
                    method = o.getString("method"),
                    glass = o.getString("glass"),
                    glassZh = o.getString("glassZh"),
                    liquid = o.getString("liquid"),
                    abv = o.getDouble("abv"),
                    timeMin = o.getInt("timeMin"),
                    allergens = allergens,
                    ingredients = recipeIngredients,
                    steps = steps
                )
            }
            return version to recipes
        }
    }
}
