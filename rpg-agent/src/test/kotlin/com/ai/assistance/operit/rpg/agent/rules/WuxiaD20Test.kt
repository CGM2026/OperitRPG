package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.TestMemories
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.memory.*
import com.ai.assistance.operit.rpg.agent.module.ExampleModules
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.*

/** Uses a minimal module built in this file. Every pool, condition and card name below is test data only. */
class WuxiaD20Test {
    /** Deterministic dice: hands out the given values in order and records the requested sides. */
    private class Seq(vararg values: Int) {
        val seen = mutableListOf<Int>()
        private val iter = values.iterator()
        val source = DiceSource { sides -> seen += sides; check(iter.hasNext()) { "DICE_EXHAUSTED" }; iter.next() }
        fun exhausted(): Boolean = !iter.hasNext()
    }
    private class Settled(val campaign: Campaign, val event: WorldEvent, val result: CommittedResult)

    // ------------------------------------------------------------ fixture

    private val src = SourceRef("module/sources/rules.md", "hash", "rules", "module_rule")
    private val evidence = listOf(RuleEvidence("t:rule", src, "quote", Visibility(Audience.PUBLIC)))

    private fun fx(type: String, target: String, tag: String = "spell", pool: String? = null, dice: String? = null, amount: Int? = null, condition: String? = null, duration: DurationSpec? = null, ifHas: String? = null) =
        CardEffect(type = type, target = target, pool = pool, dice = dice, amount = amount, condition = condition, duration = duration, tag = tag, ifTargetHasCondition = ifHas)

    private val cards: Map<String, CardRule> = mapOf(
        "bolt" to CardRule(label = "Bolt", text = "bolt", timing = "action", isSpell = true, slotLevel = 1, resolution = "save", keyAttribute = "insight", saveAttribute = "vigor",
            onSuccess = listOf(fx("pool", "target", pool = "hp", dice = "2d6")),
            onFailure = listOf(fx("pool", "target", pool = "hp", dice = "1d4")),
            onTargetSaveSuccessToActor = listOf(fx("pool", "actor", pool = "hp", amount = 2))),
        "charm" to CardRule(label = "Charm", text = "charm", timing = "action", isSpell = true, resolution = "save", keyAttribute = "insight", saveAttribute = "insight",
            onSuccess = listOf(fx("pool", "target", tag = "charm", pool = "poise", amount = 5))),
        "crush" to CardRule(label = "Crush", text = "crush", timing = "action", isSpell = true, resolution = "auto",
            onSuccess = listOf(fx("pool", "target", tag = "charm", pool = "will", amount = 100))),
        "mend" to CardRule(label = "Mend", text = "mend", timing = "bonus", isSpell = true, slotLevel = 1, resolution = "auto",
            onSuccess = listOf(fx("pool", "actor", tag = "heal", pool = "hp", amount = -3))),
        "snare" to CardRule(label = "Snare", text = "snare", timing = "action", isSpell = true, concentration = true, resolution = "auto",
            onSuccess = listOf(fx("condition_add", "target", condition = "frail", duration = DurationSpec("until_removed")))),
        "hex" to CardRule(label = "Hex", text = "hex", timing = "action", isSpell = true, concentration = true, resolution = "auto",
            onSuccess = listOf(fx("condition_add", "target", condition = "shaken", duration = DurationSpec("until_removed")))),
        "sting" to CardRule(label = "Sting", text = "sting", timing = "action", isSpell = false, resolution = "save", keyAttribute = "might", saveAttribute = "vigor", repeatSave = true,
            onSuccess = listOf(fx("condition_add", "target", tag = "poison", condition = "poisoned", duration = DurationSpec("rounds", 3)))),
        "tangle" to CardRule(label = "Tangle", text = "tangle", timing = "action", isSpell = false, maxTargets = 2, resolution = "auto",
            onSuccess = listOf(fx("condition_add", "target", condition = "frail", duration = DurationSpec("rounds", 2)))),
        "grip" to CardRule(label = "Grip", text = "grip", timing = "action", isSpell = false, resolution = "contest", contestSkill = "athletics", contestDefenseSkills = setOf("athletics", "stealth"),
            onSuccess = listOf(fx("condition_add", "target", condition = "bound", duration = DurationSpec("until_removed")))),
        "slash" to CardRule(label = "Slash", text = "slash", timing = "action", isSpell = false, resolution = "attack", keyAttribute = "might",
            onSuccess = listOf(fx("pool", "target", tag = "weapon", pool = "hp", dice = "1d8"))),
        "breather" to CardRule(label = "Breather", text = "breather", timing = "bonus", isSpell = false, counterCost = mapOf("breath" to 1), resolution = "auto",
            onSuccess = listOf(fx("pool", "actor", tag = "heal", pool = "hp", amount = -5))),
        "misuse" to CardRule(label = "Misuse", text = "misuse", timing = "action", isSpell = false, resolution = "auto",
            onSuccess = listOf(fx("pool", "target", tag = "weapon", pool = "poise", amount = 1))),
        "ambush" to CardRule(label = "Ambush", text = "ambush", timing = "action", isSpell = false, resolution = "auto", requiresTargetCondition = setOf("frail"),
            onSuccess = listOf(fx("pool", "target", pool = "hp", amount = 1))),
        "finisher" to CardRule(label = "Finisher", text = "finisher", timing = "action", isSpell = false, resolution = "auto",
            onSuccess = listOf(fx("pool", "target", pool = "hp", amount = 3, ifHas = "frail"))),
    )

    private val d20Rules = D20Rules(
        attributes = listOf("might", "agility", "vigor", "insight"),
        skills = mapOf("athletics" to "might", "stealth" to "agility", "perception" to "insight"),
        dcTiers = mapOf("easy" to 10, "medium" to 13, "hard" to 15),
        pools = mapOf(
            "hp" to PoolRule("HP", atZeroCondition = "stunned", atZeroDuration = DurationSpec("until_target_turn_start"), restoreOn = "long"),
            "poise" to PoolRule("Poise", perRoundLossCap = 6, capGroup = "g", damageSourceOnly = setOf("charm"), restoreOn = "short"),
            "will" to PoolRule("Will", damageSourceOnly = setOf("charm"), singleHitCannotEmptyFromFull = true, restoreOn = "short"),
        ),
        counters = mapOf("breath" to CounterRule("Breath", "short"), "focus_pt" to CounterRule("Focus", "long")),
        conditions = mapOf(
            "stunned" to ConditionRule("Stunned", "cannot act", blocksActions = true),
            "frail" to ConditionRule("Frail", "attacked with advantage", attackedAdvantage = true),
            "shaken" to ConditionRule("Shaken", "attacks with disadvantage", attackDisadvantage = true),
            "bound" to ConditionRule("Bound", "cannot move", speedZero = true, autoFailSaves = setOf("agility")),
            "poisoned" to ConditionRule("Poisoned", "ill"),
            "dazed" to ConditionRule("Dazed", "ends on damage", endsOnDamage = true),
        ),
        cards = cards,
        rest = RestRule(10, 480, "hp"),
        saveSuccessNegatesPoolLoss = true,
        defeatPoolIds = setOf("hp"),
        concentrationAttribute = "vigor",
    )

    private fun fighter(id: String, name: String) = D20Combatant(
        id = id, name = name, level = 1,
        scores = mapOf("might" to 14, "agility" to 10, "vigor" to 10, "insight" to 14),
        proficiencyBonus = 2, saveProficiencies = emptySet(),
        skillRanks = mapOf("athletics" to 1, "stealth" to 0, "perception" to 0),
        armorClass = 12,
        pools = mapOf("hp" to PoolValue(20, 20), "poise" to PoolValue(10, 10), "will" to PoolValue(10, 10)),
        slots = mapOf(1 to PoolValue(2, 2), 2 to PoolValue(1, 1)), slotRestoreOn = "long",
        counters = mapOf("breath" to PoolValue(1, 1), "focus_pt" to PoolValue(1, 1)),
        cards = cards.keys,
        weaponAttacks = mapOf("sword" to WeaponAttack("Sword", "might", true, "1d8+2")),
    )

    private val module = ValidatedModule(
        ModuleManifest(1, "t", "1", "T", "a", "d", 1, "wuxia-d20-v1", "1", emptyList()),
        "hash",
        ModuleWorld(
            "t:hall",
            listOf(CharacterTemplate("t:hero", "Hero", emptyMap(), emptyMap())),
            listOf(Location("t:hall", "Hall", "d", listOf("t:yard")), Location("t:yard", "Yard", "d", listOf("t:hall"))),
            listOf(NpcState("t:foe1", "Foe One", "t:hall", "idle", "p", "g"), NpcState("t:foe2", "Foe Two", "t:hall", "idle", "p", "g"), NpcState("t:far", "Far", "t:yard", "idle", "p", "g")),
            emptyList(), emptyList(), emptyList(), emptyList(),
            GenerationPolicy(emptySet(), 0, emptySet()),
            listOf(fighter("t:hero", "Hero"), fighter("t:foe1", "Foe One"), fighter("t:foe2", "Foe Two"), fighter("t:far", "Far")),
        ),
        ModuleRules("wuxia-d20-v1", "1", emptySet(), emptyMap(), emptyMap(), setOf("time"), evidence, "min", d20Rules),
        mapOf("module/sources/rules.md" to "rules"),
    )
    private val adapter = WuxiaD20()
    private val hero = "t:hero"
    private val foe1 = "t:foe1"
    private val foe2 = "t:foe2"

    private fun fresh(): Campaign = Campaign("c", "t", VersionRef("t", "1", module.contentHash), VersionRef("wuxia-d20-v1", "1", module.contentHash), 0, 0, initialWorld(module, hero), false, "m", 0)
    private fun Campaign.cb(id: String): D20Combatant = requireNotNull(world.d20).combatants.getValue(id)
    private fun Campaign.hp(id: String): Int = cb(id).pools.getValue("hp").current
    private fun Campaign.edit(id: String, f: (D20Combatant) -> D20Combatant): Campaign {
        val st = requireNotNull(world.d20)
        return copy(world = world.copy(d20 = st.copy(combatants = st.combatants + (id to f(st.combatants.getValue(id))))))
    }
    private fun cond(id: String, source: String = foe2, round: Long? = null, minute: Long? = null) = ActiveCondition(id, source, null, round, minute)

    private fun prop(vararg actions: D20Action, effects: List<Effect> = emptyList(), failure: List<Effect> = emptyList()) =
        ActionProposal("d20", emptyList(), null, "", listOf("t:rule"), emptyList(), effects, failure, publicSummary = "s", d20 = actions.toList())
    private fun checkAction(actor: String, skill: String? = null, attribute: String? = null, tier: String = "medium", adv: Int = 0) =
        D20Action("check", actor, skill = skill, attribute = attribute, dcTier = tier, advantage = adv, advantageReason = if (adv != 0) "reason" else null)
    private fun weapon(actor: String, target: String, adv: Int = 0) =
        D20Action("weapon", actor, listOf(target), weaponId = "sword", advantage = adv, advantageReason = if (adv != 0) "reason" else null)
    private fun cast(actor: String, cardId: String, vararg targets: String, level: Int? = null) = D20Action("card", actor, targets.toList(), cardId = cardId, slotLevel = level)

    private fun validate(c: Campaign, p: ActionProposal) = adapter.validateProposal(c, module, p, module.rules.evidence)
    private fun code(expected: String, block: () -> Unit) = assertEquals(expected, assertFailsWith<IllegalArgumentException> { block() }.message)
    private fun settle(c: Campaign, p: ActionProposal, dice: DiceSource): Settled {
        val plan = freeze(c, p, module.rules.evidence)
        val (event, result) = adapter.resolve(c, plan, module, "turn-${c.revision}", dice)
        val world = adapter.applyRecordedEvent(c.world, event, module)
        return Settled(c.copy(world = world, revision = c.revision + 1, lastEventSeq = event.seq), event, result)
    }
    private fun settle(c: Campaign, p: ActionProposal, seq: Seq): Settled = settle(c, p, seq.source).also { assertTrue(seq.exhausted(), "unused dice") }

    // ------------------------------------------------------------ registry, module, versions

    @Test fun registryModuleValidationAndInitialWorld() {
        assertIs<WuxiaD20>(RuleAdapters.forRules("wuxia-d20-v1", "1"))
        assertIs<WuxiaD20>(RuleAdapters.forModule(module))
        assertEquals("UNKNOWN_RULE_ADAPTER", assertFailsWith<IllegalArgumentException> { RuleAdapters.forRules("wuxia-d20-v1", "2") }.message)
        adapter.validateModule(module)
        val st = requireNotNull(fresh().world.d20)
        assertEquals(setOf(hero, foe1, foe2, "t:far"), st.combatants.keys)
        assertEquals(0L, st.round)
        val bad = module.copy(rules = module.rules.copy(d20 = d20Rules.copy(cards = cards + ("bad" to CardRule(label = "Bad", text = "t", timing = "action", isSpell = false, resolution = "auto", onSuccess = listOf(fx("pool", "target", pool = "nope", amount = 1)))))))
        code("UNKNOWN_POOL") { adapter.validateModule(bad) }
        val noHero = module.copy(world = module.world.copy(d20Combatants = module.world.d20Combatants.filter { it.id != hero }))
        code("MISSING_COMBATANT") { adapter.validateModule(noHero) }
    }

    @Test fun genericAdapterStillRejectsD20AndVersionTwo() {
        val ex = ExampleModules.all().first()
        val campaign = Campaign("c", "t", VersionRef(ex.manifest.moduleId, ex.manifest.version, ex.contentHash), VersionRef(ex.rules.adapterId, ex.rules.adapterVersion, ex.contentHash), 0, 0, initialWorld(ex, ex.world.characters.first().id), false, "m", 0)
        assertNull(campaign.world.d20)
        val p = ActionProposal("d20", emptyList(), null, "normal", listOf(ex.rules.evidence.first().ruleId), emptyList(), emptyList(), emptyList(), publicSummary = "s", d20 = listOf(checkAction(hero, skill = "athletics")))
        code("UNKNOWN_ACTION") { GenericD100().validateProposal(campaign, ex, p, ex.rules.evidence) }
        val event = WorldEvent(2, "c", 1, "t", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList())
        code("UNKNOWN_EVENT_VERSION") { GenericD100().applyRecordedEvent(campaign.world, event, ex) }
        code("UNKNOWN_EVENT_VERSION") { adapter.applyRecordedEvent(fresh().world, event.copy(eventVersion = 1), module) }
        assertFalse(StrictJson.json.encodeToString(PublicProjection.campaign(campaign, ex)).contains("d20"))
        assertFalse(ContextAssembler(TestMemories()).planning(campaign, ex, Turn("t", "c", 1, "m", "go", 0, TurnPhase.GATHERING)).system.contains("d20提议"))
    }

    // ------------------------------------------------------------ checks, advantage, naturals

    @Test fun checkSuccessFailureAndAdvantage() {
        val ok = settle(fresh(), prop(checkAction(hero, skill = "athletics")), Seq(9))
        assertTrue(ok.result.success)
        assertEquals(2, ok.event.eventVersion)
        assertEquals(listOf(RollRecord(hero, "check:athletics", "1d20", listOf(9), 4, 13, 13, true)), ok.event.rolls)
        assertEquals(1L, requireNotNull(ok.campaign.world.d20).round)
        assertFalse(settle(fresh(), prop(checkAction(hero, skill = "athletics")), Seq(8)).result.success)
        val high = settle(fresh(), prop(checkAction(hero, skill = "athletics", adv = 1)), Seq(3, 15))
        assertEquals(RollRecord(hero, "check:athletics", "2d20kh1", listOf(3, 15), 4, 19, 13, true), high.event.rolls.single())
        val low = settle(fresh(), prop(checkAction(hero, skill = "athletics", adv = -1)), Seq(15, 3))
        assertEquals(RollRecord(hero, "check:athletics", "2d20kl1", listOf(15, 3), 4, 7, 13, false), low.event.rolls.single())
        val attr = settle(fresh(), prop(checkAction(hero, attribute = "insight", tier = "easy")), Seq(8))
        assertTrue(attr.result.success)
        assertEquals("check:insight", attr.event.rolls.single().purpose)
    }

    @Test fun advantageAndDisadvantageCancel() {
        val shaken = fresh().edit(hero) { it.copy(conditions = listOf(cond("shaken"))) }
        val frail = fresh().edit(foe1) { it.copy(conditions = listOf(cond("frail"))) }
        val both = shaken.edit(foe1) { it.copy(conditions = listOf(cond("frail"))) }
        val seq = Seq(10, 5)
        val cancelled = settle(both, prop(weapon(hero, foe1)), seq)
        assertEquals(listOf(20, 8), seq.seen)
        assertEquals(listOf(10), cancelled.event.rolls.first().faces)
        assertEquals(13, cancelled.campaign.hp(foe1))
        assertEquals(listOf(20, 8), Seq(10, 5).also { settle(shaken.edit(foe1) { c -> c.copy(conditions = listOf(cond("frail"))) }, prop(weapon(hero, foe1, adv = 1)), it) }.seen)
        val disadvantage = settle(shaken, prop(weapon(hero, foe1)), Seq(15, 3))
        assertEquals(RollRecord(hero, "attack:sword", "2d20kl1", listOf(15, 3), 4, 7, 12, false), disadvantage.event.rolls.single())
        assertEquals(20, disadvantage.campaign.hp(foe1))
        val advantage = settle(frail, prop(weapon(hero, foe1)), Seq(3, 15, 4))
        assertEquals(listOf(3, 15), advantage.event.rolls.first().faces)
        assertEquals(14, advantage.campaign.hp(foe1))
    }

    @Test fun naturalTwentyIsCriticalAndNaturalOneAlwaysMisses() {
        val seq = Seq(20, 3, 4)
        val crit = settle(fresh(), prop(weapon(hero, foe1)), seq)
        assertEquals(listOf(20, 8, 8), seq.seen)
        assertEquals(RollRecord(hero, "damage:sword", "1d8+2", listOf(3, 4), 2, 9, null, null), crit.event.rolls[1])
        assertEquals(11, crit.campaign.hp(foe1))
        val miss = settle(fresh().edit(foe1) { it.copy(armorClass = 2) }, prop(weapon(hero, foe1)), Seq(1))
        assertFalse(miss.result.success)
        assertEquals(20, miss.campaign.hp(foe1))
        val wall = settle(fresh().edit(foe1) { it.copy(armorClass = 40) }, prop(weapon(hero, foe1)), Seq(20, 1, 1))
        assertEquals(16, wall.campaign.hp(foe1))
    }

    // ------------------------------------------------------------ save negation, caps, no-empty

    @Test fun savedTargetTakesNoLossButBacklashStillApplies() {
        val failed = settle(fresh(), prop(cast(hero, "bolt", foe1)), Seq(11, 4, 3))
        assertEquals(13, failed.campaign.hp(foe1))
        assertEquals(20, failed.campaign.hp(hero))
        assertEquals(1, failed.campaign.cb(hero).slots.getValue(1).current)
        assertEquals(RollRecord(foe1, "save:bolt", "1d20", listOf(11), 0, 11, 12, false), failed.event.rolls[0])
        assertEquals(RollRecord(hero, "damage:hp", "2d6", listOf(4, 3), 0, 7, null, null), failed.event.rolls[1])
        val saved = settle(fresh(), prop(cast(hero, "bolt", foe1)), Seq(12))
        assertEquals(20, saved.campaign.hp(foe1))
        assertEquals(18, saved.campaign.hp(hero))
        val upcast = settle(fresh(), prop(cast(hero, "bolt", foe1, level = 2)), Seq(12))
        assertEquals(0, upcast.campaign.cb(hero).slots.getValue(2).current)
        assertEquals(2, upcast.campaign.cb(hero).slots.getValue(1).current)
    }

    @Test fun roundLossCapIsSharedAcrossCastersAndClearsNextRound() {
        val both = settle(fresh(), prop(cast(foe1, "charm", hero), cast(foe2, "charm", hero)), Seq(5, 5))
        assertEquals(listOf(-5, -1), both.event.d20Changes.filter { it.type == "pool" }.map { it.delta })
        assertEquals(4, both.campaign.cb(hero).pools.getValue("poise").current)
        assertTrue(requireNotNull(both.campaign.world.d20).roundLosses.isEmpty())
        val next = settle(fresh(), prop(cast(foe1, "charm", hero)), Seq(5))
        assertEquals(5, next.campaign.cb(hero).pools.getValue("poise").current)
        val chained = settle(both.campaign.edit(hero) { it.copy(pools = it.pools + ("poise" to PoolValue(10, 10))) }, prop(cast(foe1, "charm", hero)), Seq(5))
        assertEquals(5, chained.campaign.cb(hero).pools.getValue("poise").current)
    }

    @Test fun fullPoolCannotBeEmptiedByOneHit() {
        val first = settle(fresh(), prop(cast(foe1, "crush", hero)), Seq())
        assertEquals(1, first.campaign.cb(hero).pools.getValue("will").current)
        val second = settle(first.campaign, prop(cast(foe1, "crush", hero)), Seq())
        assertEquals(0, second.campaign.cb(hero).pools.getValue("will").current)
    }

    @Test fun poolDamageSourceMismatchIsADataError() {
        code("POOL_SOURCE_FORBIDDEN") { settle(fresh(), prop(cast(foe1, "misuse", hero)), Seq()) }
    }

    @Test fun ifTargetHasConditionGatesEffect() {
        val without = settle(fresh(), prop(cast(hero, "finisher", foe1)), Seq())
        assertEquals(20, without.campaign.hp(foe1))
        val with = settle(fresh().edit(foe1) { it.copy(conditions = listOf(cond("frail"))) }, prop(cast(hero, "finisher", foe1)), Seq())
        assertEquals(17, with.campaign.hp(foe1))
    }

    // ------------------------------------------------------------ zero, duration, defeat

    @Test fun zeroPoolAttachesConditionThatExpiresAtNextRoundStart() {
        val low = fresh().edit(foe1) { it.copy(pools = it.pools + ("hp" to PoolValue(5, 20))) }
        val hit = settle(low, prop(weapon(hero, foe1)), Seq(15, 8))
        assertEquals(listOf("pool", "condition_add", "defeated", "round_advance", "condition_remove"), hit.event.d20Changes.map { it.type })
        assertEquals(0, hit.campaign.hp(foe1))
        assertTrue(hit.campaign.cb(foe1).conditions.isEmpty())
        assertEquals("defeated", hit.campaign.world.npcs.single { it.id == foe1 }.status)
        code("ACTOR_DEFEATED") { validate(hit.campaign, prop(weapon(foe1, hero))) }
    }

    @Test fun roundCountedConditionLastsItsRounds() {
        val first = settle(fresh(), prop(cast(hero, "tangle", foe1, foe2)), Seq())
        assertEquals(listOf(ActiveCondition("frail", hero, "tangle", 2L, null)), first.campaign.cb(foe1).conditions)
        assertEquals(1, first.campaign.cb(foe2).conditions.size)
        val second = settle(first.campaign, prop(checkAction(hero, skill = "athletics")), Seq(10))
        assertTrue(second.campaign.cb(foe1).conditions.isEmpty())
        assertTrue(second.campaign.cb(foe2).conditions.isEmpty())
    }

    @Test fun sameConditionFromSameSourceRefreshesInsteadOfStacking() {
        val first = settle(fresh(), prop(cast(hero, "tangle", foe1)), Seq())
        val again = settle(first.campaign.edit(foe1) { it.copy(conditions = it.conditions.map { c -> c.copy(expiresRound = 99L) }) }, prop(cast(hero, "tangle", foe1)), Seq())
        assertEquals(listOf(ActiveCondition("frail", hero, "tangle", 3L, null)), again.campaign.cb(foe1).conditions)
    }

    // ------------------------------------------------------------ validation rules

    @Test fun bonusSpellRuleTimingAndBlockedActors() {
        val c = fresh()
        validate(c, prop(cast(hero, "mend", hero), cast(hero, "charm", foe1)))
        validate(c, prop(cast(hero, "mend", hero), weapon(hero, foe1)))
        code("BONUS_SPELL_RULE") { validate(c, prop(cast(hero, "mend", hero), cast(hero, "bolt", foe1))) }
        code("BONUS_SPELL_RULE") { validate(c, prop(cast(hero, "bolt", foe1), cast(hero, "mend", hero))) }
        code("BONUS_SPELL_RULE") { validate(c, prop(cast(hero, "mend", hero), cast(hero, "slash", foe1))) }
        code("TIMING_ALREADY_USED") { validate(c, prop(cast(hero, "tangle", foe1), cast(hero, "tangle", foe2))) }
        code("TIMING_ALREADY_USED") { validate(c, prop(weapon(hero, foe1), checkAction(hero, skill = "athletics"))) }
        code("TIMING_ALREADY_USED") { validate(c, prop(cast(hero, "mend", hero), cast(hero, "breather", hero))) }
        validate(c, prop(weapon(hero, foe1), weapon(foe1, hero)))
        validate(c, prop(D20Action("save", hero, attribute = "vigor", dcTier = "easy"), weapon(hero, foe1)))
        val stunned = c.edit(hero) { it.copy(conditions = listOf(cond("stunned"))) }
        code("ACTOR_BLOCKED") { validate(stunned, prop(weapon(hero, foe1))) }
        code("ACTOR_NOT_PRESENT") { validate(c, prop(weapon("t:far", hero))) }
        code("TARGET_NOT_PRESENT") { validate(c, prop(weapon(hero, "t:far"))) }
        code("SAVE_NOT_ALLOWED") { validate(c, prop(D20Action("save", foe1, attribute = "vigor", dcTier = "easy"))) }
    }

    @Test fun proposalShapeAndCostValidation() {
        val c = fresh()
        code("D20_ACTIONS_REQUIRED") { validate(c, prop()) }
        code("D20_ACTIONS_REQUIRED") { validate(c, prop(*Array(7) { checkAction(hero, skill = "athletics") })) }
        code("UNKNOWN_D20_KIND") { validate(c, prop(D20Action("dance", hero))) }
        code("UNKNOWN_DC_TIER") { validate(c, prop(checkAction(hero, skill = "athletics", tier = "nope"))) }
        code("ADVANTAGE_REASON_REQUIRED") { validate(c, prop(D20Action("check", hero, skill = "athletics", dcTier = "easy", advantage = 1))) }
        code("DUPLICATE_TARGET") { validate(c, prop(cast(hero, "tangle", foe1, foe1))) }
        code("TARGET_COUNT") { validate(c, prop(cast(hero, "tangle", foe1, foe2, hero))) }
        code("CARD_NOT_OWNED") { validate(c.edit(hero) { it.copy(cards = it.cards - "bolt") }, prop(cast(hero, "bolt", foe1))) }
        code("SLOT_LEVEL_INVALID") { validate(c, prop(cast(hero, "bolt", foe1, level = 3))) }
        code("SLOT_LEVEL_INVALID") { validate(c, prop(cast(hero, "charm", foe1, level = 1))) }
        val oneSlot = c.edit(hero) { it.copy(slots = mapOf(1 to PoolValue(1, 2), 2 to PoolValue(0, 1))) }
        validate(oneSlot, prop(cast(hero, "bolt", foe1)))
        code("SLOT_EMPTY") { validate(oneSlot, prop(cast(hero, "bolt", foe1), cast(hero, "bolt", foe2))) }
        code("COUNTER_EMPTY") { validate(c.edit(hero) { it.copy(counters = it.counters + ("breath" to PoolValue(0, 1))) }, prop(cast(hero, "breather", hero))) }
        code("TARGET_CONDITION_REQUIRED") { validate(c, prop(cast(hero, "ambush", foe1))) }
        validate(c.edit(foe1) { it.copy(conditions = listOf(cond("frail"))) }, prop(cast(hero, "ambush", foe1)))
        code("REST_NOT_ALLOWED") { validate(c, prop(D20Action("rest", foe1, restType = "short"))) }
        code("REST_NOT_ALLOWED") { validate(c, prop(D20Action("rest", hero, restType = "short"), checkAction(hero, skill = "athletics"))) }
        code("INVALID_REST_TYPE") { validate(c, prop(D20Action("rest", hero, restType = "nap"))) }
    }

    @Test fun narrativeEffectsApplyAfterAllActionsAndOnlyAllowedKinds() {
        val time = Effect("time", "world", amount = 5, source = src)
        val done = settle(fresh(), prop(checkAction(hero, skill = "athletics", tier = "hard"), effects = listOf(time)), Seq(1))
        assertFalse(done.result.success)
        assertEquals(5L, done.campaign.world.gameTime)
        code("FAILURE_EFFECTS_UNUSED") { validate(fresh(), prop(checkAction(hero, skill = "athletics"), failure = listOf(time))) }
        code("UNKNOWN_EFFECT") { validate(fresh(), prop(checkAction(hero, skill = "athletics"), effects = listOf(Effect("resource", "player", key = "x", amount = 1, source = src)))) }
        code("D20_NOT_ALLOWED") { validate(fresh(), prop(checkAction(hero, skill = "athletics")).copy(actionType = "wait")) }
        code("SKILL_NOT_SUPPORTED") { validate(fresh(), prop(checkAction(hero, skill = "athletics")).copy(skill = "athletics")) }
        val wait = ActionProposal("wait", emptyList(), null, "", listOf("t:rule"), emptyList(), listOf(time), emptyList(), publicSummary = "s")
        val waited = settle(fresh(), wait, Seq())
        assertEquals(5L, waited.campaign.world.gameTime)
        assertEquals(0L, requireNotNull(waited.campaign.world.d20).round)
    }

    // ------------------------------------------------------------ contest, auto fail

    @Test fun contestTieKeepsStatusQuoUnlessCardSaysOtherwise() {
        val tie = settle(fresh(), prop(cast(hero, "grip", foe1)), Seq(10, 10))
        assertTrue(tie.campaign.cb(foe1).conditions.isEmpty())
        assertEquals(listOf(14, 14), tie.event.rolls.map { it.total })
        val win = settle(fresh(), prop(cast(hero, "grip", foe1)), Seq(11, 10))
        assertEquals(listOf(ActiveCondition("bound", hero, "grip", null, null)), win.campaign.cb(foe1).conditions)
        val standalone = settle(fresh(), prop(D20Action("contest", hero, listOf(foe1), skill = "athletics")), Seq(12, 9))
        assertTrue(standalone.result.success)
    }

    @Test fun autoFailSaveRecordsWithoutDice() {
        val bound = fresh().edit(hero) { it.copy(conditions = listOf(cond("bound"))) }
        val s = settle(bound, prop(D20Action("save", hero, attribute = "agility", dcTier = "easy")), Seq())
        assertFalse(s.result.success)
        assertEquals(RollRecord(hero, "save:agility", "auto_fail", emptyList(), 0, 0, 10, false), s.event.rolls.single())
    }

    // ------------------------------------------------------------ concentration

    @Test fun newConcentrationEndsTheOldOneAndItsConditions() {
        val first = settle(fresh(), prop(cast(hero, "snare", foe1)), Seq())
        assertEquals(listOf(ActiveCondition("frail", hero, "snare", null, null)), first.campaign.cb(foe1).conditions)
        assertEquals(ActiveConcentration("snare", listOf(foe1), null), first.campaign.cb(hero).concentration)
        val second = settle(first.campaign, prop(cast(hero, "hex", foe2)), Seq())
        assertEquals(listOf("condition_remove", "concentration_clear", "condition_add", "concentration_set", "round_advance"), second.event.d20Changes.map { it.type })
        assertTrue(second.campaign.cb(foe1).conditions.isEmpty())
        assertEquals(listOf(ActiveCondition("shaken", hero, "hex", null, null)), second.campaign.cb(foe2).conditions)
        assertEquals("hex", second.campaign.cb(hero).concentration?.cardId)
    }

    @Test fun damageForcesConcentrationSave() {
        val held = settle(fresh(), prop(cast(hero, "snare", foe1)), Seq()).campaign
        val broken = settle(held, prop(weapon(foe1, hero)), Seq(15, 6, 9))
        assertEquals(12, broken.campaign.hp(hero))
        assertNull(broken.campaign.cb(hero).concentration)
        assertTrue(broken.campaign.cb(foe1).conditions.isEmpty())
        assertEquals(RollRecord(hero, "concentration", "1d20", listOf(9), 0, 9, 10, false), broken.event.rolls.last())
        val kept = settle(held, prop(weapon(foe1, hero)), Seq(15, 6, 10))
        assertEquals("snare", kept.campaign.cb(hero).concentration?.cardId)
        assertEquals(1, kept.campaign.cb(foe1).conditions.size)
    }

    @Test fun conditionsMarkedEndsOnDamageAreRemovedByDamage() {
        val dazed = fresh().edit(foe1) { it.copy(conditions = listOf(cond("dazed"))) }
        val hit = settle(dazed, prop(weapon(hero, foe1)), Seq(15, 5))
        assertTrue(hit.campaign.cb(foe1).conditions.isEmpty())
        assertEquals(13, hit.campaign.hp(foe1))
    }

    // ------------------------------------------------------------ repeat saves, rests

    @Test fun repeatSaveRunsAtRoundEnd() {
        val cleared = settle(fresh(), prop(cast(hero, "sting", foe1)), Seq(5, 12))
        assertTrue(cleared.campaign.cb(foe1).conditions.isEmpty())
        assertEquals(RollRecord(foe1, "repeat_save:poisoned", "1d20", listOf(12), 0, 12, 12, true), cleared.event.rolls.last())
        val stuck = settle(fresh(), prop(cast(hero, "sting", foe1)), Seq(5, 11))
        assertEquals(listOf(ActiveCondition("poisoned", hero, "sting", 3L, null, RepeatSave("vigor", 12))), stuck.campaign.cb(foe1).conditions)
        val later = settle(stuck.campaign, prop(checkAction(hero, skill = "athletics")), Seq(10, 12))
        assertTrue(later.campaign.cb(foe1).conditions.isEmpty())
    }

    @Test fun shortAndLongRestRestoreAndAdvanceTime() {
        val spent = fresh().edit(hero) {
            it.copy(
                pools = mapOf("hp" to PoolValue(5, 20), "poise" to PoolValue(3, 10), "will" to PoolValue(10, 10)),
                counters = mapOf("breath" to PoolValue(0, 1), "focus_pt" to PoolValue(0, 1)),
                slots = mapOf(1 to PoolValue(0, 2), 2 to PoolValue(0, 1)),
                conditions = listOf(cond("poisoned", foe1, minute = 100L), cond("shaken", foe1)),
            )
        }
        val short = settle(spent, prop(D20Action("rest", hero, restType = "short")), Seq())
        val s = short.campaign.cb(hero)
        assertEquals(5, s.pools.getValue("hp").current)
        assertEquals(10, s.pools.getValue("poise").current)
        assertEquals(1, s.counters.getValue("breath").current)
        assertEquals(0, s.counters.getValue("focus_pt").current)
        assertEquals(0, s.slots.getValue(1).current)
        assertEquals(listOf(cond("shaken", foe1)), s.conditions)
        assertEquals(10L, short.campaign.world.gameTime)
        val long = settle(spent, prop(D20Action("rest", hero, restType = "long")), Seq())
        val l = long.campaign.cb(hero)
        assertEquals(20, l.pools.getValue("hp").current)
        assertEquals(1, l.counters.getValue("focus_pt").current)
        assertEquals(PoolValue(2, 2), l.slots.getValue(1))
        assertEquals(PoolValue(1, 1), l.slots.getValue(2))
        assertEquals(480L, long.campaign.world.gameTime)
    }

    // ------------------------------------------------------------ projection and planning

    @Test fun publicProjectionAndPlanningContext() {
        val c = fresh().edit(foe1) { it.copy(conditions = listOf(cond("frail"))) }
        val view = PublicProjection.campaign(c, module)
        assertEquals(hero, view.d20Player?.id)
        assertEquals(listOf(D20VisibleNpc("Foe One", listOf("Frail")), D20VisibleNpc("Foe Two", emptyList())), view.d20Visible)
        val packet = ContextAssembler(TestMemories()).planning(c, module, Turn("t", "c", 1, "m", "go", 0, TurnPhase.GATHERING))
        assertTrue(packet.system.contains("d20提议"))
        assertTrue(packet.system.startsWith(ContextAssembler.SYSTEM))
        assertTrue(packet.input.contains("\nD20:"))
        assertTrue(packet.input.contains("dcTiers"))
        assertTrue(packet.input.contains("\"bolt\""))
        assertFalse(packet.input.contains("armorClass"))
        assertFalse(packet.input.contains("t:far"))
    }

    // ------------------------------------------------------------ replay

    private fun randomProposal(rnd: Random): ActionProposal {
        val ids = listOf(hero, foe1, foe2)
        if (rnd.nextInt(20) == 0) return prop(D20Action("rest", hero, restType = if (rnd.nextBoolean()) "short" else "long"))
        val cardIds = cards.keys.filter { it != "misuse" }
        val actions = List(1 + rnd.nextInt(3)) {
            val actor = ids[rnd.nextInt(3)]
            val others = ids.filter { id -> id != actor }
            val adv = rnd.nextInt(3) - 1
            val reason = if (adv != 0) "reason" else null
            when (rnd.nextInt(5)) {
                0 -> D20Action("check", actor, skill = listOf("athletics", "stealth", "perception")[rnd.nextInt(3)], dcTier = listOf("easy", "medium", "hard")[rnd.nextInt(3)], advantage = adv, advantageReason = reason)
                1 -> D20Action("weapon", actor, listOf(others[rnd.nextInt(2)]), weaponId = "sword", advantage = adv, advantageReason = reason)
                2 -> D20Action("contest", actor, listOf(others[rnd.nextInt(2)]), skill = "athletics", advantage = adv, advantageReason = reason)
                else -> D20Action("card", actor, if (rnd.nextInt(4) == 0) ids.shuffled(rnd).take(2) else listOf(ids[rnd.nextInt(3)]), cardId = cardIds[rnd.nextInt(cardIds.size)], slotLevel = if (rnd.nextInt(5) == 0) 2 else null, advantage = adv, advantageReason = reason)
            }
        }
        return prop(*actions.toTypedArray())
    }

    @Test fun oneThousandRandomProposalsReplayToTheSameState() {
        val rnd = Random(20240607)
        val dice = DiceSource { sides -> rnd.nextInt(sides) + 1 }
        var c = fresh()
        var segmentStart = c.world
        val segment = mutableListOf<WorldEvent>()
        val seenTypes = mutableSetOf<String>()
        fun closeSegment() {
            val decoded = segment.map { StrictJson.json.decodeFromString<WorldEvent>(StrictJson.json.encodeToString(it)) }
            assertEquals(segment.toList(), decoded)
            val replayed = decoded.fold(segmentStart) { w, e -> adapter.applyRecordedEvent(w, e, module) }
            assertEquals(c.world, replayed)
            assertEquals(c.world, StrictJson.json.decodeFromString<WorldState>(StrictJson.json.encodeToString(c.world)))
            segment.clear()
        }
        var accepted = 0
        var invalidStreak = 0
        while (accepted < 1000) {
            val p = randomProposal(rnd)
            val valid = try { validate(c, p); true } catch (e: IllegalArgumentException) { false }
            if (!valid) {
                invalidStreak += 1
                if (invalidStreak >= 40) { closeSegment(); c = fresh(); segmentStart = c.world; invalidStreak = 0 }
                continue
            }
            invalidStreak = 0
            val s = settle(c, p, dice)
            assertEquals(2, s.event.eventVersion)
            s.event.d20Changes.forEach { seenTypes += it.type }
            segment += s.event
            c = s.campaign
            accepted += 1
            if (accepted % 50 == 0) { closeSegment(); c = fresh(); segmentStart = c.world }
        }
        if (segment.isNotEmpty()) closeSegment()
        assertTrue("pool" in seenTypes && "round_advance" in seenTypes)
    }
}
