package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun digest(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

interface RuleAdapter {
    fun validateModule(module: ValidatedModule)
    fun planningRuleIndex(module: ValidatedModule): JsonObject
    fun availableActions(campaign: Campaign, module: ValidatedModule): List<ActionDefinition>
    fun validateProposal(campaign: Campaign, module: ValidatedModule, proposal: ActionProposal, evidence: List<RuleEvidence>)
    fun resolve(campaign: Campaign, plan: FrozenPlan, module: ValidatedModule, turnId: String, dice: DiceSource): Pair<WorldEvent, CommittedResult>
    fun applyRecordedEvent(world: WorldState, event: WorldEvent, module: ValidatedModule): WorldState
    fun validateState(world: WorldState, module: ValidatedModule)
}

class GenericD100 : RuleAdapter {
    companion object {
        val effects = setOf("move", "resource", "transfer_item", "reveal_fact", "objective", "npc_status", "npc_relation", "knowledge", "time")
        val actionTypes = setOf("investigate", "move", "negotiate", "use_item", "wait", "skill_check")
    }
    override fun planningRuleIndex(module: ValidatedModule): JsonObject = module.rules.let { r ->
        buildJsonObject {
            put("adapterId", JsonPrimitive(r.adapterId))
            put("adapterVersion", JsonPrimitive(r.adapterVersion))
            put("difficulties", JsonObject(r.difficulties.mapValues { JsonPrimitive(it.value) }))
            put("allowedEffects", JsonArray(r.allowedEffects.map { JsonPrimitive(it) }))
            put("ruleIds", JsonArray(r.evidence.map { JsonPrimitive(it.ruleId) }))
        }
    }
    override fun validateModule(module: ValidatedModule) {
        val w = module.world; val r = module.rules
        require(r.adapterId == "generic-d100-v1" && r.adapterVersion == "1") { "UNKNOWN_RULE_ADAPTER" }
        validateContentLevels(r)
        require(r.allowedEffects.all { it in effects }) { "UNKNOWN_EFFECT" }
        val ids = w.locations.map { it.id } + w.npcs.map { it.id } + w.items.map { it.id } + w.facts.map { it.id } + w.objectives.map { it.id } + w.characters.map { it.id } + w.actions.map { it.id } + w.schedule.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it.startsWith(module.manifest.moduleId + ":") }) { "INVALID_ENTITY_ID" }
        require(w.locations.any { it.id == w.startLocationId }) { "MISSING_START_LOCATION" }
        require(w.characters.isNotEmpty()) { "MISSING_CHARACTER" }
        require(w.locations.all { l -> l.exits.all { target -> w.locations.any { it.id == target } } }) { "DANGLING_EXIT" }
        require(w.npcs.all { n -> w.locations.any { it.id == n.locationId } }) { "DANGLING_NPC_LOCATION" }
        require(r.difficulties.isNotEmpty() && r.resourceBounds.all { it.value.min <= it.value.max })
        require(w.characters.all { c -> c.skills.keys.all { it in r.skills } && c.skills.values.all { it in 0..100 } }) { "INVALID_SKILL" }
        require(w.facts.all { validVisibility(it.visibility) }) { "INVALID_VISIBILITY" }
        require(w.policy.maxEntities in 0..100 && w.policy.allowedEntityTypes.all { it in setOf("npc", "location_detail") }) { "INVALID_GENERATION_POLICY" }
        require(w.policy.lockedFactIds.all { id -> w.facts.any { it.id == id && it.locked } })
        val campaign = Campaign("validation", "", VersionRef(module.manifest.moduleId,module.manifest.version,module.contentHash),VersionRef(r.adapterId,r.adapterVersion,module.contentHash),0,0,initialWorld(module,w.characters.first().id),false,"","",0)
        w.actions.forEach { a ->
            val actionLocation=a.conditions.firstOrNull { it.type=="location" }?.targetId?:w.startLocationId
            val local=campaign.copy(world=campaign.world.copy(locationId=actionLocation))
            validateProposal(local,module,ActionProposal(a.actionType,a.targetIds,a.skill,a.difficulty,listOf(a.ruleId),a.conditions,a.successEffects,a.failureEffects,publicSummary=a.title),r.evidence)
        }
        validateChapters(r,campaign.world,module)
        Scheduler.validateModule(module,campaign.world)
        validateState(campaign.world,module)
    }
    override fun availableActions(campaign: Campaign, module: ValidatedModule): List<ActionDefinition> = module.world.actions.filter { it.conditions.all { c -> condition(c, campaign.world) } }
    override fun validateProposal(campaign: Campaign, module: ValidatedModule, proposal: ActionProposal, evidence: List<RuleEvidence>) {
        val w = campaign.world; val r = module.rules
        require(proposal.actionType in actionTypes && proposal.d20.isEmpty()) { "UNKNOWN_ACTION" }
        require(proposal.publicSummary.isNotBlank() && proposal.publicSummary.length <= 2000)
        require(proposal.difficulty in r.difficulties) { "UNKNOWN_DIFFICULTY" }
        require(proposal.skill == null || proposal.skill in w.skills) { "UNKNOWN_SKILL" }
        require(proposal.skill != null || proposal.actionType in setOf("move", "wait", "use_item", "investigate", "negotiate")) { "SKILL_REQUIRED" }
        require(proposal.ruleRefs.isNotEmpty() || proposal.temporaryRuling) { "RULE_NOT_FOUND" }
        require(!proposal.temporaryRuling || proposal.requiresConfirmation) { "RULING_CONFIRMATION_REQUIRED" }
        proposal.ruleRefs.forEach { id -> require(evidence.any { it.ruleId == id && module.rules.evidence.contains(it) }) { "RULE_NOT_FOUND" } }
        require(proposal.targetIds.all { exists(it,w,module) }) { "UNKNOWN_TARGET" }
        if(proposal.actionType in setOf("negotiate","use_item","investigate","skill_check")) require(proposal.targetIds.all { id -> w.npcs.none { it.id==id && it.locationId!=w.locationId } }) { "TARGET_NOT_PRESENT" }
        require(proposal.conditions.sumOf { count(it,0) } <= 64) { "CONDITION_LIMIT" }
        proposal.conditions.forEach { validateCondition(it,w,module) }
        require(proposal.successEffects.size <= 16 && proposal.failureEffects.size <= 16 && proposal.creations.size <= 3) { "EFFECT_LIMIT" }
        (proposal.successEffects + proposal.failureEffects).forEach {
            validateEffect(it,w,module)
            if(it.source.authority !in setOf("temporary_ruling","generated")) require(evidence.any { rule -> rule.source==it.source }) { "EFFECT_EVIDENCE_MISSING" }
            if(it.source.authority=="temporary_ruling") require(proposal.temporaryRuling && proposal.requiresConfirmation) { "RULING_CONFIRMATION_REQUIRED" }
        }
        (proposal.successEffects + proposal.failureEffects).filter { it.type=="reveal_fact" && w.facts.any { fact -> fact.id==it.targetId && fact.locked } }.forEach { effect ->
            require(module.world.actions.any { a -> effect in a.successEffects && proposal.conditions.containsAll(a.conditions) && a.targetIds.all { it in proposal.targetIds } }) { "LOCKED_FACT" }
        }
        require(proposal.creations.isEmpty() || campaign.allowGeneration) { "GENERATION_DISABLED" }
        require(w.generated.size + proposal.creations.size <= module.world.policy.maxEntities) { "GENERATION_LIMIT" }
        require(proposal.creations.map { it.id }.distinct().size == proposal.creations.size)
        proposal.creations.forEach { c ->
            require(c.type in module.world.policy.allowedEntityTypes && c.id.startsWith(module.manifest.moduleId+":") && !exists(c.id,w,module)) { "GENERATION_FORBIDDEN" }
            require(c.source.authority == "generated" && module.world.locations.any { it.id == c.locationId } && c.description.length <= 2000)
        }
    }
    internal fun count(c: Condition, depth: Int): Int { require(depth <= 8) { "CONDITION_DEPTH" }; return 1+c.children.sumOf { count(it,depth+1) } }
    internal fun validateCondition(c: Condition,w:WorldState,m:ValidatedModule) {
        require(c.type in setOf("all","any","not","location","resource_gte","fact_known","objective","time_gte","flag_eq","npc_at","item_owner","schedule_state")) { "UNKNOWN_CONDITION" }
        when(c.type) {
            "all","any" -> require(c.children.isNotEmpty())
            "not" -> require(c.children.size == 1)
            "location" -> require(m.world.locations.any { it.id == c.targetId })
            "resource_gte" -> require(c.key in w.resources && c.value?.toIntOrNull() != null)
            "fact_known" -> require(w.facts.any { it.id == c.targetId })
            "objective" -> require(w.objectives.any { it.id == c.targetId } && c.value in setOf("active","complete","failed"))
            "time_gte" -> require(c.value?.toLongOrNull() != null)
            "flag_eq" -> require(!c.key.isNullOrBlank() && c.value != null)
            "npc_at" -> require(w.npcs.any { it.id == c.targetId } && m.world.locations.any { it.id == c.value })
            "item_owner" -> require(w.items.any { it.id == c.targetId } && (c.value == "player" || w.npcs.any { it.id == c.value } || m.world.locations.any { it.id == c.value }))
            "schedule_state" -> require(m.world.schedule.any { it.id == c.targetId } && c.value in Scheduler.states)
        }
        c.children.forEach { validateCondition(it,w,m) }
    }
    fun condition(c: Condition,w:WorldState): Boolean = when(c.type) {
        "all" -> c.children.all { condition(it,w) }; "any" -> c.children.any { condition(it,w) }; "not" -> !condition(c.children.single(),w)
        "location" -> w.locationId == c.targetId
        "resource_gte" -> w.resources.getValue(requireNotNull(c.key)) >= requireNotNull(c.value).toInt()
        "fact_known" -> w.facts.any { it.id == c.targetId && it.visibility.audience == Audience.PUBLIC }
        "objective" -> w.objectives.any { it.id == c.targetId && it.status == c.value }
        "time_gte" -> w.gameTime >= requireNotNull(c.value).toLong()
        "flag_eq" -> w.flags[requireNotNull(c.key)] == requireNotNull(c.value)
        "npc_at" -> w.npcs.any { it.id == c.targetId && it.locationId == c.value }
        "item_owner" -> w.items.any { it.id == c.targetId && it.ownerId == c.value }
        "schedule_state" -> w.schedule[c.targetId]?.state == c.value
        else -> error("UNKNOWN_CONDITION")
    }
    private fun validateEffect(e:Effect,w:WorldState,m:ValidatedModule) = NarrativeEffects.validate(e,w,m,effects)
    override fun resolve(campaign:Campaign,plan:FrozenPlan,module:ValidatedModule,turnId:String,dice:DiceSource):Pair<WorldEvent,CommittedResult> {
        require(campaign.revision == plan.baseRevision && campaign.module.hash == plan.moduleHash && campaign.rules.hash == plan.rulesHash && campaign.modelFingerprint == plan.modelFingerprint) { "STALE_PROPOSAL" }
        validateProposal(campaign,module,plan.proposal,plan.evidence)
        require(plan.proposal.conditions.all { condition(it,campaign.world) }) { "CONDITION_FAILED" }
        val p=plan.proposal
        val roll=p.skill?.let { val value=dice.roll(100); require(value in 1..100); DiceRoll(100,value,(campaign.world.skills.getValue(it)+module.rules.difficulties.getValue(p.difficulty)).coerceIn(1,100)) }
        val success=roll == null || roll.value <= roll.threshold
        val base=WorldEvent(1,campaign.id,campaign.lastEventSeq+1,turnId,if(success)p.successEffects else p.failureEffects,if(success)p.creations else emptyList(),roll,Visibility(Audience.PUBLIC),(plan.evidence.map { it.source }+(if(success)p.successEffects else p.failureEffects).map { it.source }).distinct())
        val fired=Scheduler.run(applyRecordedEvent(campaign.world,base,module),module,base.seq)
        val event=base.copy(scheduled=fired.firings)
        check(applyRecordedEvent(campaign.world,event,module)==fired.world) { "REPLAY_MISMATCH" }
        return event to CommittedResult(turnId,event.seq,success,roll,p.publicSummary,plan.evidence.filter { it.visibility.audience == Audience.PUBLIC },notices=fired.notices)
    }
    override fun applyRecordedEvent(world:WorldState,event:WorldEvent,module:ValidatedModule):WorldState {
        require(event.eventVersion == 1) { "UNKNOWN_EVENT_VERSION" }
        var w=world
        event.effects.forEach { e ->
            validateEffect(e,w,module)
            w=NarrativeEffects.apply(w,e,event.seq,module)
        }
        event.creations.forEach { c ->
            require(c.source.authority == "generated" && c.type in module.world.policy.allowedEntityTypes && w.generated.none { it.id==c.id })
            w=w.copy(generated=w.generated+c,npcs=if(c.type=="npc")w.npcs+NpcState(c.id,c.title,c.locationId,"idle",c.description,"未设定") else w.npcs)
        }
        w=Scheduler.apply(w,event.scheduled,event.seq,module)
        validateState(w,module); return w
    }
    override fun validateState(world:WorldState,module:ValidatedModule) {
        Scheduler.validateState(world,module)
        require(module.world.locations.any { it.id==world.locationId })
        world.resources.forEach { (key,value) -> val b=module.rules.resourceBounds.getValue(key); require(value in b.min..b.max) }
        require(world.gameTime>=0 && world.generated.size<=module.world.policy.maxEntities)
        require(world.knowledge.all { k -> world.facts.any { it.id==k.factId } && (k.knowerId=="player" || world.npcs.any { it.id==k.knowerId }) })
    }
    internal fun exists(id:String,w:WorldState,m:ValidatedModule)= id=="player" || id=="world" || m.world.locations.any { it.id==id } || w.npcs.any { it.id==id } || w.items.any { it.id==id } || w.facts.any { it.id==id } || w.objectives.any { it.id==id } || w.generated.any { it.id==id }
}
fun validVisibility(v:Visibility)= when(v.audience) { Audience.PUBLIC,Audience.GM -> v.subjectId==null; Audience.NPC,Audience.FACTION -> !v.subjectId.isNullOrBlank() }
fun initialWorld(module:ValidatedModule,characterId:String):WorldState {
    val c=module.world.characters.single { it.id==characterId }
    val world=WorldState(module.world.startLocationId,c.resources,c.skills,module.world.npcs,module.world.items,module.world.facts,module.world.objectives,emptyList(),flags=module.world.initialFlags,schedule=Scheduler.initialSchedule(module.world))
    return if(module.rules.adapterId==WuxiaD20.ID) world.copy(d20=WuxiaD20.initialState(module,characterId)) else world
}
fun freeze(c:Campaign,p:ActionProposal,evidence:List<RuleEvidence>):FrozenPlan { val hash=digest(Json.encodeToString(p)+c.revision+c.module.hash+c.rules.hash+c.modelFingerprint+Json.encodeToString(evidence)); return FrozenPlan(c.revision,c.module.hash,c.rules.hash,c.modelFingerprint,p,evidence,hash) }
