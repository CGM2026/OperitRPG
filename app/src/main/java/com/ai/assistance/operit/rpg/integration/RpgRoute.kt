package com.ai.assistance.operit.rpg.integration

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.rpg.backup.RpgBackupService
import com.ai.assistance.operit.rpg.core.*
import com.ai.assistance.operit.rpg.data.*
import com.ai.assistance.operit.rpg.model.*
import com.ai.assistance.operit.rpg.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

@Composable
fun RpgRoute(onOpenModelSettings: () -> Unit) {
    val controller: RpgViewModel = viewModel()
    RpgScreen(controller, onOpenModelSettings)
}

class RpgViewModel(application: Application) : AndroidViewModel(application), RpgUiController {
    private val app = application
    private val manager = ModelConfigManager(app)
    private val store = RoomGameStore(RpgDatabase.get(app))
    private val gateway = SelectedModelGateway(OperitModelGateway(app, manager))
    private val engine = TurnCoordinator(store, gateway)
    private val backups = RpgBackupService(store, app.cacheDir)
    private val preferences = app.getSharedPreferences("rpg-ui", 0)
    @Volatile private var selectedId: String? = preferences.getString("campaign", null)
    private val mutable = MutableStateFlow(RpgUiState())
    override val state: StateFlow<RpgUiState> = mutable.asStateFlow()
    private var operation: Job? = null
    private var imported: CampaignRecord? = null
    @Volatile private var configurations = emptyMap<String, ModelConfiguration>()

    init {
        viewModelScope.launch {
            try {
                // Only orphaned requests from a previous process are recovered here; no cloud request starts.
                withContext(Dispatchers.IO) { store.list().forEach { engine.recover(it.id) } }
                load()
                store.changes.collect { load() }
            } catch (e: Exception) { if (e is CancellationException) throw e; message("无法读取存档，请保留原数据并重试。") }
        }
        viewModelScope.launch { manager.configSummariesFlow.collect { loadModels() } }
    }

    override fun refresh() { viewModelScope.launch { safely { loadModels(); load() } } }
    override fun openCampaign(id: String) {
        selectedId = id; preferences.edit().putString("campaign", id).apply()
        viewModelScope.launch { safely { load() } }
    }
    override fun closeCampaign() { selectedId = null; preferences.edit().remove("campaign").apply(); mutable.update { it.copy(campaign = null) } }
    override fun createCampaign(title: String, playerName: String, modelId: String) = launchOperation {
        val config = configurations[modelId] ?: error("请先选择模型")
        selectedId = engine.createCampaign(title.trim(), playerName.trim(), config)
        preferences.edit().putString("campaign", selectedId).apply()
    }
    override fun submit(text: String) = launchOperation {
        val view = current()
        require(view.model.providerId != "UNBOUND") { "请先选择模型" }
        showOutcome(engine.submit(view.id, UUID.randomUUID().toString(), view.revision, text))
    }
    override fun confirm(turnId: String) = launchOperation {
        val view = current(); val turn = view.turns.single { it.id == turnId }
        showOutcome(engine.confirmPlan(view.id, turnId, requireNotNull(turn.planHash)))
    }
    override fun retry(turnId: String) = launchOperation {
        val view = current(); val turn = view.turns.single { it.id == turnId }
        showOutcome(engine.retryStage(view.id, turnId, turn.stage))
    }
    override fun cancel(turnId: String) = launchOperation { showOutcome(engine.cancelUnresolved(current().id, turnId)) }
    override fun stop() {
        val id = selectedId ?: return
        viewModelScope.launch {
            safely {
                val pending = engine.publicView(id).turns.lastOrNull { it.stage !in terminal }
                pending?.let { engine.cancelUnresolved(id, it.id) }
                operation?.cancelAndJoin(); operation = null
                mutable.update { it.copy(busy = false) }; load()
            }
        }
    }
    override fun useBandage() = action("use.bandage")
    override fun move(locationId: String) {
        val from = mutable.value.campaign?.locations?.firstOrNull { it.id == locationId }
        if (from == null || !from.actionEnabled) return
        launchOperation {
            val view = current()
            require(view.model.providerId != "UNBOUND") { "请先选择模型" }
            showOutcome(engine.submitAction(view.id, UUID.randomUUID().toString(), view.revision, "move.${view.location.id}_$locationId"))
        }
    }
    private fun action(actionId: String) = launchOperation {
        val view = current()
        require(view.model.providerId != "UNBOUND") { "请先选择模型" }
        showOutcome(engine.submitAction(view.id, UUID.randomUUID().toString(), view.revision, actionId))
    }
    override fun selectModel(modelId: String) = launchOperation {
        val config = configurations[modelId] ?: error("模型配置不可用")
        val view = current()
        val pending = view.turns.lastOrNull { it.stage !in terminal }
        if (pending?.result != null && pending.errorCode != null) {
            check(engine.rebindInterruptedNarration(view.id, pending.id, config)) { "当前请求尚在进行" }
            message("已为中断的叙述选择模型。返回游玩并点击继续叙述，检定结果保持不变。")
        } else check(engine.switchModel(view.id, config)) { "请先完成当前回合" }
    }
    override fun previewImport(uri: Uri) = launchOperation {
        val record = try { withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openInputStream(uri)).use { backups.preview(it) }
        } } catch (e: IllegalArgumentException) {
            message(e.message ?: "存档格式不符合要求。")
            return@launchOperation
        }
        imported = record
        mutable.update { it.copy(importPreview = RpgImportPreview(record.title, record.state.playerName, "将创建独立战役，保留 ${record.turns.size} 条记录；导入后重新选择模型。")) }
    }
    override fun confirmImport() = launchOperation {
        selectedId = backups.importAsNew(requireNotNull(imported)); imported = null
        preferences.edit().putString("campaign", selectedId).apply()
        mutable.update { it.copy(importPreview = null, message = "存档已导入，请重新选择模型。") }
    }
    override fun dismissImport() { imported = null; mutable.update { it.copy(importPreview = null) } }
    override fun export(uri: Uri) = launchOperation {
        val id = current().id
        withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { backups.export(id, it) } }
        message("完整存档已导出。文件包含模组信息，请自行保管。")
    }
    override fun dismissMessage() { mutable.update { it.copy(message = null) } }

    private suspend fun current() = engine.publicView(requireNotNull(selectedId) { "请先选择战役" })
    private fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        mutable.update { it.copy(busy = true, message = null) }
        operation = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(readableError(e)) }
            finally { withContext(NonCancellable) { mutable.update { it.copy(busy = false) }; safely { load() } } }
        }
    }
    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message(readableError(e)) }
    }
    private fun readableError(e: Exception): String = when (e) {
        is IllegalArgumentException -> "内容或存档格式不符合要求，请检查后重试。"
        is java.io.IOException -> "文件或网络操作未完成，请检查连接与文件后重试。"
        else -> "操作未完成，已有存档保留。请检查配置后重试。"
    }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }
    private fun showOutcome(result: TurnOutcome) {
        when (result.code) {
            OutcomeCode.FAILED, OutcomeCode.INVALID_PLAN -> message("本轮未完成，请查看回合提示。已保存的结果不会重掷。")
            OutcomeCode.BUSY -> message("请先完成当前回合。")
            OutcomeCode.STALE_REVISION -> message("存档状态已更新，请查看当前状态后再行动。")
            else -> Unit
        }
    }
    private suspend fun loadModels() {
        val options = mutableListOf(RpgModelOption("demo", "离线演示", "固定演示主持，用于验收流程，不调用云端模型", true))
        val map = mutableMapOf("demo" to ModelConfiguration("demo", "DEMO", "script-v1", "demo-v1"))
        withContext(Dispatchers.IO) {
            manager.getAllConfigSummaries().forEach { summary ->
                val config = manager.getModelConfig(summary.id) ?: return@forEach
                if (ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) == null) return@forEach
                if (config.apiProviderType in setOf(ApiProviderType.MNN, ApiProviderType.LLAMA_CPP)) return@forEach
                getModelList(config.modelName).forEachIndexed { index, model ->
                    val key = "${config.id}:$index"
                    map[key] = ModelConfiguration(config.id, config.apiProviderTypeId, model, configurationFingerprint(config, model))
                    options += RpgModelOption(key, config.name, "${config.apiProviderTypeId} · $model")
                }
            }
        }
        configurations = map
        mutable.update { it.copy(models = options) }
    }
    private suspend fun load() {
        val records = withContext(Dispatchers.IO) { store.list() }
        val id = selectedId
        val play = records.firstOrNull { it.id == id }?.let { adapt(RainyDock.publicView(it)) }
        mutable.update { it.copy(campaigns = records.map { r ->
            RpgCampaignSummary(r.id, r.title, "${r.state.playerName} · 时间 ${r.state.tick}/12", r.state.status != CampaignStatus.ACTIVE)
        }, campaign = if (selectedId == id) play else it.campaign) }
    }
    private fun adapt(v: PublicCampaignView): RpgPlayState {
        val pending = v.turns.lastOrNull { it.stage !in terminal }
        val ending = when (v.status) {
            CampaignStatus.ACTIVE -> null
            CampaignStatus.SUCCESS -> "你找到了阿远，并帮助他安全离开旧码头。"
            CampaignStatus.FAILED_DEPARTED -> "潮水已经漫过通道。你未能在期限内完成救援。"
            CampaignStatus.FAILED_INCAPACITATED -> "你的生命耗尽，已经无法继续行动。"
        }
        return RpgPlayState(v.id, v.title, v.playerName, v.location.name, v.hp, v.maxHp, "时间 ${v.tick}/12",
            if (v.model.providerId == "UNBOUND") null else configurations.entries.firstOrNull { it.value == v.model }?.key,
            turns = v.turns.map { turn ->
                val dice = turn.result?.dice
                RpgTurnUi(turn.id, turn.inputText, turn.narration.orEmpty(), stageText(turn.stage),
                    checkTitle = turn.result?.let { if (dice == null) "行动结果" else if (dice.success) "检定成功" else "检定失败" },
                    checkDetail = buildString {
                        if (dice != null) append("d100：${dice.roll} / 阈值 ${dice.threshold}\n")
                        turn.result?.let { append(it.publicSummary); append("\n时间 +${it.timeCost}，生命 ${if (it.hpChange > 0) "+" else ""}${it.hpChange}") }
                    }, question = turn.question,
                    confirmation = if (turn.stage == TurnStage.NEEDS_CONFIRMATION) turn.publicIntent else null,
                    error = turn.errorCode?.let { if (turn.result != null) "结果已保存，叙述未完成。继续时不会重新掷骰。" else "行动尚未执行，时间没有推进。可重试或取消。" },
                    canRetry = turn.stage !in terminal && turn.stage != TurnStage.NEEDS_CONFIRMATION && (turn.errorCode != null || turn.stage in setOf(TurnStage.RECEIVED, TurnStage.PREPARED, TurnStage.RESOLVED)),
                    canCancel = turn.stage !in terminal && turn.result == null)
            }, inventory = v.inventory.map { (key, count) -> RpgDetail(key, if (key == "bandage") "绷带 ×$count" else key, "恢复最多 2 点生命；使用消耗 1 份。") },
            clues = v.clues.map { RpgDetail(it.id, it.title, it.content, it.sourceTurnId) },
            locations = v.adjacentLocations.map { RpgDetail(it.id, it.name, it.description, actionEnabled = it.accessible) },
            facts = v.facts.values.toList(), canAct = pending == null,
            canUseBandage = pending == null && v.allowedActions.any { it.id == "use.bandage" },
            bandageReason = if ((v.inventory["bandage"] ?: 0) == 0) "已用完" else if (v.hp == v.maxHp) "生命已满" else "本轮完成后可使用",
            ending = ending,
            pendingNotice = pending?.let { if (it.result != null) "本轮结果已保存，请完成叙述后继续。" else "本轮尚未完成。" },
            canRebindNarration = pending?.let { it.result != null && it.errorCode != null } == true)
    }
    private fun stageText(stage: TurnStage) = when (stage) {
        TurnStage.RECEIVED, TurnStage.PLANNING -> "正在理解行动"
        TurnStage.NEEDS_CONFIRMATION -> "等待确认"
        TurnStage.PREPARED -> "等待结算"
        TurnStage.RESOLVED, TurnStage.NARRATING -> "正在生成叙述"
        TurnStage.COMPLETE -> ""
        TurnStage.CANCELLED -> "已取消，未执行行动"
    }
    private companion object { val terminal = setOf(TurnStage.COMPLETE, TurnStage.CANCELLED) }
}
