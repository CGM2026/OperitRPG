package com.ai.assistance.operit.rpg.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.rpg.ui.avatar.Avatar
import com.ai.assistance.operit.rpg.ui.avatar.AvatarCatalog
import com.ai.assistance.operit.rpg.ui.avatar.AvatarKind
import com.ai.assistance.operit.rpg.ui.avatar.AvatarSpec

private const val MOTIF_COLUMNS = 6
private const val PAIR_COLUMNS = 4

@Composable
fun AvatarPickerSheet(
    campaignId: String,
    glyphId: String?,
    pairId: String?,
    onDismiss: () -> Unit,
    onSave: (glyphId: String?, pairId: String?) -> Unit,
) {
    val context = LocalContext.current
    val catalog = remember(context) { AvatarCatalog.load(context) }
    val default = remember(catalog, campaignId) { catalog.pick(AvatarKind.CAMPAIGN, campaignId) }
    val manual = glyphId != null && pairId != null
    var glyph by rememberSaveable(campaignId) {
        mutableStateOf(if (manual) requireNotNull(glyphId) else default.glyphId)
    }
    var pair by rememberSaveable(campaignId) {
        mutableStateOf(if (manual) requireNotNull(pairId) else default.pairId)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 680.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Avatar(AvatarSpec(AvatarKind.CAMPAIGN, glyph, pair), 72.dp)
        }
        catalog.motifs.chunked(MOTIF_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { motif ->
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { glyph = motif.id }
                                .border(
                                    width = 2.dp,
                                    color = if (glyph == motif.id) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .padding(4.dp),
                        ) {
                            Avatar(AvatarSpec(AvatarKind.CAMPAIGN, motif.id, pair), 44.dp)
                        }
                    }
                }
            }
        }
        catalog.pairs.chunked(PAIR_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { candidate ->
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .clickable { pair = candidate.id }
                                .border(
                                    width = 2.dp,
                                    color = if (pair == candidate.id) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .padding(3.dp),
                        ) {
                            Avatar(AvatarSpec(AvatarKind.CAMPAIGN, glyph, candidate.id), 40.dp)
                        }
                        Text(
                            candidate.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSave(null, null) }) { Text("用默认") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("取消") }
            Button(onClick = { onSave(glyph, pair) }) { Text("保存") }
        }
    }
}
