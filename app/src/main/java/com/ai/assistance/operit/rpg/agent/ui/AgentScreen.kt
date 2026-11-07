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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.ui.home.HomeFiles
import com.ai.assistance.operit.rpg.ui.play.PlayScreen
import com.ai.assistance.operit.rpg.update.RpgUpdateButton

/** Receives presentation values only. GM plans and tool output never enter this screen. */
@Composable
fun AgentScreen(
    controller: AgentUiController,
    files: HomeFiles,
    onModelSettings: () -> Unit,
    startPage: String,
    campaignId: String?,
    onExitHome: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismissKeyboard() { focus.clearFocus(force = true); keyboard?.hide() }
    var page by rememberSaveable { mutableStateOf(startPage) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::previewFile) }
    val play = state.play
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(state.forkResult) {
        if (state.forkResult != null) {
            controller.consumeForkResult()
            onExitHome()
        }
    }
    var baselinePlayId by remember { mutableStateOf(play?.id) }
    LaunchedEffect(campaignId) {
        if (campaignId != null) {
            page = "play"
            controller.open(campaignId)
        }
    }
    LaunchedEffect(play?.id) {
        val id = play?.id
        if (id != null && page == "create" && id != baselinePlayId) page = "play"
    }
    fun back() {
        if (page == "play") {
            if (!state.busy) { controller.close(); onExitHome() }
        } else onExitHome()
    }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            if (page == "play") {
                AgentMessage(state.message, controller::dismissMessage)
                if (play != null) {
                    Box(Modifier.weight(1f)) {
                        PlayScreen(
                            play = play,
                            state = state,
                            controller = controller,
                            files = files,
                            onBack = ::back,
                            onModelSettings = onModelSettings,
                            onLibrary = { page = "library" },
                        )
                    }
                } else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (page) { "create" -> "新的战役"; "library" -> "模组与资料"; "settings" -> "设置"; else -> "二十面骰" },
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { back() }) { Text("返回") }
                }
                AgentMessage(state.message, controller::dismissMessage)
                if (state.busy) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.progress ?: "正在处理", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = controller::stop) { Text("停止") }
                }
                when (page) {
                    "settings" -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedButton(onClick = { page = "library" }, Modifier.fillMaxWidth()) { Text("模组与资料") }
                        OutlinedButton(onClick = onModelSettings, Modifier.fillMaxWidth()) { Text("模型配置") }
                        RpgUpdateButton(Modifier.fillMaxWidth())
                        Text("二十面骰 0.3 · 测试版", style = MaterialTheme.typography.bodySmall)
                    }
                    "library" -> AgentLibrary(state, controller, { importer.launch(arrayOf("application/zip", "application/octet-stream", "text/*")) })
                    else -> AgentCreate(state, controller, { page = "library" }, onModelSettings, ::dismissKeyboard)
                }
            }
        }
        state.preview?.let { preview -> AlertDialog(onDismissRequest = controller::dismissPreview, title = { Text(preview.title) }, text = { Text(preview.detail, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = controller::commitImport, enabled = !state.busy) { Text(if (preview.module) "确认安装" else "保存参考资料") } }, dismissButton = { TextButton(onClick = controller::dismissPreview) { Text("取消") } }) }
        state.documentText?.let { (title, text) -> AlertDialog(onDismissRequest = controller::dismissDocument, title = { Text(title) }, text = { Text(text, Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = controller::dismissDocument, modifier = Modifier.semantics { contentDescription = "关闭$title" }) { Text("关闭") } }) }
    }
}

@Composable private fun AgentMessage(message: String?, onDismiss: () -> Unit) {
    message ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        Row(Modifier.padding(12.dp)) {
            Text(message, Modifier.weight(1f).heightIn(max = 140.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}

@Composable private fun Detail(title: String, detail: String) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail) } }

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
