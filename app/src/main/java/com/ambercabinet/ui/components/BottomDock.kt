package com.ambercabinet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.Accent
import com.ambercabinet.ui.theme.Muted
import com.ambercabinet.ui.theme.Raised

private data class DockTab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    DockTab(Routes.DISCOVER, "发现", Icons.Outlined.Explore),
    DockTab(Routes.CABINET, "酒柜", Icons.Outlined.WineBar),
    DockTab(Routes.RECIPES, "酒谱", Icons.AutoMirrored.Outlined.MenuBook),
    DockTab(Routes.RECORDS, "记录", Icons.Outlined.History)
)

/** 悬浮玻璃 Dock（对应设计稿 .tabbar）：圆角 26、半透明 Raised、模糊感以半透明模拟 */
@Composable
fun BottomDock(nav: NavHostController, active: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Raised.copy(alpha = 0.86f))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == active
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { if (!selected) nav.navigate(tab.route) { popUpTo(Routes.DISCOVER); launchSingleTop = true } }
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                    Icon(tab.icon, contentDescription = tab.label, tint = if (selected) Accent else Muted, modifier = Modifier.size(22.dp))
                    Text(tab.label, color = if (selected) Accent else Muted, fontSize = 10.sp)
                }
            }
        }
    }
}
