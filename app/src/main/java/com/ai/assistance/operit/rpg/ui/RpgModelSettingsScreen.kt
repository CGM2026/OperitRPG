package com.ai.assistance.operit.rpg.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.net.URI

@Composable
fun RpgModelSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { RpgModelConfigStore.get(context) }
    val scope = rememberCoroutineScope()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<RpgModelConfig?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val configurations by produceState(emptyList<RpgModelConfig>(), store, reload) {
        loading = true; loadFailed = false
        try { store.configurations.collect { value = it; loading = false } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loading = false; loadFailed = true; message = "无法读取模型配置，请保留原数据后重试。" }
    }
    val editing = configurations.firstOrNull { it.id == editingId }
    fun back() { if (creating || editingId != null) { creating = false; editingId = null; message = null } else onBack() }
    BackHandler(onBack = ::back)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (creating) "新增模型" else if (editingId != null) "编辑模型" else "模型配置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = ::back, enabled = !busy) { Text("返回") }
        }
        message?.let { Text(it, Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (creating || editing != null) {
            RpgModelEditor(config = editing, busy = busy, save = { edit ->
                busy = true; message = null
                scope.launch {
                    try { store.save(edit); creating = false; editingId = null; message = "模型配置已保存。" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "保存未完成，请检查填写内容。" }
                    finally { busy = false }
                }
            })
        } else {
            LazyColumn(contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Button(onClick = { creating = true; message = null }, enabled = !loadFailed && !loading, modifier = Modifier.fillMaxWidth()) { Text("新增模型配置") }
                    Spacer(Modifier.height(12.dp))
                    Text("配置仅保存在本机。选择协议和完整 API 地址，创建战役时再选择要使用的模型。", style = MaterialTheme.typography.bodySmall)
                }
                if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (loadFailed) item { OutlinedButton(onClick = { reload++; message = null }) { Text("重新读取配置") } }
                if (!loading && !loadFailed && configurations.isEmpty()) item { Text("还没有模型配置。可以添加服务商 API，也可以在新建战役时选择明确标记的离线演示。") }
                items(configurations, key = { it.id }) { config ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(config.name, style = MaterialTheme.typography.titleMedium)
                            Text(config.protocol?.label() ?: "原配置暂不支持", style = MaterialTheme.typography.bodySmall)
                            Text(modelAddressLabel(config.endpoint), style = MaterialTheme.typography.bodySmall)
                            Text(config.modelNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            Text("API Key：${if (config.apiKey.isBlank()) "未设置" else "••••••••"}", style = MaterialTheme.typography.bodySmall)
                            config.unsupportedReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick = { editingId = config.id; message = null }) { Text("编辑") }
                                TextButton(onClick = { deleting = config }) { Text("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { config -> AlertDialog(
        onDismissRequest = { if (!busy) deleting = null },
        title = { Text("删除模型配置？") },
        text = { Text("将删除“${config.name}”的本机配置。使用此配置的战役需要重新选择模型；战役存档会保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { store.delete(config.id); deleting = null; message = "模型配置已删除。" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "删除未完成，请重试。"; deleting = null }
                finally { busy = false }
            }
        }) { Text("确认删除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } }
    ) }
}

@Composable
internal fun RpgModelEditor(config: RpgModelConfig?, busy: Boolean, save: (ModelConfigEdit) -> Unit) {
    var name by rememberSaveable(config?.id) { mutableStateOf(config?.name.orEmpty()) }
    var endpoint by rememberSaveable(config?.id) { mutableStateOf(config?.endpoint ?: "https://") }
    var models by rememberSaveable(config?.id) { mutableStateOf(config?.modelNames?.joinToString("\n").orEmpty()) }
    var protocolName by rememberSaveable(config?.id) { mutableStateOf(config?.protocol?.name) }
    // Do not copy a key into SavedState or screenshots; visibility requires an explicit tap.
    var key by remember(config?.id) { mutableStateOf(config?.apiKey.orEmpty()) }
    var showKey by remember { mutableStateOf(false) }
    var pendingHttp by remember { mutableStateOf<ModelConfigEdit?>(null) }
    val protocol = protocolName?.let { ModelProtocol.valueOf(it) }
    val modelNames = models.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val validAddress = runCatching { URI(endpoint.trim().removeSuffix("#")).let {
        it.scheme?.lowercase() in setOf("https", "http") && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
    } }.getOrDefault(false)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        config?.unsupportedReason?.let { Text("$it\n原配置仍保留。请选择要使用的协议，确认地址与模型名后保存。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("请求协议", style = MaterialTheme.typography.titleMedium)
        ModelProtocol.entries.forEach { item ->
            OutlinedCard(onClick = { protocolName = item.name }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = protocol == item, onClick = null, enabled = !busy)
                    Text(item.label(), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (protocol == null) Text("请明确选择协议。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("完整 API 地址") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
        Text("Chat、Responses、Anthropic 可填写服务地址或完整接口地址；Gemini 填写服务地址或 /v1beta 基础地址。切换协议不会修改已填写的地址。凭证请填写在 API Key 中，勿放入地址。", style = MaterialTheme.typography.bodySmall)
        if (!validAddress) Text("地址需以 https:// 或 http:// 开头，不能包含账号、查询参数或片段。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(models, { models = it }, label = { Text("模型名 · 每行一个") }, enabled = !busy, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, enabled = !busy, singleLine = true,
            modifier = Modifier.fillMaxWidth(), visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } })
        Text("Key 默认遮罩显示。无密钥的本地服务可以留空。保存不会发起网络请求。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { protocol?.let {
            val edit = ModelConfigEdit(config?.id, name.trim(), key, endpoint.trim(), modelNames, it)
            if (endpoint.trim().startsWith("http://", ignoreCase = true)) pendingHttp = edit else save(edit)
        } },
            enabled = !busy && name.isNotBlank() && validAddress && modelNames.isNotEmpty() && protocol != null,
            modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在保存" else "保存配置") }
    }
    pendingHttp?.let { edit -> AlertDialog(onDismissRequest = { pendingHttp = null },
        title = { Text("使用明文 HTTP 地址？") },
        text = { Text("此地址使用明文 HTTP，API Key 和内容可能被网络读取。确认后保存该地址。") },
        confirmButton = { TextButton(onClick = { pendingHttp = null; save(edit) }) { Text("确认保存") } },
        dismissButton = { TextButton(onClick = { pendingHttp = null }) { Text("返回修改") } }) }
}

private fun ModelProtocol.label(): String = when (this) {
    ModelProtocol.OPENAI_CHAT -> "OpenAI Chat Completions"
    ModelProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    ModelProtocol.ANTHROPIC -> "Anthropic Messages"
    ModelProtocol.GEMINI -> "Gemini generateContent"
}

/** Never expose credentials embedded in an unsupported legacy URL on the passive list. */
internal fun modelAddressLabel(endpoint: String): String {
    val safe = runCatching { URI(endpoint).let {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
    } }.getOrDefault(false)
    return if (safe) endpoint else "旧地址包含需重新配置的内容"
}
