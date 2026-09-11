package com.ambercabinet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambercabinet.core.model.RecipeStatus
import com.ambercabinet.ui.theme.*

/** 状态徽章（§7.1 四色胶囊） */
@Composable
fun StatusBadge(status: RecipeStatus, label: String? = null) {
    val (color, text) = when (status) {
        RecipeStatus.OK -> StOk to (label ?: "现在就能调")
        RecipeStatus.SUBSTITUTABLE -> StSub to (label ?: "换个材料也能调")
        RecipeStatus.MISSING -> StMiss to (label ?: "还缺点东西")
        RecipeStatus.INSUFFICIENT -> StInsuff to (label ?: "快不够了")
    }
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

/** 库存条 */
@Composable
fun StockBar(pct: Float, low: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(Fg.copy(alpha = 0.10f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(pct.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(if (low) StMiss else StOk)
        )
    }
}

/** 等宽数字 */
@Composable
fun MonoNum(text: String, modifier: Modifier = Modifier, size: Int = 17, color: androidx.compose.ui.graphics.Color = Fg) {
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = size.sp, color = color)
}

/** 小标签 */
@Composable
fun SmallTag(text: String) {
    Text(
        text,
        color = Muted,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}
