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
import androidx.compose.ui.draw.clip
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
import com.ambercabinet.core.domain.InventoryService
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.MatchResult
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.domain.UndoResult
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.*
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class RecordsState(
    val sessions: List<MixSession> = emptyList(),
    val notes: Map<String, TastingNote> = emptyMap(),
    val favorites: List<Pair<Recipe, MatchResult>> = emptyList(),
    val txns: List<InventoryTransaction> = emptyList(),
    val recipes: Map<String, Recipe> = emptyMap(),
    val privateRecipes: List<Recipe> = emptyList(),
    val tab: Int = 0,
    val undoing: Boolean = false,
    val msg: String? = null
)

@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository,
    private val inventory: InventoryService
) : ViewModel() {
    private val tab = MutableStateFlow(0)
    private val msg = MutableStateFlow<String?>(null)
    private val undoing = MutableStateFlow(false)

    private data class Base(
        val sessions: List<MixSession>,
        val recipes: List<Recipe>,
        val notes: List<TastingNote>,
        val txns: List<InventoryTransaction>,
        val favTimes: Map<String, Long>,
        val bottles: List<Bottle>,
        val ings: Map<String, Ingredient>
    )

    private val base = combine(
        cabinet.sessions, cabinet.allRecipes, records.notes, cabinet.transactions, records.favoriteTimes
    ) { s, r, n, t, f -> Five(s, r, n, t, f) }

    private data class Five(val s: List<MixSession>, val r: List<Recipe>, val n: List<TastingNote>, val t: List<InventoryTransaction>, val f: Map<String, Long>)

    val state: StateFlow<RecordsState> = combine(base, cabinet.bottles, catalog.allIngredients) { five, bottles, ings ->
        Base(five.s, five.r, five.n, five.t, five.f, bottles, ings)
    }.combine(combine(tab, msg, undoing) { t, m, u -> Triple(t, m, u) }) { input, (t, m, u) ->
        val snapshot = SnapshotStore(input.bottles)
        val engine = MatchEngine(snapshot, catalog.substitutions)
        val favs = input.recipes.filter { input.favTimes.containsKey(it.id) }
            .map { it to engine.match(it, 1, input.ings) }
        RecordsState(
            sessions = input.sessions.sortedByDescending { it.finishedAt ?: it.startedAt },
            notes = input.notes.associateBy { it.sessionId },
            favorites = favs,
            txns = input.txns,
            recipes = input.recipes.associateBy { it.id },
            privateRecipes = input.recipes.filter { it.isUser },
            tab = t, msg = m, undoing = u
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecordsState())

    fun setTab(t: Int) { tab.value = t }

    fun undo(sessionId: String) {
        if (undoing.value) return
        undoing.value = true
        viewModelScope.launch {
            val result = cabinet.undoSessionAtomic(inventory, sessionId)
            msg.value = when (result) {
                is UndoResult.Done -> "已撤销：用掉的材料已经加回库存，这条记录会保留"
                is UndoResult.AlreadyUndone -> "这次已经撤销过了，不能重复撤销"
                is UndoResult.NotCompleted -> "这次调酒还没完成，没什么可撤销的"
                is UndoResult.NotFound -> "找不到对应的扣减记录，可能已经被删掉了"
            }
            undoing.value = false
        }
    }

    fun clearMsg() { msg.value = null }
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val dateFmt = SimpleDateFormat("M月d日 E", Locale.CHINA)

@Composable
fun RecordsScreen(nav: NavHostController, vm: RecordsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("调过 " + s.sessions.size.toString() + " 次 · " + s.txns.size + " 条进出记录", color = Muted, fontSize = 12.sp)
                    Text("记录", style = MaterialTheme.typography.headlineLarge)
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置与备份", tint = Fg)
                }
            }
        },
        bottomBar = { BottomDock(nav, Routes.RECORDS) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = s.tab, containerColor = Bg, contentColor = Accent) {
                listOf("调制历史", "收藏 " + s.favorites.size, "私人配方 " + s.privateRecipes.size, "进出记录").forEachIndexed { i, label ->
                    Tab(selected = s.tab == i, onClick = { vm.setTab(i) }, text = { Text(label, fontSize = 13.sp) })
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                when (s.tab) {
                    0 -> {
                        if (s.sessions.isEmpty()) item { Text("还没调过酒，去发现页挑一杯吧", color = Muted, modifier = Modifier.padding(top = 20.dp)) }
                        items(s.sessions) { session ->
                            HistoryItem(session, s.recipes[session.recipeId], s.notes[session.id]) {
                                nav.navigate(Routes.note(session.id))
                            }
                        }
                    }
                    1 -> {
                        if (s.favorites.isEmpty()) item { Text("还没有收藏，去酒谱里给喜欢的点颗星吧", color = Muted, modifier = Modifier.padding(top = 20.dp)) }
                        items(s.favorites) { (r, m) -> RecipeRow(r, m, null) { nav.navigate(Routes.recipe(r.id)) } }
                    }
                    2 -> {
                        item {
                            OutlinedButton(
                                onClick = { nav.navigate(Routes.recipeEdit()) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            ) { Text("新建私人配方") }
                        }
                        if (s.privateRecipes.isEmpty()) item { Text("还没有自己的配方。点上面新建一个，或在配方详情页「复制一份自己改」，改成你的版本。", color = Muted, fontSize = 13.sp) }
                        items(s.privateRecipes) { r ->
                            Row(
                                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.recipe(r.id)) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.zh, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Fg)
                                    Text(r.en + " · " + r.method + " · " + r.ingredients.size + " 种材料", color = Muted, fontSize = 12.sp)
                                }
                                TextButton(onClick = { nav.navigate(Routes.recipeEdit(id = r.id)) }) { Text("编辑", color = Accent, fontSize = 12.sp) }
                            }
                            HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                        }
                    }
                    else -> {
                        if (s.txns.isEmpty()) item { Text("还没有进出记录，调一杯或添瓶酒就会有了", color = Muted, modifier = Modifier.padding(top = 20.dp)) }
                        items(s.txns) { t ->
                            TxnRow(t, s.sessions.firstOrNull { it.id == t.sessionId }, s.undoing) { vm.undo(t.sessionId!!) }
                        }
                        item { Text("备份和恢复搬到右上角「设置」里了", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp)) }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    s.msg?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.clearMsg() },
            confirmButton = { TextButton(onClick = { vm.clearMsg() }) { Text("知道了", color = Accent) } },
            text = { Text(m) },
            containerColor = Raised
        )
    }
}

@Composable
private fun HistoryItem(session: MixSession, recipe: Recipe?, note: TastingNote?, onClick: () -> Unit) {
    /* 优先配方快照：配方后来修改或删除不影响历史（§四.2） */
    val zh = session.recipeZh.ifBlank { recipe?.zh ?: session.recipeId }
    val en = session.recipeEn.ifBlank { recipe?.en ?: "" }
    val glass = session.glass.ifBlank { recipe?.glass ?: "rocks" }
    val liquid = session.liquid.ifBlank { recipe?.liquid ?: "" }
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp)) {
        GlassPour(glass, parseLiquid(liquid), Modifier.size(48.dp, 56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(zh + " × " + session.servings + " 杯", fontFamily = FontFamily.Serif, fontSize = 16.sp, color = Fg)
            Text(
                en + (if (en.isNotBlank()) " · " else "") +
                    dateFmt.format(Date(session.finishedAt ?: session.startedAt)) + " " + timeFmt.format(Date(session.finishedAt ?: session.startedAt)) +
                    (if (session.undone) " · 已撤销，材料已退回" else ""),
                color = Muted, fontSize = 11.sp
            )
            if (note != null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(5) { i ->
                        Text("★", color = if (i < note.rating.toInt()) Accent else Fg.copy(alpha = 0.14f), fontSize = 14.sp)
                    }
                    Text(" " + note.rating, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                }
                if (note.text.isNotBlank()) Text(note.text, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("点这里打个分，记两句喝后感", color = Accent.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}

@Composable
private fun TxnRow(t: InventoryTransaction, session: MixSession?, undoing: Boolean, onUndo: () -> Unit) {
    val isUndo = t.reason == "撤销回滚"
    val dotColor = when {
        isUndo -> Accent
        t.delta < 0 -> StMiss
        else -> StOk
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(999.dp)).background(dotColor))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.brand + " · " + t.reason, fontSize = 14.sp, color = Fg)
            Text((if (t.detail.isNotBlank()) t.detail + " · " else "") + timeFmt.format(Date(t.time)), color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            MonoNum(
                if (isUndo) "已恢复" else (if (t.delta < 0) "−" else "+") + Units.fmt(Math.abs(t.delta)) + " " + t.unit,
                size = 14,
                color = if (isUndo || t.delta > 0) StOk else StMiss
            )
            if (t.delta < 0 && !t.undone && t.reason == "调制扣减" && session != null && !session.undone) {
                TextButton(onClick = onUndo, enabled = !undoing) { Text("撤销", color = Accent, fontSize = 12.sp) }
            }
        }
    }
    HorizontalDivider(color = Fg.copy(alpha = 0.06f))
}
