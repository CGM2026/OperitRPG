package com.ai.assistance.operit.rpg.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.encodeToString

class TurnCoordinator(private val store: GameStore, private val gateway: ModelGateway, private val dice: DiceSource = SecureRandomDice(), private val clock: Clock = SystemClock, private val ids: IdGenerator = UuidGenerator) {
    suspend fun createCampaign(title: String, playerName: String, model: ModelConfiguration): String {
        require(title.isNotBlank() && title.length <= 100 && playerName.isNotBlank() && playerName.length <= 40)
        val id = ids.next(); store.create(CampaignRecord(id, title, RainyDock.initialState(playerName), model, clock.now())); return id
    }
    suspend fun publicView(campaignId: String): PublicCampaignView = store.transaction(campaignId) { RainyDock.publicView(it) }
    suspend fun switchModel(campaignId: String, model: ModelConfiguration): Boolean = store.transaction(campaignId) { r ->
        if (r.turns.any { !terminal(it.stage) }) false else { r.model = model; true }
    }
    /** Explicit recovery selection after narration failed; never changes committed mechanics. */
    suspend fun rebindInterruptedNarration(campaignId: String, turnId: String, newModel: ModelConfiguration): Boolean = store.transaction(campaignId) { r ->
        val t = r.turns.single { it.id == turnId }
        if (t.result == null || t.errorCode == null || t.stage !in setOf(TurnStage.RESOLVED, TurnStage.NARRATING) || t.attempts.any { it.status == "ACTIVE" }) return@transaction false
        t.model = newModel
        t.stage = TurnStage.RESOLVED
        t.attemptId = null
        r.model = newModel
        r.updatedAt = clock.now()
        true
    }
    /** UI-selected actions use the same transaction/rules path without a paid planning request. */
    suspend fun submitAction(campaignId: String, clientMessageId: String, expectedRevision: Long, actionId: String): TurnOutcome {
        if (clientMessageId.isBlank()) return TurnOutcome(OutcomeCode.INVALID_PLAN, message = "CLIENT_MESSAGE_ID_REQUIRED")
        val created = store.transaction(campaignId) { r ->
            val previous = r.turns.firstOrNull { it.clientMessageId == clientMessageId }
            when {
                previous != null -> TurnOutcome(OutcomeCode.ALREADY_APPLIED, previous.id)
                r.state.revision != expectedRevision -> TurnOutcome(OutcomeCode.STALE_REVISION)
                r.turns.any { !terminal(it.stage) } -> TurnOutcome(OutcomeCode.BUSY)
                RainyDock.allowedActions(r.state).none { it.id == actionId } -> TurnOutcome(OutcomeCode.INVALID_PLAN, message = "ACTION_UNAVAILABLE")
                else -> {
                    val action = RainyDock.actions.single { it.id == actionId }
                    val plan = Plan(PlanKind.ACTION, actionId = actionId, publicIntent = action.description)
                    val t = Turn(ids.next(), campaignId, r.turns.size + 1, clientMessageId, action.description, TurnStage.PREPARED, expectedRevision, r.model, clock.now(), plan = plan, planHash = sha256(RpgJson.instance.encodeToString(plan)), actionHash = RainyDock.actionHash(actionId))
                    r.turns += t; r.updatedAt = clock.now(); TurnOutcome(OutcomeCode.APPLIED, t.id)
                }
            }
        }
        return if (created.code == OutcomeCode.APPLIED) resolvePrepared(campaignId, created.turnId!!, expectedRevision) else created
    }
    suspend fun submit(campaignId: String, clientMessageId: String, expectedRevision: Long, text: String): TurnOutcome {
        if (text.isBlank() || text.length > 2000 || clientMessageId.isBlank()) return TurnOutcome(OutcomeCode.INVALID_PLAN, message = "INPUT_LENGTH")
        val created = store.transaction(campaignId) { r ->
            val previous = r.turns.firstOrNull { it.clientMessageId == clientMessageId }
            when {
                previous != null -> TurnOutcome(OutcomeCode.ALREADY_APPLIED, previous.id)
                r.state.revision != expectedRevision -> TurnOutcome(OutcomeCode.STALE_REVISION)
                r.turns.any { !terminal(it.stage) } -> TurnOutcome(OutcomeCode.BUSY)
                else -> { val t = Turn(ids.next(), campaignId, r.turns.size + 1, clientMessageId, text, TurnStage.RECEIVED, expectedRevision, r.model, clock.now()); r.turns += t; r.updatedAt = clock.now(); TurnOutcome(OutcomeCode.APPLIED, t.id) }
            }
        }
        return if (created.code == OutcomeCode.APPLIED) plan(campaignId, created.turnId!!) else created
    }
    private suspend fun plan(campaignId: String, turnId: String): TurnOutcome {
        val request = startRequest(campaignId, turnId, true) ?: return TurnOutcome(OutcomeCode.BUSY, turnId)
        val started = clock.now()
        try {
            val reply = gateway.plan(request)
            val parsed = PlanParser.parse(reply.text)
            val outcome = store.transaction(campaignId) { r ->
                val t = r.turns.single { it.id == turnId }
                if (t.attemptId != request.attemptId || t.stage != TurnStage.PLANNING) return@transaction TurnOutcome(OutcomeCode.BUSY, turnId, "STALE_ATTEMPT")
                require(parsed.kind != PlanKind.ACTION || RainyDock.allowedActions(r.state).any { it.id == parsed.actionId }) { "ACTION_UNAVAILABLE" }
                require(parsed.kind != PlanKind.DIALOGUE || (r.state.status == CampaignStatus.ACTIVE && RainyDock.npcAt(r.state.locationId) == parsed.npcId)) { "NPC_UNAVAILABLE" }
                ContextBuilder.validateNarration(parsed.publicIntent ?: parsed.question ?: "有效", r.state)
                t.plan = parsed; t.planHash = sha256(RpgJson.instance.encodeToString(parsed)); t.actionHash = parsed.actionId?.let { RainyDock.actionHash(it) }
                finishAttempt(t, reply, clock.now() - started)
                if (parsed.kind == PlanKind.CLARIFY) { t.narration = parsed.question; t.stage = TurnStage.COMPLETE; TurnOutcome(OutcomeCode.APPLIED, turnId) }
                else if (parsed.requiresConfirmation) { t.stage = TurnStage.NEEDS_CONFIRMATION; TurnOutcome(OutcomeCode.NEEDS_CONFIRMATION, turnId) }
                else { t.stage = TurnStage.PREPARED; TurnOutcome(OutcomeCode.APPLIED, turnId) }
            }
            if (outcome.code != OutcomeCode.APPLIED || parsed.kind == PlanKind.CLARIFY) return outcome
            return resolvePrepared(campaignId, turnId, requestRevision(campaignId, turnId))
        } catch (e: Exception) { if (e is TimeoutCancellationException) return fail(campaignId, turnId, request.attemptId, "MODEL_TIMEOUT"); if (e is CancellationException) { withContext(NonCancellable) { fail(campaignId, turnId, request.attemptId, "INTERRUPTED") }; throw e }; return fail(campaignId, turnId, request.attemptId, if (e is IllegalArgumentException || e is IllegalStateException) "INVALID_MODEL_OUTPUT" else "MODEL_REQUEST_FAILED") }
    }
    suspend fun confirmPlan(campaignId: String, turnId: String, expectedPlanHash: String): TurnOutcome {
        val confirmed = store.transaction(campaignId) { r -> val t = r.turns.single { it.id == turnId }; when {
            t.planHash != expectedPlanHash -> TurnOutcome(OutcomeCode.INVALID_PLAN, turnId)
            t.stage != TurnStage.NEEDS_CONFIRMATION -> TurnOutcome(OutcomeCode.BUSY, turnId)
            else -> { t.stage = TurnStage.PREPARED; TurnOutcome(OutcomeCode.APPLIED, turnId) }
        } }
        return if (confirmed.code == OutcomeCode.APPLIED) resolvePrepared(campaignId, turnId, requestRevision(campaignId, turnId)) else confirmed
    }
    suspend fun resolvePrepared(campaignId: String, turnId: String, expectedRevision: Long): TurnOutcome {
        val applied = store.transaction(campaignId) { r ->
            val t = r.turns.single { it.id == turnId }
            when {
                t.result != null -> TurnOutcome(OutcomeCode.ALREADY_APPLIED, turnId)
                t.stage != TurnStage.PREPARED -> TurnOutcome(OutcomeCode.BUSY, turnId)
                r.state.revision != expectedRevision || t.baseRevision != expectedRevision -> TurnOutcome(OutcomeCode.STALE_REVISION, turnId)
                else -> {
                    val p = requireNotNull(t.plan)
                    if (p.kind == PlanKind.ACTION) {
                        if (t.actionHash != RainyDock.actionHash(p.actionId!!)) return@transaction TurnOutcome(OutcomeCode.INVALID_PLAN, turnId)
                        val resolved = RainyDock.resolve(r.state, p.actionId, turnId, dice)
                        r.state = resolved.first; t.result = resolved.second
                        r.events += GameEvent(ids.next(), campaignId, turnId, r.events.size + 1L, "ACTION_RESOLVED", p.actionId, resolved.second.dice?.roll)
                    } else t.result = TurnResult(publicSummary = if (p.kind == PlanKind.DIALOGUE) "与在场人物交谈，世界状态不变。" else "场外规则或回顾，世界状态不变。", status = r.state.status)
                    t.stage = TurnStage.RESOLVED; r.updatedAt = clock.now(); TurnOutcome(OutcomeCode.APPLIED, turnId)
                }
            }
        }
        return if (applied.code == OutcomeCode.APPLIED) narrate(campaignId, turnId) else applied
    }
    private suspend fun narrate(campaignId: String, turnId: String): TurnOutcome {
        val request = startRequest(campaignId, turnId, false) ?: return TurnOutcome(OutcomeCode.BUSY, turnId)
        val started = clock.now()
        return try {
            val reply = gateway.narrate(request)
            store.transaction(campaignId) { r -> val t = r.turns.single { it.id == turnId }
                if (t.attemptId != request.attemptId || t.stage != TurnStage.NARRATING) return@transaction TurnOutcome(OutcomeCode.BUSY, turnId, "STALE_ATTEMPT")
                ContextBuilder.validateNarration(reply.text, r.state); t.narration = reply.text; t.stage = TurnStage.COMPLETE; finishAttempt(t, reply, clock.now() - started); r.updatedAt = clock.now(); TurnOutcome(OutcomeCode.APPLIED, turnId)
            }
        } catch (e: Exception) { if (e is TimeoutCancellationException) return fail(campaignId, turnId, request.attemptId, "MODEL_TIMEOUT"); if (e is CancellationException) { withContext(NonCancellable) { fail(campaignId, turnId, request.attemptId, "INTERRUPTED") }; throw e }; fail(campaignId, turnId, request.attemptId, if (e is IllegalArgumentException) "INVALID_NARRATION" else "MODEL_REQUEST_FAILED") }
    }
    private suspend fun startRequest(campaignId: String, turnId: String, planning: Boolean): ModelRequest? = store.transaction(campaignId) { r ->
        val t = r.turns.single { it.id == turnId }; val expected = if (planning) TurnStage.RECEIVED else TurnStage.RESOLVED
        if (t.stage != expected) return@transaction null
        val attempt = ids.next(); t.stage = if (planning) TurnStage.PLANNING else TurnStage.NARRATING; t.attemptId = attempt; t.errorCode = null
        t.attempts += RequestAttempt(attempt, t.stage, t.attempts.size + 1, t.model.fingerprint)
        ModelRequest(attempt, t.model, if (planning) ContextBuilder.PLANNER_PROMPT else ContextBuilder.NARRATOR_PROMPT, if (planning) ContextBuilder.planner(r, t) else ContextBuilder.narrator(r, t), if (planning) 800 else 1200)
    }
    suspend fun retryStage(campaignId: String, turnId: String, expectedStage: TurnStage): TurnOutcome {
        val stage = store.transaction(campaignId) { r -> val t = r.turns.single { it.id == turnId }
            if (t.stage != expectedStage || terminal(t.stage)) return@transaction null
            if (t.stage in setOf(TurnStage.PLANNING, TurnStage.NARRATING)) {
                if (t.errorCode == null) return@transaction null
                t.attemptId = null; t.stage = if (t.result == null) TurnStage.RECEIVED else TurnStage.RESOLVED
            }; t.stage
        } ?: return TurnOutcome(OutcomeCode.BUSY, turnId)
        return when (stage) { TurnStage.RECEIVED -> plan(campaignId, turnId); TurnStage.PREPARED -> resolvePrepared(campaignId, turnId, requestRevision(campaignId, turnId)); TurnStage.RESOLVED -> narrate(campaignId, turnId); TurnStage.NEEDS_CONFIRMATION -> TurnOutcome(OutcomeCode.NEEDS_CONFIRMATION, turnId); else -> TurnOutcome(OutcomeCode.BUSY, turnId) }
    }
    suspend fun cancelUnresolved(campaignId: String, turnId: String): TurnOutcome {
        val pair = store.transaction(campaignId) { r -> val t = r.turns.single { it.id == turnId }; val attempt = t.attemptId
            when { terminal(t.stage) -> TurnOutcome(OutcomeCode.ALREADY_APPLIED, turnId) to null
                t.result != null -> { t.attempts.lastOrNull()?.takeIf { it.status == "ACTIVE" }?.apply { status = "INTERRUPTED"; errorCode = "INTERRUPTED" }; t.errorCode = "INTERRUPTED"; t.attemptId = null; t.stage = TurnStage.RESOLVED; TurnOutcome(OutcomeCode.ALREADY_APPLIED, turnId, "RESOLUTION_PRESERVED") to attempt }
                else -> { t.attempts.lastOrNull()?.takeIf { it.status == "ACTIVE" }?.status = "CANCELLED"; t.stage = TurnStage.CANCELLED; t.attemptId = null; TurnOutcome(OutcomeCode.CANCELLED, turnId) to attempt }
            }
        }; pair.second?.let { gateway.cancel(it) }; return pair.first
    }
    suspend fun recover(campaignId: String): PublicCampaignView = store.transaction(campaignId) { r ->
        r.turns.filter { it.stage == TurnStage.PLANNING || it.stage == TurnStage.NARRATING }.forEach { t -> t.errorCode = "INTERRUPTED"; t.attemptId = null; t.attempts.lastOrNull()?.let { it.status = "INTERRUPTED"; it.errorCode = "INTERRUPTED" } }; RainyDock.publicView(r)
    }
    private suspend fun requestRevision(campaignId: String, turnId: String): Long = store.transaction(campaignId) { it.turns.single { t -> t.id == turnId }.baseRevision }
    private fun finishAttempt(t: Turn, reply: ModelReply, duration: Long) { t.attempts.last().apply { status = "COMPLETE"; usage = reply.usage; durationMs = duration }; t.errorCode = null }
    private suspend fun fail(campaignId: String, turnId: String, attemptId: String, error: String): TurnOutcome = store.transaction(campaignId) { r -> val t = r.turns.single { it.id == turnId }; if (t.attemptId == attemptId) { t.errorCode = error; t.attempts.last().apply { status = "FAILED"; errorCode = error } }; TurnOutcome(OutcomeCode.FAILED, turnId, error) }
    private fun terminal(stage: TurnStage) = stage == TurnStage.COMPLETE || stage == TurnStage.CANCELLED
}
