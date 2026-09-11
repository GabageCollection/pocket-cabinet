package com.ambercabinet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class DiscoverState(
    val ingredientCount: Int = 0,
    val bottleCount: Int = 0,
    val okCount: Int = 0,
    val subCount: Int = 0,
    val recommendations: List<Recommendation> = emptyList(),
    val tonightIndex: Int = 0,
    val ready: List<Pair<Recipe, MatchResult>> = emptyList(),
    val substitutable: List<Pair<Recipe, MatchResult>> = emptyList(),
    val almost: List<Triple<Recipe, MatchResult, Int>> = emptyList(),
    val lowStock: List<Bottle> = emptyList(),
    val unlocks: List<UnlockRow> = emptyList(),
    val flavorFilter: String = "all",
    val pendingDraft: MixDraft? = null,
    val draftRecipeName: String = "",
    val recentMixes: List<String> = emptyList()
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository
) : ViewModel() {
    private val flavor = MutableStateFlow("all")
    private val tonightIndex = MutableStateFlow(0)

    private data class Base(
        val bottles: List<Bottle>,
        val recipes: List<Recipe>,
        val favs: Set<String>,
        val sessions: List<MixSession>,
        val ings: Map<String, Ingredient>,
        val draft: MixDraft?
    )

    private val base = combine(
        cabinet.bottles, cabinet.allRecipes, records.favorites, cabinet.sessions, catalog.allIngredients
    ) { b, r, f, s, i -> Base(b, r, f, s, i, null) }

    val state: StateFlow<DiscoverState> = combine(base, cabinet.latestDraft, flavor, tonightIndex) { b, draft, f, ti ->
        buildState(b.copy(draft = draft), f, ti)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiscoverState())

    private suspend fun buildState(input: Base, flavor: String, ti: Int): DiscoverState {
        /* 一次计算读入一致库存快照（§五.10），所有匹配/推荐基于同一快照 */
        val snapshot = SnapshotStore(input.bottles)
        val engine = MatchEngine(snapshot, catalog.substitutions)
        val recommender = RecommendationEngine(snapshot, engine)
        val ings = input.ings

        val matched = input.recipes.map { it to engine.match(it, 1, ings) }
        val ready = matched.filter { it.second.status == RecipeStatus.OK }
        val subs = matched.filter { it.second.status == RecipeStatus.SUBSTITUTABLE }
        val almost = matched.filter { it.second.status == RecipeStatus.MISSING && it.second.missing.size == 1 }
            .map { Triple(it.first, it.second, simulateCups(snapshot, it.first, it.second.missing[0].def, ings)) }
        /* 最近调制：仅已完成且未撤销的记录，按时间倒序取前三 */
        val recent = input.sessions
            .filter { it.status == "done" && !it.undone }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
            .take(3)
        val recs = recommender.recommend(input.recipes, ings, input.favs, recent.map { it.recipeId }.toSet())
        val readyFiltered = if (flavor == "all") ready else ready.filter { it.first.flavors.contains(flavor) }
        val draftRecipe = input.draft?.let { d -> input.recipes.firstOrNull { it.id == d.recipeId } }
        return DiscoverState(
            ingredientCount = input.bottles.map { it.ingredientId }.distinct().size,
            bottleCount = input.bottles.size,
            okCount = ready.size,
            subCount = subs.size,
            recommendations = recs,
            tonightIndex = ti,
            ready = readyFiltered,
            substitutable = subs,
            almost = almost,
            lowStock = input.bottles.filter { it.initQty > 0 && it.remaining / it.initQty * 100 <= it.lowPct },
            unlocks = if (input.bottles.isEmpty()) emptyList() else recommender.unlockRanking(input.recipes, ings).take(3),
            flavorFilter = flavor,
            pendingDraft = if (draftRecipe != null) input.draft else null,
            draftRecipeName = draftRecipe?.zh ?: "",
            recentMixes = recent.map { it.recipeZh.ifBlank { input.recipes.firstOrNull { r -> r.id == it.recipeId }?.zh ?: it.recipeId } }
        )
    }

    /** 补齐后可调杯数：在快照副本上加入模拟（§五.1 不重复计库存） */
    private suspend fun simulateCups(snapshot: SnapshotStore, recipe: Recipe, def: Ingredient, ings: Map<String, Ingredient>): Int {
        val simBottles = snapshot.allBottles() + Bottle(
            id = "__sim", ingredientId = def.id, brand = "", initQty = 9999.0,
            remaining = 9999.0, unit = if (def.dimension == UnitDimension.COUNT) def.unit else "ml"
        )
        return MatchEngine(SnapshotStore(simBottles), catalog.substitutions).match(recipe, 1, ings).maxCups
    }

    fun setFlavor(f: String) { flavor.value = f }
    fun surprise() { tonightIndex.value = tonightIndex.value + 1 }
}

private val FLAVOR_ZH = mapOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈")

@Composable
fun DiscoverScreen(nav: NavHostController, vm: DiscoverViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    /* 调酒进度保存成功后的真实提示（只有实际保存成功才显示） */
    val savedEntry = nav.currentBackStackEntry?.savedStateHandle
    val progressSaved by savedEntry?.getStateFlow("mix_progress_saved", false)?.collectAsState()
        ?: remember { mutableStateOf(false) }
    LaunchedEffect(progressSaved) {
        if (progressSaved) {
            savedEntry?.set("mix_progress_saved", false)
            snackbarHostState.showSnackbar("进度已保存，随时可以回来接着调")
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("宜小酌", color = Muted, fontSize = 12.sp)
                    Text("晚上好，调一杯？", style = MaterialTheme.typography.headlineLarge)
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Fg)
                }
            }
        },
        bottomBar = { BottomDock(nav, Routes.DISCOVER) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            /* 继续上次调酒（草稿不扣库存，完成后才扣） */
            s.pendingDraft?.let { draft ->
                item {
                    Card(
                        onClick = { nav.navigate(Routes.mix(draft.id)) },
                        colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = 0.14f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("继续上次调酒", color = Accent, fontSize = 15.sp)
                                Text(
                                    s.draftRecipeName + " × " + draft.servings + " 杯 · 进行到第 " + (draft.currentStep + 1) + " 步",
                                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Text("继续 →", color = Accent, fontSize = 13.sp)
                        }
                    }
                }
            }

            /* 库存总览：不重复计算库存的指标（在柜材料 / 可直接调制 / 替代后可调） */
            item {
                Row(Modifier.fillMaxWidth()) {
                    StatCell(s.ingredientCount.toString(), "柜里的材料", Modifier.weight(1f))
                    StatCell(s.okCount.toString(), "现在就能调", Modifier.weight(1f))
                    StatCell(s.subCount.toString(), "换个材料也能调", Modifier.weight(1f))
                }
            }

            /* 新用户引导 */
            if (s.bottleCount == 0) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Raised), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("从添加第一瓶开始", color = Fg, fontSize = 16.sp)
                            Text("录入你手边的酒和材料，口袋酒柜就能告诉你现在能调什么。", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { nav.navigate(Routes.ADD_BOTTLE) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                                    shape = RoundedCornerShape(12.dp)
                                ) { Text("添加第一瓶") }
                                OutlinedButton(onClick = { nav.navigate(Routes.RECIPES) }, shape = RoundedCornerShape(12.dp)) {
                                    Text("先逛逛酒谱")
                                }
                            }
                        }
                    }
                }
            }

            /* 今晚推荐 */
            if (s.recommendations.isNotEmpty()) {
                val pick = s.recommendations[s.tonightIndex % s.recommendations.size]
                item {
                    SectionHead("今晚推荐", action = "换一杯看看") { vm.surprise() }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Raised),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassPour(pick.recipe.glass, parseLiquid(pick.recipe.liquid), Modifier.size(112.dp, 132.dp))
                            Spacer(Modifier.width(18.dp))
                            Column {
                                Text(pick.recipe.en + " · " + pick.recipe.source.uppercase(), color = Muted, fontSize = 11.sp)
                                Text(pick.recipe.zh, fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Fg)
                                TextButton(onClick = { nav.navigate(Routes.recipe(pick.recipe.id)) }) {
                                    Text("查看配方 →", color = Accent, fontSize = 13.sp)
                                }
                            }
                        }
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                            pick.reasons.forEach { r ->
                                Text(r, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                                HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("按你的库存和口味挑的", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            /* 风味筛选 */
            item {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈").forEach { (k, label) ->
                        FilterChip(
                            selected = s.flavorFilter == k,
                            onClick = { vm.setFlavor(k) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            /* 现在就能调 */
            item { SectionHead("现在就能调", s.ready.size.toString() + " 款") }
            items(s.ready) { (r, m) ->
                RecipeRow(r, m, null) { nav.navigate(Routes.recipe(r.id)) }
            }
            if (s.ready.isEmpty() && s.bottleCount > 0) {
                item { Text("现在的材料还凑不齐任何一款，看看下面这些只差一种的", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }

            /* 替代后可调 */
            if (s.substitutable.isNotEmpty()) {
                item { SectionHead("换个材料也能调", "点进去看怎么换") }
                items(s.substitutable) { (r, m) ->
                    val sub = m.subs.firstOrNull()
                    val line = if (sub != null) sub.toDef.zh + " 可以代替 · 1 : " + Units2.fmt(sub.rule.ratio) + "，" + sub.rule.flavorImpact else ""
                    RecipeRow(r, m, line) { nav.navigate(Routes.recipe(r.id)) }
                }
            }

            /* 只差一种材料 */
            if (s.almost.isNotEmpty()) {
                item { SectionHead("只差一种材料") }
                items(s.almost) { (r, m, cups) ->
                    val miss = m.missing.first().def
                    RecipeRow(r, m, "缺 " + miss.zh + " · 有了它能调 " + cups + " 杯") { nav.navigate(Routes.recipe(r.id)) }
                }
            }

            /* 低库存与补货建议（折叠进一张卡，不抢主视觉） */
            if (s.lowStock.isNotEmpty() || s.unlocks.isNotEmpty()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    SectionHead("补货建议", (s.lowStock.size + s.unlocks.size).toString() + " 条", action = if (expanded) "收起" else "展开") { expanded = !expanded }
                    if (expanded) {
                        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (s.lowStock.isNotEmpty()) {
                                    Text("快喝完的", color = Muted, fontSize = 12.sp)
                                    s.lowStock.forEach { b ->
                                        Column {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(b.brand, fontSize = 14.sp, color = Fg)
                                                MonoNum(com.ambercabinet.core.units.Units.fmt(b.remaining) + " / " + com.ambercabinet.core.units.Units.fmt(b.initQty) + " " + b.unit, size = 12, color = Muted)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            StockBar(if (b.initQty > 0) (b.remaining / b.initQty).toFloat() else 0f, low = true)
                                        }
                                    }
                                }
                                if (s.unlocks.isNotEmpty()) {
                                    Text("买哪瓶最值？它们能解锁的配方最多", color = Muted, fontSize = 12.sp)
                                    s.unlocks.forEach { u ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(u.ingredient.zh, color = Fg, fontSize = 14.sp)
                                                Text(u.ingredient.en, color = Muted, fontSize = 11.sp)
                                            }
                                            Text("+" + u.gain + " 款", color = StOk, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            /* 最近调制 */
            if (s.recentMixes.isNotEmpty()) {
                item {
                    Text("最近调过：" + s.recentMixes.joinToString(" · "), color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private typealias Units2 = com.ambercabinet.core.units.Units

@Composable
fun StatCell(num: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MonoNum(num, size = 26)
        Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SectionHead(title: String, more: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (action != null && onAction != null) {
            Text(action, color = Muted, fontSize = 13.sp, modifier = Modifier.clickable { onAction() })
        } else if (more != null) {
            Text(more, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun RecipeRow(recipe: Recipe, m: MatchResult, subLine: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassPour(recipe.glass, parseLiquid(recipe.liquid), Modifier.size(50.dp, 60.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(recipe.zh, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Fg)
            Text(recipe.en, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            StatusBadge(m.status, if (m.status == RecipeStatus.MISSING && m.missing.size == 1) "缺" + m.missing[0].def.zh else null)
            if (subLine != null) Text(subLine, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            MonoNum(m.maxCups.toString(), size = 18)
            Text("杯", color = Muted, fontSize = 10.sp)
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}
