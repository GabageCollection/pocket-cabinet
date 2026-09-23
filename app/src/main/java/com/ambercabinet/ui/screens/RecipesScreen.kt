package com.ambercabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.MatchResult
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.model.IngredientRole
import com.ambercabinet.core.model.Recipe
import com.ambercabinet.core.model.RecipeStatus
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.Bg
import com.ambercabinet.ui.theme.Fg
import com.ambercabinet.ui.theme.Muted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class RecipesState(
    val items: List<Pair<Recipe, MatchResult>> = emptyList(),
    val query: String = "",
    val statusFilter: String = "all",
    val sourceFilter: String = "all",
    val sortByRating: Boolean = false,
    val avgByRecipe: Map<String, Double> = emptyMap(),
    val ingredientId: String? = null,
    val ingredientName: String? = null,
    val totalSystem: Int = 0,
    val totalPrivate: Int = 0,
    val loaded: Boolean = false
)

private val STATUS_RANK = mapOf(RecipeStatus.OK to 0, RecipeStatus.SUBSTITUTABLE to 1, RecipeStatus.MISSING to 2, RecipeStatus.INSUFFICIENT to 3)
private val STATUS_CHIPS = listOf("all" to "全部", "ok" to "现在就能调", "sub" to "换个材料也能调", "miss" to "只差一种", "insuff" to "材料不够")
private val SOURCE_CHIPS = listOf("all" to "全部", "iba" to "IBA", "private" to "私人配方", "made" to "我调过的", "fav" to "已收藏")

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val statusFilter = MutableStateFlow("all")
    private val sourceFilter = MutableStateFlow("all")
    private val sortByRating = MutableStateFlow(false)
    /* 酒柜页「能调 N 款」带参跳入时的材料预筛选；清掉后同时移除 SavedStateHandle，保证不再生效 */
    private val ingredientFilter = MutableStateFlow(savedState.get<String>("ing")?.takeIf { it.isNotBlank() })

    /** 贵的部分：只依赖库存/目录/调制记录。一次全量匹配（§五.10 一致快照）+ 预建小写搜索索引 */
    private data class SnapshotData(
        val matched: List<Pair<Recipe, MatchResult>>,
        val searchText: Map<String, String>,
        val madeIds: Set<String>,
        val ingNames: Map<String, String>,
        val totalSystem: Int,
        val totalPrivate: Int
    )

    private val snapshot: Flow<SnapshotData> =
        combine(cabinet.allRecipes, cabinet.bottles, catalog.allIngredients, cabinet.sessions) { recipes, bottles, ings, sessions ->
            val engine = MatchEngine(SnapshotStore(bottles), catalog.substitutions)
            val matched = recipes.map { it to engine.match(it, 1, ings) }
            val searchText = recipes.associate { r ->
                r.id to (r.zh + " " + r.en + " " + r.method + " " + r.ingredients.mapNotNull { ri ->
                    ings[ri.ingredientId]?.let { it.zh + " " + it.en + " " + it.aliases.joinToString(" ") }
                }.joinToString(" ")).lowercase()
            }
            SnapshotData(
                matched, searchText,
                sessions.filter { !it.undone }.map { it.recipeId }.toSet(),
                ings.mapValues { it.value.zh },
                catalog.systemRecipes.size, recipes.count { it.isUser }
            )
        }.flowOn(Dispatchers.Default)

    private data class RecipeFilters(val query: String, val status: String, val source: String, val sortByRating: Boolean, val ingredientId: String?)
    private val filters = combine(query, statusFilter, sourceFilter, sortByRating, ingredientFilter) { q, sf, src, sr, ing ->
        RecipeFilters(q, sf, src, sr, ing)
    }

    /* 便宜的部分：搜索词 / 筛选 / 排序变化只对已算好的结果做纯 List 操作，不再重跑匹配引擎 */
    val state: StateFlow<RecipesState> = combine(snapshot, records.favorites, records.notes, filters) { snap, favs, notes, f ->
        val q = f.query.lowercase()
        /* 品鉴均分：有笔记的配方按平均星级排序、行内显示星级 */
        val avgByRecipe = notes.groupBy { it.recipeId }
            .mapValues { e -> e.value.map { it.rating }.average() }
        var items = snap.matched
        if (q.isNotBlank()) items = items.filter { snap.searchText[it.first.id]?.contains(q) == true }
        /* 材料预筛选：只保留必需材料含该材料的配方（可选/装饰不算） */
        f.ingredientId?.let { id ->
            items = items.filter { (r, _) -> r.ingredients.any { it.ingredientId == id && it.role == IngredientRole.REQUIRED } }
        }
        items = when (f.status) {
            "ok" -> items.filter { it.second.status == RecipeStatus.OK }
            "sub" -> items.filter { it.second.status == RecipeStatus.SUBSTITUTABLE }
            "miss" -> items.filter { it.second.status == RecipeStatus.MISSING && it.second.missing.size == 1 }
            "insuff" -> items.filter { it.second.status == RecipeStatus.INSUFFICIENT }
            else -> items
        }
        items = when (f.source) {
            "fav" -> items.filter { favs.contains(it.first.id) }
            "made" -> items.filter { snap.madeIds.contains(it.first.id) }
            "all" -> items
            else -> items.filter { it.first.source == f.source }
        }
        items = if (f.sortByRating) items.sortedWith(
            /* 评分优先：有评分的在前（无评分视为最低），状态与杯数保持为次级次序 */
            compareByDescending<Pair<Recipe, MatchResult>> { avgByRecipe[it.first.id] ?: -1.0 }
                .thenBy { STATUS_RANK[it.second.status] }
                .thenByDescending { it.second.maxCups }
        ) else items.sortedWith(compareBy({ STATUS_RANK[it.second.status] }, { -it.second.maxCups }))
        RecipesState(
            items, f.query, f.status, f.source, f.sortByRating, avgByRecipe,
            f.ingredientId, f.ingredientId?.let { snap.ingNames[it] },
            snap.totalSystem, snap.totalPrivate, loaded = true
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecipesState())

    fun setQuery(q: String) { query.value = q }
    fun setStatus(s: String) { statusFilter.value = s }
    fun setSource(s: String) { sourceFilter.value = s }
    fun setSortByRating(v: Boolean) { sortByRating.value = v }
    fun clearIngredientFilter() {
        ingredientFilter.value = null
        savedState.remove<String>("ing")
    }
}

@Composable
fun RecipesScreen(nav: NavHostController, vm: RecipesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Bg,
        bottomBar = { BottomDock(nav) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("内置 " + s.totalSystem.toString() + " 款 · 私人 " + s.totalPrivate + " 款", color = Muted, fontSize = 12.sp)
                        Text("酒谱", style = MaterialTheme.typography.headlineLarge)
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Fg)
                    }
                }
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
                if (s.ingredientId != null) {
                    Spacer(Modifier.height(10.dp))
                    FilterChip(
                        selected = true,
                        onClick = { vm.clearIngredientFilter() },
                        label = { Text("含 " + (s.ingredientName ?: "") + " 的配方") },
                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "清除材料筛选") }
                    )
                }
                Text("能不能调", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(STATUS_CHIPS) { (k, label) ->
                        FilterChip(selected = s.statusFilter == k, onClick = { vm.setStatus(k) }, label = { Text(label) })
                    }
                }
                Text("配方来源", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SOURCE_CHIPS) { (k, label) ->
                        FilterChip(selected = s.sourceFilter == k, onClick = { vm.setSource(k) }, label = { Text(label) })
                    }
                }
                Text("排序", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !s.sortByRating, onClick = { vm.setSortByRating(false) }, label = { Text("默认") })
                    FilterChip(selected = s.sortByRating, onClick = { vm.setSortByRating(true) }, label = { Text("评分优先") })
                }
                Spacer(Modifier.height(10.dp))
            }
            items(s.items, key = { it.first.id }) { (r, m) ->
                RecipeRow(r, m, subLineOf(r, m), Modifier.animateItem(), rating = s.avgByRecipe[r.id]) { nav.navigate(Routes.recipe(r.id)) }
            }
            if (s.loaded && s.items.isEmpty()) {
                item { EmptyHint("这个筛选下没有配方，换个条件试试") }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun subLineOf(r: Recipe, m: MatchResult): String {
    val sub = m.subs.firstOrNull()
    if (m.status == RecipeStatus.SUBSTITUTABLE && sub != null) return sub.line()
    return r.flavors.map { Flavor.zh(it) }.joinToString("、") + " · " + r.method + " · 约 " + r.timeMin + " 分钟"
}
