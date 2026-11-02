package com.ai.assistance.operit.rpg.ui

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Presentation receives only the core's public projection, never the campaign aggregate. */
data class RpgUiState(
    val campaigns: List<RpgCampaignSummary> = emptyList(),
    val models: List<RpgModelOption> = emptyList(),
    val campaign: RpgPlayState? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val importPreview: RpgImportPreview? = null
)
data class RpgCampaignSummary(val id: String, val title: String, val subtitle: String, val ended: Boolean = false)
data class RpgModelOption(val id: String, val title: String, val detail: String, val demo: Boolean = false)
data class RpgImportPreview(val title: String, val playerName: String, val detail: String)
data class RpgDetail(val id: String, val title: String, val detail: String, val sourceTurnId: String? = null, val actionEnabled: Boolean = true)
data class RpgTurnUi(
    val id: String,
    val input: String,
    val narration: String = "",
    val stage: String = "",
    val checkTitle: String? = null,
    val checkDetail: String = "",
    val question: String? = null,
    val confirmation: String? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val canCancel: Boolean = false
)
data class RpgPlayState(
    val id: String,
    val title: String,
    val playerName: String,
    val location: String,
    val hp: Int,
    val maxHp: Int,
    val time: String,
    val modelId: String?,
    val turns: List<RpgTurnUi> = emptyList(),
    val inventory: List<RpgDetail> = emptyList(),
    val clues: List<RpgDetail> = emptyList(),
    val locations: List<RpgDetail> = emptyList(),
    val facts: List<String> = emptyList(),
    val canAct: Boolean = true,
    val canUseBandage: Boolean = false,
    val bandageReason: String = "已用完",
    val ending: String? = null,
    val pendingNotice: String? = null,
    val canRebindNarration: Boolean = false
)
interface RpgUiController {
    val state: StateFlow<RpgUiState>
    fun refresh()
    fun openCampaign(id: String)
    fun closeCampaign()
    fun createCampaign(title: String, playerName: String, modelId: String)
    fun submit(text: String)
    fun confirm(turnId: String)
    fun retry(turnId: String)
    fun cancel(turnId: String)
    fun stop()
    fun useBandage()
    fun move(locationId: String)
    fun selectModel(modelId: String)
    fun previewImport(uri: Uri)
    fun confirmImport()
    fun dismissImport()
    fun export(uri: Uri)
    fun dismissMessage()
}
