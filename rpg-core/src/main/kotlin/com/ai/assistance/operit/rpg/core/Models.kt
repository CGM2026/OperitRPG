package com.ai.assistance.operit.rpg.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object RpgJson { val instance = Json { encodeDefaults = true; ignoreUnknownKeys = false } }
fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable enum class CampaignStatus { ACTIVE, SUCCESS, FAILED_DEPARTED, FAILED_INCAPACITATED }
@Serializable enum class TurnStage { RECEIVED, PLANNING, NEEDS_CONFIRMATION, PREPARED, RESOLVED, NARRATING, COMPLETE, CANCELLED }
@Serializable enum class PlanKind { ACTION, DIALOGUE, OOC, CLARIFY }
@Serializable data class Plan(val kind: PlanKind, val actionId: String? = null, val requiresConfirmation: Boolean = false, val publicIntent: String? = null, val npcId: String? = null, val question: String? = null)
@Serializable data class ModelConfiguration(val configurationId: String, val providerId: String, val modelId: String, val fingerprint: String)
@Serializable data class Usage(val inputTokens: Int = 0, val outputTokens: Int = 0)
@Serializable data class ModelReply(val text: String, val usage: Usage = Usage())
data class ModelRequest(val attemptId: String, val configuration: ModelConfiguration, val systemPrompt: String, val userPrompt: String, val maxOutputTokens: Int)
@Serializable data class Clue(val id: String, val title: String, val content: String, val sourceTurnId: String, val evidenceQuality: String = "clear")
@Serializable data class CampaignState(
    val playerName: String = "林舟", val hp: Int = 6, val maxHp: Int = 6,
    val locationId: String = "yard", val tick: Int = 0, val alarm: Int = 0,
    val inventory: Map<String, Int> = mapOf("bandage" to 1),
    val discoveredClues: List<Clue> = emptyList(), val knownFacts: Map<String, String> = emptyMap(),
    val visitedLocations: Set<String> = setOf("yard"), val resolvedActionIds: Set<String> = emptySet(),
    val accessArchive: Boolean = false, val rescued: Boolean = false,
    val status: CampaignStatus = CampaignStatus.ACTIVE, val revision: Long = 0
)
@Serializable data class DiceResult(val skill: String, val skillValue: Int, val threshold: Int, val roll: Int, val success: Boolean, val difficulty: String)
@Serializable data class TurnResult(val actionId: String? = null, val dice: DiceResult? = null, val publicSummary: String, val timeCost: Int = 0, val hpChange: Int = 0, val addedClues: List<Clue> = emptyList(), val status: CampaignStatus = CampaignStatus.ACTIVE)
@Serializable data class GameEvent(val id: String, val campaignId: String, val turnId: String, val sequence: Long, val type: String, val actionId: String, val roll: Int? = null, val schemaVersion: Int = 1, val visibility: String = "PUBLIC")
@Serializable data class RequestAttempt(val id: String, val stage: TurnStage, val attemptNo: Int, val modelFingerprint: String, var status: String = "ACTIVE", var usage: Usage? = null, var durationMs: Long = 0, var errorCode: String? = null)
@Serializable data class Turn(
    val id: String, val campaignId: String, val ordinal: Int, val clientMessageId: String,
    val inputText: String, var stage: TurnStage, val baseRevision: Long, var model: ModelConfiguration,
    val createdAt: Long, var attemptId: String? = null, var plan: Plan? = null,
    var planHash: String? = null, var actionHash: String? = null, var result: TurnResult? = null,
    var narration: String? = null, var errorCode: String? = null,
    val attempts: MutableList<RequestAttempt> = mutableListOf()
)
@Serializable data class CampaignRecord(
    val id: String, val title: String, var state: CampaignState, var model: ModelConfiguration,
    val createdAt: Long, var updatedAt: Long = createdAt,
    val scenarioId: String = "rainy-dock-v1", val scenarioVersion: String = "1.0.0",
    val scenarioHash: String = RainyDock.hash, val rulesVersion: String = "prototype-d100-v1",
    val turns: MutableList<Turn> = mutableListOf(), val events: MutableList<GameEvent> = mutableListOf()
)
@Serializable data class PublicLocation(val id: String, val name: String, val description: String, val accessible: Boolean = true)
@Serializable data class PublicAction(val id: String, val description: String)
@Serializable data class PublicTurn(val id: String, val ordinal: Int, val inputText: String, val stage: TurnStage, val publicIntent: String?, val question: String?, val planHash: String?, val result: TurnResult?, val narration: String?, val errorCode: String?)
@Serializable data class PublicCampaignView(val id: String, val title: String, val playerName: String, val hp: Int, val maxHp: Int, val tick: Int, val location: PublicLocation, val adjacentLocations: List<PublicLocation>, val inventory: Map<String, Int>, val clues: List<Clue>, val facts: Map<String, String>, val status: CampaignStatus, val revision: Long, val turns: List<PublicTurn>, val allowedActions: List<PublicAction>, val model: ModelConfiguration)
@Serializable data class NpcKnowledgeView(val id: String, val voice: String, val facts: List<String>)
enum class OutcomeCode { APPLIED, ALREADY_APPLIED, NEEDS_CONFIRMATION, BUSY, STALE_REVISION, INVALID_PLAN, FAILED, CANCELLED }
data class TurnOutcome(val code: OutcomeCode, val turnId: String? = null, val message: String? = null)
