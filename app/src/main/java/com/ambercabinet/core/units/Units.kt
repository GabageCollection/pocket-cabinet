package com.ambercabinet.core.units

import com.ambercabinet.core.model.Ingredient
import com.ambercabinet.core.model.UnitDimension

/**
 * 单位换算（规格 §9.2）：同维度确定性换算，跨维度返回 null。
 *
 * 采用的换算标准（明确公开，不混算维度）：
 * - 液体：1 oz = 29.5735 ml（美制液体盎司）；1 cl = 10 ml；1 dash = 0.5 ml；1 茶匙(tsp) = 5 ml
 * - 固体：g
 * - 离散：个 / 片 / 瓣 / 枝 / 块 / 角（1 个青柠约 4 角，1 个柠檬约 8 片皮 —— 估算值）
 * - 鲜果出汁（Ingredient.yieldMl）：材料特定估算换算，单独定义，属估算属性
 */
object Units {
    const val DASH_ML = 0.5        // 1 dash = 0.5 ml
    const val OZ_ML = 29.5735      // 1 US fl oz = 29.5735 ml
    const val CL_ML = 10.0         // 1 cl = 10 ml
    const val TSP_ML = 5.0         // 1 茶匙 = 5 ml
    const val WEDGES_PER_FRUIT = 4 // 1 个约切 4 角（估算）
    const val PEELS_PER_FRUIT = 8  // 1 个约出 8 片皮（估算）

    val VOLUME_UNITS = listOf("ml", "cl", "oz", "dash", "茶匙")
    val COUNT_UNITS = listOf("个", "片", "瓣", "枝", "块", "角")
    val MASS_UNITS = listOf("g")
    val ALL_UNITS = VOLUME_UNITS + COUNT_UNITS + MASS_UNITS

    fun dimensionOf(unit: String): UnitDimension = when (unit) {
        "ml", "dash", "cl", "oz", "茶匙" -> UnitDimension.VOLUME
        "g" -> UnitDimension.MASS
        else -> UnitDimension.COUNT
    }

    fun unitsFor(dimension: UnitDimension): List<String> = when (dimension) {
        UnitDimension.VOLUME -> VOLUME_UNITS
        UnitDimension.MASS -> MASS_UNITS
        UnitDimension.COUNT -> COUNT_UNITS
    }

    /** 液体单位 → ml；非液体返回 null */
    fun toMl(qty: Double, unit: String): Double? = when (unit) {
        "ml" -> qty
        "cl" -> qty * CL_ML
        "oz" -> qty * OZ_ML
        "dash" -> qty * DASH_ML
        "茶匙" -> qty * TSP_ML
        else -> null
    }

    /** ml → 目标液体单位；非液体返回 null */
    fun fromMl(ml: Double, unit: String): Double? = when (unit) {
        "ml" -> ml
        "cl" -> ml / CL_ML
        "oz" -> ml / OZ_ML
        "dash" -> ml / DASH_ML
        "茶匙" -> ml / TSP_ML
        else -> null
    }

    /**
     * 把配方需求（qty/unit）换算为以库存单位（stockUnit）计的数量。
     * 无法换算（维度不同且无出汁规则）时返回 null。
     * 结果经 [Qty.round] 规整到统一精度。
     */
    fun needInStockUnit(
        ingredient: Ingredient,
        qty: Double,
        unit: String,
        stockUnit: String
    ): Double? {
        val needMl = toMl(qty, unit)
        if (needMl != null) {
            val stockMl = fromMl(needMl, stockUnit)
            if (stockMl != null) return Qty.round(stockMl)
            /* 鲜果出汁：估算换算，材料特定（§9.2 不自动跨维度，仅此显式规则） */
            if (ingredient.yieldMl != null && stockUnit == "个") return Qty.round(needMl / ingredient.yieldMl)
            return null
        }
        if (unit == stockUnit) return Qty.round(qty)
        if (unit == "角" && stockUnit == "个") return Qty.round(qty / WEDGES_PER_FRUIT)
        if (unit == "片" && stockUnit == "个") return Qty.round(qty / PEELS_PER_FRUIT)
        return null
    }

    /** 显示用格式化：最多三位小数，去掉尾零。仅用于显示，不得回写。 */
    fun fmt(n: Double): String {
        val r = Qty.round(n)
        if (r % 1.0 == 0.0) return r.toLong().toString()
        var s = "%.3f".format(r)
        while (s.endsWith("0")) s = s.dropLast(1)
        return s.trimEnd('.')
    }
}
