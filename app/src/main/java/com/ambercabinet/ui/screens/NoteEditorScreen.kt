package com.ambercabinet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.model.MixSession
import com.ambercabinet.core.model.TastingNote
import com.ambercabinet.ui.components.AmberTopBar
import com.ambercabinet.ui.components.GlassPour
import com.ambercabinet.ui.components.MessageDialog
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.abs

data class NoteState(
    val loading: Boolean = true,
    val session: MixSession? = null,
    val recipeZh: String = "",
    val recipeEn: String = "",
    val glass: String = "rocks",
    val liquid: String = "",
    val rating: Double = 0.0,
    val sweet: Int = 0,
    val sour: Int = 0,
    val bitter: Int = 0,
    val body: Int = 0,
    val text: String = "",
    val existingId: String? = null,
    val saved: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class NoteViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val records: RecordsRepository,
    private val savedState: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val sessionId: String = savedState.get<String>("sessionId") ?: ""
    private val _state = MutableStateFlow(NoteState())
    val state: StateFlow<NoteState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val session = cabinet.sessions.first().firstOrNull { it.id == sessionId }
            if (session == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val recipe = cabinet.allRecipes.first().firstOrNull { it.id == session.recipeId }
            val existing = records.noteForSession(sessionId)
            _state.update {
                it.copy(
                    loading = false,
                    session = session,
                    /* 优先用配方快照，避免配方修改/删除导致历史失真 */
                    recipeZh = session.recipeZh.ifBlank { recipe?.zh ?: session.recipeId },
                    recipeEn = session.recipeEn.ifBlank { recipe?.en ?: "" },
                    glass = session.glass.ifBlank { recipe?.glass ?: "rocks" },
                    liquid = session.liquid.ifBlank { recipe?.liquid ?: "" },
                    rating = existing?.rating ?: 0.0,
                    sweet = existing?.sweet ?: 0,
                    sour = existing?.sour ?: 0,
                    bitter = existing?.bitter ?: 0,
                    body = existing?.body ?: 0,
                    text = existing?.text ?: "",
                    existingId = existing?.id
                )
            }
        }
    }

    fun setRating(r: Double) = _state.update { it.copy(rating = r, dirty = true) }
    fun setAxis(key: String, v: Int) = _state.update {
        when (key) {
            "sweet" -> it.copy(sweet = v, dirty = true); "sour" -> it.copy(sour = v, dirty = true)
            "bitter" -> it.copy(bitter = v, dirty = true); else -> it.copy(body = v, dirty = true)
        }
    }
    fun setText(t: String) = _state.update { it.copy(text = t, dirty = true) }

    fun save() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val s = _state.value
            val session = s.session ?: return@launch
            try {
                records.saveNote(
                    TastingNote(
                        id = s.existingId ?: java.util.UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        recipeId = session.recipeId,
                        rating = s.rating,
                        sweet = s.sweet.takeIf { it > 0 },
                        sour = s.sour.takeIf { it > 0 },
                        bitter = s.bitter.takeIf { it > 0 },
                        body = s.body.takeIf { it > 0 },
                        text = s.text.trim()
                    )
                )
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "笔记没存上：" + (e.message ?: "再试一次")) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}

/* 风味轴：key + 标签 + 取值，文件顶层一份，不再每次重组重建 listOf */
private data class Axis(val key: String, val label: String, val get: (NoteState) -> Int)
private val AXES = listOf(
    Axis("sweet", "甜", { it.sweet }),
    Axis("sour", "酸", { it.sour }),
    Axis("bitter", "苦", { it.bitter }),
    Axis("body", "酒体", { it.body })
)

@Composable
fun NoteEditorScreen(nav: NavHostController, vm: NoteViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved) { if (s.saved) nav.popBackStack() }

    /* 有未保存内容时拦截返回键，避免误丢 */
    BackHandler(enabled = s.dirty && !s.saved && !s.loading) { confirmDiscard = true }

    Scaffold(
        containerColor = Bg,
        topBar = { AmberTopBar("品鉴笔记", onBack = { if (s.dirty && !s.saved) confirmDiscard = true else nav.popBackStack() }) }
    ) { padding ->
        /* 加载 → 内容淡入淡出衔接；记录不存在时保留顶栏与返回键 */
        Crossfade(s.loading || s.session == null, animationSpec = AmberMotion.med(), label = "noteLoading") { notReady ->
            if (s.loading) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
                return@Crossfade
            }
            if (s.session == null) {
                Column(Modifier.padding(padding).padding(24.dp)) {
                    Text("这条记录找不到了", color = Muted)
                    TextButton(onClick = { nav.popBackStack() }) { Text("返回", color = Accent) }
                }
                return@Crossfade
            }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassPour(s.glass, s.liquid, Modifier.size(52.dp, 62.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.recipeZh, style = AmberType.displaySerif.copy(fontSize = 20.sp, color = Fg))
                    Text(s.recipeEn + " × " + (s.session?.servings ?: 1) + " 杯", color = Muted, fontSize = 12.sp)
                }
            }

            SectionHead("这杯值几颗星")
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..5).forEach { i ->
                    /* 评星：颜色过渡 + 点按弹性缩放（可中断） */
                    var pulse by remember { mutableStateOf(false) }
                    val starScale by animateFloatAsState(if (pulse) 1.3f else 1f, AmberMotion.bounceSpring(), label = "starPulse") { pulse = false }
                    val starColor by animateColorAsState(if (s.rating >= i) Accent else Fg.copy(alpha = 0.16f), AmberMotion.fast(), label = "starColor")
                    Text(
                        "★",
                        color = starColor,
                        fontSize = 34.sp,
                        modifier = Modifier
                            /* 评分是持久化 Double，用容差比较，半星切换才不会因精度失效 */
                            .clickable { pulse = true; vm.setRating(if (abs(s.rating - i) < 0.01) i - 0.5 else i.toDouble()) }
                            .padding(horizontal = 4.dp)
                            .scale(starScale)
                    )
                }
                Text(
                    if (s.rating > 0) " " + s.rating + " 分（再点一下是半星）" else " 点颗星星打个分",
                    color = Muted, fontSize = 12.sp
                )
            }

            SectionHead("风味感受", "1 最弱 · 5 最强")
            AXES.forEach { axis ->
                val cur = axis.get(s)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(axis.label, color = Fg, fontSize = 14.sp, modifier = Modifier.width(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..5).forEach { v ->
                            val sel = cur == v
                            Surface(
                                color = if (sel) Accent else Surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable { vm.setAxis(axis.key, v) }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    Text(if (v == 0) "—" else v.toString(), color = if (sel) AccentInk else Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            SectionHead("写两句")
            OutlinedTextField(
                value = s.text,
                onValueChange = { vm.setText(it) },
                modifier = Modifier.fillMaxWidth().height(130.dp),
                placeholder = { Text("这杯怎么样？下次想怎么调整？") },
                shape = RoundedCornerShape(14.dp)
            )

            Button(
                onClick = { vm.save() },
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (s.saving) "保存中…" else if (s.existingId != null) "保存修改" else "记下这篇笔记") }
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    /* 未保存内容的丢弃确认 */
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("这篇笔记还没保存") },
            text = { Text("现在退出，刚填的内容就没有了。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; nav.popBackStack() }) { Text("不存了，退出", color = StMiss) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑", color = Accent) } },
            containerColor = Raised
        )
    }

    MessageDialog(s.error, vm::clearError)
}
