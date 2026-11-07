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
data class AgentTurnUi(val id: String, val input: String, val narration: String, val stage: String,
    val result: String?, val confirmation: String?, val error: String?, val canResume: Boolean,
    val canCancel: Boolean, val evidence: List<RuleEvidence>)
data class AgentPlayUi(val id: String, val title: String, val location: String, val status: String, val clock: String,
    val modelFingerprint: String, val narratorFingerprint: String, val contentLevelLabel: String?, val turns: List<AgentTurnUi>, val people: List<AgentPerson>,
    val places: List<AgentPerson>, val facts: List<String>, val objectives: List<String>,
    val canAct: Boolean, val hasOlderTurns: Boolean)
data class AgentDocumentUi(val id: String, val title: String)
data class AgentImportUi(val title: String, val detail: String, val module: Boolean)
data class AgentDraftUi(val title: String, val json: String, val issues: List<String>)
data class AgentUiState(val entries: List<AgentEntry> = emptyList(), val modules: List<AgentModuleOption> = emptyList(),
    val models: List<AgentModelOption> = emptyList(), val documents: List<AgentDocumentUi> = emptyList(),
    val play: AgentPlayUi? = null, val notes: List<MemoryItem> = emptyList(), val evidence: List<RuleEvidence> = emptyList(),
    val busy: Boolean = false, val progress: String? = null, val message: String? = null,
    val preview: AgentImportUi? = null, val draft: AgentDraftUi? = null, val hasMoreEntries: Boolean = false,
    val documentText: Pair<String, String>? = null, val hasSavedDraft: Boolean = false,
    val sampleModules: List<AgentModuleOption> = emptyList(),
    /** Local replies to `//` table commands; shown in the conversation flow, never stored in the event stream. */
    val notices: List<String> = emptyList())

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
    fun organize(documentIds: List<String>, modelId: String, callLimit: Int)
    fun readDocument(id: String)
    fun attachDocuments(campaignId: String, documentIds: List<String>)
    fun dismissDocument()
    fun reopenDraft()
    fun validateDraft(json: String)
    fun activateDraft()
    fun dismissDraft()
    fun dismissMessage()
}
