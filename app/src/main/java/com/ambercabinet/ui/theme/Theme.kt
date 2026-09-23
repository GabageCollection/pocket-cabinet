package com.ambercabinet.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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

/** 唯一的内联色值：Fg 的一层极淡描边（分割线/卡片描边） */
val Hairline = Color(0x14F3EBDD)

/* 配方状态四色（§7.1） */
val StOk = Color(0xFF8FCB9B)
val StSub = Accent
val StMiss = Color(0xFFE0885A)
val StInsuff = Color(0xFF8A8378)

/**
 * 动效令牌：全项目统一时长/缓动，禁止散落字面量。
 * - 可中断的状态值动画（进度、液位、颜色、弹性）一律用 [stateSpring]，用户连点从当前值继续；
 * - 一次性过渡（转场、展开收起）用 tween 三档；
 * - 标准 Compose 动画 API 自动遵循系统「动画时长缩放」，无需额外处理；
 * - 项目内不使用任何无限循环动画。
 */
object AmberMotion {
    const val FAST = 150
    const val MED = 300
    const val SLOW = 500

    val DefaultEasing = FastOutSlowInEasing

    fun <T> fast(): TweenSpec<T> = tween(FAST, easing = DefaultEasing)
    fun <T> med(): TweenSpec<T> = tween(MED, easing = DefaultEasing)
    fun <T> slow(): TweenSpec<T> = tween(SLOW, easing = DefaultEasing)

    /** 可中断状态动画：进度条、液位、颜色、点击弹性 */
    fun <T> stateSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /** 微交互弹性（收藏、评星）：带一点回弹 */
    fun <T> bounceSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
}

private val AmberColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    background = Bg,
    onBackground = Fg,
    surface = Surface,
    onSurface = Fg,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    outline = Hairline,
    secondary = Muted,
    onSecondary = Fg
)

object AmberType {
    /* 鸡尾酒名 / 推荐主标题 / 详情标题：衬线 */
    val displaySerif = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
    /* 等宽数字仅用于容量 / 杯数 / 计时（MonoNum 基于此令牌） */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
}

/* 基础排版常量：避免在构造 provider 的同时读取 MaterialTheme.typography 自身 */
private val BaseTypography = Typography()

@Composable
fun AmberTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmberColors,
        typography = BaseTypography.copy(
            headlineLarge = BaseTypography.headlineLarge.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = BaseTypography.titleLarge.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = BaseTypography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 15.sp),
            labelSmall = BaseTypography.labelSmall.copy(fontSize = 12.sp, color = Muted)
        ),
        content = content
    )
}
