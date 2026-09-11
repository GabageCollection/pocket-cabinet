package com.ambercabinet.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.core.graphics.PathParser
import com.ambercabinet.ui.theme.Fg

/* 杯型 / 瓶型渲染器：path 数据移植自设计稿 pour.js（§10.3 本地视觉，无版权照片）
   liquid 为 oklch 字符串的近似色解析，或直接使用 Color */

class GlassShape(val body: String, val line: List<String>, val liquid: String, val hi: List<String>)

object PourShapes {
    val glasses = mapOf(
        "coupe" to GlassShape(
            "M10 12h44c0 16-9 26-22 26S10 28 10 12Z",
            listOf("M32 38v20", "M20 66h24"),
            "M14.8 18h34.4C47.7 27.5 41 35 32 35S16.3 27.5 14.8 18Z",
            listOf("M15.5 15.5c-1.2 4.8-.2 9.2 1.8 12.4")
        ),
        "martini" to GlassShape(
            "M8 10h48L32 40Z",
            listOf("M32 40v18", "M20 66h24"),
            "M13.8 16.5h36.4L32 36.5Z",
            listOf("M14 13.5l18 20.5")
        ),
        "rocks" to GlassShape(
            "M18 8h28v54a5 5 0 0 1-5 5H23a5 5 0 0 1-5-5Z",
            listOf("M18.6 57.5h26.8"),
            "M20.8 30h22.4v32a3.2 3.2 0 0 1-3.2 3.2H24a3.2 3.2 0 0 1-3.2-3.2Z",
            listOf("M21.8 13v14")
        ),
        "highball" to GlassShape(
            "M21 4h22l-2 60a4.4 4.4 0 0 1-4.4 4.4h-9.2A4.4 4.4 0 0 1 23 64Z",
            emptyList(),
            "M23.4 22h17.2l-1.5 39.6a2.8 2.8 0 0 1-2.8 2.7h-8.6a2.8 2.8 0 0 1-2.8-2.7Z",
            listOf("M24.6 9v10")
        )
    )

    class BottleShape(val body: String, val cap: String, val innerTop: Float, val innerBottom: Float)
    val bottles = mapOf(
        "spirit" to BottleShape("M11.5 6h9v10l5 6.5V62a4.5 4.5 0 0 1-4.5 4.5H11A4.5 4.5 0 0 1 6.5 62V22.5l5-6.5Z", "M11.5 2.5h9v3.5h-9Z", 22f, 63.5f),
        "whiskey" to BottleShape("M11 7h10v7l4 4v44a4 4 0 0 1-4 4H11a4 4 0 0 1-4-4V18l4-4Z", "M11 3h10v4H11Z", 19f, 63f),
        "gin" to BottleShape("M12.5 4h7v12l2.8 4v42a4 4 0 0 1-4 4h-4.6a4 4 0 0 1-4-4V20l2.8-4Z", "M12.5 1.5h7v3h-7Z", 20f, 63f),
        "wine" to BottleShape("M12.6 2.5h6.8v16c3.4 3.6 5.6 7 5.6 12.5V62a4.5 4.5 0 0 1-4.5 4.5h-9A4.5 4.5 0 0 1 7 62V31c0-5.5 2.2-8.9 5.6-12.5Z", "M12.6 1h6.8v2.5h-6.8Z", 30f, 63.5f),
        "liqueur" to BottleShape("M12 6h8v7c4.4 2.4 7 6.4 7 12v33a5.5 5.5 0 0 1-5.5 5.5h-11A5.5 5.5 0 0 1 5 58V25c0-5.6 2.6-9.6 7-12Z", "M12 2.5h8v3.5h-8Z", 26f, 60.5f)
    )
}

/** oklch 字符串 → 近似 Color（解析 oklch(L C H)，做粗略 sRGB 映射） */
fun parseLiquid(spec: String): Color {
    return try {
        val nums = Regex("[0-9.]+").findAll(spec).map { it.value.toFloat() }.toList()
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

private fun pathOf(d: String): Path = PathParser.createPathFromPathData(d)?.asComposePath() ?: Path()

/** 酒杯 + 酒液（viewBox 64x78 等比缩放） */
@Composable
fun GlassPour(glass: String, liquid: Color, modifier: Modifier = Modifier) {
    val shape = PourShapes.glasses[glass] ?: PourShapes.glasses.getValue("coupe")
    Canvas(modifier.aspectRatio(64f / 78f)) {
        val scale = size.width / 64f
        val stroke = Stroke(width = 1.5f * scale)
        drawContext.canvas.save()
        drawContext.canvas.scale(scale, scale)
        drawPath(pathOf(shape.body), Fg.copy(alpha = 0.04f))
        drawPath(
            pathOf(shape.liquid),
            Brush.verticalGradient(listOf(liquid.copy(alpha = 0.85f), liquid), startY = 10f, endY = 70f)
        )
        drawPath(pathOf(shape.body), Fg.copy(alpha = 0.46f), style = stroke)
        shape.line.forEach { drawPath(pathOf(it), Fg.copy(alpha = 0.46f), style = stroke) }
        shape.hi.forEach { drawPath(pathOf(it), Color.White.copy(alpha = 0.31f), style = Stroke(width = 1.3f)) }
        drawContext.canvas.restore()
    }
}

/** 酒瓶 + 液位（viewBox 32x72；fill = 剩余比例 0..1） */
@Composable
fun BottlePour(shapeName: String, fill: Float, liquid: Color, modifier: Modifier = Modifier) {
    val shape = PourShapes.bottles[shapeName] ?: PourShapes.bottles.getValue("spirit")
    Canvas(modifier.aspectRatio(32f / 72f)) {
        val scale = size.width / 32f
        val stroke = Stroke(width = 1.5f * scale)
        drawContext.canvas.save()
        drawContext.canvas.scale(scale, scale)
        val body = pathOf(shape.body)
        drawPath(body, Fg.copy(alpha = 0.04f))
        if (fill > 0f) {
            val top = shape.innerBottom - (shape.innerBottom - shape.innerTop) * fill.coerceIn(0f, 1f)
            clipPath(body) {
                drawRect(
                    liquid.copy(alpha = 0.88f),
                    topLeft = Offset(4f, top),
                    size = androidx.compose.ui.geometry.Size(24f, shape.innerBottom - top + 4f)
                )
                drawRect(liquid.copy(alpha = 0.3f), topLeft = Offset(4f, top), size = androidx.compose.ui.geometry.Size(24f, 3.5f))
            }
        }
        drawPath(body, Fg.copy(alpha = 0.46f), style = stroke)
        drawPath(pathOf(shape.cap), Fg.copy(alpha = 0.46f), style = stroke)
        drawContext.canvas.restore()
    }
}
