package com.ambercabinet.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.core.graphics.PathParser
import com.ambercabinet.ui.theme.AmberMotion
import com.ambercabinet.ui.theme.Fg
import kotlin.math.abs
import kotlin.math.sin

/* 杯型 / 瓶型渲染器：path 数据移植自设计稿 pour.js（§10.3 本地视觉，无版权照片）
   liquid 为 oklch 字符串的近似色解析；Path 解析结果随形状缓存，不随每帧重算；
   液位变化走弹簧动画，静止后无持续动画（项目原则：无无限循环动画） */

private fun pathOf(d: String): Path = PathParser.createPathFromPathData(d)?.asComposePath() ?: Path()

/** 杯型数据：path 字符串在首次访问时解析并缓存（lazy），不再每帧解析 */
class GlassShape(
    body: String, line: List<String>, liquid: String, hi: List<String>,
    /** 液面上下边界（viewBox 64x78 坐标），用于 fill 参数裁剪液位 */
    val liquidTop: Float, val liquidBottom: Float
) {
    val bodyPath: Path by lazy { pathOf(body) }
    val linePaths: List<Path> by lazy { line.map(::pathOf) }
    val liquidPath: Path by lazy { pathOf(liquid) }
    val hiPaths: List<Path> by lazy { hi.map(::pathOf) }
}

/** 瓶型数据：同上 */
class BottleShape(body: String, cap: String, val innerTop: Float, val innerBottom: Float) {
    val bodyPath: Path by lazy { pathOf(body) }
    val capPath: Path by lazy { pathOf(cap) }
}

object PourShapes {
    val glasses = mapOf(
        "coupe" to GlassShape(
            "M10 12h44c0 16-9 26-22 26S10 28 10 12Z",
            listOf("M32 38v20", "M20 66h24"),
            "M14.8 18h34.4C47.7 27.5 41 35 32 35S16.3 27.5 14.8 18Z",
            listOf("M15.5 15.5c-1.2 4.8-.2 9.2 1.8 12.4"),
            liquidTop = 18f, liquidBottom = 35f
        ),
        "martini" to GlassShape(
            "M8 10h48L32 40Z",
            listOf("M32 40v18", "M20 66h24"),
            "M13.8 16.5h36.4L32 36.5Z",
            listOf("M14 13.5l18 20.5"),
            liquidTop = 16.5f, liquidBottom = 36.5f
        ),
        "rocks" to GlassShape(
            "M18 8h28v54a5 5 0 0 1-5 5H23a5 5 0 0 1-5-5Z",
            listOf("M18.6 57.5h26.8"),
            "M20.8 30h22.4v32a3.2 3.2 0 0 1-3.2 3.2H24a3.2 3.2 0 0 1-3.2-3.2Z",
            listOf("M21.8 13v14"),
            liquidTop = 30f, liquidBottom = 62f
        ),
        "highball" to GlassShape(
            "M21 4h22l-2 60a4.4 4.4 0 0 1-4.4 4.4h-9.2A4.4 4.4 0 0 1 23 64Z",
            emptyList(),
            "M23.4 22h17.2l-1.5 39.6a2.8 2.8 0 0 1-2.8 2.7h-8.6a2.8 2.8 0 0 1-2.8-2.7Z",
            listOf("M24.6 9v10"),
            liquidTop = 22f, liquidBottom = 61.6f
        )
    )

    val bottles = mapOf(
        "spirit" to BottleShape("M11.5 6h9v10l5 6.5V62a4.5 4.5 0 0 1-4.5 4.5H11A4.5 4.5 0 0 1 6.5 62V22.5l5-6.5Z", "M11.5 2.5h9v3.5h-9Z", 22f, 63.5f),
        "whiskey" to BottleShape("M11 7h10v7l4 4v44a4 4 0 0 1-4 4H11a4 4 0 0 1-4-4V18l4-4Z", "M11 3h10v4H11Z", 19f, 63f),
        "gin" to BottleShape("M12.5 4h7v12l2.8 4v42a4 4 0 0 1-4 4h-4.6a4 4 0 0 1-4-4V20l2.8-4Z", "M12.5 1.5h7v3h-7Z", 20f, 63f),
        "wine" to BottleShape("M12.6 2.5h6.8v16c3.4 3.6 5.6 7 5.6 12.5V62a4.5 4.5 0 0 1-4.5 4.5h-9A4.5 4.5 0 0 1 7 62V31c0-5.5 2.2-8.9 5.6-12.5Z", "M12.6 1h6.8v2.5h-6.8Z", 30f, 63.5f),
        "liqueur" to BottleShape("M12 6h8v7c4.4 2.4 7 6.4 7 12v33a5.5 5.5 0 0 1-5.5 5.5h-11A5.5 5.5 0 0 1 5 58V25c0-5.6 2.6-9.6 7-12Z", "M12 2.5h8v3.5h-8Z", 26f, 60.5f)
    )
}

private val OKLCH_NUMS = Regex("[0-9.]+")

/* 绘制坐标系（viewBox），缩放时按可用区域取能放下的最大等比矩形并居中 */
private const val VIEW_W = 64f
private const val VIEW_H = 78f
private const val BOTTLE_W = 32f
private const val BOTTLE_H = 72f

/** oklch 字符串 → 近似 Color（解析 oklch(L C H)，做粗略 sRGB 映射） */
fun parseLiquid(spec: String): Color {
    return try {
        val nums = OKLCH_NUMS.findAll(spec).map { it.value.toFloat() }.toList()
        val l = nums[0].coerceIn(0f, 1f)
        val c = nums.getOrElse(1) { 0.05f }
        val h = Math.toRadians(nums.getOrElse(2) { 80f }.toDouble())
        /* oklch → oklab → 线性 sRGB（标准矩阵） */
        val a = (c * Math.cos(h)).toFloat()
        val b = (c * Math.sin(h)).toFloat()
        val l3 = (l + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m3 = (l - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s3 = (l - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        var r = +4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        var g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        var bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        fun gam(x: Float) = (if (x <= 0.0031308f) 12.92f * x else 1.055f * Math.pow(x.toDouble(), 1 / 2.4).toFloat() - 0.055f)
        r = gam(r).coerceIn(0f, 1f); g = gam(g).coerceIn(0f, 1f); bl = gam(bl).coerceIn(0f, 1f)
        Color(r, g, bl)
    } catch (e: Exception) { Color(0xFFD6A45D) }
}

/**
 * 酒杯 + 酒液（viewBox 64x78 等比缩放）。liquidSpec 为 oklch 字符串，颜色在内部 remember。
 *
 * 关键：调用方普遍先给固定 size（外层约束已锁死），此时 aspectRatio **不生效**，只能拿到给定矩形。
 * 所以缩放比例取 min(宽/64, 高/78)，画不满的那一边用 translate 居中 —— 给定任意尺寸都只画小、不溢出。
 * （aspectRatio 保留用于约束宽松的调用点，那才是它能起作用的场景。）
 *
 * @param fill 液位 0..1（默认满杯）；完成调制等场景可从 0 动画到 1（调用方用 animateFloatAsState 驱动）
 */
@Composable
fun GlassPour(glass: String, liquidSpec: String, modifier: Modifier = Modifier, fill: Float = 1f) {
    val shape = PourShapes.glasses[glass] ?: PourShapes.glasses.getValue("coupe")
    val liquid = remember(liquidSpec) { parseLiquid(liquidSpec) }
    Box(modifier, contentAlignment = Alignment.Center) {
        /* 外层约束已锁死时 aspectRatio 是空操作，真正保证不溢出的是下面的 min() 缩放 + 居中 */
        Canvas(Modifier.fillMaxSize().aspectRatio(VIEW_W / VIEW_H, matchHeightConstraintsFirst = true)) {
            val scale = minOf(size.width / VIEW_W, size.height / VIEW_H)
            val stroke = Stroke(width = 1.5f * scale)
            drawContext.canvas.save()
            drawContext.canvas.translate((size.width - VIEW_W * scale) / 2f, (size.height - VIEW_H * scale) / 2f)
            drawContext.canvas.scale(scale, scale)
            drawPath(shape.bodyPath, Fg.copy(alpha = 0.04f))
            val brush = Brush.verticalGradient(listOf(liquid.copy(alpha = 0.85f), liquid), startY = 10f, endY = 70f)
            val f = fill.coerceIn(0f, 1f)
            if (f >= 1f) {
                drawPath(shape.liquidPath, brush)
            } else if (f > 0f) {
                /* 按液位从底部裁剪液面 */
                val top = shape.liquidBottom - (shape.liquidBottom - shape.liquidTop) * f
                clipRect(left = 0f, top = top, right = VIEW_W, bottom = VIEW_H) {
                    drawPath(shape.liquidPath, brush)
                }
            }
            drawPath(shape.bodyPath, Fg.copy(alpha = 0.46f), style = stroke)
            shape.linePaths.forEach { drawPath(it, Fg.copy(alpha = 0.46f), style = stroke) }
            shape.hiPaths.forEach { drawPath(it, Color.White.copy(alpha = 0.31f), style = Stroke(width = 1.3f)) }
            drawContext.canvas.restore()
        }
    }
}

/**
 * 酒瓶 + 液位（viewBox 32x72；fill = 剩余比例 0..1）。liquidSpec 为 oklch 字符串。
 * 液位变化内部走弹簧动画；到位过程中液面带正弦波纹（振幅随剩余差距衰减），静止后无任何持续动画。
 *
 * 与 GlassPour 同样按可用区域自适应缩放：调用方给固定 size 时 aspectRatio 无效，
 * 因此额外用 min(宽比, 高比) 兜底，避免画出来的瓶子高过给定框、压到相邻行。
 */
@Composable
fun BottlePour(shapeName: String, fill: Float, liquidSpec: String, modifier: Modifier = Modifier) {
    val shape = PourShapes.bottles[shapeName] ?: PourShapes.bottles.getValue("spirit")
    val liquid = remember(liquidSpec) { parseLiquid(liquidSpec) }
    val target = fill.coerceIn(0f, 1f)
    val animFill by animateFloatAsState(target, AmberMotion.stateSpring(), label = "bottleFill")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().aspectRatio(BOTTLE_W / BOTTLE_H, matchHeightConstraintsFirst = true)) {
            val scale = minOf(size.width / BOTTLE_W, size.height / BOTTLE_H)
            val stroke = Stroke(width = 1.5f * scale)
            drawContext.canvas.save()
            drawContext.canvas.translate((size.width - BOTTLE_W * scale) / 2f, (size.height - BOTTLE_H * scale) / 2f)
            drawContext.canvas.scale(scale, scale)
            val body = shape.bodyPath
            drawPath(body, Fg.copy(alpha = 0.04f))
            if (animFill > 0f) {
                val top = shape.innerBottom - (shape.innerBottom - shape.innerTop) * animFill
                /* 波纹振幅：液面移动越大波纹越高，静止时归零（非循环动画） */
                val amp = (3f * abs(target - animFill)).coerceAtMost(2.5f)
                clipPath(body) {
                    if (amp > 0.05f) {
                        val wave = Path()
                        wave.moveTo(4f, top + amp * sin(0f))
                        var x = 4f
                        while (x <= 28f) {
                            wave.lineTo(x, top + amp * sin((x - 4f) / 24f * (Math.PI * 2).toFloat()))
                            x += 2f
                        }
                        wave.lineTo(28f, shape.innerBottom + 4f)
                        wave.lineTo(4f, shape.innerBottom + 4f)
                        wave.close()
                        drawPath(wave, liquid.copy(alpha = 0.88f))
                    } else {
                        drawRect(
                            liquid.copy(alpha = 0.88f),
                            topLeft = Offset(4f, top),
                            size = androidx.compose.ui.geometry.Size(24f, shape.innerBottom - top + 4f)
                        )
                    }
                    drawRect(liquid.copy(alpha = 0.3f), topLeft = Offset(4f, top), size = androidx.compose.ui.geometry.Size(24f, 3.5f))
                }
            }
            drawPath(body, Fg.copy(alpha = 0.46f), style = stroke)
            drawPath(shape.capPath, Fg.copy(alpha = 0.46f), style = stroke)
            drawContext.canvas.restore()
        }
    }
}
