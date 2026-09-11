package com.ambercabinet.ui.screens

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.model.Bottle
import com.ambercabinet.core.model.Ingredient
import com.ambercabinet.core.model.InventoryTransaction
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.BottlePour
import com.ambercabinet.ui.components.MonoNum
import com.ambercabinet.ui.components.StockBar
import com.ambercabinet.ui.components.parseLiquid
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BottleDetailState(
    val loading: Boolean = true,
    val bottle: Bottle? = null,
    val ingredient: Ingredient? = null,
    val txns: List<InventoryTransaction> = emptyList(),
    val msg: String? = null,
    val archived: Boolean = false,
    val busy: Boolean = false
)

@HiltViewModel
class BottleDetailViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val savedState: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val bottleId: String = savedState.get<String>("id") ?: ""
    private val msg = MutableStateFlow<String?>(null)
    private val archived = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<BottleDetailState> = combine(
        cabinet.bottles, cabinet.transactions, catalog.allIngredients
    ) { bottles, txns, ings ->
        val b = bottles.firstOrNull { it.id == bottleId } ?: cabinet.getBottle(bottleId)
        BottleDetailState(
            loading = false,
            bottle = b,
            ingredient = b?.let { ings[it.ingredientId] },
            txns = txns.filter { it.bottleId == bottleId }
        )
    }.combine(msg) { s, m -> s.copy(msg = m) }
        .combine(archived) { s, a -> s.copy(archived = a) }
        .combine(busy) { s, bz -> s.copy(busy = bz) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BottleDetailState())

    private suspend fun <T> guard(block: suspend () -> T): T? {
        if (busy.value) return null
        busy.value = true
        return try { block() } catch (e: Exception) {
            msg.value = e.message ?: "操作失败"
            null
        } finally { busy.value = false }
    }

    /** 盘点校正：直接设定剩余量（整数），生成流水 */
    fun correctStock(newRemaining: Double) {
        viewModelScope.launch {
            guard {
                cabinet.adjustStock(bottleId, newRemaining, "盘点校正", "盘点后设定")
                msg.value = "好啦，剩余量更新为 " + Units.fmt(Qty.round(newRemaining))
            }
        }
    }

    /** 补充此瓶（显式目标瓶，不合并到其他瓶） */
    fun restock(delta: Double) {
        viewModelScope.launch {
            guard {
                cabinet.addStock(bottleId, delta, "补充库存")
                msg.value = "已补进 " + Units.fmt(Qty.round(delta))
            }
        }
    }

    fun updateInfo(brand: String, label: String, initQty: Double, abv: Double, lowPct: Int, openedAt: Long?) {
        viewModelScope.launch {
            guard {
                val b = state.value.bottle ?: return@guard
                cabinet.updateBottle(b.copy(brand = brand, label = label, initQty = initQty, abv = abv, lowPct = lowPct, openedAt = openedAt))
                msg.value = "资料改好了"
            }
        }
    }

    fun archive() {
        viewModelScope.launch {
            guard {
                cabinet.archiveBottle(bottleId)
                archived.value = true
            }
        }
    }

    fun clearMsg() { msg.value = null }
}

private val fullDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
private val dayFmt = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottleDetailScreen(nav: NavHostController, bottleId: String, vm: BottleDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var showArchive by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }

    LaunchedEffect(s.archived) { if (s.archived) nav.popBackStack() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text("酒瓶详情", fontSize = 18.sp, color = Fg)
            }
        }
    ) { padding ->
        val b = s.bottle
        if (s.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            return@Scaffold
        }
        if (b == null) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text("这瓶酒已经不在酒柜里了", color = Muted)
                Text("它留下的记录都还好好保存在「记录」页里", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            return@Scaffold
        }
        val pct = if (b.initQty > 0) (b.remaining / b.initQty).toFloat() else 0f
        val ing = s.ingredient

        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BottlePour(b.shape, pct, parseLiquid(b.liquid), Modifier.size(56.dp, 92.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(b.brand + " " + b.label, fontFamily = FontFamily.Serif, fontSize = 20.sp, color = Fg)
                    Text((ing?.let { it.zh + " · " + it.en } ?: b.ingredientId), color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 8.dp)) {
                        MonoNum(Units.fmt(b.remaining), size = 30, color = if (pct * 100 <= b.lowPct) StMiss else Fg)
                        Text(" / " + Units.fmt(b.initQty) + " " + b.unit, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    StockBar(pct, pct * 100 <= b.lowPct, Modifier.padding(top = 8.dp).width(180.dp))
                }
            }

            /* 盘点校正：直接输入剩余量（整数）+ 滑块辅助 */
            SectionHead("还剩多少？")
            var correctText by remember(b.id, b.remaining) { mutableStateOf(Units.fmt(b.remaining)) }
            val correctValue = correctText.toDoubleOrNull()
            OutlinedTextField(
                value = correctText,
                onValueChange = { v -> if (v.all { it.isDigit() }) correctText = v },   /* 只收整数，不要小数点 */
                modifier = Modifier.fillMaxWidth(),
                label = { Text("看一眼瓶子，现在还剩（" + b.unit + "）") },
                isError = correctText.isNotBlank() && (correctValue == null || correctValue < 0),
                supportingText = {
                    if (correctText.isNotBlank() && (correctValue == null || correctValue < 0)) Text("填个整数就好，不用很精确", color = StMiss)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            val sliderMax = maxOf(b.initQty, b.remaining).coerceAtLeast(1.0)
            Slider(
                value = (correctValue ?: b.remaining).toFloat().coerceIn(0f, sliderMax.toFloat()),
                onValueChange = { correctText = kotlin.math.round(it).toInt().toString() },   /* 滑块同样按整数取整 */
                valueRange = 0f..sliderMax.toFloat()
            )
            Button(
                onClick = { correctValue?.let { vm.correctStock(it) } },
                enabled = correctValue != null && correctValue >= 0 && Qty.round(correctValue) != b.remaining && !s.busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                shape = RoundedCornerShape(12.dp)
            ) { Text("更新剩余量") }

            /* 补充此瓶 */
            SectionHead("又买了一些？")
            var addText by remember { mutableStateOf("") }
            val addValue = addText.toDoubleOrNull()
            OutlinedTextField(
                value = addText,
                onValueChange = { addText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("这次补了多少（" + b.unit + "），只加到这瓶") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            OutlinedButton(
                onClick = { addValue?.let { vm.restock(it) } },
                enabled = addValue != null && addValue > 0 && !s.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("加到这瓶里") }

            /* 信息编辑 */
            SectionHead("这瓶的档案")
            if (!editMode) {
                InfoRow("整瓶容量", Units.fmt(b.initQty) + " " + b.unit)
                InfoRow("酒精度", if (b.abv > 0) Units.fmt(b.abv) + "% vol" else "无酒精")
                InfoRow("开瓶时间", b.openedAt?.let { dayFmt.format(Date(it)) } ?: "还没开")
                InfoRow("快喝完提醒", "低于 " + b.lowPct + "% 时提醒你补货")
                OutlinedButton(onClick = { editMode = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("修改资料") }
            } else {
                var brand by remember { mutableStateOf(b.brand) }
                var label by remember { mutableStateOf(b.label) }
                var initQty by remember { mutableStateOf(Units.fmt(b.initQty)) }
                var abv by remember { mutableStateOf(if (b.abv > 0) Units.fmt(b.abv) else "") }
                var lowPct by remember { mutableStateOf(b.lowPct.toString()) }
                var opened by remember { mutableStateOf(b.openedAt != null) }
                var openedDate by remember { mutableStateOf(b.openedAt) }
                var showDatePicker by remember { mutableStateOf(false) }
                EditField("品牌", brand) { brand = it }
                EditField("酒款", label) { label = it }
                EditField("整瓶容量（" + b.unit + "）", initQty) { initQty = it }
                EditField("酒精度 % vol（无酒精留空）", abv) { abv = it }
                EditField("快喝完提醒线 %", lowPct) { lowPct = it }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !opened, onClick = { opened = false }, label = { Text("还没开") })
                    FilterChip(selected = opened, onClick = { opened = true; if (openedDate == null) openedDate = System.currentTimeMillis() }, label = { Text("已开瓶") })
                }
                if (opened) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("开瓶日期：" + (openedDate?.let { dayFmt.format(Date(it)) } ?: "点我选择"), color = Fg)
                    }
                }
                if (showDatePicker) {
                    val dpState = rememberDatePickerState(initialSelectedDateMillis = openedDate ?: System.currentTimeMillis())
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = { openedDate = dpState.selectedDateMillis ?: openedDate; showDatePicker = false }) { Text("就选这天", color = Accent) }
                        },
                        dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
                    ) { DatePicker(state = dpState) }
                }
                val iq = initQty.toDoubleOrNull()
                val abvV = abv.toDoubleOrNull() ?: 0.0
                val lp = lowPct.toIntOrNull()
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { editMode = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = {
                            vm.updateInfo(brand.trim(), label.trim(), Qty.round(iq!!), abvV, lp!!, if (opened) openedDate ?: System.currentTimeMillis() else null)
                            editMode = false
                        },
                        enabled = brand.isNotBlank() && iq != null && iq > 0 && lp != null && lp in 0..100 && !s.busy,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk)
                    ) { Text("保存") }
                }
            }

            /* 流水 */
            SectionHead("这瓶的进出记录", s.txns.size.toString() + " 条")
            s.txns.take(10).forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(t.reason, fontSize = 13.sp, color = Fg)
                        Text(fullDateFmt.format(Date(t.time)) + (if (t.undone) " · 已撤销" else ""), color = Muted, fontSize = 11.sp)
                    }
                    MonoNum((if (t.delta < 0) "−" else "+") + Units.fmt(Math.abs(t.delta)) + " " + t.unit, size = 13, color = if (t.delta < 0) StMiss else StOk)
                }
                HorizontalDivider(color = Fg.copy(alpha = 0.06f))
            }

            /* 归档 */
            TextButton(onClick = { showArchive = true }, modifier = Modifier.padding(top = 16.dp)) {
                Text("这瓶不要了？移出酒柜（记录都会留着）", color = StMiss, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showArchive) {
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text("移出酒柜？") },
            text = { Text("「" + (s.bottle?.brand ?: "") + "」不会再出现在酒柜和「能调什么」的匹配里，但它的进出记录、调过的酒都会原样保留，随时可以翻看。") },
            confirmButton = {
                TextButton(onClick = { showArchive = false; vm.archive() }) { Text("确定移出", color = StMiss) }
            },
            dismissButton = { TextButton(onClick = { showArchive = false }) { Text("再想想") } },
            containerColor = Raised
        )
    }

    s.msg?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.clearMsg() },
            confirmButton = { TextButton(onClick = { vm.clearMsg() }) { Text("知道了", color = Accent) } },
            text = { Text(msg) },
            containerColor = Raised
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Fg, fontSize = 13.sp)
    }
}

@Composable
private fun EditField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        label = { Text(label) }, singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )
}
