package com.ai.assistance.operit.rpg.agent.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import com.ai.assistance.operit.rpg.agent.rules.Chapters
import com.ai.assistance.operit.rpg.domain.CampaignShelf
import com.ai.assistance.operit.rpg.export.CampaignRecap
import com.ai.assistance.operit.rpg.export.CampaignReport
import com.ai.assistance.operit.rpg.model.AgentTextModelPort
import com.ai.assistance.operit.rpg.model.RpgModelConfigStore
import com.ai.assistance.operit.rpg.ui.home.HomeFiles
import com.ai.assistance.operit.rpg.ui.home.HomeScreen
import com.ai.assistance.operit.rpg.ui.play.ChapterSummaries
import com.ai.assistance.operit.rpg.ui.play.projectCharacterSheet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.UUID

/**
 * [campaignId] opens that campaign directly; [newCampaign] starts the existing import/create flow.
 * Both return to the shelf home when the play/create shell is left.
 */
@Composable
fun D20AgentRoute(
    onModelSettings: () -> Unit,
    campaignId: String? = null,
    newCampaign: Boolean = false,
) {
    val controller: AgentViewModel = viewModel()
    var screen by rememberSaveable {
        mutableStateOf(if (campaignId != null || newCampaign) "agent" else "home")
    }
    var targetCampaign by rememberSaveable { mutableStateOf(campaignId) }
    var startPage by rememberSaveable { mutableStateOf(if (newCampaign) "create" else "play") }
    if (screen == "home") {
        HomeScreen(
            shelf = controller.campaignShelf,
            files = controller,
            onOpenCampaign = { id ->
                targetCampaign = id
                startPage = "play"
                screen = "agent"
            },
            onNewCampaign = {
                targetCampaign = null
                startPage = "create"
                screen = "agent"
            },
            onSettings = onModelSettings,
        )
    } else {
        AgentScreen(
            controller = controller,
            files = controller,
            onModelSettings = onModelSettings,
            startPage = startPage,
            campaignId = targetCampaign,
            onExitHome = {
                targetCampaign = null
                startPage = "create"
                screen = "home"
            },
        )
    }
}

/** Keeps Android documents and jobs out of the composables. All disk work runs on IO. */
class AgentViewModel(application: Application) : AndroidViewModel(application), AgentUiController, HomeFiles {
    private val app = application
    private val db = AgentDatabase.get(app)
    val campaignShelf: CampaignShelf = RoomCampaignShelf(db)
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
    private val importer = ModuleZipImporter()
    private val exampleModules by lazy { ExampleModules.all() }
    private val random = SecureRandom()
    /** Chapters whose summary was already requested; failures stay here so only an explicit retry calls the model again. */
    private val chapterSummaryRuns = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val chapterSummaryRequests = java.util.concurrent.ConcurrentHashMap<String, ChapterSummaries.Request>()

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
    private fun textModelPort(fingerprint: String, module: VersionRef): TextModelPort = if (fingerprint == DEMO_FINGERPRINT) DemoTextModelPort(catalog.get(module)) else AgentTextModelPort(app, fingerprint)
    private fun coordinator(plannerFingerprint: String, narratorFingerprint: String, module: VersionRef): AgentCoordinator {
        val planner = textModelPort(plannerFingerprint, module); val narrator = textModelPort(narratorFingerprint, module)
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
            else -> error("IMPORT_NOT_SELECTED")
        }
        clearPending(); mutable.update { it.copy(preview = null, message = "已保存。") }
    }
    override fun dismissPreview() { clearPending(); mutable.update { it.copy(preview = null) } }
    private fun clearPending() { pendingModule = null; pendingDocument = null }
    override fun importBackup(input: java.io.InputStream): String = backups.importBackup(input).campaignId
    override fun exportBackup(campaignId: String, output: java.io.OutputStream) = backups.export(campaignId, output)
    override fun exportReport(campaignId: String, output: java.io.OutputStream) {
        val campaign = requireNotNull(store.loadCampaign(campaignId)) { "CAMPAIGN_NOT_FOUND" }
        val module = catalog.get(campaign.module)
        val text = CampaignReport.markdown(
            campaign,
            module,
            characterName(campaign, module),
            allTurns(campaign.id),
            allEvents(campaign.id),
            allMemories(campaign.id),
            LocalDateTime.now(),
        )
        output.write(text.toByteArray(Charsets.UTF_8))
    }
    override fun exportRecap(campaignId: String, output: java.io.OutputStream) {
        val campaign = requireNotNull(store.loadCampaign(campaignId)) { "CAMPAIGN_NOT_FOUND" }
        val module = catalog.get(campaign.module)
        val initial = decode<WorldState>(db.dao().initial(campaignId).joinToString("") { it.text })
        val text = CampaignRecap.markdown(campaign, module, initial, allEvents(campaignId), LocalDateTime.now())
        output.write(text.toByteArray(Charsets.UTF_8))
    }
    override fun fork(campaignId: String, uptoSeq: Long) = execute("从章节另开一局") {
        val ref = backups.fork(campaignId, uptoSeq)
        mutable.update { it.copy(forkResult = ref.campaignId) }
    }
    override fun consumeForkResult() {
        mutable.update { it.copy(forkResult = null) }
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
    override fun dismissMessage() { mutable.update { it.copy(message = null) } }
    private suspend fun modelOption(id: String): AgentModelOption = modelOptions().single { it.id == id }
    private suspend fun modelOptions(): List<AgentModelOption> = listOf(AgentModelOption("offline-demo", "离线流程演示 · 固定脚本，不调用云端模型", DEMO_FINGERPRINT)) + modelConfigs.list().flatMap { config ->
        if (config.protocol == null) emptyList() else config.modelNames.mapIndexed { i, model -> AgentModelOption("${config.id}:$i", "${config.name} · $model", config.snapshot(model).fingerprint) }
    }
    private fun current() = requireNotNull(store.loadCampaign(requireNotNull(selectedId) { "CAMPAIGN_NOT_SELECTED" }))
    private suspend fun load() {
        val newer = mutableListOf<CampaignHeader>()
        var offset = 0
        while (offset < entryLimit) { val batch = store.listCampaigns(offset, minOf(500, entryLimit - offset)); newer += batch; if (batch.isEmpty()) break; offset += batch.size }
        val entries = newer.map { AgentEntry(it.ref, it.title, it.updatedAt) }
        val selectedAtLoad = selectedId
        val c = selectedAtLoad?.let(store::loadCampaign)
        var advancing: Triple<Campaign, ValidatedModule, Turn>? = null
        val play = c?.let { campaign ->
            val module = catalog.get(campaign.module); val public = PublicProjection.campaign(campaign, module)
            val latest = store.history(campaign.id, null, 30)
            val loaded = loadedPages[campaign.id].orEmpty()
            if (loaded.isEmpty()) olderPages[campaign.id] = latest.size == 30
            val history = (loaded + latest).associateBy { it.id }.values.sortedBy { it.ordinal }
            loadedPages[campaign.id] = history
            val pending = history.lastOrNull { it.phase !in terminal }
            val places = listOf(public.location) + module.world.locations.filter { it.id in public.location.exits }
            val player = public.d20Player
            val d20Rules = module.rules.d20
            val d20State = campaign.world.d20
            val sheet = if (player != null && d20Rules != null && d20State != null) projectCharacterSheet(player, d20Rules, d20State, public.gameTime, module.rules.timeUnit, public.items, public.facts, public.objectives) else null
            val summaryItems = memories.list(RoleContext(campaign.id, Audience.PUBLIC), null, 500).filter { it.kind == Chapters.SUMMARY_KIND && it.status == "ACTIVE" }
            val currentOrdinal = module.rules.chapters.indexOfFirst { it.id == campaign.chapterId } + 1
            val firstChapterSeq = if (campaign.lastEventSeq >= 1) 1L else null
            val chapterSeqs = chapterEventSeqs(campaign.id)
            val archive = module.rules.chapters.take(currentOrdinal).mapIndexed { index, chapter ->
                AgentChapterUi(
                    chapter.id,
                    chapter.title,
                    index + 1,
                    summaryItems.lastOrNull { it.entityIds.contains(chapter.id) }?.text,
                    if (index == 0) firstChapterSeq else chapterSeqs[chapter.id],
                )
            }
            history.lastOrNull { it.result?.chapters?.isNotEmpty() == true }?.let { turn -> advancing = Triple(campaign, module, turn) }
            AgentPlayUi(campaign.id, public.title, public.location.title,
                public.resources.entries.joinToString(" · ") { "${it.key} ${it.value}" } + " · 时间 ${public.gameTime} ${module.rules.timeUnit}", clockText(module.world.clock, public.gameTime), campaign.modelFingerprint, campaign.narratorFingerprint,
                module.rules.contentLevels.let { levels -> if (levels.isEmpty()) null else levels[campaign.contentLevel].label + if (campaign.sceneHold) " · 将收束场景" else "" },
                history.map(::adaptTurn), public.npcs.map { AgentPerson(it.id, it.name, if (it.status == "idle") "在场" else it.status) }, places.map { AgentPerson(it.id, it.title, it.description) },
                public.facts.map { it.text }, public.objectives.map { "${it.title} · ${when (it.status) { "active" -> "进行中"; "complete" -> "已完成"; else -> it.status }}" }, pending == null, olderPages[campaign.id] == true,
                player?.id, sheet, Chapters.currentTitle(campaign, module.rules), archive)
        }
        val models = modelOptions()
        val modules = catalog.list().map(::moduleOption)
        val referenceDocuments = documents.list().map { d -> AgentDocumentUi(d.id, d.title) }
        val samples = exampleModules.map(::moduleOption)
        mutable.update { if (selectedAtLoad != selectedId) it else it.copy(entries = entries.sortedByDescending { e -> e.updatedAt }, hasMoreEntries = newer.size == entryLimit, modules = modules, models = models, documents = referenceDocuments, play = play, sampleModules = samples,
            progress = if (it.busy) play?.turns?.lastOrNull()?.stage?.takeIf { text -> text.isNotBlank() } ?: it.progress else null) }
        if (advancing != null && !mutable.value.busy) {
            val (campaign, module, turn) = requireNotNull(advancing)
            scheduleChapterSummary(campaign, module, turn)
        }
    }
    /** Starts one summary run for the chapter the advancing turn just left; the run set blocks automatic retries. */
    private fun scheduleChapterSummary(campaign: Campaign, module: ValidatedModule, turn: Turn) {
        val firstMark = turn.result?.chapters?.firstOrNull() ?: return
        if (firstMark.ordinal <= 1) return
        val previous = module.rules.chapters.getOrNull(firstMark.ordinal - 2) ?: return
        if (!chapterSummaryRuns.add(previous.id)) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val role = RoleContext(campaign.id, Audience.PUBLIC)
                    val existing = memories.list(role, null, 500).any { it.kind == Chapters.SUMMARY_KIND && it.entityIds.contains(previous.id) }
                    if (!existing) {
                        val request = chapterSummaryRequest(campaign, module, requireNotNull(turn.result), previous)
                        if (request != null) {
                            chapterSummaryRequests[turn.id] = request
                            runChapterSummary(turn.id, request, campaign)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { recordChapterSummaryFailure(turn.id) }
            }
        }
    }
    override fun retryChapterSummary(turnId: String) {
        val request = chapterSummaryRequests[turnId] ?: return
        chapterSummaryRuns.remove(request.chapterId)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (!chapterSummaryRuns.add(request.chapterId)) return@withContext
                try {
                    val campaign = requireNotNull(store.loadCampaign(request.campaignId)) { "CAMPAIGN_NOT_FOUND" }
                    runChapterSummary(turnId, request, campaign)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { recordChapterSummaryFailure(turnId) }
            }
        }
    }
    private suspend fun runChapterSummary(turnId: String, request: ChapterSummaries.Request, campaign: Campaign) {
        ChapterSummaries.generate(request, textModelPort(campaign.narratorFingerprint, campaign.module), memories)
        chapterSummaryRequests.remove(turnId)
        mutable.update { it.copy(chapterSummaryErrors = it.chapterSummaryErrors - turnId) }
        load()
    }
    private fun recordChapterSummaryFailure(turnId: String) {
        mutable.update { it.copy(chapterSummaryErrors = it.chapterSummaryErrors + (turnId to "上一章摘要生成失败，可重试。")) }
    }
    private fun chapterSummaryRequest(campaign: Campaign, module: ValidatedModule, result: CommittedResult, previous: ChapterDefinition): ChapterSummaries.Request? {
        val toSeq = result.eventSeq - 1
        if (toSeq < 1) return null
        val fromSeq = if (previous.id == module.rules.chapters.first().id) 1L else chapterStartSeq(campaign.id, previous.id, toSeq)
        if (fromSeq > toSeq) return null
        val narrations = chapterNarrations(campaign.id, fromSeq, toSeq)
        if (narrations.isEmpty()) return null
        return ChapterSummaries.Request(campaign.id, previous.id, fromSeq, toSeq, narrations)
    }
    /** Event seq where [chapterId] was entered; earlier chapters stay at their recorded mark. */
    private fun chapterStartSeq(campaignId: String, chapterId: String, upTo: Long): Long {
        var after = 0L
        while (after < upTo) {
            val batch = store.events(campaignId, after, 500).takeWhile { it.seq <= upTo }
            if (batch.isEmpty()) break
            batch.firstOrNull { event -> event.chapters.any { it.chapterId == chapterId } }?.let { return it.seq }
            after = batch.last().seq
        }
        error("CHAPTER_START_NOT_FOUND")
    }
    private fun chapterNarrations(campaignId: String, fromSeq: Long, toSeq: Long): List<String> {
        val turnIds = linkedSetOf<String>()
        var after = fromSeq - 1
        while (after < toSeq) {
            val batch = store.events(campaignId, after, 500).takeWhile { it.seq <= toSeq }
            if (batch.isEmpty()) break
            batch.forEach { turnIds += it.turnId }
            after = batch.last().seq
        }
        return turnIds.mapNotNull { store.loadTurn(campaignId, it) }.sortedBy { it.ordinal }.mapNotNull { turn -> turn.narration?.takeIf { it.isNotBlank() } }
    }
    private fun adaptTurn(t: Turn): AgentTurnUi = AgentTurnUi(t.id, t.playerText, t.narration.orEmpty(), stageText(t), t.result?.let { r ->
        r.publicSummary + (r.dice?.let { "\nd100：${it.value} / ${it.threshold} · ${if (r.success) "成功" else "失败"}" } ?: "") + r.notices.joinToString("") { "\n征兆：$it" }
    }, if (t.phase == TurnPhase.AWAITING_CONFIRMATION) t.plan?.proposal?.publicSummary else null,
        t.error?.let { if (t.result != null) "结果已保存，叙述未完成。继续不会重新结算。" else errorText(it) },
        t.phase !in terminal && t.phase != TurnPhase.AWAITING_CONFIRMATION,
        t.phase !in terminal && t.result == null, PublicProjection.evidence(t.result?.evidence ?: t.plan?.evidence.orEmpty()),
        t.result?.rolls.orEmpty().map { RollUi(it.purpose, it.expression, it.faces, it.modifier, it.total, it.target, it.success) },
        t.result?.chapters.orEmpty())
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
        e.message == "FINISH_OR_CANCEL_PENDING_TURN" -> "本团有中断回合，请先继续完成或取消，再导出备份。"
        e.message == "TURN_PENDING" -> "请先继续完成或取消当前回合，再更换模型。"
        e.message == "MODULE_HASH_MISMATCH" || e.message == "MODULE_VERSION_CONFLICT" -> "模组内容摘要或已安装版本冲突，请检查文件。原版本保留。"
        e is java.io.IOException -> "文件或网络读取未完成，请检查后重试。"
        e.message?.matches(Regex("[A-Z_0-9:. /-]{3,160}")) == true -> "操作未完成：${e.message}"
        e is IllegalArgumentException -> "资料字段或格式不符合要求，请检查后重新校验。"
        else -> "操作未完成，请检查配置与资料后重试。"
    }
    private fun characterName(campaign: Campaign, module: ValidatedModule): String {
        val player = PublicProjection.campaign(campaign, module).d20Player
        return if (player == null) module.world.characters.first().name else player.name
    }
    private fun allTurns(campaignId: String): List<Turn> {
        val out = mutableListOf<Turn>(); var before: Long? = null
        while (true) {
            val rows = store.history(campaignId, before, 200)
            if (rows.isEmpty()) break
            out += rows
            before = rows.last().ordinal
        }
        return out.sortedBy { it.ordinal }
    }
    private fun allEvents(campaignId: String): List<WorldEvent> {
        val out = mutableListOf<WorldEvent>(); var after = 0L
        while (true) {
            val rows = store.events(campaignId, after, 200)
            if (rows.isEmpty()) break
            out += rows
            after = rows.last().seq
        }
        return out
    }
    private fun chapterEventSeqs(campaignId: String): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        allEvents(campaignId).forEach { event ->
            event.chapters.forEach { mark -> out.putIfAbsent(mark.chapterId, event.seq) }
        }
        return out
    }
    private fun allMemories(campaignId: String): List<MemoryItem> {
        val out = mutableListOf<MemoryItem>(); var after = ""
        while (true) {
            val rows = db.dao().exportMemory(campaignId, after, 200)
            if (rows.isEmpty()) break
            rows.forEach { row ->
                out += MemoryItem(
                    row.id, row.campaignId, row.text, decode(row.entitiesJson), row.fromSeq, row.toSeq,
                    Visibility(Audience.valueOf(row.audience), row.subjectId), row.status,
                    row.supersedesId, row.sourceTurnId, row.kind,
                )
            }
            after = rows.last().id
        }
        return out
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
