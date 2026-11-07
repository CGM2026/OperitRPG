package com.ai.assistance.operit.rpg.ui.play

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.agent.contract.RuleEvidence

/** Input row plus the `+` table-command and rule-search menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayInputBar(
    canSend: Boolean,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onTableCommand: (String) -> Unit,
    onSearchRules: (String) -> Unit,
    evidence: List<RuleEvidence>,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var queryOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedEvidence by remember { mutableStateOf<RuleEvidence?>(null) }

    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = { menuOpen = true }, enabled = !busy) {
            Icon(Icons.Filled.Add, contentDescription = "桌面指令与规则查询")
        }
        OutlinedTextField(
            input,
            { input = it },
            Modifier.weight(1f).semantics { contentDescription = "行动输入" },
            placeholder = { Text("你要做什么") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 3,
            enabled = !busy,
        )
        IconButton(
            onClick = { val text = input.trim(); if (text.isNotEmpty()) { input = ""; onSubmit(text) } },
            enabled = canSend && !busy && input.isNotBlank() && input.length <= 2000,
        ) {
            Icon(Icons.Filled.Send, contentDescription = "发送")
        }
    }

    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("桌面指令", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                listOf("停", "降档", "升档").forEach { command ->
                    Text(
                        command,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable {
                            menuOpen = false
                            onTableCommand(command)
                        }.padding(horizontal = 8.dp, vertical = 14.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "规则查询",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable {
                        menuOpen = false
                        queryOpen = true
                    }.padding(horizontal = 8.dp, vertical = 14.dp),
                )
            }
        }
    }

    if (queryOpen) {
        AlertDialog(
            onDismissRequest = { queryOpen = false },
            title = { Text("规则查询") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text("查询本团规则") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { onSearchRules(query) }, enabled = !busy && query.isNotBlank()) { Text("查找出处") }
                    evidence.forEach { item ->
                        TextButton(onClick = {
                            queryOpen = false
                            selectedEvidence = item
                        }) { Text("${item.source.anchor} · ${authorityText(item.source.authority)}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { queryOpen = false }) { Text("关闭") } },
        )
    }

    selectedEvidence?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedEvidence = null },
            title = { Text(item.source.anchor) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.quote)
                    Text("${item.source.sourceId.substringAfterLast('/')} · ${item.source.anchor}", style = MaterialTheme.typography.bodySmall)
                    Text(authorityText(item.source.authority), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { selectedEvidence = null }) { Text("关闭") } },
        )
    }
}

private fun authorityText(value: String) = when (value) {
    "temporary", "house_rule" -> "本团裁定"
    "module_rule" -> "模组规则"
    "module_custom" -> "模组自定"
    "reference" -> "参考资料"
    "source" -> "资料原文"
    else -> value
}
