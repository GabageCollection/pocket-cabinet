package com.ambercabinet.core.units

/**
 * 数量统一精度策略（修复「库存变化量与流水 delta 不一致」「0.125 个被舍入」）：
 * 库存、扣减、撤销、流水、备份统一保留 0.001 单位（毫升 → 微升级，个 → 千分之一个）。
 * 所有写库数量必须先经 [round] 规整；界面只做显示简化，不回写显示值。
 */
object Qty {
    const val SCALE = 1000.0
    const val EPS = 0.0005

    /** 规整到 0.001 单位。0.125 个、0.5 ml 等精确保留。 */
    fun round(v: Double): Double = Math.round(v * SCALE) / SCALE
}
