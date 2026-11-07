package com.ai.assistance.operit.rpg.agent.ui

import android.net.Uri
import com.ai.assistance.operit.rpg.agent.contract.*
import kotlinx.coroutines.flow.StateFlow

data class AgentModelOption(val id: String, val title: String, val fingerprint: String)
data class AgentCharacterOption(val id: String, val name: String, val detail: String)
data class AgentModuleOption(val ref: VersionRef, val title: String, val description: String,
    val characters: List<AgentCharacterOption>)
data class AgentEntry(val ref: CampaignRef, val title: String, val updatedAt: Long)
data class AgentPerson(val id: String, val name: String, val detail: String)
/** One recorded d20 roll, projected for the conversation flow. */
data class RollUi(val purpose: String, val expression: String, val faces: List<Int>, val modifier: Int,
    val total: Int, val target: Int?, val success: Boolean?) {
    /** 「{purpose} {total} 对 {target} · 成功/失败」; without [target] the total has nothing to compare to. */
    val summary: String get() = buildString {
        append(purpose); append(' '); append(total)
        if (target != null) {
            append(" 对 "); append(target)
            when (success) {
                true -> append(" · 成功")
                false -> append(" · 失败")
                null -> Unit
            }
        }
    }
    val detail: String get() = "骰面 ${faces.joinToString("、")} · 加值 ${if (modifier >= 0) "+$modifier" else "$modifier"} · $expression"
}

data class CharacterPoolUi(val id: String, val label: String, val current: Int, val max: Int)
data class CharacterConditionUi(val id: String, val label: String, val remaining: String?)
data class CharacterSlotUi(val level: Int, val current: Int, val max: Int)
data class CharacterCounterUi(val id: String, val label: String, val current: Int, val max: Int)
data class CharacterItemUi(val title: String, val quantity: Int)

/** Read-only player sheet projected from the d20 combatant, module labels and public campaign state. */
data class CharacterSheetUi(
    val name: String,
    val pools: List<CharacterPoolUi>,
    val conditions: List<CharacterConditionUi>,
    val slots: List<CharacterSlotUi>,
    val counters: List<CharacterCounterUi>,
    val items: List<CharacterItemUi>,
    val facts: List<String>,
    val objectives: List<String>,
)

data class AgentTurnUi(val id: String, val input: String, val narration: String, val stage: String,
    val result: String?, val confirmation: String?, val error: String?, val canResume: Boolean,
    val canCancel: Boolean, val evidence: List<RuleEvidence>, val rolls: List<RollUi>,
    val chapters: List<ChapterMark> = emptyList())
/** One reached chapter and the generated recap; the current chapter has no summary until play leaves it. */
data class AgentChapterUi(val id: String, val title: String, val ordinal: Int, val summary: String?)
data class AgentPlayUi(val id: String, val title: String, val location: String, val status: String, val clock: String,
    val modelFingerprint: String, val narratorFingerprint: String, val contentLevelLabel: String?, val turns: List<AgentTurnUi>, val people: List<AgentPerson>,
    val places: List<AgentPerson>, val facts: List<String>, val objectives: List<String>,
    val canAct: Boolean, val hasOlderTurns: Boolean, val playerCharacterId: String?, val sheet: CharacterSheetUi?,
    val currentChapter: String? = null, val chapterArchive: List<AgentChapterUi> = emptyList())
data class AgentDocumentUi(val id: String, val title: String)
data class AgentImportUi(val title: String, val detail: String, val module: Boolean)
data class AgentUiState(val entries: List<AgentEntry> = emptyList(), val modules: List<AgentModuleOption> = emptyList(),
    val models: List<AgentModelOption> = emptyList(), val documents: List<AgentDocumentUi> = emptyList(),
    val play: AgentPlayUi? = null, val notes: List<MemoryItem> = emptyList(), val evidence: List<RuleEvidence> = emptyList(),
    val busy: Boolean = false, val progress: String? = null, val message: String? = null,
    val preview: AgentImportUi? = null, val hasMoreEntries: Boolean = false,
    val documentText: Pair<String, String>? = null,
    val sampleModules: List<AgentModuleOption> = emptyList(),
    /** Local replies to `//` table commands; shown in the conversation flow, never stored in the event stream. */
    val notices: List<String> = emptyList(),
    /** Chapter-summary failures keyed by the turn that entered the new chapter; retried only on explicit user action. */
    val chapterSummaryErrors: Map<String, String> = emptyMap())

interface AgentUiController {
    val state: StateFlow<AgentUiState>
    fun refresh()
    fun loadMoreEntries()
    fun open(id: String)
    fun close()
    fun create(title: String, module: VersionRef, characterId: String, allowGeneration: Boolean, plannerModelId: String, narratorModelId: String)
    fun submit(text: String)
    fun confirm(turnId: String)
    fun resume(turnId: String)
    fun cancel(turnId: String)
    fun stop()
    fun loadOlder()
    fun bindPlanner(modelId: String)
    fun bindNarrator(modelId: String)
    fun previewFile(uri: Uri)
    fun previewExample(hash: String)
    fun commitImport()
    fun dismissPreview()
    fun importBackup(uri: Uri)
    fun exportBackup(uri: Uri)
    fun searchRules(query: String)
    fun loadNotes()
    fun correctNote(id: String, text: String)
    fun readNoteSource(note: MemoryItem)
    fun readCorrectionHistory(id: String)
    fun readDocument(id: String)
    fun attachDocuments(campaignId: String, documentIds: List<String>)
    fun dismissDocument()
    fun dismissMessage()
    fun retryChapterSummary(turnId: String)
}
