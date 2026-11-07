package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.contract.*
import kotlinx.serialization.json.*

/**
 * Data driven d20 adapter. The program owns every modifier, difficulty, roll, deduction, cap and condition;
 * the model only chooses actor, targets, content and a difficulty tier that the module registered.
 * No pool, counter, condition or card name appears in this file.
 */
class WuxiaD20 : RuleAdapter {
    companion object {
        const val ID = "wuxia-d20-v1"
        val narrativeEffects = setOf("move", "reveal_fact", "knowledge", "npc_status", "npc_relation", "objective", "time")
        val actionTypes = setOf("investigate", "move", "negotiate", "use_item", "wait", "d20")
        val kinds = setOf("check", "save", "contest", "weapon", "card", "rest")
        private val timings = setOf("action", "bonus", "reaction", "free")
        private val resolutions = setOf("save", "attack", "contest", "auto")
        private val restoreModes = setOf("short", "long", "none")
        private val durationUnits = setOf("rounds", "minutes", "until_target_turn_start", "until_actor_turn_end", "until_removed")
        private val cardEffectTypes = setOf("pool", "condition_add", "condition_remove", "counter")

        fun attributeBonus(score: Int): Int = Math.floorDiv(score - 10, 2)
        fun pcIds(module: ValidatedModule): Set<String> = module.world.characters.map { it.id }.toSet()
        /** Public dice view: only the player's own check/save/concentration rolls keep their target; every other roll hides it so NPC defense and difficulty never leak. */
        fun publicRolls(rolls: List<RollRecord>, playerIds: Set<String>): List<RollRecord> = rolls.map { r ->
            val open = r.actorId in playerIds && (r.purpose.startsWith("check:") || r.purpose.startsWith("save:") || r.purpose.startsWith("concentration"))
            if (open) r else r.copy(target = null)
        }
        fun initialState(module: ValidatedModule, characterId: String): D20State {
            val pcs = pcIds(module)
            val present = module.world.d20Combatants.filter { it.id == characterId || it.id !in pcs }
            require(present.any { it.id == characterId }) { "MISSING_COMBATANT" }
            return D20State(present.associateBy { it.id }, 0L, emptyMap())
        }
    }

    private val generic = GenericD100()
    private fun rulesOf(module: ValidatedModule): D20Rules = requireNotNull(module.rules.d20) { "MISSING_D20_RULES" }

    override fun planningRuleIndex(module: ValidatedModule): JsonObject {
        val r = module.rules; val d = rulesOf(module)
        return buildJsonObject {
            put("adapterId", JsonPrimitive(r.adapterId))
            put("adapterVersion", JsonPrimitive(r.adapterVersion))
            put("attributes", JsonArray(d.attributes.map { JsonPrimitive(it) }))
            put("skills", JsonObject(d.skills.mapValues { JsonPrimitive(it.value) }))
            put("dcTiers", JsonObject(d.dcTiers.mapValues { JsonPrimitive(it.value) }))
            put("conditions", JsonObject(d.conditions.mapValues { (_, c) -> buildJsonObject { put("label", c.label); put("text", c.text) } }))
            put("allowedEffects", JsonArray(r.allowedEffects.map { JsonPrimitive(it) }))
            put("ruleIds", JsonArray(r.evidence.map { JsonPrimitive(it.ruleId) }))
        }
    }

    /** Dynamic part of the planning rule index: cards of present combatants and the player's remaining resources. */
    fun planningState(campaign: Campaign, module: ValidatedModule): JsonObject {
        val d = rulesOf(module); val w = campaign.world
        val st = requireNotNull(w.d20) { "MISSING_D20_STATE" }
        val pcs = pcIds(module)
        val present = st.combatants.values.filter { c -> c.id in pcs || w.npcs.any { it.id == c.id && it.locationId == w.locationId } }
        val player = present.single { it.id in pcs }
        return buildJsonObject {
            put("round", st.round)
            put("combatants", JsonArray(present.map { c ->
                buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("cards", JsonArray(c.cards.sorted().map { id ->
                        val card = d.cards.getValue(id)
                        buildJsonObject { put("id", id); put("label", card.label); put("timing", card.timing); put("isSpell", card.isSpell) }
                    }))
                    put("weapons", JsonArray(c.weaponAttacks.keys.sorted().map { JsonPrimitive(it) }))
                    put("conditions", JsonArray(c.conditions.map { JsonPrimitive(it.id) }))
                }
            }))
            put("player", buildJsonObject {
                put("id", player.id)
                put("pools", Json.encodeToJsonElement(player.pools))
                put("slots", Json.encodeToJsonElement(player.slots))
                put("counters", Json.encodeToJsonElement(player.counters))
            })
        }
    }

    // ---------------------------------------------------------------- module validation

    override fun validateModule(module: ValidatedModule) {
        val w = module.world; val r = module.rules
        require(r.adapterId == ID && r.adapterVersion == "1") { "UNKNOWN_RULE_ADAPTER" }
        validateContentLevels(r)
        val d = rulesOf(module)
        require(r.allowedEffects.all { it in narrativeEffects }) { "UNKNOWN_EFFECT" }
        val ids = w.locations.map { it.id } + w.npcs.map { it.id } + w.items.map { it.id } + w.facts.map { it.id } + w.objectives.map { it.id } + w.characters.map { it.id } + w.actions.map { it.id } + w.schedule.map { it.id }
        require(ids.distinct().size == ids.size && ids.all { it.startsWith(module.manifest.moduleId + ":") }) { "INVALID_ENTITY_ID" }
        require(w.locations.any { it.id == w.startLocationId }) { "MISSING_START_LOCATION" }
        require(w.characters.isNotEmpty()) { "MISSING_CHARACTER" }
        require(w.locations.all { l -> l.exits.all { target -> w.locations.any { it.id == target } } }) { "DANGLING_EXIT" }
        require(w.npcs.all { n -> w.locations.any { it.id == n.locationId } }) { "DANGLING_NPC_LOCATION" }
        require(w.facts.all { validVisibility(it.visibility) }) { "INVALID_VISIBILITY" }
        require(w.policy.maxEntities in 0..100 && w.policy.allowedEntityTypes.all { it in setOf("npc", "location_detail") }) { "INVALID_GENERATION_POLICY" }
        require(w.policy.lockedFactIds.all { id -> w.facts.any { it.id == id && it.locked } })
        validateRules(d)
        val characterIds = w.characters.map { it.id }.toSet(); val npcIds = w.npcs.map { it.id }.toSet()
        val combatantIds = w.d20Combatants.map { it.id }
        require(combatantIds.distinct().size == combatantIds.size && combatantIds.all { it in characterIds || it in npcIds }) { "INVALID_COMBATANT" }
        require(characterIds.all { it in combatantIds }) { "MISSING_COMBATANT" }
        w.d20Combatants.forEach { validateCombatant(it, d) }
        val campaign = Campaign("validation", "", VersionRef(module.manifest.moduleId, module.manifest.version, module.contentHash), VersionRef(r.adapterId, r.adapterVersion, module.contentHash), 0, 0, initialWorld(module, w.characters.first().id), false, "", "", 0)
        w.actions.forEach { a ->
            val actionLocation = a.conditions.firstOrNull { it.type == "location" }?.targetId ?: w.startLocationId
            val local = campaign.copy(world = campaign.world.copy(locationId = actionLocation))
            val check = a.check
            require((check != null) == (a.actionType == "d20")) { "ACTION_CHECK_MISMATCH" }
            val rolls = if (check == null) emptyList() else listOf(D20Action("check", w.characters.first().id, skill = check.skill, dcTier = check.dcTier))
            validateProposal(local, module, ActionProposal(a.actionType, a.targetIds, a.skill, a.difficulty, listOf(a.ruleId), a.conditions, a.successEffects, a.failureEffects, publicSummary = a.title, d20 = rolls), r.evidence)
        }
        Scheduler.validateModule(module, campaign.world)
        validateState(campaign.world, module)
    }

    private fun validateDuration(spec: DurationSpec?) {
        if (spec == null) return
        require(spec.unit in durationUnits && spec.value in 1..100000) { "INVALID_DURATION" }
    }
    private fun validateDiceText(expression: String) { DiceExpression.roll(expression, DiceSource { _ -> 1 }) }

    private fun validateRules(d: D20Rules) {
        require(d.attributes.isNotEmpty() && d.attributes.distinct().size == d.attributes.size) { "INVALID_D20_RULES" }
        require(d.skills.values.all { it in d.attributes }) { "INVALID_D20_RULES" }
        require(d.dcTiers.isNotEmpty() && d.dcTiers.values.all { it in 1..40 }) { "INVALID_D20_RULES" }
        require(d.concentrationAttribute in d.attributes) { "INVALID_D20_RULES" }
        require(d.rest.shortMinutes > 0 && d.rest.longMinutes > 0 && d.rest.hitPoolId in d.pools) { "INVALID_D20_RULES" }
        require(d.defeatPoolIds.all { it in d.pools }) { "INVALID_D20_RULES" }
        d.pools.values.forEach { p ->
            val cap = p.perRoundLossCap
            require(p.restoreOn in restoreModes && (cap == null || cap >= 0)) { "INVALID_POOL" }
            val zero = p.atZeroCondition
            require(zero == null || zero in d.conditions) { "UNKNOWN_CONDITION" }
            validateDuration(p.atZeroDuration)
        }
        require(d.counters.values.all { it.restoreOn in restoreModes }) { "INVALID_COUNTER" }
        require(d.conditions.values.all { c -> c.autoFailSaves.all { it in d.attributes } }) { "INVALID_CONDITION" }
        d.cards.values.forEach { c -> validateCard(c, d) }
    }

    private fun validateCard(c: CardRule, d: D20Rules) {
        val minSlot = c.slotLevel
        require(c.timing in timings && c.resolution in resolutions && c.maxTargets in 1..6 && (minSlot == null || minSlot >= 1)) { "INVALID_CARD" }
        require(c.counterCost.all { (k, v) -> k in d.counters && v > 0 }) { "INVALID_CARD" }
        require(c.requiresTargetCondition.all { it in d.conditions } && c.requiresActorNotCondition.all { it in d.conditions }) { "INVALID_CARD" }
        when (c.resolution) {
            "save" -> {
                require(requireNotNull(c.keyAttribute) { "INVALID_CARD" } in d.attributes)
                require(requireNotNull(c.saveAttribute) { "INVALID_CARD" } in d.attributes)
            }
            "attack" -> require(requireNotNull(c.keyAttribute) { "INVALID_CARD" } in d.attributes)
            "contest" -> {
                require(requireNotNull(c.contestSkill) { "INVALID_CARD" } in d.skills)
                require(c.contestDefenseSkills.isNotEmpty() && c.contestDefenseSkills.all { it in d.skills }) { "INVALID_CARD" }
            }
        }
        if (c.repeatSave) require(c.saveAttribute != null) { "INVALID_CARD" }
        (c.onSuccess + c.onFailure + c.onTargetSaveSuccessToActor).forEach { validateCardEffect(it, d) }
    }

    private fun validateCardEffect(e: CardEffect, d: D20Rules) {
        require(e.type in cardEffectTypes && e.target in setOf("target", "actor") && e.tag.isNotBlank()) { "INVALID_CARD_EFFECT" }
        val ifHas = e.ifTargetHasCondition
        require(ifHas == null || ifHas in d.conditions) { "UNKNOWN_CONDITION" }
        when (e.type) {
            "pool" -> { require(requireNotNull(e.pool) { "INVALID_CARD_EFFECT" } in d.pools) { "UNKNOWN_POOL" }; validateAmount(e) }
            "counter" -> { require(requireNotNull(e.counter) { "INVALID_CARD_EFFECT" } in d.counters) { "UNKNOWN_COUNTER" }; validateAmount(e) }
            "condition_add" -> { require(requireNotNull(e.condition) { "INVALID_CARD_EFFECT" } in d.conditions) { "UNKNOWN_CONDITION" }; validateDuration(e.duration) }
            "condition_remove" -> require(requireNotNull(e.condition) { "INVALID_CARD_EFFECT" } in d.conditions) { "UNKNOWN_CONDITION" }
        }
    }
    private fun validateAmount(e: CardEffect) {
        val expression = e.dice; val amount = e.amount
        require((expression == null) != (amount == null)) { "INVALID_CARD_EFFECT" }
        if (expression != null) validateDiceText(expression)
        if (amount != null) require(amount in -1000..1000) { "INVALID_CARD_EFFECT" }
    }

    private fun validateCombatant(c: D20Combatant, d: D20Rules) {
        require(c.name.isNotBlank() && c.level in 1..30 && c.proficiencyBonus in 0..10 && c.armorClass in 0..60) { "INVALID_COMBATANT" }
        require(c.scores.keys == d.attributes.toSet() && c.scores.values.all { it in 1..30 }) { "INVALID_COMBATANT" }
        require(c.saveProficiencies.all { it in d.attributes }) { "INVALID_COMBATANT" }
        require(c.skillRanks.keys == d.skills.keys && c.skillRanks.values.all { it in 0..2 }) { "INVALID_COMBATANT" }
        require(c.pools.keys.all { it in d.pools } && d.rest.hitPoolId in c.pools && c.pools.values.all { it.max >= 0 && it.current in 0..it.max }) { "INVALID_COMBATANT" }
        require(c.slots.keys.all { it >= 1 } && c.slots.values.all { it.max >= 0 && it.current in 0..it.max } && c.slotRestoreOn in setOf("short", "long")) { "INVALID_COMBATANT" }
        require(c.counters.keys.all { it in d.counters } && c.counters.values.all { it.max >= 0 && it.current in 0..it.max }) { "INVALID_COMBATANT" }
        require(c.cards.all { it in d.cards }) { "INVALID_COMBATANT" }
        c.weaponAttacks.values.forEach { wa ->
            require(wa.attribute in d.attributes && wa.tag.isNotBlank()) { "INVALID_COMBATANT" }
            validateDiceText(wa.damage)
        }
        require(c.conditions.all { it.id in d.conditions }) { "INVALID_COMBATANT" }
        val conc = c.concentration
        require(conc == null || conc.cardId in d.cards) { "INVALID_COMBATANT" }
    }

    // ---------------------------------------------------------------- proposals

    override fun availableActions(campaign: Campaign, module: ValidatedModule): List<ActionDefinition> = module.world.actions.filter { it.conditions.all { c -> generic.condition(c, campaign.world) } }

    override fun validateProposal(campaign: Campaign, module: ValidatedModule, proposal: ActionProposal, evidence: List<RuleEvidence>) {
        val w = campaign.world
        require(proposal.actionType in actionTypes) { "UNKNOWN_ACTION" }
        require(proposal.publicSummary.isNotBlank() && proposal.publicSummary.length <= 2000)
        require(proposal.skill == null) { "SKILL_NOT_SUPPORTED" }
        require(proposal.ruleRefs.isNotEmpty() || proposal.temporaryRuling) { "RULE_NOT_FOUND" }
        require(!proposal.temporaryRuling || proposal.requiresConfirmation) { "RULING_CONFIRMATION_REQUIRED" }
        proposal.ruleRefs.forEach { id -> require(evidence.any { it.ruleId == id && module.rules.evidence.contains(it) }) { "RULE_NOT_FOUND" } }
        require(proposal.targetIds.all { generic.exists(it, w, module) }) { "UNKNOWN_TARGET" }
        require(proposal.conditions.sumOf { generic.count(it, 0) } <= 64) { "CONDITION_LIMIT" }
        proposal.conditions.forEach { generic.validateCondition(it, w, module) }
        require(proposal.successEffects.size <= 16 && proposal.failureEffects.size <= 16 && proposal.creations.size <= 3) { "EFFECT_LIMIT" }
        (proposal.successEffects + proposal.failureEffects).forEach {
            NarrativeEffects.validate(it, w, module, narrativeEffects)
            if (it.source.authority !in setOf("temporary_ruling", "generated")) require(evidence.any { rule -> rule.source == it.source }) { "EFFECT_EVIDENCE_MISSING" }
            if (it.source.authority == "temporary_ruling") require(proposal.temporaryRuling && proposal.requiresConfirmation) { "RULING_CONFIRMATION_REQUIRED" }
        }
        (proposal.successEffects + proposal.failureEffects).filter { it.type == "reveal_fact" && w.facts.any { fact -> fact.id == it.targetId && fact.locked } }.forEach { effect ->
            require(module.world.actions.any { a -> effect in a.successEffects && proposal.conditions.containsAll(a.conditions) && a.targetIds.all { it in proposal.targetIds } }) { "LOCKED_FACT" }
        }
        require(proposal.creations.isEmpty() || campaign.allowGeneration) { "GENERATION_DISABLED" }
        require(w.generated.size + proposal.creations.size <= module.world.policy.maxEntities) { "GENERATION_LIMIT" }
        require(proposal.creations.map { it.id }.distinct().size == proposal.creations.size)
        proposal.creations.forEach { c ->
            require(c.type in module.world.policy.allowedEntityTypes && c.id.startsWith(module.manifest.moduleId + ":") && !generic.exists(c.id, w, module)) { "GENERATION_FORBIDDEN" }
            require(c.source.authority == "generated" && module.world.locations.any { it.id == c.locationId } && c.description.length <= 2000)
        }
        if (proposal.actionType == "d20") {
            require(proposal.d20.isNotEmpty() && proposal.d20.size <= 6) { "D20_ACTIONS_REQUIRED" }
            validateD20Actions(campaign, module, proposal.d20)
        } else {
            require(proposal.d20.isEmpty()) { "D20_NOT_ALLOWED" }
        }
    }

    private fun effectiveSlotLevel(a: D20Action, card: CardRule): Int? {
        val base = card.slotLevel
        if (base == null) return null
        val chosen = a.slotLevel
        return if (chosen != null) chosen else base
    }

    private fun validateD20Actions(campaign: Campaign, module: ValidatedModule, actions: List<D20Action>) {
        val w = campaign.world; val d = rulesOf(module)
        val st = requireNotNull(w.d20) { "MISSING_D20_STATE" }
        val pcs = pcIds(module)
        fun present(id: String) = id in pcs || w.npcs.any { it.id == id && it.locationId == w.locationId }
        val used = mutableMapOf<String, MutableSet<String>>()
        fun occupy(actorId: String, timing: String) {
            if (timing == "free") return
            require(used.getOrPut(actorId) { mutableSetOf() }.add(timing)) { "TIMING_ALREADY_USED" }
        }
        val bonusSpellActors = actions.filter { a ->
            val id = a.cardId
            a.kind == "card" && id != null && d.cards[id]?.let { it.timing == "bonus" && it.isSpell } == true
        }.map { it.actorId }.toSet()
        val slotUse = mutableMapOf<Pair<String, Int>, Int>()
        val counterUse = mutableMapOf<Pair<String, String>, Int>()
        actions.forEach { a ->
            require(a.kind in kinds) { "UNKNOWN_D20_KIND" }
            val actor = requireNotNull(st.combatants[a.actorId]) { "UNKNOWN_ACTOR" }
            require(present(a.actorId)) { "ACTOR_NOT_PRESENT" }
            a.targetIds.forEach { id -> requireNotNull(st.combatants[id]) { "UNKNOWN_TARGET" }; require(present(id)) { "TARGET_NOT_PRESENT" } }
            require(a.targetIds.distinct().size == a.targetIds.size) { "DUPLICATE_TARGET" }
            require(actor.conditions.none { d.conditions.getValue(it.id).blocksActions }) { "ACTOR_BLOCKED" }
            require(d.defeatPoolIds.none { actor.pools[it]?.current == 0 }) { "ACTOR_DEFEATED" }
            require(a.advantage in -1..1) { "INVALID_ADVANTAGE" }
            require(a.advantage == 0 || !a.advantageReason.isNullOrBlank()) { "ADVANTAGE_REASON_REQUIRED" }
            val tier = a.dcTier
            when (a.kind) {
                "check" -> {
                    require(a.targetIds.isEmpty() && a.weaponId == null && a.cardId == null) { "INVALID_D20_ACTION" }
                    require((a.skill == null) != (a.attribute == null)) { "INVALID_D20_ACTION" }
                    val skill = a.skill; val attribute = a.attribute
                    require((skill == null || skill in d.skills) && (attribute == null || attribute in d.attributes)) { "INVALID_D20_ACTION" }
                    require(tier != null && tier in d.dcTiers) { "UNKNOWN_DC_TIER" }
                    occupy(a.actorId, "action")
                }
                "save" -> {
                    require(a.actorId in pcs) { "SAVE_NOT_ALLOWED" }
                    require(a.targetIds.isEmpty() && a.skill == null && a.weaponId == null && a.cardId == null) { "INVALID_D20_ACTION" }
                    val attribute = a.attribute
                    require(attribute != null && attribute in d.attributes) { "INVALID_D20_ACTION" }
                    require(tier != null && tier in d.dcTiers) { "UNKNOWN_DC_TIER" }
                }
                "contest" -> {
                    val skill = a.skill
                    require(skill != null && skill in d.skills && a.attribute == null && a.weaponId == null && a.cardId == null) { "INVALID_D20_ACTION" }
                    require(a.targetIds.size == 1) { "INVALID_D20_ACTION" }
                    occupy(a.actorId, "action")
                }
                "weapon" -> {
                    val weaponId = a.weaponId
                    require(weaponId != null && weaponId in actor.weaponAttacks && a.skill == null && a.attribute == null && a.cardId == null) { "UNKNOWN_WEAPON" }
                    require(a.targetIds.size == 1) { "INVALID_D20_ACTION" }
                    occupy(a.actorId, "action")
                }
                "card" -> {
                    val cardId = requireNotNull(a.cardId) { "UNKNOWN_CARD" }
                    val card = requireNotNull(d.cards[cardId]) { "UNKNOWN_CARD" }
                    require(a.skill == null && a.attribute == null && a.weaponId == null) { "INVALID_D20_ACTION" }
                    require(cardId in actor.cards) { "CARD_NOT_OWNED" }
                    require(a.targetIds.size in 1..card.maxTargets) { "TARGET_COUNT" }
                    val minLevel = card.slotLevel
                    if (minLevel == null) {
                        require(a.slotLevel == null) { "SLOT_LEVEL_INVALID" }
                    } else {
                        val level = requireNotNull(effectiveSlotLevel(a, card))
                        val highest = actor.slots.keys.maxOrNull()
                        require(highest != null && level >= minLevel && level <= highest) { "SLOT_LEVEL_INVALID" }
                        val slot = requireNotNull(actor.slots[level]) { "SLOT_LEVEL_INVALID" }
                        val key = a.actorId to level
                        val spent = slotUse.getOrDefault(key, 0) + 1
                        require(slot.current >= spent) { "SLOT_EMPTY" }
                        slotUse[key] = spent
                    }
                    card.counterCost.forEach { (counterId, cost) ->
                        val counter = requireNotNull(actor.counters[counterId]) { "COUNTER_EMPTY" }
                        val key = a.actorId to counterId
                        val spent = counterUse.getOrDefault(key, 0) + cost
                        require(counter.current >= spent) { "COUNTER_EMPTY" }
                        counterUse[key] = spent
                    }
                    if (card.requiresTargetCondition.isNotEmpty()) require(a.targetIds.all { id -> st.combatants.getValue(id).conditions.any { it.id in card.requiresTargetCondition } }) { "TARGET_CONDITION_REQUIRED" }
                    require(actor.conditions.none { it.id in card.requiresActorNotCondition }) { "ACTOR_CONDITION_FORBIDDEN" }
                    occupy(a.actorId, card.timing)
                    if (a.actorId in bonusSpellActors && card.timing == "action") require(card.slotLevel == null && card.isSpell) { "BONUS_SPELL_RULE" }
                }
                "rest" -> {
                    require(a.actorId in pcs && actions.size == 1) { "REST_NOT_ALLOWED" }
                    require(a.restType == "short" || a.restType == "long") { "INVALID_REST_TYPE" }
                    require(a.targetIds.isEmpty() && a.skill == null && a.attribute == null && a.weaponId == null && a.cardId == null) { "INVALID_D20_ACTION" }
                }
            }
        }
    }

    // ---------------------------------------------------------------- resolution

    override fun resolve(campaign: Campaign, plan: FrozenPlan, module: ValidatedModule, turnId: String, dice: DiceSource): Pair<WorldEvent, CommittedResult> {
        require(campaign.revision == plan.baseRevision && campaign.module.hash == plan.moduleHash && campaign.rules.hash == plan.rulesHash && campaign.modelFingerprint == plan.modelFingerprint) { "STALE_PROPOSAL" }
        validateProposal(campaign, module, plan.proposal, plan.evidence)
        require(plan.proposal.conditions.all { generic.condition(it, campaign.world) }) { "CONDITION_FAILED" }
        val p = plan.proposal
        val settle = Run(module, rulesOf(module), campaign.world, dice)
        val outcomes = p.d20.map { settle.perform(it) }
        // The player's own action is first; its outcome picks the narrative branch, so a failed check never grants success effects.
        val success = if (outcomes.isEmpty()) true else outcomes.first()
        val branch = if (success) p.successEffects else p.failureEffects
        if (p.actionType == "d20") settle.closeExchange(p.d20.first().actorId, branch)
        val base = WorldEvent(2, campaign.id, campaign.lastEventSeq + 1, turnId, branch, p.creations, null, Visibility(Audience.PUBLIC), (plan.evidence.map { it.source } + branch.map { it.source }).distinct(), settle.rolls.toList(), settle.changes.toList())
        val played = applyRecordedEvent(campaign.world, base, module)
        check(played.d20 == settle.world.d20) { "REPLAY_MISMATCH" }
        // The schedule runs on the world the player's action produced, including rest time and narrative time effects.
        val fired = Scheduler.run(played, module, base.seq)
        val event = base.copy(scheduled = fired.firings)
        check(applyRecordedEvent(campaign.world, event, module) == fired.world) { "REPLAY_MISMATCH" }
        return event to CommittedResult(turnId, event.seq, success, null, p.publicSummary, plan.evidence.filter { it.visibility.audience == Audience.PUBLIC }, publicRolls(event.rolls, pcIds(module)), fired.notices)
    }

    private class D20Roll(val natural: Int, val faces: List<Int>, val expression: String)

    /** One settlement run. Every state change is emitted as a recorded D20Change and applied immediately, so replay equals the online result. */
    private inner class Run(val module: ValidatedModule, val d: D20Rules, start: WorldState, val dice: DiceSource) {
        var world: WorldState = start
        val changes = mutableListOf<D20Change>()
        val rolls = mutableListOf<RollRecord>()
        val st: D20State get() = requireNotNull(world.d20) { "MISSING_D20_STATE" }

        fun cb(id: String): D20Combatant = requireNotNull(st.combatants[id]) { "UNKNOWN_COMBATANT" }
        fun emit(change: D20Change) { changes += change; world = applyChange(world, change, d) }
        fun mod(c: D20Combatant, attribute: String): Int = attributeBonus(c.scores.getValue(attribute))
        fun skillBonus(c: D20Combatant, skill: String): Int = mod(c, d.skills.getValue(skill)) + c.proficiencyBonus * c.skillRanks.getValue(skill)
        fun saveBonus(c: D20Combatant, attribute: String): Int = mod(c, attribute) + if (attribute in c.saveProficiencies) c.proficiencyBonus else 0
        fun netAdvantage(ups: Int, downs: Int): Int = if (ups > 0 && downs > 0) 0 else if (ups > 0) 1 else if (downs > 0) -1 else 0
        fun modelAdvantage(value: Int): Int = netAdvantage(if (value > 0) 1 else 0, if (value < 0) 1 else 0)

        fun rollD20(advantage: Int): D20Roll {
            if (advantage == 0) {
                val out = DiceExpression.roll("1d20", dice)
                return D20Roll(out.faces.single(), out.faces, "1d20")
            }
            val out = DiceExpression.roll("2d20", dice)
            return if (advantage > 0) D20Roll(out.faces.max(), out.faces, "2d20kh1") else D20Roll(out.faces.min(), out.faces, "2d20kl1")
        }

        fun perform(a: D20Action): Boolean = when (a.kind) {
            "check" -> abilityCheck(a)
            "save" -> standaloneSave(a)
            "contest" -> contest(a.actorId, a.targetIds.single(), requireNotNull(a.skill), setOf(requireNotNull(a.skill)), false, a.advantage, "contest:${a.skill}")
            "weapon" -> weapon(a)
            "card" -> card(a)
            "rest" -> rest(a)
            else -> error("UNKNOWN_D20_KIND")
        }

        fun abilityCheck(a: D20Action): Boolean {
            val actor = cb(a.actorId)
            val skill = a.skill; val attribute = a.attribute
            val bonus: Int; val label: String
            if (skill != null) { bonus = skillBonus(actor, skill); label = skill } else { val attr = requireNotNull(attribute); bonus = mod(actor, attr); label = attr }
            val dc = d.dcTiers.getValue(requireNotNull(a.dcTier))
            val roll = rollD20(modelAdvantage(a.advantage))
            val total = roll.natural + bonus
            val success = total >= dc
            rolls += RollRecord(a.actorId, "check:$label", roll.expression, roll.faces, bonus, total, dc, success)
            return success
        }

        /** A saving throw. Conditions that auto fail the attribute record an "auto_fail" roll without consuming dice. */
        fun saveRoll(id: String, attribute: String, dc: Int, advantage: Int, purpose: String): Boolean {
            val c = cb(id)
            if (c.conditions.any { attribute in d.conditions.getValue(it.id).autoFailSaves }) {
                rolls += RollRecord(id, purpose, "auto_fail", emptyList(), 0, 0, dc, false)
                return false
            }
            val roll = rollD20(advantage)
            val bonus = saveBonus(c, attribute)
            val total = roll.natural + bonus
            val success = total >= dc
            rolls += RollRecord(id, purpose, roll.expression, roll.faces, bonus, total, dc, success)
            return success
        }

        fun standaloneSave(a: D20Action): Boolean = saveRoll(a.actorId, requireNotNull(a.attribute), d.dcTiers.getValue(requireNotNull(a.dcTier)), modelAdvantage(a.advantage), "save:${a.attribute}")

        fun contest(actorId: String, targetId: String, actorSkill: String, defenseSkills: Set<String>, tieGoesToActor: Boolean, advantage: Int, purpose: String): Boolean {
            val actor = cb(actorId); val target = cb(targetId)
            val actorBonus = skillBonus(actor, actorSkill)
            val defenseBonus = defenseSkills.maxOf { skillBonus(target, it) }
            val actorRoll = rollD20(modelAdvantage(advantage))
            val defenseRoll = rollD20(0)
            val actorTotal = actorRoll.natural + actorBonus
            val defenseTotal = defenseRoll.natural + defenseBonus
            val won = actorTotal > defenseTotal || (actorTotal == defenseTotal && tieGoesToActor)
            rolls += RollRecord(actorId, purpose, actorRoll.expression, actorRoll.faces, actorBonus, actorTotal, defenseTotal, won)
            rolls += RollRecord(targetId, purpose, defenseRoll.expression, defenseRoll.faces, defenseBonus, defenseTotal, actorTotal, !won)
            return won
        }

        /** Returns (hit, critical). Natural 20 always hits and is critical; natural 1 always misses. */
        fun attackRoll(attackerId: String, defenderId: String, bonus: Int, modelAdv: Int, purpose: String): Pair<Boolean, Boolean> {
            val attacker = cb(attackerId); val defender = cb(defenderId)
            val ups = (if (defender.conditions.any { d.conditions.getValue(it.id).attackedAdvantage }) 1 else 0) + (if (modelAdv > 0) 1 else 0)
            val downs = (if (attacker.conditions.any { d.conditions.getValue(it.id).attackDisadvantage }) 1 else 0) + (if (modelAdv < 0) 1 else 0)
            val roll = rollD20(netAdvantage(ups, downs))
            val critical = roll.natural == 20
            val total = roll.natural + bonus
            val hit = roll.natural != 1 && (critical || total >= defender.armorClass)
            rolls += RollRecord(attackerId, purpose, roll.expression, roll.faces, bonus, total, defender.armorClass, hit)
            return hit to critical
        }

        /** A critical hit rolls the dice part of the expression a second time; the flat modifier is counted once. */
        fun rollDamage(expression: String, critical: Boolean): DiceOutcome {
            val base = DiceExpression.roll(expression, dice)
            if (!critical || base.faces.isEmpty()) return base
            val diceOnly = requireNotNull(Regex("[0-9]+d[0-9]+").find(expression)) { "INVALID_DICE" }.value
            val extra = DiceExpression.roll(diceOnly, dice)
            return DiceOutcome(expression, base.faces + extra.faces, base.modifier, base.total + extra.total)
        }

        fun weapon(a: D20Action): Boolean {
            val actor = cb(a.actorId)
            val weapon = actor.weaponAttacks.getValue(requireNotNull(a.weaponId))
            val targetId = a.targetIds.single()
            val bonus = mod(actor, weapon.attribute) + if (weapon.proficient) actor.proficiencyBonus else 0
            val (hit, critical) = attackRoll(a.actorId, targetId, bonus, a.advantage, "attack:${a.weaponId}")
            if (hit) {
                val out = rollDamage(weapon.damage, critical)
                rolls += RollRecord(a.actorId, "damage:${a.weaponId}", out.expression, out.faces, out.modifier, out.total, null, null)
                poolChange(targetId, d.rest.hitPoolId, maxOf(0, out.total), weapon.tag, a.actorId, null)
            }
            return hit
        }

        fun card(a: D20Action): Boolean {
            val cardId = requireNotNull(a.cardId)
            val card = d.cards.getValue(cardId)
            val actor = cb(a.actorId)
            val level = effectiveSlotLevel(a, card)
            if (card.concentration && actor.concentration != null) endConcentration(a.actorId)
            if (level != null) emit(D20Change("slot", a.actorId, level.toString(), -1))
            card.counterCost.forEach { (counterId, cost) -> emit(D20Change("counter", a.actorId, counterId, -cost)) }
            val keyAttribute = card.keyAttribute
            val dc = if (keyAttribute != null) 8 + actor.proficiencyBonus + mod(actor, keyAttribute) else 0
            var effective = false
            a.targetIds.forEach { targetId ->
                when (card.resolution) {
                    "auto" -> { effective = true; applyEffects(card.onSuccess, card, cardId, a.actorId, targetId, false, false, dc) }
                    "contest" -> {
                        val won = contest(a.actorId, targetId, requireNotNull(card.contestSkill), card.contestDefenseSkills, card.tieGoesToActor, a.advantage, "contest:$cardId")
                        if (won) effective = true
                        applyEffects(if (won) card.onSuccess else card.onFailure, card, cardId, a.actorId, targetId, false, false, dc)
                    }
                    "attack" -> {
                        val bonus = mod(actor, requireNotNull(keyAttribute)) + actor.proficiencyBonus
                        val (hit, critical) = attackRoll(a.actorId, targetId, bonus, a.advantage, "attack:$cardId")
                        if (hit) effective = true
                        applyEffects(if (hit) card.onSuccess else card.onFailure, card, cardId, a.actorId, targetId, critical, false, dc)
                    }
                    "save" -> {
                        val advantage = if (card.saveAdvantageWhenHostile && targetId != a.actorId) 1 else 0
                        val saved = saveRoll(targetId, requireNotNull(card.saveAttribute), dc, advantage, "save:$cardId")
                        if (!saved) effective = true
                        applyEffects(if (saved) card.onFailure else card.onSuccess, card, cardId, a.actorId, targetId, false, saved, dc)
                        if (saved) applyEffects(card.onTargetSaveSuccessToActor, card, cardId, a.actorId, targetId, false, false, dc)
                    }
                    else -> error("UNKNOWN_RESOLUTION")
                }
            }
            if (card.concentration) emit(D20Change("concentration_set", a.actorId, cardId, null, null, a.targetIds.joinToString(",")))
            return effective
        }

        fun applyEffects(effects: List<CardEffect>, card: CardRule, cardId: String, actorId: String, targetId: String, critical: Boolean, targetSaved: Boolean, dc: Int) {
            effects.forEach { e ->
                val required = e.ifTargetHasCondition
                if (required != null && cb(targetId).conditions.none { it.id == required }) return@forEach
                val recipient = if (e.target == "target") targetId else actorId
                when (e.type) {
                    "pool" -> {
                        val poolId = requireNotNull(e.pool)
                        val negated = targetSaved && recipient == targetId && d.saveSuccessNegatesPoolLoss
                        if (!negated) poolChange(recipient, poolId, rawAmount(e, actorId, critical && e.target == "target", "damage:$poolId"), e.tag, actorId, cardId)
                    }
                    "counter" -> counterChange(recipient, requireNotNull(e.counter), rawAmount(e, actorId, false, "counter:${e.counter}"))
                    "condition_add" -> {
                        val repeat = if (card.repeatSave) RepeatSave(requireNotNull(card.saveAttribute), dc) else null
                        addCondition(recipient, requireNotNull(e.condition), e.duration, actorId, cardId, repeat)
                    }
                    "condition_remove" -> {
                        val id = requireNotNull(e.condition)
                        cb(recipient).conditions.filter { it.id == id }.forEach { emit(D20Change("condition_remove", recipient, condition = it)) }
                    }
                    else -> error("UNKNOWN_EFFECT")
                }
            }
        }

        fun rawAmount(e: CardEffect, rollerId: String, critical: Boolean, purpose: String): Int {
            val expression = e.dice
            if (expression != null) {
                val out = rollDamage(expression, critical)
                rolls += RollRecord(rollerId, purpose, out.expression, out.faces, out.modifier, out.total, null, null)
                return out.total
            }
            return requireNotNull(e.amount) { "INVALID_CARD_EFFECT" }
        }

        fun counterChange(id: String, counterId: String, raw: Int) {
            val value = requireNotNull(cb(id).counters[counterId]) { "UNKNOWN_COUNTER" }
            val delta = if (raw > 0) -minOf(raw, value.current) else minOf(-raw, value.max - value.current)
            if (delta != 0) emit(D20Change("counter", id, counterId, delta))
        }

        /** Positive raw is loss, negative raw restores. Save negation is decided by the caller before any dice are rolled. Order: source check, cap, no-empty-from-full, floor, zero effects. */
        fun poolChange(id: String, poolId: String, raw: Int, tag: String, sourceId: String, sourceCard: String?) {
            val pool = d.pools.getValue(poolId)
            val value = requireNotNull(cb(id).pools[poolId]) { "UNKNOWN_POOL" }
            if (raw == 0) return
            if (raw < 0) {
                val healed = minOf(-raw, value.max - value.current)
                if (healed > 0) emit(D20Change("pool", id, poolId, healed))
                return
            }
            var loss = raw
            if (loss > 0) require(pool.damageSourceOnly.isEmpty() || tag in pool.damageSourceOnly) { "POOL_SOURCE_FORBIDDEN" }
            val cap = pool.perRoundLossCap
            if (loss > 0 && cap != null) {
                val group = if (pool.capGroup != null) pool.capGroup else poolId
                val spent = st.roundLosses.getOrDefault("$id|$group", 0)
                loss = minOf(loss, maxOf(0, cap - spent))
            }
            if (loss > 0 && pool.singleHitCannotEmptyFromFull && value.current == value.max && loss >= value.current) loss = maxOf(0, value.current - 1)
            loss = minOf(loss, value.current)
            if (loss <= 0) return
            emit(D20Change("pool", id, poolId, -loss))
            if (poolId == d.rest.hitPoolId) {
                cb(id).conditions.filter { d.conditions.getValue(it.id).endsOnDamage }.forEach { emit(D20Change("condition_remove", id, condition = it)) }
                if (cb(id).concentration != null) {
                    if (!saveRoll(id, d.concentrationAttribute, maxOf(10, loss / 2), 0, "concentration")) endConcentration(id)
                }
            }
            if (cb(id).pools.getValue(poolId).current == 0) {
                val zero = pool.atZeroCondition
                if (zero != null) addCondition(id, zero, pool.atZeroDuration, sourceId, sourceCard, null)
                if (poolId in d.defeatPoolIds) emit(D20Change("defeated", id, poolId))
            }
        }

        fun expiry(spec: DurationSpec?): Pair<Long?, Long?> {
            if (spec == null) return Pair(null, null)
            return when (spec.unit) {
                "until_removed" -> Pair(null, null)
                "rounds" -> Pair(st.round + spec.value, null)
                "minutes" -> Pair(null, world.gameTime + spec.value)
                "until_target_turn_start", "until_actor_turn_end" -> Pair(st.round + 1, null)
                else -> error("INVALID_DURATION")
            }
        }

        fun addCondition(id: String, conditionId: String, spec: DurationSpec?, sourceId: String, sourceCard: String?, repeat: RepeatSave?) {
            val (round, minute) = expiry(spec)
            emit(D20Change("condition_add", id, condition = ActiveCondition(conditionId, sourceId, sourceCard, round, minute, repeat)))
        }

        /** Ends the actor's concentration and removes every condition that the concentrated card attached. */
        fun endConcentration(id: String) {
            val conc = requireNotNull(cb(id).concentration) { "NOT_CONCENTRATING" }
            conc.targetIds.forEach { targetId ->
                cb(targetId).conditions.filter { it.sourceId == id && it.sourceCard == conc.cardId }.forEach { emit(D20Change("condition_remove", targetId, condition = it)) }
            }
            emit(D20Change("concentration_clear", id))
        }

        fun rest(a: D20Action): Boolean {
            val actor = cb(a.actorId)
            val long = a.restType == "long"
            fun due(mode: String) = mode == "short" || (long && mode == "long")
            actor.pools.forEach { (poolId, value) -> if (due(d.pools.getValue(poolId).restoreOn) && value.current < value.max) emit(D20Change("pool", a.actorId, poolId, value.max - value.current)) }
            actor.counters.forEach { (counterId, value) -> if (due(d.counters.getValue(counterId).restoreOn) && value.current < value.max) emit(D20Change("counter", a.actorId, counterId, value.max - value.current)) }
            if (due(actor.slotRestoreOn)) actor.slots.forEach { (level, value) -> if (value.current < value.max) emit(D20Change("slot", a.actorId, level.toString(), value.max - value.current)) }
            emit(D20Change("minutes_advance", a.actorId, null, if (long) d.rest.longMinutes else d.rest.shortMinutes))
            st.combatants.keys.toList().forEach { id -> cb(id).conditions.filter { it.expiresMinute != null }.forEach { emit(D20Change("condition_remove", id, condition = it)) } }
            return true
        }

        /** Round bookkeeping after all actions: advance, expire, repeat saves, then drop minute based conditions that narrative time has passed. */
        fun closeExchange(anchorId: String, narrative: List<Effect>) {
            emit(D20Change("round_advance", anchorId, null, 1))
            val round = st.round
            st.combatants.keys.toList().forEach { id ->
                cb(id).conditions.filter { it.expiresRound != null && it.expiresRound <= round }.forEach { emit(D20Change("condition_remove", id, condition = it)) }
            }
            st.combatants.keys.toList().forEach { id ->
                cb(id).conditions.filter { it.repeatSave != null }.forEach { active ->
                    val repeat = requireNotNull(active.repeatSave)
                    if (saveRoll(id, repeat.attribute, repeat.dc, 0, "repeat_save:${active.id}")) emit(D20Change("condition_remove", id, condition = active))
                }
            }
            val later = world.gameTime + narrative.filter { it.type == "time" }.sumOf { requireNotNull(it.amount) }
            st.combatants.keys.toList().forEach { id ->
                cb(id).conditions.filter { it.expiresMinute != null && it.expiresMinute <= later }.forEach { emit(D20Change("condition_remove", id, condition = it)) }
            }
        }
    }

    // ---------------------------------------------------------------- recorded events

    private fun applyChange(w: WorldState, ch: D20Change, d: D20Rules): WorldState {
        val st = requireNotNull(w.d20) { "MISSING_D20_STATE" }
        fun withCombatant(f: (D20Combatant) -> D20Combatant): D20State {
            val c = requireNotNull(st.combatants[ch.combatantId]) { "UNKNOWN_COMBATANT" }
            return st.copy(combatants = st.combatants + (ch.combatantId to f(c)))
        }
        return when (ch.type) {
            "pool" -> {
                val key = requireNotNull(ch.key); val delta = requireNotNull(ch.delta)
                val pool = requireNotNull(d.pools[key]) { "UNKNOWN_POOL" }
                val next = withCombatant { c ->
                    val value = requireNotNull(c.pools[key]) { "UNKNOWN_POOL" }
                    require(value.current + delta in 0..value.max) { "POOL_BOUNDS" }
                    c.copy(pools = c.pools + (key to value.copy(current = value.current + delta)))
                }
                if (delta < 0) {
                    val group = if (pool.capGroup != null) pool.capGroup else key
                    val lossKey = "${ch.combatantId}|$group"
                    w.copy(d20 = next.copy(roundLosses = next.roundLosses + (lossKey to (next.roundLosses.getOrDefault(lossKey, 0) - delta))))
                } else w.copy(d20 = next)
            }
            "slot" -> {
                val level = requireNotNull(ch.key).toInt(); val delta = requireNotNull(ch.delta)
                w.copy(d20 = withCombatant { c ->
                    val value = requireNotNull(c.slots[level]) { "UNKNOWN_SLOT" }
                    require(value.current + delta in 0..value.max) { "SLOT_BOUNDS" }
                    c.copy(slots = c.slots + (level to value.copy(current = value.current + delta)))
                })
            }
            "counter" -> {
                val key = requireNotNull(ch.key); val delta = requireNotNull(ch.delta)
                w.copy(d20 = withCombatant { c ->
                    val value = requireNotNull(c.counters[key]) { "UNKNOWN_COUNTER" }
                    require(value.current + delta in 0..value.max) { "COUNTER_BOUNDS" }
                    c.copy(counters = c.counters + (key to value.copy(current = value.current + delta)))
                })
            }
            "condition_add" -> {
                val added = requireNotNull(ch.condition)
                require(added.id in d.conditions) { "UNKNOWN_CONDITION" }
                w.copy(d20 = withCombatant { c -> c.copy(conditions = c.conditions.filterNot { it.id == added.id && it.sourceId == added.sourceId } + added) })
            }
            "condition_remove" -> {
                val removed = requireNotNull(ch.condition)
                w.copy(d20 = withCombatant { c ->
                    val kept = c.conditions.filterNot { it == removed }
                    require(kept.size < c.conditions.size) { "CONDITION_NOT_FOUND" }
                    c.copy(conditions = kept)
                })
            }
            "concentration_set" -> {
                val targets = requireNotNull(ch.value).split(",")
                w.copy(d20 = withCombatant { c -> c.copy(concentration = ActiveConcentration(requireNotNull(ch.key), targets, null)) })
            }
            "concentration_clear" -> w.copy(d20 = withCombatant { c ->
                require(c.concentration != null) { "NOT_CONCENTRATING" }
                c.copy(concentration = null)
            })
            "defeated" -> {
                requireNotNull(st.combatants[ch.combatantId]) { "UNKNOWN_COMBATANT" }
                w.copy(npcs = w.npcs.map { if (it.id == ch.combatantId) it.copy(status = "defeated") else it })
            }
            "round_advance" -> w.copy(d20 = st.copy(round = st.round + requireNotNull(ch.delta), roundLosses = emptyMap()))
            "minutes_advance" -> w.copy(gameTime = Math.addExact(w.gameTime, requireNotNull(ch.delta).toLong()))
            else -> error("UNKNOWN_D20_CHANGE")
        }
    }

    override fun applyRecordedEvent(world: WorldState, event: WorldEvent, module: ValidatedModule): WorldState {
        require(event.eventVersion == 2) { "UNKNOWN_EVENT_VERSION" }
        val d = rulesOf(module)
        var w = world
        event.d20Changes.forEach { w = applyChange(w, it, d) }
        event.effects.forEach { e ->
            NarrativeEffects.validate(e, w, module, narrativeEffects)
            w = NarrativeEffects.apply(w, e, event.seq, module)
        }
        event.creations.forEach { c ->
            require(c.source.authority == "generated" && c.type in module.world.policy.allowedEntityTypes && w.generated.none { it.id == c.id })
            w = w.copy(generated = w.generated + c, npcs = if (c.type == "npc") w.npcs + NpcState(c.id, c.title, c.locationId, "idle", c.description, "未设定") else w.npcs)
        }
        w = Scheduler.apply(w, event.scheduled, event.seq, module)
        validateState(w, module); return w
    }

    override fun validateState(world: WorldState, module: ValidatedModule) {
        Scheduler.validateState(world, module)
        val d = rulesOf(module)
        require(module.world.locations.any { it.id == world.locationId })
        require(world.gameTime >= 0 && world.generated.size <= module.world.policy.maxEntities)
        require(world.knowledge.all { k -> world.facts.any { it.id == k.factId } && (k.knowerId == "player" || world.npcs.any { it.id == k.knowerId }) })
        val st = requireNotNull(world.d20) { "MISSING_D20_STATE" }
        val pcs = pcIds(module)
        require(st.round >= 0 && st.roundLosses.values.all { it >= 0 }) { "INVALID_D20_STATE" }
        require(st.combatants.all { (key, c) -> key == c.id && (c.id in pcs || world.npcs.any { it.id == c.id }) }) { "INVALID_COMBATANT" }
        require(st.combatants.keys.count { it in pcs } == 1) { "INVALID_COMBATANT" }
        st.combatants.values.forEach { validateCombatant(it, d) }
    }
}
