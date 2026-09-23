package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** 「只差一种材料」：按缺失材料归组的配方（cups = 组内配方补货后可调杯数合计） */
data class MissingGroup(
    val ingredient: Ingredient,
    val recipes: List<Recipe>,
    val cups: Int
)

data class ShoppingState(
    val missingGroups: List<MissingGroup> = emptyList(),
    val lowStock: List<Bottle> = emptyList(),
    val unlocks: List<UnlockRow> = emptyList(),
    val loaded: Boolean = false
)

@HiltViewModel
class ShoppingViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository
) : ViewModel() {

    /** 清单勾选状态：会话内即可，不进数据库 */
    val checkedIds = MutableStateFlow<Set<String>>(emptySet())

    /** 贵的部分（§五.10 一致快照）：全量 match 在 Default 线程算 */
    private data class Computed(
        val missingGroups: List<MissingGroup>,
        val lowStock: List<Bottle>,
        val unlocks: List<UnlockRow>
    )

    private val computed: Flow<Computed> = combine(
        cabinet.bottles, cabinet.allRecipes, catalog.allIngredients
    ) { b, r, i -> computeAll(b, r, i) }.flowOn(Dispatchers.Default)

    private suspend fun computeAll(
        bottles: List<Bottle>, recipes: List<Recipe>, ings: Map<String, Ingredient>
    ): Computed {
        val snapshot = SnapshotStore(bottles)
        val engine = MatchEngine(snapshot, catalog.substitutions)
        val recommender = RecommendationEngine(snapshot, engine)

        val matched = recipes.map { it to engine.match(it, 1, ings) }
        /* MISSING 且只缺一种的配方，按缺失材料分组（同 DiscoverViewModel 的 almost 算法，多一步 groupBy） */
        val missingGroups = matched
            .filter { it.second.status == RecipeStatus.MISSING && it.second.missing.size == 1 }
            .groupBy { it.second.missing[0].def.id }
            .map { (_, rows) ->
                val def = rows.first().second.missing[0].def
                val rs = rows.map { it.first }
                MissingGroup(
                    ingredient = def,
                    recipes = rs,
                    cups = rs.sumOf { recommender.cupsIfRestocked(it, def, ings) }
                )
            }
            .sortedByDescending { it.recipes.size }
        return Computed(
            missingGroups = missingGroups,
            lowStock = bottles.filter { it.isLowStock() },
            unlocks = recommender.unlockRanking(recipes, ings).take(5)
        )
    }

    /* 便宜的部分：只对已算好的结果做纯 List 操作；checkedIds 必须作为 combine 输入，否则勾选不会刷新 */
    val state: StateFlow<ShoppingState> = combine(computed, checkedIds) { c, _ ->
        ShoppingState(
            missingGroups = c.missingGroups,
            lowStock = c.lowStock,
            unlocks = c.unlocks,
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShoppingState())

    fun toggle(id: String) {
        checkedIds.value = checkedIds.value.let { if (id in it) it - id else it + id }
    }
}

@Composable
fun ShoppingScreen(nav: NavHostController, vm: ShoppingViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val checked by vm.checkedIds.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("购物清单", style = MaterialTheme.typography.headlineLarge)
            }
        },
        bottomBar = { BottomDock(nav) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {

            /* 补这些，解锁最多 */
            if (s.unlocks.isNotEmpty()) {
                item { SectionHead("补这些，解锁最多", s.unlocks.size.toString() + " 种") }
                items(s.unlocks, key = { "unlock-" + it.ingredient.id }) { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.ingredient.zh, color = Fg, fontSize = 14.sp)
                            Text(u.ingredient.en, color = Muted, fontSize = 11.sp)
                        }
                        Text("可解锁 " + u.gain + " 款", color = StOk, fontSize = 13.sp)
                        TextButton(onClick = { nav.navigate(Routes.ADD_BOTTLE) }) {
                            Text("去添加", color = Accent, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                }
            }

            /* 只差一种材料 */
            if (s.missingGroups.isNotEmpty()) {
                item { SectionHead("只差一种材料") }
                items(s.missingGroups, key = { "miss-" + it.ingredient.id }) { g ->
                    val isChecked = g.ingredient.id in checked
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { vm.toggle(g.ingredient.id) },
                                colors = CheckboxDefaults.colors(checkedColor = Accent)
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    g.ingredient.zh,
                                    color = if (isChecked) Muted else Fg,
                                    fontSize = 15.sp,
                                    textDecoration = if (isChecked) TextDecoration.LineThrough else null
                                )
                                Text(
                                    "补货后可调 " + g.recipes.size + " 款 · 约 " + g.cups + " 杯",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    g.recipes.take(3).joinToString("、") { it.zh } +
                                        if (g.recipes.size > 3) " 等 " + g.recipes.size + " 款" else "",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            /* 快见底了 */
            if (s.lowStock.isNotEmpty()) {
                item { SectionHead("快见底了", s.lowStock.size.toString() + " 瓶") }
                items(s.lowStock, key = { "low-" + it.id }) { b ->
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.bottle(b.id)) }.padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(b.brand, fontSize = 14.sp, color = Fg)
                            MonoNum(Units.fmt(b.remaining) + " / " + Units.fmt(b.initQty) + " " + b.unit, size = 12, color = Muted)
                        }
                        Spacer(Modifier.height(6.dp))
                        StockBar(if (b.initQty > 0) (b.remaining / b.initQty).toFloat() else 0f, low = true)
                    }
                    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                }
            }

            /* 全部为空（loaded 之后才判断，避免冷启动闪假空态） */
            if (s.loaded && s.unlocks.isEmpty() && s.missingGroups.isEmpty() && s.lowStock.isEmpty()) {
                item { EmptyHint("什么都不缺，调一杯吧") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
