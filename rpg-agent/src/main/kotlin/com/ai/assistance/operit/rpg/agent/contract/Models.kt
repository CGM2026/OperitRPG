package com.ai.assistance.operit.rpg.agent.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val AGENT_API_VERSION = 1
const val LONGFORM_ENGINE = "longform-v1"
@Serializable data class CampaignRef(val engineId: String, val campaignId: String)
@Serializable data class VersionRef(val id: String, val version: String, val hash: String)
@Serializable enum class Audience { PUBLIC, GM, NPC, FACTION }
@Serializable data class Visibility(val audience: Audience, val subjectId: String? = null)
@Serializable data class RoleContext(val campaignId: String, val audience: Audience, val subjectId: String? = null)
@Serializable data class SourceRef(val sourceId: String, val sourceHash: String, val anchor: String, val authority: String)
@Serializable data class RuleEvidence(val ruleId: String, val source: SourceRef, val quote: String, val visibility: Visibility)
@Serializable data class FileDigest(val path: String, val size: Long, val sha256: String)
@Serializable data class ModuleManifest(val formatVersion: Int, val moduleId: String, val version: String, val title: String, val author: String, val description: String, val engineApiVersion: Int, val rulesetId: String, val rulesetVersion: String, val files: List<FileDigest>)
@Serializable data class Location(val id: String, val title: String, val description: String, val exits: List<String>)
@Serializable data class NpcState(val id: String, val name: String, val locationId: String, val status: String, val personality: String, val goal: String, val relationships: Map<String, Int> = emptyMap(), val nextActionAt: Long? = null)
@Serializable data class Fact(val id: String, val text: String, val visibility: Visibility, val locked: Boolean, val source: SourceRef)
@Serializable data class Claim(val id: String, val actorId: String, val text: String, val sourceEventSeq: Long, val visibility: Visibility, val supersedesId: String? = null)
@Serializable data class KnowledgeEdge(val knowerId: String, val factId: String, val learnedAtSeq: Long, val method: String, val belief: String)
@Serializable data class Objective(val id: String, val title: String, val chapter: Int, val status: String)
@Serializable data class Item(val id: String, val title: String, val ownerId: String, val quantity: Int)
@Serializable data class CharacterTemplate(val id: String, val name: String, val skills: Map<String, Int>, val resources: Map<String, Int>)
@Serializable data class GenerationPolicy(val allowedEntityTypes: Set<String>, val maxEntities: Int, val lockedFactIds: Set<String>)
@Serializable data class Condition(val type: String, val targetId: String? = null, val key: String? = null, val value: String? = null, val children: List<Condition> = emptyList())
@Serializable data class Effect(val type: String, val targetId: String, val key: String? = null, val amount: Int? = null, val value: String? = null, val source: SourceRef)
@Serializable data class EntityCreation(val type: String, val id: String, val title: String, val locationId: String, val description: String, val source: SourceRef)
@Serializable data class ActionDefinition(val id: String, val title: String, val actionType: String, val targetIds: List<String>, val skill: String?, val difficulty: String, val ruleId: String, val conditions: List<Condition>, val successEffects: List<Effect>, val failureEffects: List<Effect>)
/** Host-side timed or conditional event. `atMinute` set: timed. `atMinute` null: conditional, `conditions` non-empty. [label] is never shown to the player. */
@Serializable data class ScheduledEvent(val id: String, val label: String, val atMinute: Long? = null, val conditions: List<Condition> = emptyList(), val delayMinutes: Int = 0, val cancelIf: List<Condition> = emptyList(), val effects: List<Effect>, val notice: String? = null)
/** state: pending | armed | fired | skipped | cancelled. [fireAt] is set once armed. */
@Serializable data class ScheduleStatus(val state: String, val fireAt: Long? = null)
/** One recorded schedule transition. Replay applies it verbatim; `effects` is non-empty only for "fired", `fireAt` only for "armed". */
@Serializable data class ScheduledFiring(val scheduleId: String, val state: String, val effects: List<Effect>, val fireAt: Long? = null)
/** Cyclic clock face: [labels] repeat every `labels.size * minutesPerUnit` minutes and gameTime 0 falls on [startLabel]. */
@Serializable data class ClockSpec(val startLabel: String, val minutesPerUnit: Int, val labels: List<String>)
@Serializable data class ModuleWorld(val startLocationId: String, val characters: List<CharacterTemplate>, val locations: List<Location>, val npcs: List<NpcState>, val items: List<Item>, val facts: List<Fact>, val objectives: List<Objective>, val actions: List<ActionDefinition>, val policy: GenerationPolicy, val d20Combatants: List<D20Combatant> = emptyList(), val schedule: List<ScheduledEvent> = emptyList(), val initialFlags: Map<String, String> = emptyMap(), val clock: ClockSpec? = null)
@Serializable data class ResourceBounds(val min: Int, val max: Int)
@Serializable data class ContentLevel(val label: String, val narratorGuideline: String)
@Serializable data class ModuleRules(val adapterId: String, val adapterVersion: String, val skills: Set<String>, val resourceBounds: Map<String, ResourceBounds>, val difficulties: Map<String, Int>, val allowedEffects: Set<String>, val evidence: List<RuleEvidence>, val timeUnit: String, val d20: D20Rules? = null, val contentLevels: List<ContentLevel> = emptyList(), val defaultContentLevel: Int = 0)
@Serializable data class ValidatedModule(val manifest: ModuleManifest, val contentHash: String, val world: ModuleWorld, val rules: ModuleRules, val sources: Map<String, String>, val originalFiles: Map<String, String> = emptyMap())
@Serializable data class WorldState(val locationId: String, val resources: Map<String, Int>, val skills: Map<String, Int>, val npcs: List<NpcState>, val items: List<Item>, val facts: List<Fact>, val objectives: List<Objective>, val knowledge: List<KnowledgeEdge>, val generated: List<EntityCreation> = emptyList(), val gameTime: Long = 0, val d20: D20State? = null, val flags: Map<String, String> = emptyMap(), val schedule: Map<String, ScheduleStatus> = emptyMap())
@Serializable data class Campaign(val id: String, val title: String, val module: VersionRef, val rules: VersionRef, val revision: Long, val lastEventSeq: Long, val world: WorldState, val allowGeneration: Boolean, val modelFingerprint: String, val narratorFingerprint: String, val updatedAt: Long, val nextTurnOrdinal: Long = 1, val contentLevel: Int = 0, val sceneHold: Boolean = false)
@Serializable enum class TurnPhase { RECEIVED, GATHERING, PROPOSED, AWAITING_CONFIRMATION, READY, COMMITTED, NARRATING, COMPLETE, CANCELLED }
@Serializable data class ActionProposal(val actionType: String, val targetIds: List<String>, val skill: String?, val difficulty: String, val ruleRefs: List<String>, val conditions: List<Condition>, val successEffects: List<Effect>, val failureEffects: List<Effect>, val creations: List<EntityCreation> = emptyList(), val requiresConfirmation: Boolean = false, val publicSummary: String, val temporaryRuling: Boolean = false, val d20: List<D20Action> = emptyList())
@Serializable data class FrozenPlan(val baseRevision: Long, val moduleHash: String, val rulesHash: String, val modelFingerprint: String, val proposal: ActionProposal, val evidence: List<RuleEvidence>, val planHash: String)
@Serializable data class DiceRoll(val sides: Int, val value: Int, val threshold: Int)
@Serializable data class WorldEvent(val eventVersion: Int, val campaignId: String, val seq: Long, val turnId: String, val effects: List<Effect>, val creations: List<EntityCreation>, val dice: DiceRoll?, val visibility: Visibility, val sources: List<SourceRef>, val rolls: List<RollRecord> = emptyList(), val d20Changes: List<D20Change> = emptyList(), val scheduled: List<ScheduledFiring> = emptyList())
@Serializable data class CommittedResult(val turnId: String, val eventSeq: Long, val success: Boolean, val dice: DiceRoll?, val publicSummary: String, val evidence: List<RuleEvidence>, val rolls: List<RollRecord> = emptyList(), val notices: List<String> = emptyList())
@Serializable data class ToolCall(val invocationId: String, val name: String, val arguments: JsonObject)
@Serializable data class ToolResult(val invocationId: String, val toolSchemaVersion: Int, val argumentHash: String, val revision: Long, val sourceRefs: List<SourceRef>, val output: String)
@Serializable data class AgentStep(val version: Int, val kind: String, val calls: List<ToolCall>? = null, val proposal: ActionProposal? = null, val text: String? = null)
@Serializable data class Usage(val inputTokens: Int, val outputTokens: Int)
@Serializable data class ModelAttempt(val id: String, val purpose: String, val fingerprint: String, val status: String, val usage: Usage? = null)
@Serializable data class Turn(val id: String, val campaignId: String, val ordinal: Long, val clientMessageId: String, val playerText: String, val baseRevision: Long, val phase: TurnPhase, val plan: FrozenPlan? = null, val result: CommittedResult? = null, val narration: String? = null, val attempts: List<ModelAttempt> = emptyList(), val tools: List<ToolResult> = emptyList(), val error: String? = null)
@Serializable data class MemoryItem(val id: String, val campaignId: String, val text: String, val entityIds: List<String>, val fromSeq: Long, val toSeq: Long, val visibility: Visibility, val status: String, val supersedesId: String? = null, val sourceTurnId: String? = null, val kind: String = "EVENT")
@Serializable data class CampaignHeader(val ref: CampaignRef, val title: String, val updatedAt: Long)
@Serializable data class BackupManifestV2(val archiveFormatVersion: Int, val engineId: String, val engineVersion: Int, val eventSchemaVersion: Int, val module: VersionRef, val rules: VersionRef, val files: List<FileDigest>)
@Serializable data class ContextPacket(val purpose: String, val system: String, val input: String, val maxOutputTokens: Int)
@Serializable data class TextModelReply(val text: String, val usage: Usage)
data class CampaignSession(val campaign: Campaign, val pendingTurn: Turn?)
