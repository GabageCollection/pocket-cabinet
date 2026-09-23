package com.ambercabinet.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambercabinet.core.domain.SubChoice
import com.ambercabinet.core.model.Bottle
import com.ambercabinet.core.model.RecipeStatus
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Locale

/** 状态徽章（§7.1 四色胶囊）；状态变化时颜色平滑过渡 */
@Composable
fun StatusBadge(status: RecipeStatus, label: String? = null) {
    val (targetColor, text) = when (status) {
        RecipeStatus.OK -> StOk to (label ?: "现在就能调")
        RecipeStatus.SUBSTITUTABLE -> StSub to (label ?: "换个材料也能调")
        RecipeStatus.MISSING -> StMiss to (label ?: "还缺点东西")
        RecipeStatus.INSUFFICIENT -> StInsuff to (label ?: "快不够了")
    }
    val color by animateColorAsState(targetColor, AmberMotion.fast(), label = "badgeColor")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = color, fontSize = 11.sp)
    }
}

/**
 * 库存条：库存变化时弹簧动画到位。
 * 用 graphicsLayer{scaleX}（transformOrigin 左端）而非 fillMaxWidth(动画值)，避免每帧 relayout。
 */
@Composable
fun StockBar(pct: Float, low: Boolean, modifier: Modifier = Modifier) {
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), AmberMotion.stateSpring(), label = "stockPct")
    val fillColor by animateColorAsState(if (low) StMiss else StOk, label = "stockColor")
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Fg.copy(alpha = 0.10f))
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animPct
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(999.dp))
                .background(fillColor)
        )
    }
}

/** 等宽数字（容量 / 杯数 / 计时），基于设计令牌 AmberType.mono */
@Composable
fun MonoNum(text: String, modifier: Modifier = Modifier, size: Int = 17, color: Color = Fg) {
    Text(text, modifier = modifier, style = AmberType.mono.copy(fontSize = size.sp, color = color))
}

/** 小标签（可选/装饰等次要标记）：细边框 + 弱化文字 */
@Composable
fun SmallTag(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Fg.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/** 统一的「返回箭头 + 标题」顶栏 */
@Composable
fun AmberTopBar(title: String, onBack: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
        Text(title, fontSize = 18.sp, color = Fg, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** 统一的「知道了」消息弹窗 */
@Composable
fun MessageDialog(msg: String?, onDismiss: () -> Unit) {
    if (msg == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了", color = Accent) } },
        text = { Text(msg) },
        containerColor = Raised
    )
}

/**
 * 组合期创建、随组件一起销毁的日期格式化器。
 * SimpleDateFormat 不是线程安全的，不能做成顶层单例（多屏共用会串数据）。
 */
@Composable
fun rememberDateFmt(pattern: String): SimpleDateFormat = remember(pattern) { SimpleDateFormat(pattern, Locale.CHINA) }

/** 一行「标签 · 值」（酒瓶档案、设置里的数据清单共用一套排版） */
@Composable
fun InfoRow(
    label: String,
    value: String,
    labelColor: Color = Muted,
    valueColor: Color = Fg,
    labelSize: Int = 13
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = labelColor, fontSize = labelSize.sp)
        Text(value, color = valueColor, fontSize = 13.sp)
    }
}

/** 单行输入框 + 字段级错误提示（酒瓶资料、私人配方表单共用） */
@Composable
fun LabeledField(label: String, value: String, error: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label) }, singleLine = true,
        isError = error != null,
        shape = RoundedCornerShape(12.dp)
    )
    if (error != null) Text(error, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

/** 低库存判定：唯一来源（酒瓶详情、酒柜列表、发现页的低库存提示共用）。容量为 0 时不算低。 */
fun Bottle.isLowStock(): Boolean = initQty > 0 && remaining / initQty * 100 <= lowPct

/** 剩余比例 0..1（容量为 0 时按空瓶处理） */
fun Bottle.remainingPct(): Float = if (initQty > 0) (remaining / initQty).toFloat() else 0f

/** 统一的空态提示 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, color = Muted)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 风味词表（发现页 chip / 酒谱页与详情页标签共用一份） */
object Flavor {
    val ZH = mapOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈")
    val CHIPS = listOf("all" to "全部") + ZH.map { it.key to it.value }
    fun zh(f: String): String = ZH[f] ?: f
}

/** 替代方案一行说明（发现页与酒谱页同一句话） */
fun SubChoice.line(): String = toDef.zh + " 可以代替 · 1 : " + Units.fmt(rule.ratio) + " · " + rule.flavorImpact
