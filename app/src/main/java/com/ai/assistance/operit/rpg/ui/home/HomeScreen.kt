package com.ai.assistance.operit.rpg.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.rpg.domain.CampaignShelf
import com.ai.assistance.operit.rpg.domain.ShelfCampaign
import com.ai.assistance.operit.rpg.ui.avatar.Avatar
import com.ai.assistance.operit.rpg.ui.avatar.AvatarCatalog
import com.ai.assistance.operit.rpg.ui.avatar.AvatarKind
import com.ai.assistance.operit.rpg.ui.avatar.AvatarSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    shelf: CampaignShelf,
    onOpenCampaign: (String) -> Unit,
    onNewCampaign: () -> Unit,
    onSettings: () -> Unit,
) {
    val controller: HomeViewModel = viewModel(factory = remember(shelf) { HomeViewModelFactory(shelf) })
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val catalog = remember(context) { AvatarCatalog.load(context) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var campaignMenu by remember { mutableStateOf<ShelfCampaign?>(null) }
    var groupMenu by remember { mutableStateOf<HomeSection?>(null) }
    var renameCampaign by remember { mutableStateOf<ShelfCampaign?>(null) }
    var renameCampaignText by remember { mutableStateOf("") }
    var renameGroup by remember { mutableStateOf<HomeSection?>(null) }
    var renameGroupText by remember { mutableStateOf("") }
    var newGroupOpen by remember { mutableStateOf(false) }
    var newGroupFor by remember { mutableStateOf<String?>(null) }
    var newGroupText by remember { mutableStateOf("") }
    var deleteCampaign by remember { mutableStateOf<ShelfCampaign?>(null) }
    var deleteGroup by remember { mutableStateOf<HomeSection?>(null) }
    var avatarCampaign by remember { mutableStateOf<ShelfCampaign?>(null) }
    LaunchedEffect(controller) { controller.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (search) {
                    OutlinedTextField(
                        query,
                        { query = it; controller.setQuery(it) },
                        Modifier.weight(1f),
                        placeholder = { Text("搜索战役") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        search = false
                        query = ""
                        controller.setQuery("")
                    }) { Text("取消") }
                } else {
                    Text("跑团", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { search = true }) { Icon(Icons.Filled.Search, contentDescription = "搜索") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewCampaign) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.sections.forEach { section ->
                item(key = "section:${section.key}") {
                    SectionHeader(
                        section = section,
                        onToggle = {
                            val groupId = section.groupId
                            if (groupId != null) controller.setGroupCollapsed(groupId, !section.collapsed)
                        },
                        onLongPress = { groupMenu = section },
                    )
                }
                if (!section.collapsed) {
                    items(section.campaigns, key = { it.campaignId }) { campaign ->
                        CampaignRow(
                            campaign = campaign,
                            avatar = campaignAvatar(catalog, campaign),
                            onClick = { onOpenCampaign(campaign.campaignId) },
                            onLongPress = { campaignMenu = campaign },
                        )
                    }
                }
            }
            if (!state.loading && state.sections.isEmpty()) {
                item { Text("还没有战役。", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    campaignMenu?.let { campaign ->
        ModalBottomSheet(
            onDismissRequest = { campaignMenu = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    campaign.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SheetAction(if (campaign.pinned) "取消置顶" else "置顶") {
                    controller.setPinned(campaign.campaignId, !campaign.pinned)
                    campaignMenu = null
                }
                SheetAction("重命名") {
                    renameCampaign = campaign
                    renameCampaignText = campaign.title
                    campaignMenu = null
                }
                Text(
                    "移动到分组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                state.sections.filter { it.groupId != null }.forEach { group ->
                    SheetAction(group.title + if (campaign.groupId == group.groupId) " · 当前" else "") {
                        controller.moveToGroup(campaign.campaignId, group.groupId)
                        campaignMenu = null
                    }
                }
                SheetAction("新建分组") {
                    newGroupText = ""
                    newGroupFor = campaign.campaignId
                    newGroupOpen = true
                    campaignMenu = null
                }
                if (campaign.groupId != null) {
                    SheetAction("移出分组") {
                        controller.moveToGroup(campaign.campaignId, null)
                        campaignMenu = null
                    }
                }
                SheetAction("换头像") {
                    avatarCampaign = campaign
                    campaignMenu = null
                }
                SheetAction("隐藏") {
                    controller.setHidden(campaign.campaignId, true)
                    campaignMenu = null
                }
                SheetAction("删除") {
                    deleteCampaign = campaign
                    campaignMenu = null
                }
            }
        }
    }

    groupMenu?.let { section ->
        ModalBottomSheet(
            onDismissRequest = { groupMenu = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SheetAction("重命名") {
                    renameGroup = section
                    renameGroupText = section.title
                    groupMenu = null
                }
                SheetAction("新建分组") {
                    newGroupText = ""
                    newGroupFor = null
                    newGroupOpen = true
                    groupMenu = null
                }
                SheetAction("删除分组") {
                    deleteGroup = section
                    groupMenu = null
                }
            }
        }
    }

    avatarCampaign?.let { campaign ->
        ModalBottomSheet(
            onDismissRequest = { avatarCampaign = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            AvatarPickerSheet(
                campaignId = campaign.campaignId,
                glyphId = campaign.avatarGlyph,
                pairId = campaign.avatarPair,
                onDismiss = { avatarCampaign = null },
                onSave = { glyph, pair ->
                    controller.setAvatar(campaign.campaignId, glyph, pair)
                    avatarCampaign = null
                },
            )
        }
    }

    renameCampaign?.let { campaign ->
        AlertDialog(
            onDismissRequest = { renameCampaign = null },
            title = { Text("重命名战役") },
            text = {
                OutlinedTextField(
                    renameCampaignText,
                    { renameCampaignText = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.rename(campaign.campaignId, renameCampaignText.trim())
                        renameCampaign = null
                    },
                    enabled = renameCampaignText.isNotBlank() && renameCampaignText.length <= 100,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameCampaign = null }) { Text("取消") } },
        )
    }

    renameGroup?.let { section ->
        AlertDialog(
            onDismissRequest = { renameGroup = null },
            title = { Text("重命名分组") },
            text = {
                OutlinedTextField(
                    renameGroupText,
                    { renameGroupText = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val groupId = section.groupId
                        if (groupId != null) controller.renameGroup(groupId, renameGroupText.trim())
                        renameGroup = null
                    },
                    enabled = renameGroupText.isNotBlank() && renameGroupText.length <= 60,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameGroup = null }) { Text("取消") } },
        )
    }

    if (newGroupOpen) {
        AlertDialog(
            onDismissRequest = { newGroupOpen = false },
            title = { Text("新建分组") },
            text = {
                OutlinedTextField(
                    newGroupText,
                    { newGroupText = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.createGroup(newGroupText.trim(), newGroupFor)
                        newGroupOpen = false
                        newGroupFor = null
                    },
                    enabled = newGroupText.isNotBlank() && newGroupText.length <= 60,
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { newGroupOpen = false }) { Text("取消") } },
        )
    }

    deleteCampaign?.let { campaign ->
        AlertDialog(
            onDismissRequest = { deleteCampaign = null },
            title = { Text("删除战役") },
            text = { Text("删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    controller.delete(campaign.campaignId)
                    deleteCampaign = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCampaign = null }) { Text("取消") } },
        )
    }

    deleteGroup?.let { section ->
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("删除分组") },
            text = { Text("组内战役将回到未分组。") },
            confirmButton = {
                TextButton(onClick = {
                    val groupId = section.groupId
                    if (groupId != null) controller.deleteGroup(groupId)
                    deleteGroup = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionHeader(section: HomeSection, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val group = section.groupId != null
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (group) onToggle() },
                onLongClick = { if (group) onLongPress() },
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            section.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (group) {
            Text(
                if (section.collapsed) "▸" else "▾",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CampaignRow(
    campaign: ShelfCampaign,
    avatar: AvatarSpec,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatar, 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    campaign.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    campaign.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                relativeTime(campaign.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

private fun campaignAvatar(catalog: AvatarCatalog, campaign: ShelfCampaign): AvatarSpec {
    val glyph = campaign.avatarGlyph
    val pair = campaign.avatarPair
    return if (glyph != null && pair != null) {
        AvatarSpec(AvatarKind.CAMPAIGN, glyph, pair)
    } else {
        catalog.pick(AvatarKind.CAMPAIGN, campaign.campaignId)
    }
}

/** Today shows HH:mm, yesterday is 昨天, the rest of the week a weekday, older dates M月d日. */
fun relativeTime(updatedAt: Long, now: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(updatedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(at.toLocalDate(), today)
    return when {
        days <= 0 -> at.format(DateTimeFormatter.ofPattern("HH:mm"))
        days == 1L -> "昨天"
        days < 7L -> when (at.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }
        else -> at.format(DateTimeFormatter.ofPattern("M月d日"))
    }
}
