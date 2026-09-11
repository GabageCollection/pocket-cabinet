package com.ambercabinet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.data.repo.RecordsRepository
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 编辑器内的材料行 */
data class EditIng(
    val key: String = UUID.randomUUID().toString(),
    val ingredientId: String = "",
    val qty: String = "",
    val unit: String = "ml",
    val role: IngredientRole = IngredientRole.REQUIRED
)

data class EditStep(
    val key: String = UUID.randomUUID().toString(),
    val title: String = "",
    val detail: String = "",
    val timer: String = ""
)

data class RecipeEditState(
    val loading: Boolean = true,
    val baseRecipeId: String? = null,
    val editingId: String? = null,
    val zh: String = "",
    val en: String = "",
    val method: String = "摇和",
    val glass: String = "rocks",
    val glassZh: String = "古典杯",
    val difficulty: Int = 2,
    val timeMin: String = "5",
    val flavors: Set<String> = emptySet(),
    val ings: List<EditIng> = emptyList(),
    val steps: List<EditStep> = listOf(EditStep()),
    val errors: Map<String, String> = emptyMap(),
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val saving: Boolean = false,
    val allIngredients: Map<String, Ingredient> = emptyMap(),
    val pickerOpen: Boolean = false,
    val pickerQuery: String = ""
)

@HiltViewModel
class RecipeEditViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val records: RecordsRepository,
    private val savedState: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val editId: String? = savedState.get<String>("id")?.takeIf { it.isNotBlank() }
    private val baseId: String? = savedState.get<String>("base")?.takeIf { it.isNotBlank() }
    private val _state = MutableStateFlow(RecipeEditState())
    val state: StateFlow<RecipeEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val ings = catalog.allIngredients.first()
            val all = cabinet.allRecipes.first()
            val source = all.firstOrNull { it.id == editId } ?: all.firstOrNull { it.id == baseId }
            _state.update {
                it.copy(
                    loading = false, allIngredients = ings,
                    editingId = editId, baseRecipeId = baseId,
                    zh = source?.let { s -> if (editId != null) s.zh else s.zh + "（我的版本）" } ?: "",
                    en = source?.en ?: "",
                    method = source?.method ?: "摇和",
                    glass = source?.glass ?: "rocks",
                    glassZh = source?.glassZh ?: "古典杯",
                    difficulty = source?.difficulty ?: 2,
                    timeMin = (source?.timeMin ?: 5).toString(),
                    flavors = source?.flavors?.toSet() ?: emptySet(),
                    ings = source?.ingredients?.map { ri ->
                        EditIng(ingredientId = ri.ingredientId, qty = if (ri.qty > 0) Units.fmt(ri.qty) else "", unit = ri.unit, role = ri.role)
                    } ?: listOf(EditIng()),
                    steps = source?.steps?.map { st -> EditStep(title = st.title, detail = st.detail, timer = if (st.timerSeconds > 0) st.timerSeconds.toString() else "") }
                        ?: listOf(EditStep())
                )
            }
        }
    }

    fun update(f: (RecipeEditState) -> RecipeEditState) = _state.update(f)
    fun updateIng(key: String, g: (EditIng) -> EditIng) = _state.update { s -> s.copy(ings = s.ings.map { if (it.key == key) g(it) else it }, errors = s.errors - "ings") }
    fun addIng() = _state.update { it.copy(ings = it.ings + EditIng()) }
    fun removeIng(key: String) = _state.update { it.copy(ings = it.ings.filterNot { i -> i.key == key }) }
    fun updateStep(key: String, g: (EditStep) -> EditStep) = _state.update { s -> s.copy(steps = s.steps.map { if (it.key == key) g(it) else it }, errors = s.errors - "steps") }
    fun addStep() = _state.update { it.copy(steps = it.steps + EditStep()) }
    fun removeStep(key: String) = _state.update { it.copy(steps = it.steps.filterNot { s -> s.key == key }) }
    fun toggleFlavor(f: String) = _state.update { it.copy(flavors = if (it.flavors.contains(f)) it.flavors - f else it.flavors + f) }

    /** 保存前定位具体错误字段，禁止保存无效配方（§14） */
    private fun validate(s: RecipeEditState): Map<String, String> {
        val errs = mutableMapOf<String, String>()
        if (s.zh.isBlank()) errs["zh"] = "给配方起个中文名吧"
        val time = s.timeMin.toIntOrNull()
        if (time == null || time !in 1..60) errs["timeMin"] = "调制时间填 1 到 60 之间的分钟数"
        if (s.ings.isEmpty()) {
            errs["ings"] = "先加一种材料吧"
        } else {
            s.ings.forEachIndexed { i, ing ->
                if (ing.ingredientId.isBlank()) {
                    errs["ings"] = "第 " + (i + 1) + " 行还没选材料"
                    return@forEachIndexed
                }
                val def = s.allIngredients[ing.ingredientId]
                if (def == null) { errs["ings"] = "第 " + (i + 1) + " 行的材料找不到了，重新选一个"; return@forEachIndexed }
                val q = ing.qty.toDoubleOrNull()
                if (q == null || q <= 0) { errs["ings"] = "第 " + (i + 1) + " 行「" + def.zh + "」的用量填一个大于 0 的数"; return@forEachIndexed }
                if (q > 100000) { errs["ings"] = "第 " + (i + 1) + " 行的用量太大了，检查一下"; return@forEachIndexed }
                if (Units.dimensionOf(ing.unit) != def.dimension) {
                    errs["ings"] = "第 " + (i + 1) + " 行「" + def.zh + "」的单位「" + ing.unit + "」对不上，这种材料应该用 " + Units.unitsFor(def.dimension).joinToString("/")
                }
            }
            if (errs["ings"] == null && s.ings.none { it.role == IngredientRole.REQUIRED }) {
                errs["ings"] = "至少留一种「必需」材料"
            }
        }
        if (s.steps.isEmpty() || s.steps.all { it.title.isBlank() }) {
            errs["steps"] = "给至少一步写个标题吧"
        } else {
            s.steps.forEachIndexed { i, st ->
                if (st.timer.isNotBlank() && (st.timer.toIntOrNull() == null || st.timer.toInt() < 0)) {
                    errs["steps"] = "第 " + (i + 1) + " 步的计时填个不小于 0 的秒数，或者不填"
                }
            }
        }
        return errs
    }

    fun save() {
        val s = _state.value
        val errs = validate(s)
        if (errs.isNotEmpty()) { _state.update { it.copy(errors = errs) }; return }
        if (s.saving) return
        _state.update { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val existing = editId?.let { records.customRecipeById(it) }
                val recipe = Recipe(
                    id = editId ?: ("private_" + UUID.randomUUID().toString()),
                    zh = s.zh.trim(),
                    en = s.en.trim().ifBlank { s.zh.trim() },
                    source = "private",
                    sourceNote = "私人配方",
                    flavors = s.flavors.toList(),
                    difficulty = s.difficulty,
                    method = s.method,
                    glass = s.glass,
                    glassZh = s.glassZh,
                    liquid = existing?.liquid ?: "oklch(0.62 0.13 60)",
                    abv = 0.0,
                    timeMin = s.timeMin.toInt(),
                    ingredients = s.ings.map { ing ->
                        RecipeIngredient(ing.ingredientId, ing.qty.toDouble(), ing.unit, ing.role)
                    },
                    steps = s.steps.map { st ->
                        RecipeStep(st.title.trim(), st.detail.trim(), timerSeconds = st.timer.toIntOrNull() ?: 0)
                    },
                    isUser = true,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
                records.saveCustomRecipe(recipe, baseRecipeId = existing?.let { null } ?: baseId)
                _state.update { it.copy(saving = false, saved = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, errors = mapOf("save" to "保存失败：" + (e.message ?: "原因不明，再试一次"))) }
            }
        }
    }

    fun delete() {
        val id = editId ?: return
        viewModelScope.launch {
            records.deleteCustomRecipe(id)
            _state.update { it.copy(deleted = true) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditorScreen(nav: NavHostController, editId: String?, baseId: String?, vm: RecipeEditViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(s.saved, s.deleted) { if (s.saved || s.deleted) nav.popBackStack() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text(
                    when {
                        editId != null -> "编辑私人配方"
                        baseId != null -> "复制为私人配方"
                        else -> "新建私人配方"
                    },
                    fontSize = 18.sp, color = Fg
                )
            }
        }
    ) { padding ->
        if (s.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            return@Scaffold
        }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            /* 基本信息 */
            FieldWithError("中文名", s.zh, s.errors["zh"]) { v -> vm.update { it.copy(zh = v, errors = it.errors - "zh") } }
            FieldWithError("英文名", s.en, null) { v -> vm.update { it.copy(en = v) } }

            Text("调制方法", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("摇和", "搅拌", "直调", "捣压", "分层").forEach { m ->
                    FilterChip(selected = s.method == m, onClick = { vm.update { it.copy(method = m) } }, label = { Text(m, fontSize = 12.sp) })
                }
            }

            Text("杯型", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("rocks" to "古典杯", "highball" to "高球杯", "coupe" to "碟形杯", "martini" to "马天尼杯").forEach { (k, label) ->
                    FilterChip(selected = s.glass == k, onClick = { vm.update { it.copy(glass = k, glassZh = label) } }, label = { Text(label, fontSize = 12.sp) })
                }
            }

            Text("风味（可多选）", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("sweet" to "甜", "sour" to "酸", "bitter" to "苦", "fresh" to "清爽", "strong" to "浓烈").forEach { (k, label) ->
                    FilterChip(selected = s.flavors.contains(k), onClick = { vm.toggleFlavor(k) }, label = { Text(label, fontSize = 12.sp) })
                }
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = s.difficulty.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { d -> vm.update { it.copy(difficulty = d.coerceIn(1, 5)) } } },
                    modifier = Modifier.weight(1f),
                    label = { Text("难度 1–5") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = s.timeMin,
                    onValueChange = { v -> vm.update { it.copy(timeMin = v, errors = it.errors - "timeMin") } },
                    modifier = Modifier.weight(1f),
                    label = { Text("时间（分钟）") }, singleLine = true,
                    isError = s.errors.containsKey("timeMin"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            s.errors["timeMin"]?.let { Text(it, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }

            /* 材料 */
            SectionHead("材料与用量", s.ings.size.toString() + " 种")
            s.errors["ings"]?.let { Text(it, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            s.ings.forEach { ing ->
                IngredientEditRow(ing, s.allIngredients, { g -> vm.updateIng(ing.key, g) }, { vm.removeIng(ing.key) })
            }
            OutlinedButton(onClick = { vm.addIng() }, modifier = Modifier.fillMaxWidth()) { Text("加一种材料") }

            /* 步骤 */
            SectionHead("步骤", s.steps.size.toString() + " 步")
            s.errors["steps"]?.let { Text(it, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
            s.steps.forEachIndexed { i, st ->
                Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(value = st.title, onValueChange = { v -> vm.updateStep(st.key) { it.copy(title = v) } }, modifier = Modifier.fillMaxWidth(), label = { Text("第 " + (i + 1) + " 步叫什么") }, singleLine = true, shape = RoundedCornerShape(10.dp))
                        OutlinedTextField(value = st.detail, onValueChange = { v -> vm.updateStep(st.key) { it.copy(detail = v) } }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("这一步怎么做") }, shape = RoundedCornerShape(10.dp))
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = st.timer, onValueChange = { v -> vm.updateStep(st.key) { it.copy(timer = v) } },
                                modifier = Modifier.weight(1f), label = { Text("计时秒数（可不填）") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(10.dp)
                            )
                            if (s.steps.size > 1) {
                                TextButton(onClick = { vm.removeStep(st.key) }) { Text("删除", color = StMiss, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { vm.addStep() }, modifier = Modifier.fillMaxWidth()) { Text("再加一步") }

            s.errors["save"]?.let { Text(it, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            Button(
                onClick = { vm.save() },
                enabled = !s.saving,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                shape = RoundedCornerShape(14.dp)
            ) { Text(if (s.saving) "保存中…" else "保存私人配方") }

            if (editId != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("删除这个私人配方", color = StMiss, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除私人配方？") },
            text = { Text("「" + s.zh + "」会被删掉，之前做过的调制记录和品鉴笔记都会保留。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("删除", color = StMiss) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            containerColor = Raised
        )
    }
}

@Composable
private fun FieldWithError(label: String, value: String, error: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label) }, singleLine = true,
        isError = error != null,
        shape = RoundedCornerShape(12.dp)
    )
    if (error != null) Text(error, color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun IngredientEditRow(
    ing: EditIng,
    allIngredients: Map<String, Ingredient>,
    onUpdate: ((EditIng) -> EditIng) -> Unit,
    onRemove: () -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val def = allIngredients[ing.ingredientId]

    Card(colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    def?.let { it.zh + " · " + it.en } ?: "选一种材料",
                    color = if (def != null) Fg else Accent, fontSize = 14.sp,
                    modifier = Modifier.weight(1f).clickable { pickerOpen = true }
                )
                TextButton(onClick = onRemove) { Text("删除", color = StMiss, fontSize = 12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ing.qty,
                    onValueChange = { v -> onUpdate { it.copy(qty = v) } },
                    modifier = Modifier.width(110.dp),
                    label = { Text("每杯用多少") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.width(8.dp))
                val unitOptions = def?.let { Units.unitsFor(it.dimension) } ?: Units.ALL_UNITS
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    unitOptions.take(4).forEach { u ->
                        FilterChip(selected = ing.unit == u, onClick = { onUpdate { it.copy(unit = u) } }, label = { Text(u, fontSize = 11.sp) })
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(IngredientRole.REQUIRED to "必需", IngredientRole.OPTIONAL to "可选", IngredientRole.GARNISH to "装饰").forEach { (r, label) ->
                    FilterChip(selected = ing.role == r, onClick = { onUpdate { it.copy(role = r) } }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选一种材料") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("搜中文或英文名") }, singleLine = true)
                    val q = query.lowercase()
                    val list = allIngredients.values
                        .filter { !it.staple && (q.isBlank() || (it.zh + it.en).lowercase().contains(q)) }
                        .sortedBy { !it.isCustom }
                        .take(50)
                    LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                        items(list) { cand ->
                            Text(
                                cand.zh + " · " + cand.en + (if (cand.isCustom) "（自己加的）" else ""),
                                color = Fg, fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onUpdate { it.copy(ingredientId = cand.id, unit = cand.unit) }
                                    pickerOpen = false
                                }.padding(vertical = 10.dp)
                            )
                            HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("关闭") } },
            containerColor = Raised
        )
    }
}
