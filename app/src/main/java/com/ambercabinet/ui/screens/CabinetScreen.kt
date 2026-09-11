package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.RecommendationEngine
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.model.Bottle
import com.ambercabinet.core.model.Ingredient
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class BottleUi(val bottle: Bottle, val usageRecipes: Int, val usageCups: Int, val catZh: String, val catEn: String)

data class CabinetState(
    val groups: List<Pair<String, List<BottleUi>>> = emptyList(),
    val total: Int = 0,
    val opened: Int = 0,
    val filter: String = "all"
)

@HiltViewModel
class CabinetViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository
) : ViewModel() {
    private val filter = MutableStateFlow("all")

    private data class Base(val bottles: List<Bottle>, val recipes: List<com.ambercabinet.core.model.Recipe>, val ings: Map<String, Ingredient>)

    val state: StateFlow<CabinetState> = combine(cabinet.bottles, cabinet.allRecipes, catalog.allIngredients, filter) { bottles, recipes, ings, f ->
        val catMeta = linkedMapOf(
            "base" to ("基酒" to "BASE SPIRITS"),
            "liqueur" to ("利口酒 · 味美思" to "LIQUEUR & VERMOUTH"),
            "mix" to ("糖浆 · 汽水" to "SYRUP & SODA"),
            "bitters" to ("苦精" to "BITTERS"),
            "fresh" to ("水果 · 香草" to "FRESH")
        )
        /* 一致快照计算（§五.10/五.11），库存变化即自动刷新 */
        val snapshot = SnapshotStore(bottles)
        val engine = MatchEngine(snapshot, catalog.substitutions)
        val recommender = RecommendationEngine(snapshot, engine)
        val uis = bottles.map { b ->
            val (n, cups) = recommender.bottleUsage(b, recipes, ings)
            val def = ings[b.ingredientId]
            val cat = catMeta[def?.category] ?: ("其他" to "OTHER")
            BottleUi(b, n, cups, cat.first, cat.second)
        }
        val filtered = if (f == "all") uis else uis.filter { ings[it.bottle.ingredientId]?.category == f }
        val groups = filtered.groupBy { ings[it.bottle.ingredientId]?.category ?: "mix" }
            .map { (cat, list) -> cat to list }
        CabinetState(groups, bottles.size, bottles.count { it.openedAt != null }, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CabinetState())

    fun setFilter(f: String) { filter.value = f }
}

private val CAT_LABELS = listOf("all" to "全部", "base" to "基酒", "liqueur" to "利口酒", "mix" to "糖浆汽水", "bitters" to "苦精", "fresh" to "水果香草")

@Composable
fun CabinetScreen(nav: NavHostController, vm: CabinetViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

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
        bottomBar = { BottomDock(nav, Routes.CABINET) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("共 " + s.total.toString() + " 样 · 开了 " + s.opened + " 瓶", color = Muted, fontSize = 12.sp)
                Text("酒柜", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(CAT_LABELS) { (k, label) ->
                        FilterChip(selected = s.filter == k, onClick = { vm.setFilter(k) }, label = { Text(label) })
                    }
                }
            }
            if (s.groups.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("酒柜还是空的，先把第一瓶放进来", color = Muted)
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { nav.navigate(Routes.ADD_BOTTLE) },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("添加第一瓶") }
                        TextButton(onClick = { nav.navigate(Routes.RECIPES) }) { Text("先看看酒谱", color = Muted) }
                    }
                }
            }
            s.groups.forEach { (_, list) ->
                item {
                    Text(
                        list.first().catZh + "  " + list.first().catEn,
                        color = Muted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                    )
                }
                items(list) { ui ->
                    BottleRow(ui) { nav.navigate(Routes.bottle(ui.bottle.id)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun BottleRow(ui: BottleUi, onClick: () -> Unit) {
    val b = ui.bottle
    val pct = if (b.initQty > 0) (b.remaining / b.initQty).toFloat() else 0f
    val low = pct * 100 <= b.lowPct
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)) {
        BottlePour(b.shape, pct, parseLiquid(b.liquid), Modifier.size(44.dp, 72.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(b.brand + " " + b.label, fontSize = 15.sp, color = Fg, fontFamily = FontFamily.Serif, modifier = Modifier.weight(1f))
                if (low) Text("快见底了", color = StMiss, fontSize = 11.sp)
            }
            /* 当前剩余量最突出，初始容量/开瓶天数降层级 */
            Row(verticalAlignment = Alignment.Bottom) {
                MonoNum(Units.fmt(b.remaining), size = 22, color = if (low) StMiss else Fg)
                Text(" " + b.unit + " 剩余", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
            Text(
                "容量 " + Units.fmt(b.initQty) + " " + b.unit +
                    " · " + (if (b.openedAt != null) "开了 " + ((System.currentTimeMillis() - b.openedAt!!) / 86400000) + " 天" else "还没开瓶") +
                    (if (b.abv > 0) " · " + Units.fmt(b.abv) + "% vol" else " · 无酒精") +
                    (if (ui.usageRecipes > 0) " · 能调 " + ui.usageRecipes + " 款" else ""),
                color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StockBar(pct, low, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                MonoNum((pct * 100).toInt().toString() + "%", size = 11, color = if (low) StMiss else Muted)
            }
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}
