package com.ai.assistance.operit.rpg.agent.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.clockText
import com.ai.assistance.operit.rpg.agent.data.*
import com.ai.assistance.operit.rpg.agent.engine.AgentCoordinator
import com.ai.assistance.operit.rpg.agent.engine.DemoTextModelPort
import com.ai.assistance.operit.rpg.agent.memory.PublicProjection
import com.ai.assistance.operit.rpg.agent.module.ModuleZipImporter
import com.ai.assistance.operit.rpg.agent.module.ModuleValidator
import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import com.ai.assistance.operit.rpg.data.RpgDatabase
import com.ai.assistance.operit.rpg.model.AgentTextModelPort
import com.ai.assistance.operit.rpg.model.AgentDraftService
import com.ai.assistance.operit.rpg.model.DraftDocument
import com.ai.assistance.operit.rpg.model.RpgModelConfigStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.UUID

@Composable
fun D20AgentRoute(onOpenLegacy: (String?) -> Unit, onModelSettings: () -> Unit) {
    val controller: AgentViewModel = viewModel()
    AgentScreen(controller, onOpenLegacy, onModelSettings)
}

/** Keeps Android documents and jobs out of the composables. All disk work runs on IO. */
class AgentViewModel(application: Application) : AndroidViewModel(application), AgentUiController {
    private val app = application
    private val db = AgentDatabase.get(app)
    private val store = RoomAgentStore(db)
    private val catalog = RoomModuleCatalog(db)
    private val rules = RoomRuleRepository(db)
    private val memories = RoomMemoryRepository(db)
    private val documents = ReferenceDocumentService(db)
    private val backups = AgentBackupService(db, app.cacheDir)
    private val modelConfigs = RpgModelConfigStore.get(app)
    private val preferences = app.getSharedPreferences("rpg-agent-ui", 0)
    private val mutable = MutableStateFlow(AgentUiState())
    override val state: StateFlow<AgentUiState> = mutable.asStateFlow()
    @Volatile private var selectedId: String? = preferences.getString("campaign", null)
    private var operation: Job? = null
    private var activePorts: List<TextModelPort> = emptyList()
    private val loadedPages = java.util.concurrent.ConcurrentHashMap<String, List<Turn>>()
    private val olderPages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var entryLimit = 50
    private var pendingModule: ValidatedModule? = null
    private var pendingDocument: Pair<String, String>? = null
    private var pendingBackup: File? = null
    private var validatedDraft: ValidatedModule? = null
    private val importer = ModuleZipImporter()
    private val draftFile = File(app.filesDir, "rpg-agent-module-draft.json")
    private val exampleModules by lazy { ExampleModules.all() }
    private val random = SecureRandom()

    init {
        viewModelScope.launch {
            safely {
                withContext(Dispatchers.IO) {
                    selectedId?.let { id -> store.loadCampaign(id)?.let { coordinator(it).interrupt(id) } }
                    load()
                }
            }
        }
        viewModelScope.launch { modelConfigs.configurations.collect { refresh() } }
    }
    private fun coordinator(c: Campaign): AgentCoordinator = coordinator(c.modelFingerprint, c.narratorFingerprint, c.module)
    private fun coordinator(plannerFingerprint: String, narratorFingerprint: String, module: VersionRef): AgentCoordinator {
        fun port(fingerprint: String): TextModelPort = if (fingerprint == DEMO_FINGERPRINT) DemoTextModelPort(catalog.get(module)) else AgentTextModelPort(app, fingerprint)
        val planner = port(plannerFingerprint); val narrator = port(narratorFingerprint)
        activePorts = listOf(planner, narrator)
        return AgentCoordinator(store, catalog, rules, memories, planner, narrator, DiceSource { sides -> random.nextInt(sides) + 1 }, Clock { System.currentTimeMillis() })
    }
    override fun refresh() { viewModelScope.launch { safely { withContext(Dispatchers.IO) { load() } } } }
    override fun loadMoreEntries() { entryLimit += 50; refresh() }
    override fun open(id: String) = execute("读取战役") {
        val c = requireNotNull(store.loadCampaign(id)) { "CAMPAIGN_NOT_FOUND" }
        selectedId = id; loadedPages.remove(id); olderPages.remove(id)
        mutable.update { it.copy(notes = emptyList(), evidence = emptyList(), notices = emptyList()) }
        preferences.edit().putString("campaign", id).apply()
        coordinator(c).interrupt(id)
    }
    override fun close() {
        if (mutable.value.busy) return
        selectedId = null; preferences.edit().remove("campaign").apply()
        mutable.update { it.copy(play = null, notes = emptyList(), evidence = emptyList(), notices = emptyList()) }
    }
    override fun create(title: String, module: VersionRef, characterId: String, allowGeneration: Boolean, plannerModelId: String, narratorModelId: String) = execute("建立战役") {
        val planner = modelOption(plannerModelId); val narrator = modelOption(narratorModelId)
        val id = UUID.randomUUID().toString()
        coordinator(planner.fingerprint, narrator.fingerprint, module).create(id, title, module, characterId, allowGeneration, planner.fingerprint, narrator.fingerprint)
        selectedId = id; loadedPages.remove(id); olderPages.remove(id); preferences.edit().putString("campaign", id).apply()
        mutable.update { it.copy(notes = emptyList(), evidence = emptyList(), notices = emptyList()) }
    }
    override fun submit(text: String) {
        if (text.startsWith(TABLE_COMMAND_PREFIX)) { tableCommand(text); return }
        execute("正在理解行动", poll = true) {
            val c = current(); coordinator(c).submit(c.id, UUID.randomUUID().toString(), text)
        }
    }
    /** `//` input never becomes a turn or reaches a model; the reply is a local notice and is not written to the event stream. */
    private fun tableCommand(text: String) = execute("场外指令") {
        val c = current(); val levels = catalog.get(c.module).rules.contentLevels
        val name = text.removePrefix(TABLE_COMMAND_PREFIX).trim()
        val reply = when {
            name !in TABLE_COMMANDS -> "可用场外指令：" + TABLE_COMMANDS.joinToString("　") { TABLE_COMMAND_PREFIX + it }
            levels.isEmpty() -> "本模组未声明内容分档"
            name == "停" -> { coordinator(c).holdScene(c.id); "下一段旁白将收束当前场景。" }
            else -> { val changed = coordinator(c).adjustContentLevel(c.id, if (name == "降档") -1 else 1); "内容分档：${levels[changed.contentLevel].label}" }
        }
        mutable.update { it.copy(notices = it.notices + reply) }
    }
    override fun confirm(turnId: String) = execute("结算行动", poll = true) { val c = current(); coordinator(c).confirm(c.id, turnId) }
    override fun resume(turnId: String) = execute("继续本轮", poll = true) { val c = current(); coordinator(c).resume(c.id, turnId) }
    override fun cancel(turnId: String) = execute("取消行动") { val c = current(); coordinator(c).cancel(c.id, turnId) }
    override fun stop() {
        val running = operation ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                selectedId?.let { id -> store.history(id, null, 1).firstOrNull()?.attempts?.filter { it.status == "RUNNING" }?.forEach { attempt -> activePorts.forEach { it.cancel(attempt.id) } } }
            }
            running.cancelAndJoin()
            safely { withContext(Dispatchers.IO) {
                selectedId?.let { id -> store.loadCampaign(id)?.let { coordinator(it).interrupt(id) } }
                load()
            } }
            mutable.update { it.copy(busy = false, progress = null, message = "已停止。已提交的结果保留，可明确继续本轮。") }
        }
    }
    override fun loadOlder() = execute("读取更早记录") {
        val c = current()
        val loaded = loadedPages[c.id].orEmpty()
        val older = store.history(c.id, loaded.minOfOrNull { it.ordinal }, 30)
        loadedPages[c.id] = (loaded + older).associateBy { it.id }.values.sortedBy { it.ordinal }
        olderPages[c.id] = older.size == 30
    }
    override fun bindPlanner(modelId: String) = execute("绑定规划模型") {
        val c = current(); coordinator(c).rebindPlanner(c.id, modelOption(modelId).fingerprint)
    }
    override fun bindNarrator(modelId: String) = execute("绑定旁白模型") {
        val c = current(); val fingerprint = modelOption(modelId).fingerprint
        val pending = store.history(c.id, null, 1).firstOrNull { it.phase !in terminal }
        val engine = coordinator(c)
        if (pending?.result != null && pending.error != null) {
            engine.rebindInterruptedNarration(c.id, pending.id, fingerprint)
            message("已为中断叙述绑定模型。点击继续本轮生成旁白，已保存结果保持不变。")
        } else engine.rebindNarrator(c.id, fingerprint)
    }
    override fun previewExample(hash: String) = execute("读取示例模组") {
        clearPending()
        val module = exampleModules.single { it.contentHash == hash }
        pendingModule = module
        mutable.update { it.copy(preview = AgentImportUi(module.manifest.title, "${module.manifest.version} · ${module.manifest.description}\n确认后安装；可选择离线流程演示或你配置的真实模型。", true)) }
    }
    override fun previewFile(uri: Uri) = execute("校验导入文件") {
        clearPending()
        val title = fileName(uri)
        app.contentResolver.openInputStream(uri).let { requireNotNull(it) { "FILE_UNAVAILABLE" } }.use { stream ->
            if (title.endsWith(".md", true) || title.endsWith(".txt", true) || title.endsWith(".markdown", true)) {
                val bytes = readLimited(stream, 5 * 1024 * 1024)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                require(text.isNotBlank()) { "EMPTY_DOCUMENT" }
                pendingDocument = title to text
                mutable.update { it.copy(preview = AgentImportUi(title, "参考资料 · ${text.length} 字\n原文仅保存为资料，不直接成为可执行模组。可在资料页主动选择模型整理成草稿。", false)) }
            } else {
                val module = importer.validate(stream)
                pendingModule = module
                val m = module.manifest
                mutable.update { it.copy(preview = AgentImportUi(m.title, "${m.version} · ${m.author}\n${m.description}\n规则：${m.rulesetId} ${m.rulesetVersion}\n可用于开团。详细主持内容不会在此预览公开。", true)) }
            }
        }
    }
    override fun commitImport() = execute("保存导入内容") {
        when {
            pendingModule != null -> catalog.install(requireNotNull(pendingModule))
            pendingDocument != null -> { val (title, text) = requireNotNull(pendingDocument); documents.importText(title, text, Visibility(Audience.PUBLIC)) }
            pendingBackup != null -> requireNotNull(pendingBackup).inputStream().use { stream ->
                val ref = backups.importBackup(stream); selectedId = ref.campaignId; loadedPages.remove(ref.campaignId); olderPages.remove(ref.campaignId); preferences.edit().putString("campaign", selectedId).apply()
                mutable.update { it.copy(notes = emptyList(), evidence = emptyList()) }
            }
            else -> error("IMPORT_NOT_SELECTED")
        }
        clearPending(); mutable.update { it.copy(preview = null, message = "已保存。") }
    }
    override fun dismissPreview() { clearPending(); mutable.update { it.copy(preview = null) } }
    private fun clearPending() { pendingModule = null; pendingDocument = null; pendingBackup?.delete(); pendingBackup = null }
    override fun importBackup(uri: Uri) = execute("校验长团备份") {
        clearPending()
        val file = File.createTempFile("agent-backup-", ".zip", app.cacheDir)
        try {
            requireNotNull(app.contentResolver.openInputStream(uri)).use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(8192); var count = 0L
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break; count += n; require(count <= 100L * 1024 * 1024) { "BACKUP_TOO_LARGE" }; output.write(buffer, 0, n) }
            } }
            val title = file.inputStream().use { backups.preview(it).title }
            pendingBackup = file
            mutable.update { it.copy(preview = AgentImportUi(title, "导入为独立长团战役，恢复模组、事件与记事；导入后需重新绑定本机模型。", true)) }
        } catch (e: Exception) { file.delete(); throw e }
    }
    override fun exportBackup(uri: Uri) = execute("准备一致备份，暂时暂停本团写入") {
        val c = current()
        val job = currentCoroutineContext().job
        requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { target ->
            val output = object : java.io.OutputStream() {
                override fun write(value: Int) { job.ensureActive(); target.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) { job.ensureActive(); target.write(bytes, offset, length) }
                override fun flush() = target.flush()
            }
            backups.export(c.id, output)
        }
        message("完整备份已导出。")
    }
    override fun searchRules(query: String) = execute("查阅规则") {
        val c = current(); val found = rules.search(RoleContext(c.id, Audience.PUBLIC), c.module, query, 5)
        mutable.update { it.copy(evidence = found, message = if (found.isEmpty()) "没有找到可公开的规则依据。" else null) }
    }
    override fun loadNotes() = execute("读取记事") { mutable.update { it.copy(notes = memories.list(RoleContext(current().id, Audience.PUBLIC), null, 100)) } }
    override fun correctNote(id: String, text: String) = execute("保存更正") {
        val role = RoleContext(current().id, Audience.PUBLIC); memories.correct(role, id, text)
        mutable.update { it.copy(notes = memories.list(role, null, 100), message = "已保存更正，原条目保留。") }
    }
    override fun readNoteSource(note: MemoryItem) = execute("读取来源") {
        val c = current(); require(note.campaignId == c.id)
        val sourceTurnId = note.sourceTurnId
        if (sourceTurnId != null) {
            val turn = requireNotNull(store.loadTurn(c.id, sourceTurnId)) { "SOURCE_TURN_NOT_FOUND" }
            val text = "${turn.playerText}\n\n${turn.narration.orEmpty()}"
            mutable.update { it.copy(documentText = (if (note.kind == "CONVERSATION") "对话回合" else "记事来源") to text) }
            return@execute
        }
        val events = store.events(c.id, (note.fromSeq - 1).coerceAtLeast(0), 100).filter { it.seq <= note.toSeq && it.visibility.audience == Audience.PUBLIC }
        val turns = events.mapNotNull { store.loadTurn(c.id, it.turnId) }.distinctBy { it.id }
        val text = turns.joinToString("\n\n") { "${it.playerText}\n${it.result?.publicSummary.orEmpty()}\n${it.narration.orEmpty()}" }.ifEmpty { "此来源没有可公开的回合正文。" }
        mutable.update { it.copy(documentText = "记事来源" to text) }
    }
    override fun readCorrectionHistory(id: String) = execute("读取更正记录") {
        val chain = memories.correctionHistory(RoleContext(current().id, Audience.PUBLIC), id)
        val text = chain.joinToString("\n\n") { "${if (it.status == "ACTIVE") "当前内容" else "先前内容"}\n${it.text}\n${if (it.kind == "CONVERSATION") "对话回合" else "来源事件 ${it.fromSeq}–${it.toSeq}"}" }
        mutable.update { it.copy(documentText = "更正记录" to text) }
    }
    override fun organize(documentIds: List<String>, modelId: String, callLimit: Int) = execute("模型正在整理草稿") {
        require(callLimit in 1..4 && documentIds.isNotEmpty())
        val model = modelOption(modelId)
        require(model.fingerprint != DEMO_FINGERPRINT) { "REAL_MODEL_REQUIRED_FOR_DRAFT" }
        val port = AgentTextModelPort(app, model.fingerprint); activePorts = listOf(port)
        val selected = documentIds.map { id -> val doc = documents.list().single { it.id == id }; DraftDocument(doc.id, doc.title, documents.read(id)) }
        require(selected.sumOf { it.text.length + it.title.length } <= 6000) { "DRAFT_SOURCE_TOO_LARGE" }
        val result = AgentDraftService(port).organize(selected, callLimit)
        validateDraftContent(result)
    }
    override fun validateDraft(json: String) = execute("校验草稿") { validateDraftContent(json) }
    private fun validateDraftContent(content: String) {
        require(content.toByteArray(Charsets.UTF_8).size <= 300 * 1024) { "DRAFT_TOO_LARGE" }
        draftFile.writeText(content, Charsets.UTF_8)
        validatedDraft = null
        val issues = try { validatedDraft = ModuleValidator.validateDraft(content); emptyList() } catch (e: Exception) {
            val detail = if (e is kotlinx.serialization.SerializationException) e.message?.substringBefore("JSON input:")?.take(600).orEmpty() else safeValidationMessage(e)
            listOf("草稿尚不能激活：$detail")
        }
        mutable.update { it.copy(draft = AgentDraftUi(validatedDraft?.manifest?.title ?: "模组草稿", content, issues)) }
    }
    override fun activateDraft() = execute("激活模组") { catalog.install(requireNotNull(validatedDraft)); draftFile.delete(); validatedDraft = null; mutable.update { it.copy(draft = null, message = "模组已激活，可用于新建战役。") } }
    override fun reopenDraft() = execute("读取草稿") { validateDraftContent(draftFile.readText(Charsets.UTF_8)) }
    override fun readDocument(id: String) = execute("读取原文") {
        val doc = documents.list().single { it.id == id }; mutable.update { it.copy(documentText = doc.title to documents.read(id)) }
    }
    override fun attachDocuments(campaignId: String, documentIds: List<String>) = execute("添加战役资料") {
        require(documentIds.isNotEmpty())
        requireNotNull(store.loadCampaign(campaignId))
        documentIds.forEach { documents.attach(campaignId, it) }
        message("所选原文已添加为本团参考资料，不改变已采用的可执行规则。")
    }
    override fun dismissDocument() { mutable.update { it.copy(documentText = null) } }
    override fun dismissDraft() { mutable.update { it.copy(draft = null) } }
    override fun dismissMessage() { mutable.update { it.copy(message = null) } }
    private suspend fun modelOption(id: String): AgentModelOption = modelOptions().single { it.id == id }
    private suspend fun modelOptions(): List<AgentModelOption> = listOf(AgentModelOption("offline-demo", "离线流程演示 · 固定脚本，不调用云端模型", DEMO_FINGERPRINT)) + modelConfigs.list().flatMap { config ->
        if (config.protocol == null) emptyList() else config.modelNames.mapIndexed { i, model -> AgentModelOption("${config.id}:$i", "${config.name} · $model", config.snapshot(model).fingerprint) }
    }
    private fun current() = requireNotNull(store.loadCampaign(requireNotNull(selectedId) { "CAMPAIGN_NOT_SELECTED" }))
    private suspend fun load() {
        val legacy = RpgDatabase.get(app).campaigns().headers(entryLimit, 0).map { AgentEntry(CampaignRef("legacy-rainydock-v1", it.id), it.title, it.updatedAt) }
        val newer = mutableListOf<CampaignHeader>()
        var offset = 0
        while (offset < entryLimit) { val batch = store.listCampaigns(offset, minOf(500, entryLimit - offset)); newer += batch; if (batch.isEmpty()) break; offset += batch.size }
        val entries = newer.map { AgentEntry(it.ref, it.title, it.updatedAt) } + legacy
        val selectedAtLoad = selectedId
        val c = selectedAtLoad?.let(store::loadCampaign)
        val play = c?.let { campaign ->
            val module = catalog.get(campaign.module); val public = PublicProjection.campaign(campaign, module)
            val latest = store.history(campaign.id, null, 30)
            val loaded = loadedPages[campaign.id].orEmpty()
            if (loaded.isEmpty()) olderPages[campaign.id] = latest.size == 30
            val history = (loaded + latest).associateBy { it.id }.values.sortedBy { it.ordinal }
            loadedPages[campaign.id] = history
            val pending = history.lastOrNull { it.phase !in terminal }
            val places = listOf(public.location) + module.world.locations.filter { it.id in public.location.exits }
            AgentPlayUi(campaign.id, public.title, public.location.title,
                public.resources.entries.joinToString(" · ") { "${it.key} ${it.value}" } + " · 时间 ${public.gameTime} ${module.rules.timeUnit}", clockText(module.world.clock, public.gameTime), campaign.modelFingerprint, campaign.narratorFingerprint,
                module.rules.contentLevels.let { levels -> if (levels.isEmpty()) null else levels[campaign.contentLevel].label + if (campaign.sceneHold) " · 将收束场景" else "" },
                history.map(::adaptTurn), public.npcs.map { AgentPerson(it.id, it.name, if (it.status == "idle") "在场" else it.status) }, places.map { AgentPerson(it.id, it.title, it.description) },
                public.facts.map { it.text }, public.objectives.map { "${it.title} · ${when (it.status) { "active" -> "进行中"; "complete" -> "已完成"; else -> it.status }}" }, pending == null, olderPages[campaign.id] == true)
        }
        val models = modelOptions()
        val modules = catalog.list().map(::moduleOption)
        val referenceDocuments = documents.list().map { d -> AgentDocumentUi(d.id, d.title) }
        val samples = exampleModules.map(::moduleOption)
        mutable.update { if (selectedAtLoad != selectedId) it else it.copy(entries = entries.sortedByDescending { e -> e.updatedAt }, hasMoreEntries = newer.size == entryLimit || legacy.size == entryLimit, modules = modules, models = models, documents = referenceDocuments, play = play, hasSavedDraft = draftFile.isFile, sampleModules = samples,
            progress = if (it.busy) play?.turns?.lastOrNull()?.stage?.takeIf { text -> text.isNotBlank() } ?: it.progress else null) }
    }
    private fun adaptTurn(t: Turn): AgentTurnUi = AgentTurnUi(t.id, t.playerText, t.narration.orEmpty(), stageText(t), t.result?.let { r ->
        r.publicSummary + (r.dice?.let { "\nd100：${it.value} / ${it.threshold} · ${if (r.success) "成功" else "失败"}" } ?: "") + r.notices.joinToString("") { "\n征兆：$it" }
    }, if (t.phase == TurnPhase.AWAITING_CONFIRMATION) t.plan?.proposal?.publicSummary else null,
        t.error?.let { if (t.result != null) "结果已保存，叙述未完成。继续不会重新结算。" else errorText(it) },
        t.phase !in terminal && t.phase != TurnPhase.AWAITING_CONFIRMATION,
        t.phase !in terminal && t.result == null, PublicProjection.evidence(t.result?.evidence ?: t.plan?.evidence.orEmpty()))
    private fun stageText(t: Turn) = when (t.phase) {
        TurnPhase.RECEIVED -> "收到行动"
        TurnPhase.GATHERING -> if (t.tools.isEmpty()) "正在理解行动" else "已核对 ${t.tools.size} 项资料，正在规划"
        TurnPhase.PROPOSED, TurnPhase.READY -> "准备结算"
        TurnPhase.AWAITING_CONFIRMATION -> "等待确认"
        TurnPhase.COMMITTED -> "结果已保存"
        TurnPhase.NARRATING -> "正在生成叙述"
        TurnPhase.COMPLETE -> ""
        TurnPhase.CANCELLED -> "已取消"
    }
    private fun errorText(code: String) = when (code) {
        "INTERRUPTED" -> "请求已中断。点击继续或取消，应用不会自动重发。"
        "PLANNING_BUDGET", "TOOL_BUDGET", "TURN_TIME_BUDGET", "CONTEXT_BUDGET" -> "本轮达到执行预算，请缩小问题后明确重试。"
        "RULE_NOT_FOUND" -> "未找到可用规则，请补充依据或明确本团裁定。"
        "STALE_PROPOSAL" -> "战役状态已改变，当前方案需要重新规划。"
        else -> "本轮未完成，请检查模型配置与资料，明确继续或取消。"
    }
    private fun execute(progress: String, poll: Boolean = false, block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        mutable.update { it.copy(busy = true, progress = progress, message = null) }
        operation = viewModelScope.launch {
            val ticker = if (poll) launch { while (isActive) { delay(400); safely { withContext(Dispatchers.IO) { load() } } } } else null
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message(safeValidationMessage(e)) }
            finally { ticker?.cancel(); withContext(NonCancellable + Dispatchers.IO) { mutable.update { it.copy(busy = false, progress = null) }; safely { load() } } }
        }
    }
    private suspend fun safely(block: suspend () -> Unit) { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { message(safeValidationMessage(e)) } }
    private fun safeValidationMessage(e: Exception): String = when {
        e.message == "DRAFT_SOURCE_TOO_LARGE" -> "一次整理的资料与标题合计上限为 6000 字。请将需要的章节或节选保存为 MD/TXT 后导入，再明确整理。"
        e.message == "FINISH_OR_CANCEL_PENDING_TURN" -> "本团有中断回合，请先继续完成或取消，再导出备份。"
        e.message == "TURN_PENDING" -> "请先继续完成或取消当前回合，再更换模型。"
        e.message == "MODULE_HASH_MISMATCH" || e.message == "MODULE_VERSION_CONFLICT" -> "模组内容摘要或已安装版本冲突，请检查文件。原版本保留。"
        e is java.io.IOException -> "文件或网络读取未完成，请检查后重试。"
        e.message?.matches(Regex("[A-Z_0-9:. /-]{3,160}")) == true -> "操作未完成：${e.message}"
        e is IllegalArgumentException -> "资料字段或格式不符合要求，请检查后重新校验。"
        else -> "操作未完成，请检查配置与资料后重试。"
    }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }
    private fun fileName(uri: Uri): String = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        require(cursor.moveToFirst()) { "FILE_NAME_UNAVAILABLE" }; cursor.getString(0)
    } ?: error("FILE_NAME_UNAVAILABLE")
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "DOCUMENT_TOO_LARGE" }; output.write(buffer, 0, n) }
        return output.toByteArray()
    }
    private fun moduleOption(module: ValidatedModule) = AgentModuleOption(VersionRef(module.manifest.moduleId, module.manifest.version, module.contentHash), module.manifest.title, module.manifest.description, module.world.characters.map { character -> AgentCharacterOption(character.id, character.name, (character.skills + character.resources).entries.joinToString(" · ") { "${it.key} ${it.value}" }) })
    private companion object { val terminal = setOf(TurnPhase.COMPLETE, TurnPhase.CANCELLED); const val DEMO_FINGERPRINT = "explicit-demo-v1"
        const val TABLE_COMMAND_PREFIX = "//"
        val TABLE_COMMANDS = listOf("停", "降档", "升档") }
}
