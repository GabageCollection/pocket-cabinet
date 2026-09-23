package com.ambercabinet.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
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
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.data.prefs.UserPrefs
import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.LocalAmberAnimScope
import com.ambercabinet.ui.nav.LocalAmberSharedScope
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/* 「换一杯」计数器的回绕上界：只在自增处取模，索引就永远非负（能挑出哪一杯由界面按列表长度再取模） */
private const val TONIGHT_CYCLE = 1024

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
    /** 补货建议的失效版本；变化即表示需要重算（见 DiscoverViewModel.unlockVersion） */
    val unlockVersion: Int = 0,
    val flavorFilter: String = "all",
    /** 品鉴笔记推演出的口味倾向（甜/酸/苦中均值最高且 ≥3.5 的维度），用于首次预选风味 chip */
    val flavorAffinity: String? = null,
    val pendingDraft: MixDraft? = null,
    val draftRecipeName: String = "",
    val recentMixes: List<String> = emptyList(),
    val loaded: Boolean = false,
    /* 顶栏全局搜索：query 非空时下面两个列表才有内容 */
    val searchQuery: String = "",
    val searchRecipes: List<Recipe> = emptyList(),
    /** 命中的库存瓶：瓶 + 材料中文名（剩余量展示用） */
    val searchBottles: List<Pair<Bottle, String>> = emptyList()
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository,
    private val userPrefs: UserPrefs
) : ViewModel() {
    private val flavor = MutableStateFlow("all")
    private val tonightIndex = MutableStateFlow(0)
    private val unlocks = MutableStateFlow<List<UnlockRow>?>(null)   // null = 尚未计算
    private val searchQuery = MutableStateFlow("")

    /** 补货建议的失效版本：库存/目录每变一次 +1（见 computeAll），展开中的面板据此重算而不是被永久清空。
        跨线程读写（computeAll 在 Default 线程），加 @Volatile */
    @Volatile
    private var unlockVersion = 0

    /** 贵的部分（§五.10 一致快照）：只依赖库存/目录/收藏/记录，在 Default 线程算 */
    private data class Computed(
        val bottles: List<Bottle>,
        val matched: List<Pair<Recipe, MatchResult>>,
        val ready: List<Pair<Recipe, MatchResult>>,
        val substitutable: List<Pair<Recipe, MatchResult>>,
        val almost: List<Triple<Recipe, MatchResult, Int>>,
        val recommendations: List<Recommendation>,
        val lowStock: List<Bottle>,
        val recentMixes: List<String>,
        /** 品鉴笔记推演的口味倾向维度（sweet/sour/bitter 之一），笔记不足或无倾向时为 null */
        val flavorAffinity: String?,
        /** 全局搜索索引（预建小写串）：配方 id → 中英文名+方法+材料名+别名；瓶 id → label+品牌+材料中英文名/别名 */
        val recipeSearchText: Map<String, String>,
        val bottleSearchText: Map<String, String>,
        /** 瓶 id → 材料中文名，搜索结果行展示用 */
        val bottleIngName: Map<String, String>
    )

    private val computed: Flow<Computed> = combine(
        cabinet.bottles, cabinet.allRecipes, records.favorites, cabinet.sessions, catalog.allIngredients, records.notes
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        computeAll(
            arr[0] as List<Bottle>, arr[1] as List<Recipe>, arr[2] as Set<String>,
            arr[3] as List<MixSession>, arr[4] as Map<String, Ingredient>, arr[5] as List<TastingNote>
        )
    }.flowOn(Dispatchers.Default)

    /* 供「补货建议」惰性计算复用的最新快照输入（跨线程读写，加 @Volatile） */
    @Volatile
    private var lastInput: Pair<List<Recipe>, Map<String, Ingredient>>? = null

    private suspend fun computeAll(
        bottles: List<Bottle>, recipes: List<Recipe>, favs: Set<String>,
        sessions: List<MixSession>, ings: Map<String, Ingredient>, notes: List<TastingNote>
    ): Computed {
        val snapshot = SnapshotStore(bottles)
        val engine = MatchEngine(snapshot, catalog.substitutions)
        val recommender = RecommendationEngine(snapshot, engine)

        val matched = recipes.map { it to engine.match(it, 1, ings) }
        val ready = matched.filter { it.second.status == RecipeStatus.OK }
        val subs = matched.filter { it.second.status == RecipeStatus.SUBSTITUTABLE }
        val almost = matched.filter { it.second.status == RecipeStatus.MISSING && it.second.missing.size == 1 }
            .map { Triple(it.first, it.second, recommender.cupsIfRestocked(it.first, it.second.missing[0].def, ings)) }
        /* 最近调制：未撤销的记录按时间倒序取前三（本表只保存已完成调制） */
        val recent = sessions
            .filter { !it.undone }
            .sortedByDescending { it.finishedAt ?: it.startedAt }
            .take(3)
        /* 口味倾向：全部笔记甜/酸/苦各自的非空均值，取最高且 ≥3.5 的维度（笔记 <3 条不判定） */
        val flavorAffinity = if (notes.size >= 3) {
            listOf(
                "sweet" to notes.mapNotNull { it.sweet }.takeIf { it.isNotEmpty() }?.average(),
                "sour" to notes.mapNotNull { it.sour }.takeIf { it.isNotEmpty() }?.average(),
                "bitter" to notes.mapNotNull { it.bitter }.takeIf { it.isNotEmpty() }?.average()
            ).filter { it.second != null && it.second!! >= 3.5 }
                .maxByOrNull { it.second!! }?.first
        } else null
        /* 推荐复用同一份 matched，不再重复匹配 */
        val recs = recommender.recommend(
            matched, favs, recent.map { it.recipeId }.toSet(),
            notes.groupBy { it.recipeId }
        )
        lastInput = recipes to ings
        /* 全局搜索索引：照 RecipesViewModel 的 searchText 构建方式，在这里一次预算好 */
        val recipeSearchText = recipes.associate { r ->
            r.id to (r.zh + " " + r.en + " " + r.method + " " + r.ingredients.mapNotNull { ri ->
                ings[ri.ingredientId]?.let { it.zh + " " + it.en + " " + it.aliases.joinToString(" ") }
            }.joinToString(" ")).lowercase()
        }
        val bottleSearchText = bottles.associate { b ->
            val ing = ings[b.ingredientId]
            b.id to (b.label + " " + b.brand + " " +
                (ing?.let { it.zh + " " + it.en + " " + it.aliases.joinToString(" ") } ?: "")).lowercase()
        }
        /* 库存/目录变了 → 补货建议作废；只置空 unlocks 是不会通知 combine 的（同值不重发），
           所以另加一个版本号，界面据它重算，而不是让展开中的面板永久空白 */
        unlocks.value = null
        unlockVersion = unlockVersion + 1
        return Computed(
            bottles = bottles,
            matched = matched,
            ready = ready,
            substitutable = subs,
            almost = almost,
            recommendations = recs,
            lowStock = bottles.filter { it.isLowStock() },
            recentMixes = recent.map { it.recipeZh.ifBlank { recipes.firstOrNull { r -> r.id == it.recipeId }?.zh ?: it.recipeId } },
            flavorAffinity = flavorAffinity,
            recipeSearchText = recipeSearchText,
            bottleSearchText = bottleSearchText,
            bottleIngName = bottles.associate { it.id to (ings[it.ingredientId]?.zh ?: "") }
        )
    }

    /* 便宜的部分：风味筛选 / 今晚推荐轮换 / 草稿卡 / 补货建议 / 全局搜索，只对已算好的结果做纯 List 操作。
       unlocks 必须作为 combine 输入，否则惰性计算完成后界面不会刷新 */
    val state: StateFlow<DiscoverState> = combine(computed, cabinet.latestDraft, flavor, tonightIndex, unlocks, searchQuery) { arr ->
        @Suppress("UNCHECKED_CAST")
        val c = arr[0] as Computed
        val draft = arr[1] as MixDraft?
        val f = arr[2] as String
        val ti = arr[3] as Int
        val ul = arr[4] as List<UnlockRow>?
        val rawQuery = arr[5] as String
        /* 读一次版本号：它必须在同一次 combine 里取，unlocks 置空没有通知，重算只能靠版本号变化驱动 */
        val uv = unlockVersion
        val matchesFlavor: (Recipe) -> Boolean = { f == "all" || it.flavors.contains(f) }
        val readyF = c.ready.filter { matchesFlavor(it.first) }
        val subsF = c.substitutable.filter { matchesFlavor(it.first) }
        val almostF = c.almost.filter { matchesFlavor(it.first) }
        val draftRecipe = draft?.let { d -> c.matched.firstOrNull { it.first.id == d.recipeId }?.first }
        /* 全局搜索：对预算好的索引做 contains，各取前 8；查询为空时结果为空，结果卡随之消失 */
        val q = rawQuery.trim().lowercase()
        val searchRecipes = if (q.isEmpty()) emptyList() else
            c.matched.filter { c.recipeSearchText[it.first.id]?.contains(q) == true }.take(8).map { it.first }
        val searchBottles = if (q.isEmpty()) emptyList() else
            c.bottles.filter { c.bottleSearchText[it.id]?.contains(q) == true }.take(8)
                .map { it to (c.bottleIngName[it.id] ?: "") }
        DiscoverState(
            ingredientCount = c.bottles.map { it.ingredientId }.distinct().size,
            bottleCount = c.bottles.size,
            okCount = readyF.size,
            subCount = subsF.size,
            recommendations = c.recommendations,
            tonightIndex = ti,
            ready = readyF,
            substitutable = subsF,
            almost = almostF,
            lowStock = c.lowStock,
            unlocks = ul ?: emptyList(),
            flavorFilter = f,
            pendingDraft = if (draftRecipe != null) draft else null,
            draftRecipeName = draftRecipe?.zh ?: "",
            recentMixes = c.recentMixes,
            unlockVersion = uv,
            flavorAffinity = c.flavorAffinity,
            loaded = true,
            searchQuery = rawQuery,
            searchRecipes = searchRecipes,
            searchBottles = searchBottles
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DiscoverState())

    init {
        /* 首次加载完成且用户从没动过风味筛选：笔记够多时有口味倾向就预选对应 chip。
           只执行一次；flavor 已被用户改过（非 all）时绝不覆盖 */
        viewModelScope.launch {
            val first = state.first { it.loaded }
            if (!userPrefs.flavorFilterTouched && first.flavorAffinity != null && flavor.value == "all") {
                flavor.value = first.flavorAffinity
            }
        }
    }

    /** 「补货建议」惰性计算：展开时才算，且只重测受影响配方（见 RecommendationEngine.unlockRanking）。
        unlocks 已被清空时重算不会提前 return，所以库存变化后面板能自己刷新出来 */
    fun loadUnlocks() {
        val (recipes, ings) = lastInput ?: return
        if (unlocks.value != null) return
        viewModelScope.launch(Dispatchers.Default) {
            val version = unlockVersion
            val snapshot = cabinet.snapshot()
            val engine = MatchEngine(snapshot, catalog.substitutions)
            val rows = RecommendationEngine(snapshot, engine).unlockRanking(recipes, ings).take(3)
            /* 算的过程中库存又变了（版本已前进）：这份结果是过期快照，丢掉，让新一轮的写 */
            if (version == unlockVersion) unlocks.value = rows
        }
    }

    fun setFlavor(f: String) {
        userPrefs.flavorFilterTouched = true
        flavor.value = f
    }

    fun setQuery(q: String) { searchQuery.value = q }

    /* 取模只在自增处做：索引永远落在 [0, TONIGHT_CYCLE)，不会溢出成负数后越界 */
    fun surprise() { tonightIndex.value = (tonightIndex.value + 1) % TONIGHT_CYCLE }
}

@Composable
fun DiscoverScreen(nav: NavHostController, vm: DiscoverViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    /* 调酒进度保存成功后的真实提示（只有实际保存成功才显示） */
    val savedEntry = nav.currentBackStackEntry?.savedStateHandle
    val progressSaved by produceState(false, savedEntry) {
        savedEntry?.getStateFlow(Routes.MIX_PROGRESS_SAVED, false)?.collect { value = it }
    }
    LaunchedEffect(progressSaved) {
        if (progressSaved) {
            savedEntry?.set(Routes.MIX_PROGRESS_SAVED, false)
            snackbarHostState.showSnackbar("进度已保存，随时可以回来接着调")
        }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            /* 全局搜索：点放大镜展开搜索框，再点 ✕ 清空并收起 */
            var searchOpen by rememberSaveable { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedVisibility(visible = !searchOpen, enter = fadeIn(AmberMotion.med()), exit = fadeOut(AmberMotion.med())) {
                    Column {
                        Text("宜小酌", color = Muted, fontSize = 12.sp)
                        Text("晚上好，调一杯？", style = MaterialTheme.typography.headlineLarge)
                    }
                }
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = fadeIn(AmberMotion.med()),
                    exit = fadeOut(AmberMotion.med()),
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = s.searchQuery,
                        onValueChange = { vm.setQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜配方、库存，中英文都行") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { vm.setQuery(""); searchOpen = false }) {
                                Icon(Icons.Outlined.Close, contentDescription = "关闭搜索", tint = Muted)
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Outlined.Search, contentDescription = "搜索", tint = Fg)
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = Fg)
                }
            }
        },
        bottomBar = { BottomDock(nav) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
            /* 全局搜索结果：查询非空时插在列表最顶，两段（配方 / 我的库存）；都空给一句空态 */
            if (s.searchQuery.isNotBlank()) {
                item(key = "searchResults") {
                    Card(colors = CardDefaults.cardColors(containerColor = Raised), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            if (s.searchRecipes.isNotEmpty()) {
                                Text("配方", color = Muted, fontSize = 12.sp)
                                s.searchRecipes.forEach { r ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.recipe(r.id)) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(r.zh, fontSize = 14.sp, color = Fg)
                                            Text(r.method, color = Muted, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            if (s.searchBottles.isNotEmpty()) {
                                Text(
                                    "我的库存",
                                    color = Muted, fontSize = 12.sp,
                                    modifier = if (s.searchRecipes.isNotEmpty()) Modifier.padding(top = 12.dp) else Modifier
                                )
                                s.searchBottles.forEach { (b, ingZh) ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.bottle(b.id)) }.padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(b.label + if (b.brand.isNotBlank()) " · " + b.brand else "", fontSize = 14.sp, color = Fg)
                                            Text(
                                                ingZh + " · 剩余 " + Units.fmt(b.remaining) + " " + b.unit,
                                                color = Muted, fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                            if (s.searchRecipes.isEmpty() && s.searchBottles.isEmpty()) {
                                Text("没有找到相关的配方或库存", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            /* 继续上次调酒（草稿不扣库存，完成后才扣）。出现/消失用展开动画；
               lastDraft 保证退出动画播放期间内容仍在 */
            item(key = "pendingDraft") {
                var lastDraft by remember { mutableStateOf<MixDraft?>(null) }
                var lastName by remember { mutableStateOf("") }
                LaunchedEffect(s.pendingDraft) {
                    if (s.pendingDraft != null) { lastDraft = s.pendingDraft; lastName = s.draftRecipeName }
                }
                AnimatedVisibility(
                    visible = s.pendingDraft != null,
                    enter = expandVertically(AmberMotion.med()) + fadeIn(AmberMotion.med()),
                    exit = shrinkVertically(AmberMotion.med()) + fadeOut(AmberMotion.med())
                ) {
                    lastDraft?.let { draft ->
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
                                        lastName + " × " + draft.servings + " 杯 · 进行到第 " + (draft.currentStep + 1) + " 步",
                                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                Text("继续 →", color = Accent, fontSize = 13.sp)
                            }
                        }
                    } ?: Spacer(Modifier.height(0.dp))
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

            /* 新用户引导（loaded 之后才判断真空，避免冷启动闪假空态） */
            if (s.loaded && s.bottleCount == 0) {
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
                                ) { Text("一键添加常见酒") }
                                OutlinedButton(onClick = { nav.navigate(Routes.recipes()) }, shape = RoundedCornerShape(12.dp)) {
                                    Text("先逛逛酒谱")
                                }
                            }
                        }
                    }
                }
            } else if (s.loaded && s.bottleCount in 1..2) {
                /* 刚起步的轻量提示：与空柜引导卡互斥（条件不重叠），不抢主视觉 */
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("还差几瓶就能调更多", color = Fg, fontSize = 14.sp)
                                Text("常见基酒和辅料补齐后，能调的款式会多不少。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            TextButton(onClick = { nav.navigate(Routes.ADD_BOTTLE) }) {
                                Text("常见酒一键补齐", color = Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            /* 今晚推荐：换一杯时整卡内容连续滚动切换（可中断，连点不回弹） */
            if (s.recommendations.isNotEmpty()) {
                /* 计数只在自增处回绕，这里再做一次非负取模，双保险不会越界 */
                val pick = s.recommendations[((s.tonightIndex % s.recommendations.size) + s.recommendations.size) % s.recommendations.size]
                item(key = "tonightPick") {
                    SectionHead("今晚推荐", action = "换一杯看看") { vm.surprise() }
                    AnimatedContent(
                        targetState = pick,
                        transitionSpec = {
                            (slideInVertically(AmberMotion.med()) { it / 4 } + fadeIn(AmberMotion.med()))
                                .togetherWith(slideOutVertically(AmberMotion.med()) { -it / 4 } + fadeOut(AmberMotion.med()))
                        },
                        label = "tonightPick"
                    ) { p ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Raised),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassPour(p.recipe.glass, p.recipe.liquid, Modifier.size(112.dp, 132.dp))
                                Spacer(Modifier.width(18.dp))
                                Column {
                                    Text(p.recipe.en + " · " + p.recipe.source.uppercase(), color = Muted, fontSize = 11.sp)
                                    Text(p.recipe.zh, fontFamily = FontFamily.Serif, fontSize = 25.sp, color = Fg)
                                    TextButton(onClick = { nav.navigate(Routes.recipe(p.recipe.id)) }) {
                                        Text("查看配方 →", color = Accent, fontSize = 13.sp)
                                    }
                                }
                            }
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                                p.reasons.forEach { r ->
                                    Text(r, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                                    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Text("按你的库存和口味挑的", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }

            /* 风味筛选（统一作用于统计与下方全部区块） */
            item {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Flavor.CHIPS.forEach { (k, label) ->
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
            items(s.ready, key = { it.first.id }) { (r, m) ->
                RecipeRow(r, m, null, Modifier.animateItem()) { nav.navigate(Routes.recipe(r.id)) }
            }
            if (s.loaded && s.ready.isEmpty() && s.bottleCount > 0) {
                item { Text("现在的材料还凑不齐任何一款，看看下面这些只差一种的", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }

            /* 替代后可调 */
            if (s.substitutable.isNotEmpty()) {
                item { SectionHead("换个材料也能调", "点进去看怎么换") }
                items(s.substitutable, key = { it.first.id }) { (r, m) ->
                    RecipeRow(r, m, m.subs.firstOrNull()?.line(), Modifier.animateItem()) { nav.navigate(Routes.recipe(r.id)) }
                }
            }

            /* 只差一种材料 */
            if (s.almost.isNotEmpty()) {
                item { SectionHead("只差一种材料") }
                items(s.almost, key = { it.first.id }) { (r, m, cups) ->
                    val miss = m.missing.first().def
                    RecipeRow(r, m, "缺 " + miss.zh + " · 有了它能调 " + cups + " 杯", Modifier.animateItem()) { nav.navigate(Routes.recipe(r.id)) }
                }
            }

            /* 低库存与补货建议（折叠进一张卡，不抢主视觉；展开状态用 rememberSaveable，滚动不丢；展开时才计算解锁排名） */
            if (s.loaded && s.bottleCount > 0) {
                item(key = "restock") {
                    /* 进程恢复后 expanded=true 时 lastInput 可能尚未就绪：loaded 后重试一次；
                       unlockVersion 变化（库存/目录变了）也要重算，否则展开中的面板会一直是空的 */
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(expanded, s.loaded, s.unlockVersion) { if (expanded && s.loaded) vm.loadUnlocks() }
                    val total = s.lowStock.size + s.unlocks.size
                    SectionHead("补货建议", total.toString() + " 条", action = if (expanded) "收起" else "展开") { expanded = !expanded }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(AmberMotion.med()) + fadeIn(AmberMotion.med()),
                        exit = shrinkVertically(AmberMotion.med()) + fadeOut(AmberMotion.med())
                    ) {
                        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (s.lowStock.isNotEmpty()) {
                                    Text("快喝完的", color = Muted, fontSize = 12.sp)
                                    s.lowStock.forEach { b ->
                                        Column {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(b.brand, fontSize = 14.sp, color = Fg)
                                                MonoNum(Units.fmt(b.remaining) + " / " + Units.fmt(b.initQty) + " " + b.unit, size = 12, color = Muted)
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
                                if (s.lowStock.isEmpty() && s.unlocks.isEmpty()) {
                                    Text("库存都很健康，也没有明显值得补的", color = Muted, fontSize = 13.sp)
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RecipeRow(recipe: Recipe, m: MatchResult, subLine: String?, modifier: Modifier = Modifier, rating: Double? = null, onClick: () -> Unit) {
    /* 共享元素：列表行的杯型飞向配方详情页主视觉（作用域不存在时自动降级为普通渲染） */
    val sharedScope = LocalAmberSharedScope.current
    val animScope = LocalAmberAnimScope.current
    Row(
        modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val glassModifier = if (sharedScope != null && animScope != null) {
            with(sharedScope) {
                Modifier.sharedElement(
                    rememberSharedContentState(key = "glass-" + recipe.id),
                    animScope
                )
            }
        } else Modifier
        GlassPour(recipe.glass, recipe.liquid, glassModifier.size(50.dp, 60.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(recipe.zh, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Fg)
            Text(recipe.en, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            StatusBadge(m.status, if (m.status == RecipeStatus.MISSING && m.missing.size == 1) "缺" + m.missing[0].def.zh else null)
            if (subLine != null) Text(subLine, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            if (rating != null) Text("★" + String.format("%.1f", rating), color = Accent, fontSize = 12.sp)
            MonoNum(m.maxCups.toString(), size = 18)
            Text("杯", color = Muted, fontSize = 10.sp)
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}
