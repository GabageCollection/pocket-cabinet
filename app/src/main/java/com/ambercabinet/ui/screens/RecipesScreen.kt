package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.MatchResult
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.units.Units
import com.ambercabinet.core.model.Recipe
import com.ambercabinet.core.model.RecipeStatus
import com.ambercabinet.ui.components.BottomDock
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.Bg
import com.ambercabinet.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class RecipesState(
    val items: List<Pair<Recipe, MatchResult>> = emptyList(),
    val query: String = "",
    val statusFilter: String = "all",
    val sourceFilter: String = "all",
    val totalSystem: Int = 0,
    val totalPrivate: Int = 0
)

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val statusFilter = MutableStateFlow("all")
    private val sourceFilter = MutableStateFlow("all")

    private data class RecipeFilters(val query: String, val status: String, val source: String)

    private val filters = combine(query, statusFilter, sourceFilter) { q, sf, src -> RecipeFilters(q, sf, src) }

    val state: StateFlow<RecipesState> = combine(
        cabinet.allRecipes, cabinet.bottles, records.favorites, catalog.allIngredients, filters
    ) { recipes, bottles, favs, ings, f ->
        val q = f.query.lowercase()
        val sf = f.status
        val src = f.source

        /* 一致库存快照计算，库存变化即自动刷新（§五.10/五.11） */
        val engine = MatchEngine(SnapshotStore(bottles), catalog.substitutions)
        var items = recipes.map { it to engine.match(it, 1, ings) }
        if (q.isNotBlank()) {
            items = items.filter { (r, _) ->
                val text = (r.zh + " " + r.en + " " + r.method + " " + r.ingredients.mapNotNull { ri ->
                    ings[ri.ingredientId]?.let { it.zh + " " + it.en + " " + it.aliases.joinToString(" ") }
                }.joinToString(" ")).lowercase()
                text.contains(q)
            }
        }
        items = when (sf) {
            "ok" -> items.filter { it.second.status == RecipeStatus.OK }
            "sub" -> items.filter { it.second.status == RecipeStatus.SUBSTITUTABLE }
            "miss" -> items.filter { it.second.status == RecipeStatus.MISSING && it.second.missing.size == 1 }
            "insuff" -> items.filter { it.second.status == RecipeStatus.INSUFFICIENT }
            else -> items
        }
        items = when (src) {
            "fav" -> items.filter { favs.contains(it.first.id) }
            "all" -> items
            else -> items.filter { it.first.source == src }
        }
        val rank = mapOf(RecipeStatus.OK to 0, RecipeStatus.SUBSTITUTABLE to 1, RecipeStatus.MISSING to 2, RecipeStatus.INSUFFICIENT to 3)
        items = items.sortedWith(compareBy({ rank[it.second.status] }, { -it.second.maxCups }))
        RecipesState(items, f.query, sf, src, catalog.systemRecipes.size, recipes.count { it.isUser })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecipesState())

    fun setQuery(q: String) { query.value = q }
    fun setStatus(s: String) { statusFilter.value = s }
    fun setSource(s: String) { sourceFilter.value = s }
}

@Composable
fun RecipesScreen(nav: NavHostController, vm: RecipesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

    Scaffold(
        containerColor = Bg,
        bottomBar = { BottomDock(nav, Routes.RECIPES) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Text("内置 " + s.totalSystem.toString() + " 款 · 私人 " + s.totalPrivate + " 款", color = Muted, fontSize = 12.sp)
                Text("酒谱", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = s.query,
                    onValueChange = { vm.setQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜配方、材料，中英文都行") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                )
                Text("能不能调", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("all" to "全部", "ok" to "现在就能调", "sub" to "换个材料也能调", "miss" to "只差一种", "insuff" to "材料不够")) { (k, label) ->
                        FilterChip(selected = s.statusFilter == k, onClick = { vm.setStatus(k) }, label = { Text(label) })
                    }
                }
                Text("配方来源", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("all" to "全部", "iba" to "IBA", "private" to "私人配方", "fav" to "已收藏")) { (k, label) ->
                        FilterChip(selected = s.sourceFilter == k, onClick = { vm.setSource(k) }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            items(s.items) { (r, m) ->
                RecipeRow(r, m, subLineOf(r, m)) { nav.navigate(Routes.recipe(r.id)) }
            }
            if (s.items.isEmpty()) {
                item {
                    Text("这个筛选下没有配方，换个条件试试", color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun subLineOf(r: Recipe, m: MatchResult): String {
    val sub = m.subs.firstOrNull()
    if (m.status == RecipeStatus.SUBSTITUTABLE && sub != null) {
        val others = m.subs.map { it.ri.ingredientId }.distinct().size
        return sub.toDef.zh + " 可以代替 · 1 : " + Units.fmt(sub.rule.ratio) + (if (others > 1) " · 共 " + m.subs.size + " 种换法" else "")
    }
    val flavorZh = mapOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈")
    return r.flavors.mapNotNull { flavorZh[it] }.joinToString("、") + " · " + r.method + " · 约 " + r.timeMin + " 分钟"
}
