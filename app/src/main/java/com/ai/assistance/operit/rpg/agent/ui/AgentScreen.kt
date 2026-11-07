package com.ai.assistance.operit.rpg.agent.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.update.RpgUpdateButton

private val agentColors = lightColorScheme(primary = Color(0xFF586B62), onPrimary = Color.White,
    background = Color(0xFFF5F4F0), surface = Color(0xFFFCFBF8), onSurface = Color(0xFF303735),
    onBackground = Color(0xFF303735), surfaceVariant = Color(0xFFE3E9E1),
    error = Color(0xFF966F64), errorContainer = Color(0xFFEFE3DD), outline = Color(0xFFD9DDD6))

/** Receives presentation values only. GM plans and tool output never enter this screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(controller: AgentUiController, onModelSettings: () -> Unit) {
    val state by controller.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismissKeyboard() { focus.clearFocus(force = true); keyboard?.hide() }
    var page by rememberSaveable { mutableStateOf("home") }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var selectedEvidence by remember { mutableStateOf<RuleEvidence?>(null) }
    var correcting by remember { mutableStateOf<MemoryItem?>(null) }
    var correction by remember { mutableStateOf("") }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::previewFile) }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::importBackup) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(controller::exportBackup) }
    val play = state.play
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(play?.id) { if (play != null) page = "play" }
    fun back() { if (panel != null) panel = null else if (page == "play") { if (!state.busy) { controller.close(); page = "home" } } else page = if (play != null) "play" else "home" }
    BackHandler(enabled = panel != null || page != "home") { back() }
    val baseTypography = Typography()
    MaterialTheme(colorScheme = agentColors, typography = Typography(
        bodyLarge = baseTypography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = baseTypography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        titleLarge = baseTypography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
        titleMedium = baseTypography.titleMedium.copy(fontFamily = FontFamily.SansSerif))) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (page) { "create" -> "新的战役"; "library" -> "模组与资料"; "settings" -> "设置"; "play" -> play?.title ?: "二十面骰"; else -> "二十面骰" },
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (page == "home") TextButton(onClick = { page = "settings" }) { Text("设置") }
                    else TextButton(onClick = { back() }) { Text("返回") }
                }
                state.message?.let { message -> Surface(color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Text(message, Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = controller::dismissMessage) { Text("关闭") }
                    }
                } }
                if (state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.progress ?: "正在处理", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = controller::stop) { Text("停止") }
                }
                when (page) {
                    "home" -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item { Text("一个人，也能走进故事。", style = MaterialTheme.typography.bodyMedium) }
                        item { Button(onClick = { page = "create" }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("新建战役") } }
                        items(state.entries, key = { "${it.ref.engineId}:${it.ref.campaignId}" }) { entry ->
                            OutlinedCard(onClick = { controller.open(entry.ref.campaignId) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                    Text("长团 Agent", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (state.entries.isEmpty()) item { Text("从一个模组开始你的故事。已保存的战役会出现在这里。") }
                        if (state.hasMoreEntries) item { TextButton(onClick = controller::loadMoreEntries, enabled = !state.busy) { Text("加载更多战役") } }
                    }
                    "settings" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedButton(onClick = { page = "library" }, Modifier.fillMaxWidth()) { Text("模组与资料") }
                        OutlinedButton(onClick = onModelSettings, Modifier.fillMaxWidth()) { Text("模型配置") }
                        OutlinedButton(onClick = { backupImporter.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), enabled = !state.busy) { Text("导入长团备份") }
                        RpgUpdateButton(Modifier.fillMaxWidth())
                        Text("二十面骰 0.3 · 测试版", style = MaterialTheme.typography.bodySmall)
                    }
                    "library" -> AgentLibrary(state, controller, { importer.launch(arrayOf("application/zip", "application/octet-stream", "text/*")) })
                    "create" -> AgentCreate(state, controller, { page = "library" }, onModelSettings, ::dismissKeyboard)
                    "play" -> if (play != null) {
                        Text("${play.location} · ${play.status}", Modifier.padding(horizontal = 28.dp), style = MaterialTheme.typography.bodySmall)
                        Text("时辰 ${play.clock}", Modifier.padding(horizontal = 28.dp).semantics { contentDescription = "时辰" }, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("人物", "线索", "地点", "记事", "规则", "存档").forEach { name ->
                                TextButton(onClick = { dismissKeyboard(); panel = name; if (name == "记事") controller.loadNotes() }, contentPadding = PaddingValues(4.dp)) { Text(name) }
                            }
                        }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (play.hasOlderTurns) item { TextButton(onClick = controller::loadOlder, enabled = !state.busy) { Text("加载更早记录") } }
                            if (play.turns.isEmpty() && state.notices.isEmpty()) item { Text("你来到了${play.location}。可以查看人物、地点与目标，然后说出你想做的事。") }
                            items(play.turns, key = { it.id }) { turn ->
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(turn.input, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                    if (turn.narration.isNotBlank()) Text(turn.narration)
                                    turn.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (turn.stage.isNotBlank()) Text(turn.stage, style = MaterialTheme.typography.labelMedium)
                                    turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    turn.confirmation?.let { summary ->
                                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(summary)
                                                Button(onClick = { controller.confirm(turn.id) }, enabled = !state.busy) { Text("确认执行") }
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (turn.canResume) TextButton(onClick = { controller.resume(turn.id) }, enabled = !state.busy) { Text("继续本轮") }
                                        if (turn.canCancel) TextButton(onClick = { controller.cancel(turn.id) }, enabled = !state.busy) { Text("取消行动") }
                                    }
                                    turn.evidence.forEach { evidence -> TextButton(onClick = { selectedEvidence = evidence }) { Text("规则出处 · ${evidence.source.anchor}") } }
                                }
                            }
                            items(state.notices) { notice -> Text(notice, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (state.models.none { it.fingerprint == play.modelFingerprint } || state.models.none { it.fingerprint == play.narratorFingerprint }) TextButton(onClick = { panel = "存档" }, Modifier.padding(horizontal = 28.dp)) { Text("请为本战役绑定本机模型") }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(input, { input = it }, Modifier.weight(1f).semantics { contentDescription = "行动输入" }, placeholder = { Text("你想做什么？//开头为场外指令") }, maxLines = 3, enabled = !state.busy, isError = input.length > 2000)
                            Button(onClick = { dismissKeyboard(); controller.submit(input.trim()); input = "" }, enabled = !state.busy && input.isNotBlank() && input.length <= 2000 && (input.startsWith("//") || play.canAct && state.models.any { it.fingerprint == play.modelFingerprint } && state.models.any { it.fingerprint == play.narratorFingerprint }), modifier = Modifier.heightIn(min = 56.dp)) { Text("发送") }
                        }
                    }
                }
            }
            if (panel != null && play != null) ModalBottomSheet(onDismissRequest = { panel = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(start = 28.dp, end = 28.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(panel!!, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { dismissKeyboard(); panel = null }, modifier = Modifier.semantics { contentDescription = "关闭${panel}面板" }) { Text("关闭") }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (panel) {
                        "人物" -> { play.people.forEach { Detail(it.name, it.detail) }; if (play.people.isEmpty()) Text("当前没有已知人物。") }
                        "线索" -> { play.facts.forEach { Text(it) }; play.objectives.forEach { Text(it) }; if (play.facts.isEmpty() && play.objectives.isEmpty()) Text("当前没有已知线索。") }
                        "地点" -> play.places.forEach { Detail(it.name, it.detail) }
                        "记事" -> {
                            if (state.notes.isEmpty()) Text("还没有可见记事。")
                            state.notes.forEach { note -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(note.text)
                                Text(if (note.kind == "CONVERSATION") "对话回合" else "来源事件 ${note.fromSeq}–${note.toSeq}", style = MaterialTheme.typography.bodySmall)
                                if (note.supersedesId != null) Text("已更正先前记事", style = MaterialTheme.typography.bodySmall)
                                Row { TextButton(onClick = { controller.readNoteSource(note) }) { Text("查看来源") }; TextButton(onClick = { correcting = note; correction = note.text }) { Text("更正") } }
                                if (note.supersedesId != null) TextButton(onClick = { controller.readCorrectionHistory(note.id) }) { Text("更正记录") }
                            } }
                        }
                        "规则" -> {
                            var query by rememberSaveable { mutableStateOf("") }
                            OutlinedTextField(query, { query = it }, label = { Text("查询本团规则") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = { dismissKeyboard(); controller.searchRules(query) }, enabled = !state.busy && query.isNotBlank()) { Text("查找出处") }
                            state.evidence.forEach { evidence -> TextButton(onClick = { selectedEvidence = evidence }) { Text("${evidence.source.anchor} · ${authorityText(evidence.source.authority)}") } }
                        }
                        "存档" -> {
                            Text("模型变更用于后续请求。规划模型与旁白模型分别绑定；已结算但叙述中断时，重绑旁白模型后可继续旁白，已保存结果保持不变。", style = MaterialTheme.typography.bodySmall)
                            Text("规划模型", style = MaterialTheme.typography.titleMedium)
                            state.models.forEach { model -> OutlinedButton(onClick = { controller.bindPlanner(model.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(model.title + if (model.fingerprint == play.modelFingerprint) " · 当前" else "") } }
                            Text("旁白模型", style = MaterialTheme.typography.titleMedium)
                            state.models.forEach { model -> OutlinedButton(onClick = { controller.bindNarrator(model.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(model.title + if (model.fingerprint == play.narratorFingerprint) " · 当前" else "") } }
                            play.contentLevelLabel?.let { Text("当前内容分档：$it。输入 //降档 或 //升档 调整，//停 收束当前场景。", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = onModelSettings) { Text("模型配置") }
                            OutlinedButton(onClick = { exporter.launch("${play.title}.rpgsave2.zip") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
                            TextButton(onClick = { panel = null; page = "library" }) { Text("模组与资料") }
                            Text("备份包含本团依赖模组与原文，不含 API Key。中断回合需先处理。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    }
                }
            }
            selectedEvidence?.let { evidence -> AlertDialog(onDismissRequest = { selectedEvidence = null }, title = { Text(evidence.source.anchor) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(evidence.quote)
                    Text("${evidence.source.sourceId.substringAfterLast('/')} · ${evidence.source.anchor}", style = MaterialTheme.typography.bodySmall)
                    Text(authorityText(evidence.source.authority), style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { selectedEvidence = null }, modifier = Modifier.semantics { contentDescription = "关闭规则出处" }) { Text("关闭") } }) }
            correcting?.let { original -> AlertDialog(onDismissRequest = { correcting = null }, title = { Text("更正记事") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("原内容：${original.text}")
                    OutlinedTextField(correction, { correction = it }, label = { Text("新内容") }, modifier = Modifier.fillMaxWidth())
                    Text("保留原条目与来源，更正不会修改生命、物品或骰点。", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { dismissKeyboard(); controller.correctNote(original.id, correction); correcting = null }, enabled = correction.isNotBlank() && correction != original.text && !state.busy) { Text("确认更正") } }, dismissButton = { TextButton(onClick = { correcting = null }) { Text("取消") } }) }
            state.preview?.let { preview -> AlertDialog(onDismissRequest = controller::dismissPreview, title = { Text(preview.title) }, text = { Text(preview.detail, Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { TextButton(onClick = controller::commitImport, enabled = !state.busy) { Text(if (preview.module) "确认安装" else "保存参考资料") } }, dismissButton = { TextButton(onClick = controller::dismissPreview) { Text("取消") } }) }
            state.documentText?.let { (title, text) -> AlertDialog(onDismissRequest = controller::dismissDocument, title = { Text(title) }, text = { Text(text, Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = controller::dismissDocument, modifier = Modifier.semantics { contentDescription = "关闭$title" }) { Text("关闭") } }) }
        }
    }
}

@Composable private fun Detail(title: String, detail: String) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail) } }
private fun authorityText(value: String) = when (value) { "temporary", "house_rule" -> "本团裁定"; "module_rule" -> "模组规则"; "module_custom" -> "模组自定"; "reference" -> "参考资料"; "source" -> "资料原文"; else -> value }

@Composable private fun AgentCreate(state: AgentUiState, controller: AgentUiController, onLibrary: () -> Unit, onModels: () -> Unit, dismissKeyboard: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var moduleId by rememberSaveable { mutableStateOf<String?>(null) }
    var characterId by rememberSaveable { mutableStateOf<String?>(null) }
    var plannerChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var narratorChoice by rememberSaveable { mutableStateOf<String?>(null) }
    var allow by rememberSaveable { mutableStateOf(false) }
    val module = state.modules.firstOrNull { it.ref.hash == moduleId }
    // Both selectors start on the first listed model; the user may change either.
    val plannerId = plannerChoice ?: state.models.firstOrNull()?.id
    val narratorId = narratorChoice ?: state.models.firstOrNull()?.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("战役名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Text("选择模组", style = MaterialTheme.typography.titleMedium)
        state.modules.forEach { candidate -> OutlinedButton(onClick = { dismissKeyboard(); moduleId = candidate.ref.hash; characterId = null }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择模组 · ${candidate.title}" }) { Text(candidate.title + " · " + candidate.ref.version + if (moduleId == candidate.ref.hash) " ✓" else "") } }
        if (state.modules.isEmpty()) Text("先导入一个通过校验的模组，再开始长团。")
        TextButton(onClick = onLibrary) { Text("导入模组与资料") }
        module?.let { selected ->
            Text(selected.description)
            Text("选择角色", style = MaterialTheme.typography.titleMedium)
            selected.characters.forEach { character -> OutlinedButton(onClick = { dismissKeyboard(); characterId = character.id }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择角色 · ${character.name}" }) { Column { Text(character.name + if (characterId == character.id) " ✓" else ""); Text(character.detail, style = MaterialTheme.typography.bodySmall) } } }
        }
        Row { Switch(checked = allow, onCheckedChange = { allow = it }); Column(Modifier.padding(start = 12.dp)) { Text(if (allow) "允许补充细节" else "严格采用模组"); Text(if (allow) "在模组允许范围内补充人物、场景和支线。" else "按模组既有内容推进。", style = MaterialTheme.typography.bodySmall) } }
        Text("规划模型", style = MaterialTheme.typography.titleMedium)
        state.models.forEach { model -> OutlinedButton(onClick = { dismissKeyboard(); plannerChoice = model.id }, modifier = Modifier.fillMaxWidth()) { Text(model.title + if (plannerId == model.id) " ✓" else "") } }
        Text("旁白模型", style = MaterialTheme.typography.titleMedium)
        state.models.forEach { model -> OutlinedButton(onClick = { dismissKeyboard(); narratorChoice = model.id }, modifier = Modifier.fillMaxWidth()) { Text(model.title + if (narratorId == model.id) " ✓" else "") } }
        TextButton(onClick = onModels) { Text("模型配置") }
        Button(onClick = { dismissKeyboard(); controller.create(title.trim(), requireNotNull(module).ref, requireNotNull(characterId), allow, requireNotNull(plannerId), requireNotNull(narratorId)) },
            enabled = !state.busy && title.isNotBlank() && title.length <= 100 && module != null && characterId != null && state.models.any { it.id == plannerId } && state.models.any { it.id == narratorId }, modifier = Modifier.fillMaxWidth()) { Text("开始战役") }
    }
}

@Composable private fun AgentLibrary(state: AgentUiState, controller: AgentUiController, onImport: () -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var targetCampaignId by rememberSaveable { mutableStateOf(state.play?.id) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Button(onClick = onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("导入模组 / MD / TXT") } }
        items(state.modules, key = { it.ref.hash }) { module -> Detail(module.title, "${module.ref.version} · 可用于开团\n${module.description}") }
        item { Text("示例模组", style = MaterialTheme.typography.titleMedium) }
        items(state.sampleModules, key = { "sample:${it.ref.hash}" }) { module -> OutlinedButton(onClick = { controller.previewExample(module.ref.hash) }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("查看并安装 · ${module.title}") } }
        item { Text("参考资料", style = MaterialTheme.typography.titleMedium); Text("原文可作为资料保存。选中后可添加为战役参考资料。", style = MaterialTheme.typography.bodySmall) }
        items(state.documents, key = { it.id }) { document -> Row { Checkbox(document.id in selected, { checked -> selected = if (checked) selected + document.id else selected - document.id }); TextButton(onClick = { controller.readDocument(document.id) }) { Text(document.title) } } }
        if (selected.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("添加为战役参考资料", style = MaterialTheme.typography.titleMedium)
            state.entries.filter { it.ref.engineId == LONGFORM_ENGINE }.forEach { entry -> OutlinedButton(onClick = { targetCampaignId = entry.ref.campaignId }, modifier = Modifier.fillMaxWidth()) { Text(entry.title + if (targetCampaignId == entry.ref.campaignId) " ✓" else "") } }
            Button(onClick = { controller.attachDocuments(requireNotNull(targetCampaignId), selected.toList()) }, enabled = !state.busy && targetCampaignId != null, modifier = Modifier.fillMaxWidth()) { Text("添加所选资料") }
        } }
    }
}
