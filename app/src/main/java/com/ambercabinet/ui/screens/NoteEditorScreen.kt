package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.model.MixSession
import com.ambercabinet.core.model.TastingNote
import com.ambercabinet.ui.components.GlassPour
import com.ambercabinet.ui.components.parseLiquid
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val saving: Boolean = false
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

    fun setRating(r: Double) = _state.update { it.copy(rating = r) }
    fun setAxis(key: String, v: Int) = _state.update {
        when (key) {
            "sweet" -> it.copy(sweet = v); "sour" -> it.copy(sour = v)
            "bitter" -> it.copy(bitter = v); else -> it.copy(body = v)
        }
    }
    fun setText(t: String) = _state.update { it.copy(text = t) }

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
                _state.update { it.copy(saving = false) }
            }
        }
    }
}

@Composable
fun NoteEditorScreen(nav: NavHostController, sessionId: String, vm: NoteViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

    LaunchedEffect(s.saved) { if (s.saved) nav.popBackStack() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text("品鉴笔记", fontSize = 18.sp, color = Fg)
            }
        }
    ) { padding ->
        if (s.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            return@Scaffold
        }
        if (s.session == null) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text("这条记录找不到了", color = Muted)
                TextButton(onClick = { nav.popBackStack() }) { Text("返回", color = Accent) }
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassPour(s.glass, parseLiquid(s.liquid), Modifier.size(52.dp, 62.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.recipeZh, fontFamily = FontFamily.Serif, fontSize = 20.sp, color = Fg)
                    Text(s.recipeEn + " × " + (s.session?.servings ?: 1) + " 杯", color = Muted, fontSize = 12.sp)
                }
            }

            SectionHead("这杯值几颗星")
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..5).forEach { i ->
                    Text(
                        "★",
                        color = if (s.rating >= i) Accent else Fg.copy(alpha = 0.16f),
                        fontSize = 34.sp,
                        modifier = Modifier
                            .clickable { vm.setRating(if (s.rating == i.toDouble()) i - 0.5 else i.toDouble()) }
                            .padding(horizontal = 4.dp)
                    )
                }
                Text(
                    if (s.rating > 0) " " + s.rating + " 分（再点一下是半星）" else " 点颗星星打个分",
                    color = Muted, fontSize = 12.sp
                )
            }

            SectionHead("风味感受", "1 最弱 · 5 最强")
            listOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "body" to "酒体").forEach { (key, label) ->
                val cur = when (key) { "sweet" -> s.sweet; "sour" -> s.sour; "bitter" -> s.bitter; else -> s.body }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Fg, fontSize = 14.sp, modifier = Modifier.width(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..5).forEach { v ->
                            val sel = cur == v
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clickable { vm.setAxis(key, v) }
                                    .then(Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    color = if (sel) Accent else Surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Text(if (v == 0) "—" else v.toString(), color = if (sel) AccentInk else Muted, fontSize = 12.sp)
                                    }
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
