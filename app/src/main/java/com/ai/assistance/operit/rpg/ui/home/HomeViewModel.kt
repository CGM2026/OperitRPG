package com.ai.assistance.operit.rpg.ui.home

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ai.assistance.operit.rpg.domain.CampaignShelf
import com.ai.assistance.operit.rpg.domain.Shelf
import com.ai.assistance.operit.rpg.domain.ShelfCampaign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A rendered shelf segment. [groupId] is null for the pinned and ungrouped segments. */
data class HomeSection(
    val key: String,
    val title: String,
    val groupId: String?,
    val collapsed: Boolean,
    val campaigns: List<ShelfCampaign>,
)

data class HomeUiState(
    val sections: List<HomeSection> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val message: String? = null,
)

enum class ExportKind { BACKUP, REPORT, RECAP }

/**
 * Pinned campaigns first, then groups ordered by sortKey, then ungrouped campaigns.
 * Hidden campaigns never reach the home list; [query] filters by title.
 */
fun buildSections(shelf: Shelf, query: String): List<HomeSection> {
    val needle = query.trim()
    val visible = shelf.campaigns.filter { campaign ->
        !campaign.hidden && (needle.isEmpty() || campaign.title.contains(needle, ignoreCase = true))
    }
    val pinned = visible.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val sections = mutableListOf<HomeSection>()
    if (pinned.isNotEmpty()) {
        sections += HomeSection("pinned", "置顶", null, false, pinned)
    }
    shelf.groups.sortedWith(compareBy({ it.sortKey }, { it.name })).forEach { group ->
        val members = visible
            .filter { !it.pinned && it.groupId == group.id }
            .sortedByDescending { it.updatedAt }
        sections += HomeSection("group:${group.id}", group.name, group.id, group.collapsed, members)
    }
    val ungrouped = visible
        .filter { !it.pinned && it.groupId == null }
        .sortedByDescending { it.updatedAt }
    if (ungrouped.isNotEmpty()) {
        sections += HomeSection("ungrouped", "未分组", null, false, ungrouped)
    }
    return sections
}

class HomeViewModel(
    private val shelf: CampaignShelf,
    private val files: HomeFiles,
    private val contentResolver: ContentResolver,
) : ViewModel() {
    private val mutable = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutable.asStateFlow()
    private var loaded = Shelf(emptyList(), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun setQuery(query: String) {
        mutable.update { it.copy(query = query, sections = buildSections(loaded, query)) }
    }

    fun createGroup(name: String, moveCampaignId: String? = null) = mutate { target ->
        val id = target.createGroup(name)
        if (moveCampaignId != null) target.moveToGroup(moveCampaignId, id)
    }

    fun renameGroup(groupId: String, name: String) = mutate { it.renameGroup(groupId, name) }

    fun deleteGroup(groupId: String) = mutate { it.deleteGroup(groupId) }

    fun setGroupCollapsed(groupId: String, collapsed: Boolean) =
        mutate { it.setGroupCollapsed(groupId, collapsed) }

    fun moveToGroup(campaignId: String, groupId: String?) =
        mutate { it.moveToGroup(campaignId, groupId) }

    fun setPinned(campaignId: String, pinned: Boolean) = mutate { it.setPinned(campaignId, pinned) }

    fun setFinished(campaignId: String, finished: Boolean) =
        mutate { it.setFinished(campaignId, finished) }

    fun setHidden(campaignId: String, hidden: Boolean) = mutate { it.setHidden(campaignId, hidden) }

    fun rename(campaignId: String, title: String) = mutate { it.rename(campaignId, title) }

    fun setAvatar(campaignId: String, glyphId: String?, pairId: String?) =
        mutate { it.setAvatar(campaignId, glyphId, pairId) }

    fun delete(campaignId: String) = mutate { it.delete(campaignId) }

    fun export(campaignId: String, uri: Uri, kind: ExportKind) = fileOperation("已导出。") {
        val output = contentResolver.openOutputStream(uri, "wt") ?: error("FILE_UNAVAILABLE")
        output.use { stream ->
            when (kind) {
                ExportKind.BACKUP -> files.exportBackup(campaignId, stream)
                ExportKind.REPORT -> files.exportReport(campaignId, stream)
                ExportKind.RECAP -> files.exportRecap(campaignId, stream)
            }
        }
    }

    fun importBackup(uri: Uri) = fileOperation("已导入备份。") {
        val input = contentResolver.openInputStream(uri) ?: error("FILE_UNAVAILABLE")
        input.use { files.importBackup(it) }
    }

    fun dismissMessage() {
        mutable.update { it.copy(message = null) }
    }

    private fun mutate(block: (CampaignShelf) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block(shelf) }
            reload()
        }
    }

    private fun fileOperation(success: String, block: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                mutable.update { it.copy(message = success) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(message = "文件操作未完成：${e.message ?: "未知错误"}") }
            }
        }
    }

    private suspend fun reload() {
        val snapshot = withContext(Dispatchers.IO) { shelf.load() }
        loaded = snapshot
        mutable.update {
            it.copy(sections = buildSections(snapshot, it.query), loading = false)
        }
    }
}

class HomeViewModelFactory(
    private val shelf: CampaignShelf,
    private val files: HomeFiles,
    private val contentResolver: ContentResolver,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(shelf, files, contentResolver) as T
}
