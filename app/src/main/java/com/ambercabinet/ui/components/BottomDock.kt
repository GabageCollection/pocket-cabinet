package com.ambercabinet.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.Accent
import com.ambercabinet.ui.theme.Muted
import com.ambercabinet.ui.theme.Raised

private data class DockTab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    DockTab(Routes.DISCOVER, "发现", Icons.Outlined.Explore),
    DockTab(Routes.CABINET, "酒柜", Icons.Outlined.WineBar),
    DockTab(Routes.SHOPPING, "清单", Icons.Outlined.ShoppingCart),
    DockTab(Routes.recipes(), "酒谱", Icons.AutoMirrored.Outlined.MenuBook),
    DockTab(Routes.RECORDS, "记录", Icons.Outlined.History)
)

/** 悬浮玻璃 Dock（对应设计稿 .tabbar）：圆角 26、半透明 Raised、模糊感以半透明模拟。
 *  active 由当前路由推导，不再由各页面硬编码；切 tab 保存/恢复各 tab 的状态与滚动位置 */
@Composable
fun BottomDock(nav: NavHostController) {
    val vm: DockViewModel = hiltViewModel()
    val hasDraft by vm.hasDraft.collectAsStateWithLifecycle()
    val active = nav.currentBackStackEntryAsState().value?.destination?.route
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
                /* 带参跳转后 destination.route 是模板串（如 "recipes?ing={ing}"），取 '?' 前裸路由再比 */
                val selected = tab.route == active?.substringBefore('?')
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            if (!selected) nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                ) {
                    /* 有未完成的调酒草稿：「发现」tab 右上角点个小红点 */
                    Box {
                        Icon(
                            tab.icon, contentDescription = tab.label, tint = if (selected) Accent else Muted, modifier = Modifier.size(22.dp)
                        )
                        if (tab.route == Routes.DISCOVER && hasDraft) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Accent)
                            )
                        }
                    }
                    Text(tab.label, color = if (selected) Accent else Muted, fontSize = 10.sp)
                }
            }
        }
    }
}
