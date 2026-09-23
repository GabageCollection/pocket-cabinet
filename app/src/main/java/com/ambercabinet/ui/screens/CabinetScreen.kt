package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.data.prefs.UserPrefs
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.MatchResult
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.model.Bottle
import com.ambercabinet.core.model.Ingredient
import com.ambercabinet.core.model.IngredientRole
import com.ambercabinet.core.model.RecipeStatus
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class BottleUi(val bottle: Bottle, val usageRecipes: Int, val openedDays: Long?)

data class CabinetGroup(val zh: String, val en: String, val items: List<BottleUi>)

data class CabinetState(
    val groups: List<CabinetGroup> = emptyList(),
    val total: Int = 0,
    val opened: Int = 0,
    val filter: String = "all",
    val loaded: Boolean = false,
    /** 备份提醒：有库存且距上次备份超过 30 天（0 = 从未备份） */
    val showBackupHint: Boolean = false
)

/* 备份提醒的超期阈值：30 天 */
private const val BACKUP_OVERDUE_MS = 30L * 24 * 3600 * 1000

private val CAT_META: Map<String, Pair<String, String>> = linkedMapOf(
    "base" to ("基酒" to "BASE SPIRITS"),
    "liqueur" to ("利口酒 · 味美思" to "LIQUEUR & VERMOUTH"),
    "mix" to ("糖浆 · 汽水" to "SYRUP & SODA"),
    "bitters" to ("苦精" to "BITTERS"),
    "fresh" to ("水果 · 香草" to "FRESH"),
    "other" to ("其他" to "OTHER")
)
private val CAT_ORDER = listOf("base", "liqueur", "mix", "bitters", "fresh", "other")
private val CAT_LABELS = listOf("all" to "全部") + CAT_ORDER.map { it to (CAT_META[it]?.first ?: "其他") }

@HiltViewModel
class CabinetViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val userPrefs: UserPrefs
) : ViewModel() {
    private val filter = MutableStateFlow("all")

    /* 贵的部分（§五.10/五.11 一致快照）：一次全量匹配，瓶的「能调几款」从共享结果推导，
       不再做 O(瓶 × 配方) 的重复匹配 */
    private data class Computed(
        val bottles: List<Bottle>,
        val ings: Map<String, Ingredient>,
        val matches: Map<String, MatchResult>,
        val usageByIngredient: Map<String, Int>
    )

    private val computed: Flow<Computed> =
        combine(cabinet.bottles, cabinet.allRecipes, catalog.allIngredients) { bottles, recipes, ings ->
            val engine = MatchEngine(SnapshotStore(bottles), catalog.substitutions)
            val matches = recipes.associate { it.id to engine.match(it, 1, ings) }
            val usage = mutableMapOf<String, Int>()
            for (r in recipes) {
                val m = matches[r.id] ?: continue
                if (m.status != RecipeStatus.OK && m.status != RecipeStatus.SUBSTITUTABLE) continue
                r.ingredients.filter { it.role == IngredientRole.REQUIRED }
                    .forEach { usage.merge(it.ingredientId, 1, Int::plus) }
            }
            Computed(bottles, ings, matches, usage)
        }.flowOn(Dispatchers.Default)

    /* 便宜的部分：筛选/分组/排序只是纯 List 操作 */
    val state: StateFlow<CabinetState> = combine(computed, filter) { c, f ->
        val now = System.currentTimeMillis()
        val uis = c.bottles.map { b ->
            BottleUi(b, c.usageByIngredient[b.ingredientId] ?: 0, b.openedAt?.let { (now - it) / 86400000 })
        }
        val filtered = if (f == "all") uis else uis.filter { (c.ings[it.bottle.ingredientId]?.category ?: "other") == f }
        val groups = filtered
            .groupBy { c.ings[it.bottle.ingredientId]?.category ?: "other" }
            .toList()
            .sortedBy { (cat, _) -> CAT_ORDER.indexOf(cat).let { if (it < 0) CAT_ORDER.size else it } }
            .map { (cat, list) -> CabinetGroup(CAT_META[cat]?.first ?: "其他", CAT_META[cat]?.second ?: "OTHER", list) }
        /* lastBackupAt 是同步 SharedPreferences 读取，在 Default 线程读无压力；0 = 从未备份，直接按超期处理 */
        val backupDue = c.bottles.isNotEmpty() && System.currentTimeMillis() - userPrefs.lastBackupAt > BACKUP_OVERDUE_MS
        CabinetState(groups, c.bottles.size, c.bottles.count { it.openedAt != null }, f, loaded = true, showBackupHint = backupDue)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CabinetState())

    fun setFilter(f: String) { filter.value = f }
}

@Composable
fun CabinetScreen(nav: NavHostController, vm: CabinetViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    /* ✕ 只本次会话不再显示（进程恢复后若仍未备份会重新出现） */
    var backupHintDismissed by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Bg,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { nav.navigate(Routes.ADD_BOTTLE) },
                containerColor = Accent,
                contentColor = AccentInk,
                shape = RoundedCornerShape(16.dp)
            ) { Icon(Icons.Filled.Add, contentDescription = "添加酒瓶") }
        },
        bottomBar = { BottomDock(nav) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("共 " + s.total.toString() + " 样 · 开了 " + s.opened + " 瓶", color = Muted, fontSize = 12.sp)
                        Text("酒柜", style = MaterialTheme.typography.headlineLarge)
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Fg)
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CAT_LABELS) { (k, label) ->
                        FilterChip(selected = s.filter == k, onClick = { vm.setFilter(k) }, label = { Text(label) })
                    }
                }
            }

            /* 备份提醒：顶栏之下、列表之上；超期才出现，✕ 仅本次会话关闭 */
            if (s.showBackupHint && !backupHintDismissed) {
                item(key = "backupHint") {
                    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("很久没备份了，去设置页导出一份", color = Fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                                Text("去备份", color = Accent, fontSize = 13.sp)
                            }
                            IconButton(onClick = { backupHintDismissed = true }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭备份提醒", tint = Muted)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            /* 酒柜真空与「当前分类筛不出东西」是两回事：前者引导添加，后者引导换个分类看 */
            if (s.loaded && s.groups.isEmpty() && s.total == 0) {
                item {
                    EmptyHint("酒柜还是空的，先把第一瓶放进来") {
                        Button(
                            onClick = { nav.navigate(Routes.ADD_BOTTLE) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("添加第一瓶") }
                        TextButton(onClick = { nav.navigate(Routes.recipes()) }) { Text("先看看酒谱", color = Muted) }
                    }
                }
            } else if (s.loaded && s.groups.isEmpty()) {
                item {
                    EmptyHint("这个分类下还没有酒") {
                        TextButton(onClick = { vm.setFilter("all") }) { Text("看全部", color = Accent) }
                    }
                }
            }
            s.groups.forEach { group ->
                item {
                    Text(
                        group.zh + "  " + group.en,
                        color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                    )
                }
                items(group.items, key = { it.bottle.id }) { ui ->
                    BottleRow(ui, nav, Modifier.animateItem(), onUsageClick = { nav.navigate(Routes.recipes(it)) }) { nav.navigate(Routes.bottle(ui.bottle.id)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun BottleRow(ui: BottleUi, nav: NavHostController, modifier: Modifier = Modifier, onUsageClick: (String) -> Unit = {}, onClick: () -> Unit) {
    val b = ui.bottle
    val pct = b.remainingPct()
    val low = b.isLowStock()
    Row(modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)) {
        BottlePour(b.shape, pct, b.liquid, Modifier.size(44.dp, 72.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(b.brand + " " + b.label, fontSize = 15.sp, color = Fg, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                /* 快见底了：点一下直接去购物清单补货（clickable 优先于整行点击） */
                if (low) Text(
                    "快见底了",
                    color = StMiss, fontSize = 11.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.SHOPPING) }
                )
            }
            /* 当前剩余量最突出，初始容量/开瓶天数降层级（天数在 ViewModel 按瓶算好，组合期不再读时钟） */
            Row(verticalAlignment = Alignment.Bottom) {
                MonoNum(Units.fmt(b.remaining), size = 22, color = if (low) StMiss else Fg)
                Text(" " + b.unit + " 剩余", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "容量 " + Units.fmt(b.initQty) + " " + b.unit +
                        " · " + (if (ui.openedDays != null) "开了 " + ui.openedDays + " 天" else "还没开瓶") +
                        (if (b.abv > 0) " · " + Units.fmt(b.abv) + "% vol" else " · 无酒精") +
                        (if (ui.usageRecipes > 0) " · " else ""),
                    color = Muted, fontSize = 12.sp
                )
                /* 「能调 N 款」可点：跳酒谱页并按该材料预筛选（clickable 优先于整行点击）；Accent 色提示可点 */
                if (ui.usageRecipes > 0) {
                    Text(
                        "能调 " + ui.usageRecipes + " 款",
                        color = Accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { onUsageClick(b.ingredientId) }
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StockBar(pct, low, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                MonoNum((pct * 100).toInt().toString() + "%", size = 11, color = if (low) StMiss else Muted)
            }
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}
