package com.ambercabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AddBottleState(
    val mode: String = "entry",          // entry / search / manual / confirm
    val query: String = "",
    val searchResults: List<Pair<Ingredient, Brand?>> = emptyList(),
    val selected: Pair<Ingredient, Brand?>? = null,
    val capacity: String = "750",
    val remaining: String = "750",
    val openedToday: Boolean = true,
    val duplicates: List<Bottle> = emptyList(),
    val dupMode: String = "new",         // new / merge
    val mergeTargetId: String? = null,
    /* 手动新增自定义材料表单 */
    val mZh: String = "",
    val mEn: String = "",
    val mCat: String = "mix",
    val mDim: UnitDimension = UnitDimension.VOLUME,
    val mUnit: String = "ml",
    val saved: Boolean = false,
    val unlockedGain: Int = 0,
    val submitting: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AddBottleViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AddBottleState())
    val state: StateFlow<AddBottleState> = _state.asStateFlow()

    fun setMode(m: String) { _state.update { it.copy(mode = m, error = null) } }

    fun setQuery(q: String) {
        val ings = _state.value.let { catalogCache }
        val results = mutableListOf<Pair<Ingredient, Brand?>>()
        ings.values.filter { !it.staple }.forEach { ing ->
            val brands = catalog.brands.filter { it.ingredientId == ing.id }
            if (brands.isNotEmpty()) {
                brands.forEach { b ->
                    val text = (ing.zh + ing.en + ing.aliases.joinToString(" ") + b.brand + b.label).lowercase()
                    if (q.isBlank() || text.contains(q.lowercase())) results.add(ing to b)
                }
            } else {
                val text = (ing.zh + ing.en + ing.aliases.joinToString(" ")).lowercase()
                if (q.isBlank() || text.contains(q.lowercase())) results.add(ing to null)
            }
        }
        _state.update { it.copy(query = q, searchResults = results.take(30)) }
    }

    private var catalogCache: Map<String, Ingredient> = emptyMap()

    init {
        viewModelScope.launch {
            catalog.allIngredients.collect { catalogCache = it }
        }
    }

    fun select(ing: Ingredient, brand: Brand?) {
        viewModelScope.launch {
            val dups = cabinet.bottlesFor(ing.id)
            val cap = (brand?.capacityMl ?: if (ing.dimension == UnitDimension.COUNT) 6 else 750)
            _state.update {
                it.copy(
                    selected = ing to brand,
                    capacity = cap.toString(),
                    remaining = cap.toString(),
                    mode = "confirm", duplicates = dups, dupMode = "new", mergeTargetId = null
                )
            }
        }
    }

    /* 手动新增自定义材料（§四.1） */
    fun setMZh(v: String) = _state.update { it.copy(mZh = v) }
    fun setMEn(v: String) = _state.update { it.copy(mEn = v) }
    fun setMCat(v: String) = _state.update { it.copy(mCat = v) }
    fun setMDim(v: UnitDimension) = _state.update {
        it.copy(mDim = v, mUnit = Units.unitsFor(v).first())
    }
    fun setMUnit(v: String) = _state.update { it.copy(mUnit = v) }

    fun saveCustomIngredient() {
        val st = _state.value
        if (st.mZh.isBlank()) { _state.update { it.copy(error = "给材料起个中文名吧") }; return }
        viewModelScope.launch {
            val ing = Ingredient(
                id = "custom_" + UUID.randomUUID().toString().take(8),
                zh = st.mZh.trim(), en = st.mEn.trim(),
                category = st.mCat, dimension = st.mDim, unit = st.mUnit,
                isCustom = true
            )
            try {
                catalog.saveCustomIngredient(ing)
                select(ing, null)
            } catch (e: Exception) {
                _state.update { it.copy(error = "材料没存上：" + e.message) }
            }
        }
    }

    fun setCapacity(v: String) { _state.update { it.copy(capacity = v) } }
    fun setRemaining(v: String) { _state.update { it.copy(remaining = v) } }
    fun setOpened(o: Boolean) { _state.update { it.copy(openedToday = o) } }
    fun setDupMode(m: String) { _state.update { it.copy(dupMode = m) } }
    fun setMergeTarget(id: String) { _state.update { it.copy(mergeTargetId = id, dupMode = "merge") } }

    private suspend fun okCount(): Int {
        val bottles = cabinet.bottles.first()
        val engine = MatchEngine(SnapshotStore(bottles), catalog.substitutions)
        val ings = catalogCache
        return cabinet.allRecipes.first().count { engine.match(it, 1, ings).status == RecipeStatus.OK }
    }

    fun save() {
        val st = _state.value
        if (st.submitting) return   /* 提交期间禁止重复操作 */
        val (ing, brand) = st.selected ?: return
        val cap = st.capacity.toDoubleOrNull()
        val rem = st.remaining.toDoubleOrNull()
        if (cap == null || cap <= 0) { _state.update { it.copy(error = "容量填一个大于 0 的数") }; return }
        if (rem == null || rem < 0 || rem > cap) { _state.update { it.copy(error = "剩余量要在 0 到容量之间") }; return }
        if (st.dupMode == "merge" && st.mergeTargetId == null) { _state.update { it.copy(error = "选一下要补到哪一瓶里") }; return }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val before = okCount()
                if (st.dupMode == "merge" && st.mergeTargetId != null) {
                    cabinet.addStock(st.mergeTargetId, rem, "补充库存")
                } else {
                    val shapeMap = mapOf("base" to "spirit", "liqueur" to "liqueur", "mix" to "liqueur", "bitters" to "liqueur", "fresh" to "spirit")
                    val liquidMap = mapOf("base" to "oklch(0.75 0.10 75)", "liqueur" to "oklch(0.62 0.13 55)", "mix" to "oklch(0.85 0.06 90)", "bitters" to "oklch(0.45 0.10 45)", "fresh" to "oklch(0.78 0.12 120)")
                    cabinet.addBottle(
                        Bottle(
                            ingredientId = ing.id,
                            brand = brand?.brand ?: ing.zh,
                            label = brand?.label ?: if (ing.isCustom) "自定义" else "",
                            shape = shapeMap[ing.category] ?: "spirit",
                            liquid = liquidMap[ing.category] ?: "oklch(0.7 0.08 80)",
                            initQty = Qty.round(cap),
                            remaining = Qty.round(rem),
                            unit = ing.unit,
                            abv = brand?.abv ?: ing.defaultAbv,
                            openedAt = if (ing.dimension == UnitDimension.VOLUME && ing.defaultAbv > 0 && st.openedToday) System.currentTimeMillis() else null
                        ),
                        reason = "手动新增"
                    )
                }
                _state.update { it.copy(saved = true, submitting = false, unlockedGain = 0) }
                val gain = try { okCount() - before } catch (e: Exception) { 0 }
                _state.update { it.copy(unlockedGain = gain) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = "保存失败：" + (e.message ?: "原因不明，再试一次")) }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
}

@Composable
fun AddBottleScreen(nav: NavHostController, vm: AddBottleViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text("添加酒瓶", fontSize = 18.sp, color = Fg)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {

            if (s.mode == "entry") {
                item {
                    EntryRow("从材料库里搜", "内置的中英文材料和常见品牌，别名也能搜到") { vm.setMode("search"); vm.setQuery("") }
                    EntryRow("库里没有？自己新建", "起个名字、选好分类和计量方式，就能放进酒柜") { vm.setMode("manual") }
                }
            }

            if (s.mode == "manual") {
                item {
                    Text("新建一种材料", style = MaterialTheme.typography.titleLarge)
                    Text("新建的材料一样能用来配配方、算推荐，也会跟着备份走", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                    OutlinedTextField(value = s.mZh, onValueChange = { vm.setMZh(it) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("中文名（必填）") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = s.mEn, onValueChange = { vm.setMEn(it) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("英文名") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                    Text("分类", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("base" to "基酒", "liqueur" to "利口酒", "mix" to "辅料", "bitters" to "苦精", "fresh" to "鲜果").forEach { (k, label) ->
                            FilterChip(selected = s.mCat == k, onClick = { vm.setMCat(k) }, label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                    Text("按什么计量（毫升/克/个，选错不会自动换算）", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(UnitDimension.VOLUME to "液体", UnitDimension.MASS to "重量", UnitDimension.COUNT to "个数").forEach { (k, label) ->
                            FilterChip(selected = s.mDim == k, onClick = { vm.setMDim(k) }, label = { Text(label, fontSize = 12.sp) })
                        }
                    }
                    Text("平时用什么单位", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Units.unitsFor(s.mDim).forEach { u ->
                            FilterChip(selected = s.mUnit == u, onClick = { vm.setMUnit(u) }, label = { Text(u, fontSize = 12.sp) })
                        }
                    }
                    Button(
                        onClick = { vm.saveCustomIngredient() },
                        enabled = s.mZh.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("建好材料，继续入库") }
                    OutlinedButton(onClick = { vm.setMode("entry") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("返回") }
                }
            }

            if (s.mode == "search") {
                item {
                    OutlinedTextField(
                        value = s.query,
                        onValueChange = { vm.setQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索材料、品牌或别名，如：金酒 / gin / 添加利") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                items(s.searchResults) { (ing, brand) ->
                    Card(
                        onClick = { vm.select(ing, brand) },
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(13.dp)) {
                            Text(ing.zh + (brand?.let { " · " + it.brand } ?: "") + (if (ing.isCustom) "（自己加的）" else ""), fontSize = 14.sp, color = Fg)
                            Text(ing.en + (brand?.let { " " + it.label } ?: "") + " · " + ing.unit, color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (s.searchResults.isEmpty()) {
                    item {
                        Text("没找到这个材料", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedButton(onClick = { vm.setMode("manual") }, modifier = Modifier.fillMaxWidth()) { Text("自己新建它") }
                    }
                }
            }

            if (s.mode == "confirm" && s.selected != null) {
                val (ing, brand) = s.selected!!
                item {
                    Text("确认这瓶的信息", style = MaterialTheme.typography.titleLarge)
                    if (s.duplicates.isNotEmpty()) {
                        Card(colors = CardDefaults.cardColors(containerColor = StMiss.copy(alpha = 0.10f)), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("酒柜里已经有 " + s.duplicates.size + " 瓶这种材料了，这瓶是？", fontSize = 13.sp, color = Fg)
                                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = s.dupMode == "new", onClick = { vm.setDupMode("new") }, label = { Text("这是一瓶新的") })
                                    FilterChip(selected = s.dupMode == "merge", onClick = { vm.setDupMode("merge") }, label = { Text("倒进已有的瓶子") })
                                }
                                if (s.dupMode == "merge") {
                                    /* 必须明确选择目标瓶，不默认合并到第一瓶 */
                                    s.duplicates.forEach { b ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                            RadioButton(selected = s.mergeTargetId == b.id, onClick = { vm.setMergeTarget(b.id) })
                                            Text(b.brand + " · 剩 " + Units.fmt(b.remaining) + " " + b.unit, fontSize = 13.sp, color = Fg)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    InfoField("材料类型", ing.zh + " · " + ing.category)
                    InfoField("品牌与酒款", (brand?.brand ?: (if (ing.isCustom) "自定义" else "未指定")) + (brand?.let { " · " + it.label } ?: ""))
                    OutlinedTextField(
                        value = s.capacity, onValueChange = { vm.setCapacity(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text("这瓶一共多少（" + ing.unit + "）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, shape = RoundedCornerShape(12.dp)
                    )
                    val capV = s.capacity.toDoubleOrNull()
                    val remV = s.remaining.toDoubleOrNull()
                    OutlinedTextField(
                        value = s.remaining, onValueChange = { vm.setRemaining(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text("现在还剩多少（" + ing.unit + "，可以是 0）") },
                        isError = remV == null || remV < 0 || (capV != null && remV > capV),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, shape = RoundedCornerShape(12.dp)
                    )
                    if (capV != null && capV > 0) {
                        Slider(
                            value = (remV ?: capV).toFloat().coerceIn(0f, capV.toFloat()),
                            onValueChange = { vm.setRemaining(Units.fmt(it.toDouble())) },
                            valueRange = 0f..capV.toFloat()
                        )
                    }
                    if (ing.dimension == UnitDimension.VOLUME && ing.defaultAbv > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !s.openedToday, onClick = { vm.setOpened(false) }, label = { Text("未开瓶") })
                            FilterChip(selected = s.openedToday, onClick = { vm.setOpened(true) }, label = { Text("今天开瓶") })
                        }
                    }
                    Button(
                        onClick = { vm.save() },
                        enabled = !s.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (s.submitting) "保存中…" else if (s.dupMode == "merge") "补到这瓶里" else "放进酒柜") }
                    Text("会记进库存，并留一条「入库」记录", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    s.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = { TextButton(onClick = { vm.clearError() }) { Text("知道了", color = Accent) } },
            text = { Text(msg) },
            containerColor = Raised
        )
    }

    if (s.saved) {
        AlertDialog(
            onDismissRequest = { nav.popBackStack() },
            confirmButton = {
                TextButton(onClick = { nav.popBackStack() }) { Text("返回酒柜", color = Accent) }
            },
            title = { Text("放进酒柜了") },
            text = {
                Text(
                    (s.selected?.second?.brand ?: s.selected?.first?.zh ?: "") + " · 进出记录已更新" +
                        (if (s.unlockedGain > 0) "\n又能多调 " + s.unlockedGain + " 款酒了" else "")
                )
            },
            containerColor = Raised
        )
    }
}

@Composable
private fun EntryRow(title: String, sub: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Surface), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, color = Fg)
            Text(sub, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, fontSize = 13.sp, color = Fg)
        Surface(color = Surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(value, Modifier.padding(12.dp), fontSize = 14.sp, color = Fg)
        }
    }
}

