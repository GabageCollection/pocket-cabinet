package com.ambercabinet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* 视觉令牌（重构锁定值，与设计稿一致） */
val Bg = Color(0xFF100E0C)
val Surface = Color(0xFF191613)
val Raised = Color(0xFF221D18)
val Fg = Color(0xFFF3EBDD)
val Muted = Color(0xFFA89F93)
val Accent = Color(0xFFD6A45D)   // 琥珀金
val AccentInk = Color(0xFF2A1E0E)

/* 配方状态四色（§7.1） */
val StOk = Color(0xFF8FCB9B)
val StSub = Accent
val StMiss = Color(0xFFE0885A)
val StInsuff = Color(0xFF8A8378)

private val AmberColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    background = Bg,
    onBackground = Fg,
    surface = Surface,
    onSurface = Fg,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Color(0x14F3EBDD),
    secondary = Muted,
    onSecondary = Fg
)

object AmberType {
    /* 鸡尾酒名 / 推荐主标题 / 详情标题：衬线 */
    val displaySerif = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
    /* 界面正文：无衬线 */
    val body = TextStyle(fontFamily = FontFamily.Default)
    /* 等宽数字仅用于容量 / 杯数 / 计时 */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

@Composable
fun AmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmberColors,
        typography = MaterialTheme.typography.copy(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            labelSmall = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, color = Muted)
        ),
        content = content
    )
}
