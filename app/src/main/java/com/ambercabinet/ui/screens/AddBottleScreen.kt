package com.ambercabinet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.domain.MatchEngine
import com.ambercabinet.core.domain.SnapshotStore
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.AmberTopBar
import com.ambercabinet.ui.components.MessageDialog
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/* 新瓶默认外观：按材料分类给瓶型与酒液色（顶层常量，不再每次保存重建） */
private val SHAPE_BY_CATEGORY = mapOf("base" to "spirit", "liqueur" to "liqueur", "mix" to "liqueur", "bitters" to "liqueur", "fresh" to "spirit")
private val LIQUID_BY_CATEGORY = mapOf("base" to "oklch(0.75 0.10 75)", "liqueur" to "oklch(0.62 0.13 55)", "mix" to "oklch(0.85 0.06 90)", "bitters" to "oklch(0.45 0.10 45)", "fresh" to "oklch(0.78 0.12 120)")
private val CATEGORY_ZH = mapOf("base" to "基酒", "liqueur" to "利口酒", "mix" to "辅料", "bitters" to "苦精", "fresh" to "鲜果")

/* 「常见酒一键添加」：只列内置材料里真实存在的 id（君度类并入橙味利口酒，白砂糖以方糖代替） */
private val POPULAR_IDS = listOf(
    "gin", "vodka", "white_rum", "tequila", "bourbon", "cognac",
    "orange_liqueur", "sweet_vermouth", "dry_vermouth", "campari", "coffee_liqueur",
    "grenadine", "simple_syrup", "lime", "lemon", "soda", "tonic", "cola",
    "aromatic_bitters", "mint"
)

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
    val error: String? = null,
    /* 常见酒一键添加 */
    val popular: List<Pair<Ingredient, Brand?>> = emptyList(),
    val ownedIngredientIds: Set<String> = emptySet(),
    val quickAddedMsg: String? = null
)

@HiltViewModel
class AddBottleViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AddBottleState())
    val state: StateFlow<AddBottleState> = _state.asStateFlow()

    fun setMode(m: String) { _state.update { it.copy(mode = m, error = null) } }

    /* 搜索索引：材料表 × 品牌表 join 一次并预先小写化；setQuery 只做 contains。
       三个缓存都在 Main 线程写、okCount() 在 Default 线程读，加 @Volatile 保证可见性 */
    @Volatile
    private var catalogCache: Map<String, Ingredient> = emptyMap()
    @Volatile
    private var searchIndex: List<Triple<Ingredient, Brand?, String>> = emptyList()
    /* 配方表随 allRecipes 的每次发射更新（新增/编辑私人配方后自动失效），
       避免长期缓存导致保存后的「又能多调 N 款」失真；也不在 save() 里重复 JSON 解码 */
    @Volatile
    private var recipesCache: List<Recipe> = emptyList()

    init {
        viewModelScope.launch {
            catalog.allIngredients.collect { ings ->
                catalogCache = ings
                val brandsByIng = catalog.brands.groupBy { it.ingredientId }
                searchIndex = ings.values.filter { !it.staple }.flatMap { ing ->
                    val base = ing.zh + " " + ing.en + " " + ing.aliases.joinToString(" ")
                    val brands = brandsByIng[ing.id].orEmpty()
                    if (brands.isEmpty()) listOf(Triple(ing, null, base.lowercase()))
                    else brands.map { b -> Triple(ing, b, (base + " " + b.brand + " " + b.label).lowercase()) }
                }
                /* 常见酒列表：按固定顺序取真实存在的内置材料，每材料带品牌表里的第一个品牌 */
                val popular = POPULAR_IDS.mapNotNull { id ->
                    ings[id]?.let { ing -> ing to brandsByIng[id]?.firstOrNull() }
                }
                _state.update { it.copy(popular = popular) }
            }
        }
        viewModelScope.launch { cabinet.allRecipes.collect { recipesCache = it } }
        viewModelScope.launch {
            cabinet.bottles.collect { list -> _state.update { it.copy(ownedIngredientIds = list.map { b -> b.ingredientId }.toSet()) } }
        }
    }

    fun setQuery(q: String) {
        val query = q.trim().lowercase()
        val results = if (query.isEmpty()) searchIndex else searchIndex.filter { it.third.contains(query) }
        _state.update { it.copy(query = q, searchResults = results.take(30).map { t -> t.first to t.second }) }
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

    /* 配方列表与匹配计数都在 Default 线程算；配方表由 allRecipes 的订阅随时保持最新，不重复 JSON 解码 */
    private suspend fun okCount(): Int = withContext(Dispatchers.Default) {
        val bottles = cabinet.bottles.first()
        val engine = MatchEngine(SnapshotStore(bottles), catalog.substitutions)
        /* 订阅还没发出第一份时兜一次，避免「保存前」基准拿到空表把增量算大 */
        val recipes = recipesCache.ifEmpty { cabinet.allRecipes.first().also { recipesCache = it } }
        recipes.count { engine.match(it, 1, catalogCache).status == RecipeStatus.OK }
    }

    fun save() {
        val st = _state.value
        if (st.submitting) return   /* 提交期间禁止重复操作 */
        val (ing, brand) = st.selected ?: return
        /* 合并时界面按目标瓶的单位标注输入量，这里必须用同一个单位解释，否则会写错数量 */
        val target = if (st.dupMode == "merge") st.duplicates.firstOrNull { it.id == st.mergeTargetId } else null
        val inputUnit = target?.unit ?: ing.unit
        val cap = st.capacity.toDoubleOrNull()
        val rem = st.remaining.toDoubleOrNull()
        if (st.dupMode == "merge" && target == null) { _state.update { it.copy(error = "选一下要补到哪一瓶里") }; return }
        if (rem == null || rem < 0) { _state.update { it.copy(error = "剩余量填一个不小于 0 的数") }; return }
        if (target == null) {
            /* 合并路径不消费 cap（界面只拿它定滑块上限），容量校验只在新建模式做 */
            if (cap == null || cap <= 0) { _state.update { it.copy(error = "容量填一个大于 0 的数") }; return }
            if (rem > cap) { _state.update { it.copy(error = "剩余量不能超过容量") }; return }
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val before = okCount()
                if (target != null) {
                    /* 输入量按界面标注的单位解释，再换算到目标瓶的库存单位写入 */
                    val delta = Units.needInStockUnit(ing, rem, inputUnit, target.unit)
                        ?: throw IllegalStateException("「" + inputUnit + "」和这瓶的「" + target.unit + "」对不上，没法直接补")
                    cabinet.addStock(target.id, delta, "补充库存")
                } else {
                    cabinet.addBottle(
                        Bottle(
                            ingredientId = ing.id,
                            brand = brand?.brand ?: ing.zh,
                            label = brand?.label ?: if (ing.isCustom) "自定义" else "",
                            shape = SHAPE_BY_CATEGORY[ing.category] ?: "spirit",
                            liquid = LIQUID_BY_CATEGORY[ing.category] ?: "oklch(0.7 0.08 80)",
                            initQty = Qty.round(cap!!),   /* 走到这里必是新建模式，容量已校验非空且 > 0 */
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

    /* 常见酒一键添加：满瓶入库，字段默认值与 save() 新建路径一致；同材料不去重，直接再建一瓶 */
    fun quickAdd(ing: Ingredient, brand: Brand?) {
        viewModelScope.launch {
            try {
                val before = okCount()
                val cap = (brand?.capacityMl ?: if (ing.dimension == UnitDimension.COUNT) 6 else 750).toDouble()
                cabinet.addBottle(
                    Bottle(
                        ingredientId = ing.id,
                        brand = brand?.brand ?: ing.zh,
                        label = brand?.label ?: if (ing.isCustom) "自定义" else "",
                        shape = SHAPE_BY_CATEGORY[ing.category] ?: "spirit",
                        liquid = LIQUID_BY_CATEGORY[ing.category] ?: "oklch(0.7 0.08 80)",
                        initQty = Qty.round(cap),
                        remaining = Qty.round(cap),
                        unit = ing.unit,
                        abv = brand?.abv ?: ing.defaultAbv,
                        openedAt = null
                    ),
                    reason = "一键添加"
                )
                val gain = try { okCount() - before } catch (e: Exception) { 0 }
                _state.update { it.copy(quickAddedMsg = "已添加 " + ing.zh + (if (gain > 0) " · 又能多调 " + gain + " 款" else "")) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "添加失败：" + (e.message ?: "原因不明，再试一次")) }
            }
        }
    }

    fun clearQuickAdded() { _state.update { it.copy(quickAddedMsg = null) } }
}

@Composable
fun AddBottleScreen(nav: NavHostController, vm: AddBottleViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(s.quickAddedMsg) {
        s.quickAddedMsg?.let { snackbarHostState.showSnackbar(it); vm.clearQuickAdded() }
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AmberTopBar("添加酒瓶", onBack = { nav.popBackStack() }) }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {

            if (s.mode == "entry") {
                item {
                    Text("常见酒一键添加", style = MaterialTheme.typography.titleLarge)
                    Text("家里常备的这些，点一下就以满瓶放进酒柜", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    s.popular.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (ing, brand) ->
                                val owned = ing.id in s.ownedIngredientIds
                                Card(
                                    onClick = { vm.quickAdd(ing, brand) },
                                    enabled = !owned,
                                    colors = CardDefaults.cardColors(containerColor = Surface),
                                    modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(ing.zh, fontSize = 13.sp, color = if (owned) Muted else Fg)
                                        Text(if (owned) "已在柜中" else CATEGORY_ZH[ing.category] ?: ing.category, color = Muted, fontSize = 11.sp)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
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
                items(s.searchResults, key = { it.first.id + "|" + (it.second?.brand ?: "") + "|" + (it.second?.label ?: "") }) { (ing, brand) ->
                    Card(
                        onClick = { vm.select(ing, brand) },
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        modifier = Modifier.fillMaxWidth().animateItem().padding(vertical = 4.dp)
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
                    /* 合并到已有瓶时按目标瓶单位标注与解释，和 save() 里换算用的是同一单位 */
                    val mergeTargetUnit = if (s.dupMode == "merge") s.duplicates.firstOrNull { it.id == s.mergeTargetId }?.unit else null
                    val inputUnit = mergeTargetUnit ?: ing.unit
                    OutlinedTextField(
                        value = s.capacity, onValueChange = { vm.setCapacity(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        label = { Text(if (mergeTargetUnit != null) "最多补多少（" + inputUnit + "，只用来定滑块上限）" else "这瓶一共多少（" + inputUnit + "）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, shape = RoundedCornerShape(12.dp)
                    )
                    val capV = s.capacity.toDoubleOrNull()
                    val remV = s.remaining.toDoubleOrNull()
                    val remError = remV == null || remV < 0 || (mergeTargetUnit == null && capV != null && remV > capV)
                    OutlinedTextField(
                        value = s.remaining, onValueChange = { vm.setRemaining(it) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text(if (mergeTargetUnit != null) "这次补多少（" + inputUnit + "）" else "现在还剩多少（" + inputUnit + "，可以是 0）") },
                        isError = remError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, shape = RoundedCornerShape(12.dp)
                    )
                    if (capV != null && capV > 0) {
                        /* 滑块直接写数值字符串（不经过 fmt 往返，否则 Float 精度截断会让拖动卡住），显示处再格式化 */
                        Slider(
                            value = (remV ?: capV).toFloat().coerceIn(0f, capV.toFloat()),
                            onValueChange = { vm.setRemaining(kotlin.math.round(it).toInt().toString()) },
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

    MessageDialog(s.error, vm::clearError)

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

