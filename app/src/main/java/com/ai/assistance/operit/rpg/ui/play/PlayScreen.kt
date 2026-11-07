package com.ai.assistance.operit.rpg.ui.play

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.rpg.agent.contract.MemoryItem
import com.ai.assistance.operit.rpg.agent.ui.AgentPlayUi
import com.ai.assistance.operit.rpg.agent.ui.AgentTurnUi
import com.ai.assistance.operit.rpg.agent.ui.AgentUiController
import com.ai.assistance.operit.rpg.agent.ui.AgentUiState
import com.ai.assistance.operit.rpg.ui.avatar.Avatar
import com.ai.assistance.operit.rpg.ui.avatar.AvatarCatalog
import com.ai.assistance.operit.rpg.ui.avatar.AvatarKind
import com.ai.assistance.operit.rpg.ui.theme.NarrationFontFamily
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reading-style play surface: narration, player bubbles, roll chips and the character sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(
    play: AgentPlayUi,
    state: AgentUiState,
    controller: AgentUiController,
    onBack: () -> Unit,
    onModelSettings: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val catalog = remember(context) { AvatarCatalog.load(context) }
    val campaignAvatar = remember(catalog, play.id) { catalog.pick(AvatarKind.CAMPAIGN, play.id) }
    val playerCharacterId = play.playerCharacterId
    val characterAvatar = remember(catalog, playerCharacterId) { playerCharacterId?.let { catalog.pick(AvatarKind.CHARACTER, it) } }
    val backupImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(controller::importBackup) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(controller::exportBackup) }
    val listState = rememberLazyListState()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuPage by rememberSaveable { mutableStateOf("menu") }
    var correcting by remember { mutableStateOf<MemoryItem?>(null) }
    var correction by remember { mutableStateOf("") }
    var reachedLatest by remember { mutableStateOf(false) }
    val lastTurn = play.turns.lastOrNull()
    val sheet = play.sheet
    val modelsReady = state.models.any { it.fingerprint == play.modelFingerprint } && state.models.any { it.fingerprint == play.narratorFingerprint }

    // Jump to the newest turn only when a new turn arrives, never when older turns are prepended.
    LaunchedEffect(lastTurn?.id) {
        if (play.turns.isNotEmpty()) {
            listState.scrollToItem(play.turns.lastIndex)
            reachedLatest = true
        }
    }
    LaunchedEffect(state.notices.size) {
        if (state.notices.isNotEmpty()) listState.animateScrollToItem(play.turns.size + state.notices.size - 1)
    }
    LaunchedEffect(listState, play.hasOlderTurns, play.turns.firstOrNull()?.id, state.busy, reachedLatest) {
        if (!reachedLatest) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            if (index == 0 && play.hasOlderTurns && !state.busy) controller.loadOlder()
        }
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Avatar(campaignAvatar, 32.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(play.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    listOfNotNull(play.clock.takeIf { it.isNotBlank() }, play.location).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            characterAvatar?.let { avatar ->
                Avatar(
                    avatar,
                    32.dp,
                    Modifier.clickable { sheetOpen = true }.semantics { contentDescription = "人物卡" },
                )
            }
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
        }

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(play.turns, key = { it.id }) { turn ->
                TurnBlock(
                    turn = turn,
                    busy = state.busy,
                    isLast = turn.id == lastTurn?.id,
                    controller = controller,
                )
            }
            items(state.notices) { notice ->
                Text(
                    notice,
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (state.busy && (lastTurn == null || !lastTurn.canResume)) {
                item(key = "in-progress") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("主持中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = controller::stop) { Text("取消") }
                    }
                }
            }
        }

        if (!modelsReady) {
            TextButton(onClick = { menuPage = "models"; menuOpen = true }, Modifier.padding(horizontal = 16.dp)) {
                Text("请为本战役绑定本机模型")
            }
        }
        PlayInputBar(
            canSend = play.canAct && modelsReady,
            busy = state.busy,
            onSubmit = controller::submit,
            onTableCommand = { controller.submit("//$it") },
            onSearchRules = controller::searchRules,
            evidence = state.evidence,
        )
    }

    if (sheetOpen && sheet != null && characterAvatar != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            CharacterSheet(sheet, characterAvatar, Modifier.fillMaxWidth().fillMaxHeight(0.92f))
        }
    }

    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false; menuPage = "menu" },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (menuPage != "menu") TextButton(onClick = { menuPage = "menu" }) { Text("返回") }
                    Text(menuTitle(menuPage), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { menuOpen = false; menuPage = "menu" }) { Text("关闭") }
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (menuPage) {
                        "models" -> {
                            Text("模型变更用于后续请求。规划模型与旁白模型分别绑定；已结算但叙述中断时，重绑旁白模型后可继续旁白，已保存结果保持不变。", style = MaterialTheme.typography.bodySmall)
                            Text("规划模型", style = MaterialTheme.typography.titleMedium)
                            state.models.forEach { model ->
                                OutlinedButton(onClick = { controller.bindPlanner(model.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(model.title + if (model.fingerprint == play.modelFingerprint) " · 当前" else "")
                                }
                            }
                            Text("旁白模型", style = MaterialTheme.typography.titleMedium)
                            state.models.forEach { model ->
                                OutlinedButton(onClick = { controller.bindNarrator(model.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                    Text(model.title + if (model.fingerprint == play.narratorFingerprint) " · 当前" else "")
                                }
                            }
                            TextButton(onClick = { menuOpen = false; onModelSettings() }) { Text("模型配置") }
                        }
                        "notes" -> {
                            if (state.notes.isEmpty()) Text("还没有可见记事。")
                            state.notes.forEach { note ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(note.text)
                                    Text(if (note.kind == "CONVERSATION") "对话回合" else "来源事件 ${note.fromSeq}–${note.toSeq}", style = MaterialTheme.typography.bodySmall)
                                    if (note.supersedesId != null) Text("已更正先前记事", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { menuOpen = false; controller.readNoteSource(note) }) { Text("查看来源") }
                                        TextButton(onClick = { correcting = note; correction = note.text }) { Text("更正") }
                                        if (note.supersedesId != null) TextButton(onClick = { menuOpen = false; controller.readCorrectionHistory(note.id) }) { Text("更正记录") }
                                    }
                                }
                            }
                        }
                        "documents" -> {
                            if (state.documents.isEmpty()) Text("还没有参考资料。")
                            state.documents.forEach { document ->
                                TextButton(onClick = { menuOpen = false; controller.readDocument(document.id) }) { Text(document.title) }
                            }
                            OutlinedButton(onClick = { menuOpen = false; onLibrary() }, modifier = Modifier.fillMaxWidth()) { Text("管理资料与挂载") }
                        }
                        "backup" -> {
                            Text("备份包含本团依赖模组与原文，不含 API Key。中断回合需先处理。", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                onClick = { menuOpen = false; backupImporter.launch(arrayOf("application/zip", "application/octet-stream")) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("导入长团备份") }
                            OutlinedButton(
                                onClick = { menuOpen = false; exporter.launch("${play.title}.rpgsave2.zip") },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("导出完整备份") }
                            play.contentLevelLabel?.let { Text("当前内容分档：$it。输入 //降档 或 //升档 调整，//停 收束当前场景。", style = MaterialTheme.typography.bodySmall) }
                        }
                        else -> {
                            MenuRow("模型绑定") { menuPage = "models" }
                            MenuRow("记事修订") { controller.loadNotes(); menuPage = "notes" }
                            MenuRow("参考资料") { menuPage = "documents" }
                            MenuRow("备份") { menuPage = "backup" }
                            MenuRow("模组与资料") { menuOpen = false; onLibrary() }
                        }
                    }
                }
            }
        }
    }

    correcting?.let { original ->
        AlertDialog(
            onDismissRequest = { correcting = null },
            title = { Text("更正记事") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("原内容：${original.text}")
                    OutlinedTextField(correction, { correction = it }, label = { Text("新内容") }, modifier = Modifier.fillMaxWidth())
                    Text("保留原条目与来源，更正不会修改生命、物品或骰点。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { controller.correctNote(original.id, correction); correcting = null },
                    enabled = correction.isNotBlank() && correction != original.text && !state.busy,
                ) { Text("确认更正") }
            },
            dismissButton = { TextButton(onClick = { correcting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TurnBlock(turn: AgentTurnUi, busy: Boolean, isLast: Boolean, controller: AgentUiController) {
    val inProgress = busy && isLast && turn.canResume
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (turn.input.isNotBlank()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = maxWidth * 0.8f),
                    ) {
                        Text(turn.input, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (turn.narration.isNotBlank()) {
            Text(
                turn.narration,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = NarrationFontFamily, fontSize = 16.sp, lineHeight = 28.sp),
            )
        }
        if (inProgress) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("主持中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = controller::stop) { Text("取消") }
            }
        }
        turn.rolls.forEach { roll -> RollChip(roll) }
        turn.confirmation?.let { summary -> Text(summary, style = MaterialTheme.typography.bodySmall) }
        turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (!inProgress && (turn.canResume || turn.canCancel || turn.confirmation != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (turn.canResume) TextButton(onClick = { controller.resume(turn.id) }, enabled = !busy) { Text("继续") }
                if (turn.confirmation != null) TextButton(onClick = { controller.confirm(turn.id) }, enabled = !busy) { Text("确认") }
                if (turn.canCancel) TextButton(onClick = { controller.cancel(turn.id) }, enabled = !busy) { Text("取消") }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

private fun menuTitle(page: String) = when (page) {
    "models" -> "模型绑定"
    "notes" -> "记事修订"
    "documents" -> "参考资料"
    "backup" -> "备份"
    else -> "更多"
}
