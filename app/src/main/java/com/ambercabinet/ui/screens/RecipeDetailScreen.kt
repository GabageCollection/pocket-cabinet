package com.ambercabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.MatchResult
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.domain.SubChoice
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailState(
    val recipe: Recipe? = null,
    val match: MatchResult? = null,
    val maxCups: Int = 1,
    val cups: Int = 1,
    val isFav: Boolean = false,
    val overrides: Map<String, String> = emptyMap(),
    /** 已确认替代：原材料 ID → 替代材料 ID */
    val chosenSubs: Map<String, String> = emptyMap(),
    val bottleSheetFor: String? = null,
    val subSheetFor: String? = null,
    val ingredients: Map<String, Ingredient> = emptyMap(),
    val bottlesOf: Map<String, List<Bottle>> = emptyMap(),
    val existingDraft: MixDraft? = null,
    val createdDraftId: String? = null,
    val starting: Boolean = false
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository,
    private val savedState: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val recipeId: String = savedState.get<String>("id") ?: ""
    private val cups = MutableStateFlow(1)
    private val overrides = MutableStateFlow<Map<String, String>>(emptyMap())
    private val chosenSubs = MutableStateFlow<Map<String, String>>(emptyMap())
    private val sheetFor = MutableStateFlow<String?>(null)
    private val subSheetFor = MutableStateFlow<String?>(null)
    private val createdDraftId = MutableStateFlow<String?>(null)
    private val starting = MutableStateFlow(false)

    private data class Prefs(
        val overrides: Map<String, String>,
        val chosenSubs: Map<String, String>,
        val sheetFor: String?,
        val subSheetFor: String?
    )

    private val prefs = combine(overrides, chosenSubs, sheetFor, subSheetFor) { o, c, s, ss -> Prefs(o, c, s, ss) }
    private val base = combine(cabinet.allRecipes, cabinet.bottles, records.favorites, catalog.allIngredients) { r, b, f, i -> Base(r, b, f, i) }

    private data class Base(val recipes: List<Recipe>, val bottles: List<Bottle>, val favs: Set<String>, val ings: Map<String, Ingredient>)

    val state: StateFlow<DetailState> = combine(base, cups, prefs, cabinet.latestDraft, createdDraftId) { b, cupsRaw, p, draft, created ->
        val recipe = b.recipes.firstOrNull { it.id == recipeId } ?: return@combine DetailState()
        val c = cupsRaw.coerceAtLeast(1)
        /* 用一致库存快照计算匹配（§五.10） */
        val engine = MatchEngine(SnapshotStore(b.bottles), catalog.substitutions)
        val m1 = engine.match(recipe, 1, b.ings)
        val m = engine.match(recipe, c, b.ings)
        val bottlesOf = recipe.ingredients.map { it.ingredientId }.distinct()
            .associateWith { id -> b.bottles.filter { it.ingredientId == id } }
        DetailState(
            recipe = recipe,
            match = m,
            maxCups = maxOf(1, m1.maxCups),
            cups = c,
            isFav = b.favs.contains(recipeId),
            overrides = p.overrides,
            chosenSubs = p.chosenSubs,
            bottleSheetFor = p.sheetFor,
            subSheetFor = p.subSheetFor,
            ingredients = b.ings,
            bottlesOf = bottlesOf,
            existingDraft = draft?.takeIf { it.recipeId == recipeId },
            createdDraftId = created,
            starting = starting.value
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailState())

    fun setCups(delta: Int, max: Int) { cups.value = (cups.value + delta).coerceIn(1, maxOf(1, max)) }
    fun toggleFav() {
        viewModelScope.launch { records.toggleFavorite(recipeId, state.value.isFav) }
    }
    fun openSheet(ingId: String) { sheetFor.value = ingId }
    fun closeSheet() { sheetFor.value = null }
    fun openSubSheet(ingId: String) { subSheetFor.value = ingId }
    fun closeSubSheet() { subSheetFor.value = null }
    fun pickBottle(ingId: String, bottleId: String?) {
        overrides.value = if (bottleId == null) overrides.value - ingId else overrides.value + (ingId to bottleId)
        sheetFor.value = null
    }
    /** 确认某个替代方案（from → to）；再次选择同一方案可取消 */
    fun chooseSub(fromId: String, toId: String) {
        val cur = chosenSubs.value[fromId]
        chosenSubs.value = if (cur == toId) chosenSubs.value - fromId else chosenSubs.value + (fromId to toId)
        subSheetFor.value = null
    }

    /** 开始调酒：创建持久化草稿，杯数/选瓶/替代全部随草稿传递（§二.1） */
    fun startMix(resumeDraftId: String?) {
        if (starting.value) return
        if (resumeDraftId != null) { createdDraftId.value = resumeDraftId; return }
        starting.value = true
        viewModelScope.launch {
            val st = state.value
            try {
                st.existingDraft?.let { cabinet.deleteDraft(it.id) }
                val draft = cabinet.createDraft(recipeId, st.cups, st.overrides, st.chosenSubs)
                createdDraftId.value = draft.id
            } finally {
                starting.value = false
            }
        }
    }

    fun consumeNavigation() { createdDraftId.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(nav: NavHostController, recipeId: String, vm: DetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val recipe = s.recipe ?: return
    val m = s.match ?: return
    var confirmResume by remember { mutableStateOf(false) }

    LaunchedEffect(s.createdDraftId) {
        s.createdDraftId?.let {
            vm.consumeNavigation()
            nav.navigate(Routes.mix(it))
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text(recipe.sourceNote, color = Muted, fontSize = 12.sp)
                IconButton(onClick = { vm.toggleFav() }) {
                    if (s.isFav) Icon(Icons.Filled.Star, "已收藏", tint = Accent)
                    else Icon(Icons.Outlined.StarBorder, "收藏", tint = Fg)
                }
            }
        },
        bottomBar = {
            /* 需要替代的每一项都必须有用户明确确认的替代方案 */
            val needSubIds = m.subs.map { it.ri.ingredientId }.distinct()
            val blocked = m.status == RecipeStatus.MISSING || m.status == RecipeStatus.INSUFFICIENT ||
                (m.status == RecipeStatus.SUBSTITUTABLE && !needSubIds.all { s.chosenSubs[it] != null })
            Column(Modifier.padding(14.dp)) {
                Button(
                    onClick = {
                        if (s.existingDraft != null) confirmResume = true else vm.startMix(null)
                    },
                    enabled = !blocked && !s.starting,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                    shape = RoundedCornerShape(14.dp)
                ) { Text((if (s.starting) "正在准备… · " else "开始调酒 · ") + s.cups + " 杯") }
                Text(
                    when {
                        m.status == RecipeStatus.MISSING -> "还缺必需材料，先补齐再来"
                        m.status == RecipeStatus.INSUFFICIENT -> "有的材料快见底了，暂时调不了"
                        m.status == RecipeStatus.SUBSTITUTABLE && blocked -> "缺的材料先各选一个替代"
                        else -> "开始前会再看一眼库存 · 调完自动扣掉用量"
                    },
                    color = Muted, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            /* 主视觉 */
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GlassPour(recipe.glass, parseLiquid(recipe.liquid), Modifier.size(150.dp, 172.dp))
                Text(recipe.zh, fontFamily = FontFamily.Serif, fontSize = 30.sp, color = Fg, modifier = Modifier.padding(top = 16.dp))
                Text(recipe.en, color = Muted, fontSize = 12.sp)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val flavorZh = mapOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈")
                    (recipe.flavors.mapNotNull { flavorZh[it] } + recipe.method + recipe.glassZh + ("约 " + Units.fmt(recipe.abv) + "% vol")).forEach {
                        AssistChip(onClick = {}, label = { Text(it, fontSize = 11.sp) })
                    }
                }
            }

            /* 状态 */
            Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(m.status)
                    Spacer(Modifier.width(12.dp))
                    Text(statusText(m, s.maxCups), color = Muted, fontSize = 13.sp)
                }
                HorizontalDivider(color = Fg.copy(alpha = 0.06f), modifier = Modifier.padding(top = 14.dp))
            }
            if (recipe.allergens.isNotEmpty()) {
                Text("过敏提醒：含" + recipe.allergens.joinToString("、"), color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            /* 杯数 */
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("调几杯", fontSize = 15.sp, color = Fg)
                    Text("材料用量会跟着算好", color = Muted, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.setCups(-1, s.maxCups) }, enabled = s.cups > 1) { Text("−") }
                    MonoNum(s.cups.toString(), Modifier.padding(horizontal = 14.dp), size = 24)
                    OutlinedButton(onClick = { vm.setCups(1, s.maxCups) }, enabled = s.cups < s.maxCups) { Text("+") }
                }
            }

            /* 材料与用量 */
            SectionHead("材料与用量", s.cups.toString() + " 杯")
            recipe.ingredients.forEach { ri ->
                IngredientRow(ri, s, vm)
                HorizontalDivider(color = Fg.copy(alpha = 0.06f))
            }

            /* 步骤预览 */
            SectionHead("步骤预览", recipe.steps.size.toString() + " 步 · 约 " + recipe.timeMin + " 分钟")
            Column {
                recipe.steps.forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        Text((i + 1).toString(), fontFamily = FontFamily.Monospace, color = Muted, fontSize = 12.sp, modifier = Modifier.width(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text(step.title, fontSize = 14.sp, color = Fg)
                            Text(step.detail, fontSize = 13.sp, color = Muted)
                        }
                        if (step.timerSeconds > 0) MonoNum(step.timerSeconds.toString() + " 秒", size = 11, color = Muted)
                    }
                }
            }

            /* 私人配方操作 */
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nav.navigate(Routes.recipeEdit(base = recipe.id)) }) {
                    Text("复制一份自己改", fontSize = 13.sp)
                }
                if (recipe.isUser) {
                    OutlinedButton(onClick = { nav.navigate(Routes.recipeEdit(id = recipe.id)) }) {
                        Text("编辑此配方", fontSize = 13.sp)
                    }
                }
            }

            Text(
                "来源：" + recipe.sourceNote + " · 中文说明为原创 · 理性饮酒，未成年人禁止饮酒",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    /* 已有未完成草稿：继续或重新开始 */
    if (confirmResume) {
        AlertDialog(
            onDismissRequest = { confirmResume = false },
            title = { Text("上次还没调完") },
            text = { Text("这杯上次调到一半。接着调，还是从头来？") },
            confirmButton = {
                TextButton(onClick = { confirmResume = false; vm.startMix(s.existingDraft!!.id) }) { Text("接着调", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmResume = false; vm.startMix(null) }) { Text("从头来") }
            },
            containerColor = Raised
        )
    }

    /* 选瓶弹层（§7.4） */
    s.bottleSheetFor?.let { ingId ->
        val bottles = s.bottlesOf[ingId] ?: emptyList()
        ModalBottomSheet(onDismissRequest = { vm.closeSheet() }, containerColor = Raised) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
                Text("用哪一瓶", style = MaterialTheme.typography.titleLarge)
                Text("不挑的话，优先用上次用过、已开瓶、剩得少的那瓶", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                SheetOption("自动（推荐）", "优先用已开瓶、剩得少的；一瓶不够就接着用下一瓶", s.overrides[ingId] == null) { vm.pickBottle(ingId, null) }
                bottles.forEach { b ->
                    SheetOption(
                        b.brand,
                        (if (b.openedAt != null) "已开瓶" else "未开瓶") + " · 剩 " + Units.fmt(b.remaining) + " " + b.unit,
                        s.overrides[ingId] == b.id
                    ) { vm.pickBottle(ingId, b.id) }
                }
            }
        }
    }

    /* 替代方案弹层：展示全部可用方案、比例、风味变化与适用限制（§五.5/五.6） */
    s.subSheetFor?.let { ingId ->
        val choices = m.subs.filter { it.ri.ingredientId == ingId }
        ModalBottomSheet(onDismissRequest = { vm.closeSubSheet() }, containerColor = Raised) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
                Text("换个材料顶上", style = MaterialTheme.typography.titleLarge)
                Text("你确认之后，才会按替代来算", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                choices.forEach { c ->
                    val r = c.rule
                    SheetOption(
                        c.toDef.zh + " · 比例 1 : " + Units.fmt(r.ratio),
                        r.flavorImpact + " · 适用：" + r.methods +
                            (if (r.extraIngredientId != null) " · 需搭配 " + (s.ingredients[r.extraIngredientId]?.zh ?: r.extraIngredientId) else "") +
                            " · 最多 " + c.maxCups + " 杯",
                        s.chosenSubs[ingId] == r.toId
                    ) { vm.chooseSub(ingId, r.toId) }
                }
                if (choices.isEmpty()) Text("眼下没有合适的材料能替代", color = Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SheetOption(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = if (selected) Accent.copy(alpha = 0.13f) else Surface),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title, fontSize = 14.sp, color = Fg)
                Text(sub, fontSize = 12.sp, color = Muted)
            }
        }
    }
}

private fun statusText(m: MatchResult, maxCups: Int): String = when (m.status) {
    RecipeStatus.OK -> "现在的库存最多能调 " + maxCups + " 杯" + (m.limiting?.let { "，卡在" + it + "上" } ?: "")
    RecipeStatus.SUBSTITUTABLE -> "选好替代后，最多能调 " + maxCups + " 杯"
    RecipeStatus.MISSING -> "还缺 " + m.missing.joinToString("、") { it.def.zh } + " · 补齐了再来"
    RecipeStatus.INSUFFICIENT -> "柜子里有，但不太够：" + m.insufficient.joinToString("；") { it.def.zh + " 需要 " + Units.fmt(it.need) + " " + it.unit }
}

@Composable
private fun IngredientRow(ri: RecipeIngredient, s: DetailState, vm: DetailViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ingredientName(ri.ingredientId, s), fontSize = 15.sp, color = Fg)
                when (ri.role) {
                    IngredientRole.OPTIONAL -> { Spacer(Modifier.width(4.dp)); SmallTag("可选") }
                    IngredientRole.GARNISH -> { Spacer(Modifier.width(4.dp)); SmallTag("装饰") }
                    else -> {}
                }
            }
            if (ri.freeText != null) Text(ri.freeText, fontSize = 14.sp, color = Fg)
            else MonoNum(Units.fmt(ri.qty * s.cups) + " " + ri.unit, size = 17)
        }
        val choices = s.match?.subs?.filter { it.ri.ingredientId == ri.ingredientId } ?: emptyList()
        val chosen = s.chosenSubs[ri.ingredientId]?.let { to -> choices.firstOrNull { it.rule.toId == to } }
        val bottles = s.bottlesOf[ri.ingredientId] ?: emptyList()
        val subText = when {
            chosen != null -> "已换成：" + chosen.toDef.zh + " · 1 : " + Units.fmt(chosen.rule.ratio) + " · " + chosen.rule.flavorImpact
            choices.isNotEmpty() -> choices.first().toDef.zh + " 等 " + choices.size + " 个材料能顶上 · 选一个吧"
            bottles.isNotEmpty() && ri.role == IngredientRole.REQUIRED ->
                "使用：" + bottles.first().brand + " · 剩 " + Units.fmt(bottles.first().remaining) + " " + bottles.first().unit
            ri.role != IngredientRole.REQUIRED -> "没有也能调，就是卖相差一点"
            else -> ri.note ?: ""
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(subText, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (choices.isNotEmpty()) {
                TextButton(onClick = { vm.openSubSheet(ri.ingredientId) }) {
                    Text(if (chosen != null) "换一个替代" else "选个替代", color = Accent, fontSize = 12.sp)
                }
            } else if (bottles.size > 1 && ri.role == IngredientRole.REQUIRED) {
                TextButton(onClick = { vm.openSheet(ri.ingredientId) }) {
                    Text("换一瓶", color = Accent, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun ingredientName(ingId: String, s: DetailState): String =
    s.ingredients[ingId]?.zh ?: ingId
