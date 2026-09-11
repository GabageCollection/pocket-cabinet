package com.ambercabinet.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.BackupCodec
import com.ambercabinet.core.data.repo.BackupService
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class SettingsState(
    val bottleCount: Int = 0,
    val sessionCount: Int = 0,
    val txnCount: Int = 0,
    val recipeCount: Int = 0,
    val privateRecipeCount: Int = 0,
    val customIngredientCount: Int = 0,
    val seedVersion: String = "",
    val busy: Boolean = false,
    val msg: String? = null,
    val pendingSummary: BackupCodec.Summary? = null,
    val pendingUri: android.net.Uri? = null,
    val hasSnapshot: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val backup: BackupService
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val msg = MutableStateFlow<String?>(null)
    private val pending = MutableStateFlow<Pair<BackupCodec.Summary, android.net.Uri>?>(null)
    private val hasSnapshot = MutableStateFlow(backup.hasSnapshot)

    val state: StateFlow<SettingsState> = combine(
        cabinet.bottles, cabinet.sessions, cabinet.transactions, cabinet.allRecipes, catalog.customIngredients
    ) { b, s, t, r, ci -> Counts(b.size, s.size, t.size, r.size, r.count { it.isUser }, ci.size) }
        .combine(combine(busy, msg, pending, hasSnapshot) { bz, m, p, hs -> Quad(bz, m, p, hs) }) { c, q ->
            SettingsState(
                bottleCount = c.bottles, sessionCount = c.sessions, txnCount = c.txns,
                recipeCount = c.recipes, privateRecipeCount = c.privateRecipes, customIngredientCount = c.customIngs,
                seedVersion = catalog.catalog.version,
                busy = q.busy, msg = q.msg,
                pendingSummary = q.pending?.first, pendingUri = q.pending?.second,
                hasSnapshot = q.hasSnapshot
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsState())

    private data class Counts(val bottles: Int, val sessions: Int, val txns: Int, val recipes: Int, val privateRecipes: Int, val customIngs: Int)
    private data class Quad(val busy: Boolean, val msg: String?, val pending: Pair<BackupCodec.Summary, android.net.Uri>?, val hasSnapshot: Boolean)

    fun export(uri: android.net.Uri) {
        viewModelScope.launch {
            busy.value = true
            val ok = try { backup.exportTo(uri) } catch (e: Exception) { false }
            busy.value = false
            msg.value = if (ok) "备份好了，文件在你选的位置" else "没导出成：选的位置写不进去，换一个试试"
        }
    }

    /** 第一步：只解析与校验，展示摘要，等待用户确认 */
    fun inspectRestore(uri: android.net.Uri) {
        viewModelScope.launch {
            busy.value = true
            backup.summarize(uri)
                .onSuccess { pending.value = it to uri }
                .onFailure { msg.value = "这个备份读不了：" + (it.message ?: "文件无效") }
            busy.value = false
        }
    }

    /** 第二步：用户确认后执行全量替换（恢复前自动生成快照） */
    fun confirmRestore() {
        val p = pending.value ?: return
        viewModelScope.launch {
            busy.value = true
            backup.restore(p.second)
                .onSuccess {
                    msg.value = "已经回到备份时的样子了。想反悔的话，用下面的「回到最近一次恢复之前」。"
                    hasSnapshot.value = backup.hasSnapshot
                }
                .onFailure { msg.value = "恢复没成功：" + (it.message ?: "未知原因") + " · 现在的数据没动过" }
            pending.value = null
            busy.value = false
        }
    }

    fun cancelRestore() { pending.value = null }

    fun rollbackSnapshot() {
        viewModelScope.launch {
            busy.value = true
            backup.rollbackToSnapshot()
                .onSuccess { msg.value = "已经回到恢复前的样子了" }
                .onFailure { msg.value = "回不去：" + (it.message ?: "没有可用的存档") }
            hasSnapshot.value = backup.hasSnapshot
            busy.value = false
        }
    }

    fun clearMsg() { msg.value = null }
}

private val settingsDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.export(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.inspectRestore(it) }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Fg) }
                Text("设置", fontSize = 18.sp, color = Fg)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {

            SectionHead("备份与恢复")
            Text(
                "备份里有酒柜、进出记录、调过的酒、品鉴笔记、私人配方、自定义材料和收藏。全程不联网，文件就存在你选的地方。",
                color = Muted, fontSize = 12.sp
            )
            Button(
                onClick = { exportLauncher.launch("pocket-cabinet-backup.acb") },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                shape = RoundedCornerShape(12.dp)
            ) { Text("导出备份") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                enabled = !s.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("从备份恢复…") }
            if (s.hasSnapshot) {
                TextButton(onClick = { vm.rollbackSnapshot() }, enabled = !s.busy) {
                    Text("回到最近一次恢复之前", color = StMiss, fontSize = 13.sp)
                }
            }

            SectionHead("数据")
            SettingsRow("内置配方", s.recipeCount.let { (it - s.privateRecipeCount).toString() } + " 款（配方库版本 " + s.seedVersion + "）")
            SettingsRow("私人配方", s.privateRecipeCount.toString() + " 款")
            SettingsRow("自定义材料", s.customIngredientCount.toString() + " 种")
            SettingsRow("酒柜", s.bottleCount.toString() + " 瓶")
            SettingsRow("调制记录", s.sessionCount.toString() + " 次")
            SettingsRow("进出记录", s.txnCount.toString() + " 条")

            SectionHead("关于")
            Text("口袋酒柜 · 完全离线的家庭调酒与定量酒柜", color = Muted, fontSize = 13.sp)
            Text("请确认您已达到当地法定饮酒年龄。理性饮酒，未成年人禁止饮酒。", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    /* 恢复确认：展示备份时间与数据摘要，明确将替换当前数据 */
    s.pendingSummary?.let { sum ->
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("恢复这份备份？") },
            text = {
                Column {
                    Text("备份时间：" + (if (sum.exportedAt > 0) settingsDateFmt.format(Date(sum.exportedAt)) else "未知"))
                    Text("备份格式版本：v" + sum.schemaVersion, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Text(
                        "酒瓶 " + (sum.counts["bottles"] ?: 0) + " · 进出记录 " + (sum.counts["txns"] ?: 0) +
                            " · 调制记录 " + (sum.counts["sessions"] ?: 0) + " · 笔记 " + (sum.counts["notes"] ?: 0) +
                            " · 私人配方 " + (sum.counts["customRecipes"] ?: 0) + " · 自定义材料 " + (sum.counts["customIngredients"] ?: 0) +
                            " · 收藏 " + (sum.counts["favorites"] ?: 0) + " · 照片 " + sum.photoCount,
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "现在的全部数据（" + s.bottleCount + " 瓶、" + s.sessionCount + " 次调制记录等）会被这份备份整个替换掉。替换前会自动留一份现在的存档，随时可以回来。",
                        color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRestore() }, enabled = !s.busy) {
                    Text(if (s.busy) "恢复中…" else "确认恢复", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { vm.cancelRestore() }) { Text("取消") } },
            containerColor = Raised
        )
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
private fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Fg, fontSize = 14.sp)
        Text(value, color = Muted, fontSize = 13.sp)
    }
}
