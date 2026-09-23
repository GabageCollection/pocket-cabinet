package com.ambercabinet.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.ambercabinet.core.data.repo.CabinetRepository
import com.ambercabinet.core.data.repo.CatalogRepository
import com.ambercabinet.core.domain.*
import com.ambercabinet.core.model.*
import com.ambercabinet.core.units.Qty
import com.ambercabinet.core.units.Units
import com.ambercabinet.ui.components.GlassPour
import com.ambercabinet.ui.components.MessageDialog
import com.ambercabinet.ui.components.MonoNum
import com.ambercabinet.ui.components.isLowStock
import com.ambercabinet.ui.nav.Routes
import com.ambercabinet.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.ceil

data class MixState(
    val loading: Boolean = true,
    val draft: MixDraft? = null,
    val recipe: Recipe? = null,
    val timerLeft: Int = 0,
    val timerTotal: Int = 0,
    val timerRunning: Boolean = false,
    val finishOpen: Boolean = false,
    val actual: Int = 1,
    val plan: PourPlan? = null,
    val planChangedNotice: Boolean = false,
    val submitting: Boolean = false,
    val undoing: Boolean = false,
    val doneSession: MixSession? = null,
    val donePlan: PourPlan? = null,
    val undone: Boolean = false,
    val error: String? = null,
    val exitSaved: Boolean = false,
    val ingredients: Map<String, Ingredient> = emptyMap()
)

@HiltViewModel
class MixViewModel @Inject constructor(
    private val cabinet: CabinetRepository,
    private val catalog: CatalogRepository,
    private val inventory: InventoryService,
    private val savedState: androidx.lifecycle.SavedStateHandle
) : ViewModel() {
    private val draftId: String = savedState.get<String>("draftId") ?: ""
    private val _state = MutableStateFlow(MixState())
    val state: StateFlow<MixState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val draft = cabinet.getDraft(draftId)
            if (draft == null) {
                _state.update { it.copy(loading = false, error = "这次调酒找不到了，可能已经完成") }
                return@launch
            }
            val allIng = catalog.allIngredients.first()
            val recipe = cabinet.allRecipes.first().firstOrNull { it.id == draft.recipeId }
            if (recipe == null) {
                _state.update { it.copy(loading = false, error = "配方已经不在了") }
                return@launch
            }
            val clamped = draft.copy(currentStep = draft.currentStep.coerceIn(0, (recipe.steps.size - 1).coerceAtLeast(0)))
            _state.update {
                it.copy(
                    loading = false,
                    draft = clamped,
                    recipe = recipe, ingredients = allIng,
                    actual = draft.servings
                )
            }
            applyStepTimer(clamped, recipe)
            refreshPlan()
            startTicker()
        }
    }

    /* 计时恢复：运行中以 timerEndAt 为明确时间基准；暂停以剩余秒数为准（§二.2）。
       timerStep 记录计时所属步骤，避免从别的步骤恢复时显示错误的倒计时（-1 = 旧草稿，按当前步处理） */
    private fun applyStepTimer(draft: MixDraft, recipe: Recipe) {
        val stepDef = recipe.steps.getOrNull(draft.currentStep)
        val total = stepDef?.timerSeconds ?: 0
        if (total <= 0) {
            _state.update { it.copy(timerTotal = 0, timerLeft = 0, timerRunning = false) }
            return
        }
        val timerMatchesStep = draft.timerStep < 0 || draft.timerStep == draft.currentStep
        val running = draft.timerRunning && draft.timerEndAt != null && timerMatchesStep
        val endAt = draft.timerEndAt
        val left = when {
            running && endAt != null -> ceil((endAt - System.currentTimeMillis()) / 1000.0).toInt()
            draft.timerRemainingSec > 0 && timerMatchesStep -> draft.timerRemainingSec
            else -> total
        }
        val nowDone = running && left <= 0
        _state.update { it.copy(timerTotal = total, timerLeft = left.coerceIn(0, total), timerRunning = running && !nowDone) }
        if (nowDone) persistTimer(running = false, remaining = 0, endAt = null)
        else if (running) persistTimer(running = true, remaining = left, endAt = draft.timerEndAt)
        else if (draft.timerRemainingSec <= 0 || !timerMatchesStep) persistTimer(running = false, remaining = total, endAt = null)
    }

    private fun startTicker() {
        viewModelScope.launch {
            /* 只在计时运行时 tick：运行状态翻转才唤醒（collectLatest 取消上一轮循环），
               暂停/停止时协程挂起，不再每 250ms 空转 */
            _state.map { it.timerRunning }.distinctUntilChanged().collectLatest { running ->
                if (!running) return@collectLatest
                while (true) {
                    delay(250)
                    val st = _state.value
                    val d = st.draft ?: break
                    if (!st.timerRunning || d.timerEndAt == null) break
                    val left = ceil((d.timerEndAt - System.currentTimeMillis()) / 1000.0).toInt().coerceAtLeast(0)
                    _state.update { it.copy(timerLeft = left) }
                    if (left <= 0) {
                        _state.update { it.copy(timerRunning = false) }
                        persistTimer(running = false, remaining = 0, endAt = null)
                    }
                }
            }
        }
    }

    private fun persistTimer(running: Boolean, remaining: Int, endAt: Long?) {
        val d = _state.value.draft ?: return
        viewModelScope.launch {
            cabinet.saveDraft(d.copy(timerRunning = running, timerRemainingSec = remaining, timerEndAt = endAt, timerStep = d.currentStep))
            _state.update { it.copy(draft = it.draft?.copy(timerRunning = running, timerRemainingSec = remaining, timerEndAt = endAt, timerStep = d.currentStep)) }
        }
    }

    fun setStep(step: Int) {
        val r = _state.value.recipe ?: return
        val d = _state.value.draft ?: return
        val s = step.coerceIn(0, r.steps.size - 1)
        val total = r.steps[s].timerSeconds
        val nd = d.copy(currentStep = s, timerRunning = false, timerRemainingSec = total, timerEndAt = null, timerStep = s)
        _state.update { it.copy(draft = nd, timerTotal = total, timerLeft = total, timerRunning = false) }
        viewModelScope.launch { cabinet.saveDraft(nd) }   /* 切换步骤即保存（§14） */
    }

    fun toggleTimer() {
        val st = _state.value
        if (st.timerTotal <= 0) return
        if (st.timerRunning) {
            persistTimer(running = false, remaining = st.timerLeft, endAt = null)
            _state.update { it.copy(timerRunning = false) }
        } else {
            val remaining = if (st.timerLeft <= 0) st.timerTotal else st.timerLeft
            val endAt = System.currentTimeMillis() + remaining * 1000L
            persistTimer(running = true, remaining = remaining, endAt = endAt)
            _state.update { it.copy(timerLeft = remaining, timerRunning = true) }
        }
    }

    fun resetTimer() {
        val total = _state.value.timerTotal
        persistTimer(running = false, remaining = total, endAt = null)
        _state.update { it.copy(timerLeft = total, timerRunning = false) }
    }

    /** 退出：草稿全程已持久化，这里做最终保存；成功后界面才能提示「进度已保存」 */
    fun exitAndSave() {
        viewModelScope.launch {
            val d = _state.value.draft
            if (d != null) {
                try {
                    cabinet.saveDraft(d)
                    _state.update { it.copy(exitSaved = true) }
                } catch (e: Exception) {
                    _state.update { it.copy(error = "进度没存上，再试一次") }
                }
            } else {
                _state.update { it.copy(exitSaved = true) }
            }
        }
    }

    fun openFinish() {
        _state.update { it.copy(finishOpen = true, actual = it.draft?.servings ?: 1, planChangedNotice = false) }
        refreshPlan()
    }
    fun closeFinish() { _state.update { it.copy(finishOpen = false) } }

    fun setActual(delta: Int) {
        _state.update { it.copy(actual = (it.actual + delta).coerceAtLeast(1), planChangedNotice = false) }
        refreshPlan()
    }

    /* 完成时按草稿中已确认的选瓶与替代重新检查库存（§6.3 / §14） */
    private fun refreshPlan() {
        viewModelScope.launch {
            val st = _state.value
            val r = st.recipe ?: return@launch
            val d = st.draft ?: return@launch
            val plan = inventory.planPour(
                r, st.actual, st.ingredients,
                overrides = d.bottleOverrides, chosenSubs = d.chosenSubs
            )
            _state.update { it.copy(plan = plan) }
        }
    }

    /** 确认扣减：幂等（submitting + 同一 sessionId 只能成功一次）；计划变化须重新确认 */
    fun confirm() {
        val st = _state.value
        if (st.submitting) return
        val r = st.recipe ?: return
        val d = st.draft ?: return
        val plan = st.plan ?: return
        if (!plan.ok) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = try {
                cabinet.commitMixAtomic(inventory, d, r, st.actual, st.ingredients, plan.fingerprint(st.actual))
            } catch (e: Exception) {
                CommitResult.Failed(listOf(e.message ?: "保存失败"))
            }
            when (result) {
                is CommitResult.Success -> _state.update {
                    it.copy(submitting = false, doneSession = result.session, donePlan = result.plan)
                }
                is CommitResult.AlreadyCommitted -> _state.update {
                    it.copy(submitting = false, doneSession = result.session)
                }
                is CommitResult.PlanChanged -> _state.update {
                    it.copy(submitting = false, plan = result.freshPlan, planChangedNotice = true)
                }
                is CommitResult.Failed -> _state.update {
                    it.copy(submitting = false, error = result.problems.joinToString("；").ifBlank { "材料不够，库存扣不了" })
                }
            }
        }
    }

    fun undo() {
        val id = _state.value.doneSession?.id ?: return
        if (_state.value.undoing) return
        _state.update { it.copy(undoing = true) }
        viewModelScope.launch {
            when (cabinet.undoSessionAtomic(inventory, id)) {
                is UndoResult.Done -> _state.update { it.copy(undoing = false, undone = true) }
                is UndoResult.AlreadyUndone -> _state.update { it.copy(undoing = false, undone = true) }
                else -> _state.update { it.copy(undoing = false, error = "这次没能撤销，可能这条记录已经撤销过了") }
            }
        }
    }

    fun clearError() { _state.update { it.copy(error = null) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixSessionScreen(nav: NavHostController, vm: MixViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()

    /* 退出：保存成功后才提示「进度已保存」。标记写进**调用方的 entry**（发现页或配方详情），
       它们各自读自己的 entry，所以从哪儿进来，退回哪儿就能弹出来 */
    LaunchedEffect(s.exitSaved) {
        if (s.exitSaved) {
            nav.previousBackStackEntry?.savedStateHandle?.set(Routes.MIX_PROGRESS_SAVED, true)
            nav.popBackStack()
        }
    }

    /* 屏幕常亮（§6.3），离开页面时清除 */
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    /* 加载 → 内容用淡入淡出衔接 */
    Crossfade(s.loading, animationSpec = AmberMotion.med(), label = "mixLoading") { loading ->
        if (loading) {
            Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else {
            MixSessionBody(nav, vm, s)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixSessionBody(nav: NavHostController, vm: MixViewModel, s: MixState) {
    val recipe = s.recipe
    val draft = s.draft
    if (recipe == null || draft == null) {
        Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding().padding(24.dp)) {
            Text(s.error ?: "这次调酒恢复不了了", color = Fg)
            TextButton(onClick = { nav.popBackStack() }) { Text("返回", color = Accent) }
        }
        return
    }
    val step = recipe.steps.getOrNull(draft.currentStep) ?: return

    Scaffold(containerColor = Bg) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vm.exitAndSave() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Muted, modifier = Modifier.size(13.dp))
                    Text("存好进度，先退出去", color = Muted, fontSize = 12.sp)
                }
                Text("屏幕会一直亮着 · 随时退出，进度都在", color = Muted, fontSize = 12.sp)
            }
            /* 进度（切步时颜色平滑过渡） */
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                recipe.steps.forEachIndexed { i, _ ->
                    val dotColor by animateColorAsState(
                        when {
                            i < draft.currentStep -> Muted.copy(alpha = 0.42f)
                            i == draft.currentStep -> Accent
                            else -> Fg.copy(alpha = 0.10f)
                        },
                        AmberMotion.fast(), label = "stepDot"
                    )
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(999.dp))
                            .background(dotColor)
                    )
                }
            }

            /* 步骤内容：前进/后退带方向感的滑动切换（计时卡不进动画，避免干扰运行中的计时） */
            AnimatedContent(
                targetState = draft.currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally(AmberMotion.med()) { it / 2 } + fadeIn(AmberMotion.med()))
                            .togetherWith(slideOutHorizontally(AmberMotion.med()) { -it / 2 } + fadeOut(AmberMotion.med()))
                    } else {
                        (slideInHorizontally(AmberMotion.med()) { -it / 2 } + fadeIn(AmberMotion.med()))
                            .togetherWith(slideOutHorizontally(AmberMotion.med()) { it / 2 } + fadeOut(AmberMotion.med()))
                    }
                },
                label = "mixStep"
            ) { stepIndex ->
                val animStep = recipe.steps.getOrNull(stepIndex)
                if (animStep != null) {
                    Column {
                        Text("第 " + (stepIndex + 1) + " 步 / 共 " + recipe.steps.size + " 步 · " + recipe.zh + " × " + draft.servings, color = Accent, fontSize = 12.5.sp, modifier = Modifier.padding(top = 26.dp))
                        Text(animStep.title, fontSize = 28.sp, color = Fg, modifier = Modifier.padding(top = 10.dp))
                        Text(animStep.detail, color = Muted, fontSize = 15.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 12.dp))

                        /* 本步材料用量 */
                        if (animStep.needs.isNotEmpty()) {
                            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                animStep.needs.forEach { (ingId, qty, unit) ->
                                    Surface(color = Surface, shape = RoundedCornerShape(12.dp)) {
                                        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                            Text(s.ingredients[ingId]?.zh ?: ingId, fontSize = 13.sp, color = Fg)
                                            Spacer(Modifier.width(6.dp))
                                            MonoNum(if (qty > 0) Units.fmt(qty * draft.servings) + " " + unit else unit, size = 13, color = Accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            /* 计时器（以到期时间为基准，可恢复） */
            if (s.timerTotal > 0) {
                Card(
                    Modifier.fillMaxWidth().padding(top = 22.dp),
                    colors = CardDefaults.cardColors(containerColor = if (s.timerRunning) Raised else Surface)
                ) {
                    Column(Modifier.padding(20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(step.timerLabel ?: "建议 " + s.timerTotal + " 秒", color = Muted, fontSize = 12.sp)
                        MonoNum(
                            (s.timerLeft / 60).toString() + ":" + (s.timerLeft % 60).toString().padStart(2, '0'),
                            Modifier.padding(vertical = 16.dp), size = 46,
                            color = if (s.timerRunning) Accent else Fg
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { vm.resetTimer() }, shape = RoundedCornerShape(999.dp)) { Text("重置") }
                            Button(
                                onClick = { vm.toggleTimer() },
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk)
                            ) { Text(if (s.timerRunning) "暂停" else "开始计时") }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            /* 底部导航 */
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { vm.setStep(draft.currentStep - 1) },
                    enabled = draft.currentStep > 0,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("上一步") }
                Button(
                    onClick = {
                        if (draft.currentStep < recipe.steps.size - 1) vm.setStep(draft.currentStep + 1) else vm.openFinish()
                    },
                    modifier = Modifier.weight(1.6f).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(if (draft.currentStep < recipe.steps.size - 1) "下一步" else "完成调制") }
            }
        }
    }

    /* 提交/保存失败等运行时错误：统一弹窗（恢复失败的场景已在上方内联展示并 return） */
    MessageDialog(s.error, vm::clearError)

    /* 完成确认弹层（§6.3）：展示的计划与实际提交严格一致 */
    if (s.finishOpen) {
        ModalBottomSheet(onDismissRequest = { if (!s.submitting) vm.closeFinish() }, containerColor = Raised) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
                if (s.doneSession == null) {
                    Text("调好了，核对一下扣减", style = MaterialTheme.typography.titleLarge)
                    Text("所有材料一起扣；只要有一样不够，就一样都不扣", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("实际调了几杯", fontSize = 14.sp, color = Fg)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { vm.setActual(-1) }, enabled = s.actual > 1 && !s.submitting) { Text("−") }
                                MonoNum(s.actual.toString(), Modifier.padding(horizontal = 12.dp), size = 20)
                                OutlinedButton(onClick = { vm.setActual(1) }, enabled = !s.submitting) { Text("+") }
                            }
                        }
                    }
                    s.plan?.lines?.forEach { line ->
                        line.picks.forEachIndexed { i, (bottle, qty) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(
                                    bottle.brand + (if (line.via != null) "（换的材料）" else "") +
                                        /* 这一瓶确实被扣到见底才标「用完」 */
                                        (if (bottle.remaining - qty <= Qty.EPS) "（用完）" else "") +
                                        (if (i > 0) "（换下一瓶）" else "") +
                                        " · 剩 " + Units.fmt(bottle.remaining - qty) + " " + bottle.unit,
                                    fontSize = 14.sp, color = Fg
                                )
                                MonoNum("−" + Units.fmt(qty) + " " + bottle.unit, color = StMiss)
                            }
                            HorizontalDivider(color = Fg.copy(alpha = 0.06f))
                        }
                    }
                    s.plan?.skipped?.forEach { sk ->
                        Text("已跳过：" + sk.name + "（" + sk.reason + "）", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    val plan = s.plan
                    if (plan != null && !plan.ok) {
                        Text(
                            "现在库存不够：" + plan.problems.joinToString("；") + "。不会扣任何东西，少调几杯或回去调整一下。",
                            color = StMiss, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    if (s.planChangedNotice) {
                        Text("库存刚有变化，用量重新算过了，再确认一次。", color = StSub, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Button(
                        onClick = { vm.confirm() },
                        enabled = s.plan?.ok == true && !s.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (s.submitting) "正在扣减…" else "确认，扣掉用量并记下这杯") }
                } else {
                    val done = s.doneSession ?: return@ModalBottomSheet
                    Column(Modifier.fillMaxWidth()) {
                        /* 完成庆祝：酒液从 0 倒满（一次性确认反馈，非循环动画） */
                        var poured by remember(done.id) { mutableStateOf(false) }
                        LaunchedEffect(done.id) { poured = true }
                        val celebrationFill by animateFloatAsState(if (poured) 1f else 0f, AmberMotion.slow(), label = "celebration")
                        GlassPour(
                            done.glass.ifBlank { recipe.glass },
                            done.liquid.ifBlank { recipe.liquid },
                            Modifier.size(88.dp, 108.dp).align(Alignment.CenterHorizontally),
                            fill = celebrationFill
                        )
                        Text(
                            if (s.undone) "这杯的扣减已撤销" else "库存已更新",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)
                        )
                        Text(
                            if (s.undone) "扣掉的量已经加回去了，这杯的记录还在"
                            else recipe.zh + " × " + done.servings + " 杯 · 已记下这杯，库存也扣好了",
                            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                        /* 实际扣减与剩余量 */
                        s.donePlan?.lines?.forEach { line ->
                            line.picks.forEach { (bottle, qty) ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(bottle.brand, fontSize = 13.sp, color = Fg)
                                    MonoNum(
                                        "−" + Units.fmt(qty) + " → 剩 " + Units.fmt(bottle.remaining - qty) + " " + bottle.unit,
                                        size = 12, color = Muted
                                    )
                                }
                                /* 扣完后见底的瓶：琥珀色提示，点一下直接去这瓶的详情盘点 */
                                if (bottle.copy(remaining = bottle.remaining - qty).isLowStock()) {
                                    Text(
                                        bottle.brand + " 快见底了 · 去盘点",
                                        color = StSub, fontSize = 12.sp,
                                        modifier = Modifier
                                            .clickable { vm.closeFinish(); nav.navigate(Routes.bottle(bottle.id)) }
                                            .padding(bottom = 4.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { nav.navigate(Routes.note(done.id)) },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = AccentInk),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("给这杯打个分、写两句") }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (!s.undone) {
                                TextButton(onClick = { vm.undo() }, enabled = !s.undoing) {
                                    Text(if (s.undoing) "正在撤销…" else "撤销这杯", color = Muted)
                                }
                            }
                            TextButton(onClick = { nav.popBackStack(Routes.DISCOVER, false) }) { Text("完成", color = Accent) }
                        }
                    }
                }
            }
        }
    }
}
