package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.TestMemories
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.memory.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.*

/** Minimal modules built in this file; every id, label and flag below is test data only. */
class SchedulerTest {
    private val src = SourceRef("module/sources/rules.md", "hash", "rules", "module_rule")
    private val evidence = listOf(RuleEvidence("t:rule", src, "quote", Visibility(Audience.PUBLIC)))
    private val hero = "t:hero"

    private fun fx(type: String, target: String, key: String? = null, value: String? = null) = Effect(type, target, key = key, value = value, source = src)
    private fun flag(key: String, value: String) = fx("set_flag", "world", key, value)
    private fun eq(key: String, value: String) = Condition("flag_eq", key = key, value = value)
    private fun timed(id: String, at: Long, vararg effects: Effect, conditions: List<Condition> = emptyList(), cancelIf: List<Condition> = emptyList(), notice: String? = null) =
        ScheduledEvent(id, "label-$id", at, conditions, 0, cancelIf, effects.toList(), notice)
    private fun conditional(id: String, conditions: List<Condition>, delay: Int, vararg effects: Effect, notice: String? = null) =
        ScheduledEvent(id, "label-$id", null, conditions, delay, emptyList(), effects.toList(), notice)

    private fun genericModule(schedule: List<ScheduledEvent>, clock: ClockSpec? = null) = ValidatedModule(
        ModuleManifest(1, "t", "1", "T", "a", "d", 1, "generic-d100-v1", "1", emptyList()), "hash",
        ModuleWorld(
            "t:hall", listOf(CharacterTemplate(hero, "Hero", mapOf("s" to 50), emptyMap())),
            listOf(Location("t:hall", "Hall", "d", listOf("t:yard")), Location("t:yard", "Yard", "d", listOf("t:hall")), Location("t:far", "Far", "d", emptyList())),
            listOf(NpcState("t:foe", "Foe", "t:hall", "idle", "p", "g")),
            listOf(Item("t:coin", "Coin", "player", 1)),
            listOf(Fact("t:f1", "secret", Visibility(Audience.GM), false, src)),
            listOf(Objective("t:o1", "Goal", 1, "pending")),
            emptyList(), GenerationPolicy(emptySet(), 0, emptySet()),
            schedule = schedule, initialFlags = mapOf("bridge" to "open"), clock = clock,
        ),
        ModuleRules("generic-d100-v1", "1", setOf("s"), emptyMap(), mapOf("normal" to 0), GenericD100.effects, evidence, "min"),
        mapOf("module/sources/rules.md" to "rules"),
    )

    private fun wuxiaModule(schedule: List<ScheduledEvent>): ValidatedModule {
        val d20 = D20Rules(
            attributes = listOf("might"), skills = mapOf("athletics" to "might"), dcTiers = mapOf("easy" to 10),
            pools = mapOf("hp" to PoolRule("HP", restoreOn = "long")), counters = emptyMap(), conditions = emptyMap(), cards = emptyMap(),
            rest = RestRule(10, 480, "hp"), saveSuccessNegatesPoolLoss = true, defeatPoolIds = setOf("hp"), concentrationAttribute = "might",
        )
        val pc = D20Combatant(hero, "Hero", 1, mapOf("might" to 10), 2, emptySet(), mapOf("athletics" to 0), 10, mapOf("hp" to PoolValue(10, 10)), emptyMap(), "long", emptyMap(), emptySet(), emptyMap())
        return ValidatedModule(
            ModuleManifest(1, "t", "1", "T", "a", "d", 1, "wuxia-d20-v1", "1", emptyList()), "hash",
            ModuleWorld(
                "t:hall", listOf(CharacterTemplate(hero, "Hero", emptyMap(), emptyMap())),
                listOf(Location("t:hall", "Hall", "d", emptyList())),
                listOf(NpcState("t:foe", "Foe", "t:hall", "idle", "p", "g")), emptyList(), emptyList(), emptyList(), emptyList(),
                GenerationPolicy(emptySet(), 0, emptySet()), listOf(pc), schedule,
            ),
            ModuleRules("wuxia-d20-v1", "1", emptySet(), emptyMap(), emptyMap(), setOf("time"), evidence, "min", d20),
            mapOf("module/sources/rules.md" to "rules"),
        )
    }

    /** Online settlement loop: resolve, apply the recorded event, keep every event for replay. */
    private class Rig(val module: ValidatedModule) {
        val adapter = RuleAdapters.forModule(module)
        val initial: WorldState = initialWorld(module, "t:hero")
        var campaign = Campaign("c", "t", VersionRef("t", "1", module.contentHash), VersionRef(module.rules.adapterId, "1", module.contentHash), 0, 0, initial, false, "m", "m", 0)
        val events = mutableListOf<WorldEvent>()
        val world: WorldState get() = campaign.world
        fun settle(p: ActionProposal, dice: DiceSource = DiceSource { 1 }): Pair<WorldEvent, CommittedResult> {
            val plan = freeze(campaign, p, module.rules.evidence)
            val (event, result) = adapter.resolve(campaign, plan, module, "turn-${campaign.revision}", dice)
            val next = adapter.applyRecordedEvent(campaign.world, event, module)
            campaign = campaign.copy(world = next, revision = campaign.revision + 1, lastEventSeq = event.seq)
            events += event
            return event to result
        }
        fun replayed(): WorldState = events.fold(initial) { w, e -> adapter.applyRecordedEvent(w, e, module) }
    }

    private fun waitFor(minutes: Int) = ActionProposal("wait", emptyList(), null, "normal", listOf("t:rule"), emptyList(), listOf(fx("time", "world").copy(amount = minutes)), emptyList(), publicSummary = "wait")
    private fun d20(vararg actions: D20Action) = ActionProposal("d20", emptyList(), null, "", listOf("t:rule"), emptyList(), emptyList(), emptyList(), publicSummary = "s", d20 = actions.toList())
    private fun code(expected: String, block: () -> Unit) = assertEquals(expected, assertFailsWith<IllegalArgumentException> { block() }.message)
    private fun WorldEvent.trail() = scheduled.map { it.scheduleId to it.state }

    private val chain = listOf(
        timed("t:d", 200, knowledge("t:foe", "t:f1"), flag("done", "1"), conditions = listOf(Condition("npc_at", targetId = "t:foe", value = "t:far"), Condition("item_owner", targetId = "t:coin", value = "t:far"))),
        conditional("t:c", listOf(eq("bridge", "closed"), Condition("schedule_state", targetId = "t:a", value = "fired")), 15, fx("npc_move", "t:foe", value = "t:far"), fx("transfer_item", "t:coin", value = "t:far"), notice = "脚步声"),
        timed("t:b", 30, fx("npc_move", "t:foe", value = "t:far"), conditions = listOf(eq("bridge", "open"))),
        timed("t:e", 100, flag("late", "yes"), cancelIf = listOf(eq("bridge", "closed"))),
        timed("t:a", 30, flag("bridge", "closed"), notice = "鼓声"),
    )
    private fun knowledge(npc: String, fact: String) = fx("knowledge", npc, value = fact)

    // ------------------------------------------------------------ firing, skipping, delay, cancel

    @Test fun timedConditionalDelayedAndCancelledEventsFireInStableOrder() {
        val module = genericModule(chain)
        GenericD100().validateModule(module)
        val rig = Rig(module)
        assertEquals(setOf("t:a", "t:b", "t:c", "t:d", "t:e"), rig.world.schedule.keys)
        assertTrue(rig.world.schedule.values.all { it == ScheduleStatus("pending") })
        val (e20, r20) = rig.settle(waitFor(20))
        assertTrue(e20.scheduled.isEmpty() && r20.notices.isEmpty())
        val (e30, r30) = rig.settle(waitFor(10))
        assertEquals(listOf("t:a" to "fired", "t:e" to "cancelled", "t:c" to "armed", "t:b" to "skipped"), e30.trail())
        assertEquals(listOf("鼓声"), r30.notices)
        assertEquals(ScheduleStatus("armed", 45), rig.world.schedule.getValue("t:c"))
        assertEquals("closed", rig.world.flags["bridge"])
        assertEquals("t:hall", rig.world.npcs.single().locationId)
        assertTrue(rig.settle(waitFor(14)).first.scheduled.isEmpty())
        val (e45, r45) = rig.settle(waitFor(1))
        assertEquals(listOf("t:c" to "fired"), e45.trail())
        assertEquals(listOf("脚步声"), r45.notices)
        assertEquals("t:far", rig.world.npcs.single().locationId)
        assertEquals("t:far", rig.world.items.single().ownerId)
        val (e200, _) = rig.settle(waitFor(155))
        assertEquals(listOf("t:d" to "fired"), e200.trail())
        assertTrue(rig.world.knowledge.any { it.knowerId == "t:foe" && it.factId == "t:f1" })
        assertEquals(
            mapOf("t:a" to "fired", "t:b" to "skipped", "t:c" to "fired", "t:d" to "fired", "t:e" to "cancelled"),
            rig.world.schedule.mapValues { it.value.state },
        )
        assertNull(rig.world.flags["late"])
        assertEquals(rig.world, rig.replayed())
        rig.settle(waitFor(1000))
        assertEquals(rig.world, rig.replayed())
    }

    @Test fun armedEventIgnoresLaterConditionChange() {
        val module = genericModule(listOf(
            timed("t:a", 10, flag("bridge", "closed")),
            conditional("t:c", listOf(eq("bridge", "closed")), 20, flag("seen", "yes")),
            timed("t:r", 20, flag("bridge", "open")),
        ))
        val rig = Rig(module)
        rig.settle(waitFor(10))
        assertEquals("armed", rig.world.schedule.getValue("t:c").state)
        rig.settle(waitFor(10))
        assertEquals("open", rig.world.flags["bridge"])
        rig.settle(waitFor(10))
        assertEquals("fired", rig.world.schedule.getValue("t:c").state)
        assertEquals("yes", rig.world.flags["seen"])
    }

    @Test fun scheduleLoopGuard() {
        val module = genericModule((0..64).map { timed("t:e%02d".format(it), 0, flag("k", "v")) })
        GenericD100().validateModule(module)
        val rig = Rig(module)
        code("SCHEDULE_LOOP") { rig.settle(waitFor(1)) }
        val ok = Rig(genericModule((0 until 64).map { timed("t:e%02d".format(it), 0, flag("k", "v")) }))
        assertEquals(64, ok.settle(waitFor(1)).first.scheduled.size)
    }

    @Test fun restCrossingSeveralEntriesFiresEachInTimeOrder() {
        val module = wuxiaModule(listOf(
            timed("t:s3", 300, flag("k3", "v"), notice = "third"),
            timed("t:s1", 100, flag("k1", "v"), notice = "first"),
            timed("t:s9", 600, flag("k9", "v")),
            timed("t:s2", 200, flag("k2", "v"), notice = "second"),
        ))
        WuxiaD20().validateModule(module)
        val rig = Rig(module)
        val (event, result) = rig.settle(d20(D20Action("rest", hero, restType = "long")))
        assertEquals(480L, rig.world.gameTime)
        assertEquals(listOf("t:s1", "t:s2", "t:s3"), event.scheduled.map { it.scheduleId })
        assertEquals(listOf("first", "second", "third"), result.notices)
        assertEquals("pending", rig.world.schedule.getValue("t:s9").state)
        assertEquals(rig.world, rig.replayed())
    }

    // ------------------------------------------------------------ authority

    @Test fun playerProposalsCannotUseScheduleEffects() {
        val module = genericModule(emptyList())
        val rig = Rig(module)
        fun proposal(e: Effect) = ActionProposal("wait", emptyList(), null, "normal", listOf("t:rule"), emptyList(), listOf(e), emptyList(), publicSummary = "s")
        val adapter = GenericD100()
        code("UNKNOWN_EFFECT") { adapter.validateProposal(rig.campaign, module, proposal(fx("npc_move", "t:foe", value = "t:yard")), evidence) }
        code("UNKNOWN_EFFECT") { adapter.validateProposal(rig.campaign, module, proposal(flag("bridge", "closed")), evidence) }
        val far = module.copy(world = module.world.copy(npcs = module.world.npcs + NpcState("t:away", "Away", "t:far", "idle", "p", "g")))
        val farRig = Rig(far)
        assertFailsWith<IllegalArgumentException> { adapter.validateProposal(farRig.campaign, far, proposal(fx("npc_status", "t:away", value = "busy")), evidence) }
        val wuxia = wuxiaModule(emptyList())
        val wuxiaRig = Rig(wuxia)
        code("UNKNOWN_EFFECT") { WuxiaD20().validateProposal(wuxiaRig.campaign, wuxia, proposal(flag("bridge", "closed")).copy(actionType = "wait"), evidence) }
    }

    @Test fun moduleScheduleValidation() {
        fun invalid(expected: String, vararg events: ScheduledEvent) = code(expected) { GenericD100().validateModule(genericModule(events.toList())) }
        invalid("INVALID_SCHEDULE", conditional("t:x", emptyList(), 0, flag("k", "v")))
        invalid("INVALID_SCHEDULE", timed("t:x", 5, flag("k", "v")).copy(delayMinutes = 3))
        invalid("INVALID_SCHEDULE", ScheduledEvent("t:x", "l", 5, effects = emptyList()))
        invalid("INVALID_ENTITY_ID", timed("t:x", 5, flag("k", "v")), timed("t:x", 6, flag("k", "v")))
        invalid("INVALID_ENTITY_ID", timed("bad", 5, flag("k", "v")))
        invalid("INVALID_EFFECT_SOURCE", timed("t:x", 5, flag("k", "v").copy(source = src.copy(authority = "module_custom"))))
        invalid("UNKNOWN_EFFECT", timed("t:x", 5, fx("time", "world").copy(amount = 5)))
        invalid("INVALID_SCHEDULE_EFFECT", timed("t:x", 5, fx("npc_move", "t:foe", value = "t:nowhere")))
        invalid("INVALID_SCHEDULE_EFFECT", timed("t:x", 5, fx("set_flag", "world", key = "k")))
        invalid("UNKNOWN_CONDITION", timed("t:x", 5, flag("k", "v"), conditions = listOf(Condition("nope"))))
        invalid("INVALID_SCHEDULE", timed("t:x", -1, flag("k", "v")))
        code("INVALID_CLOCK") { GenericD100().validateModule(genericModule(emptyList(), ClockSpec("x", 60, listOf("a", "b")))) }
        code("INVALID_CLOCK") { GenericD100().validateModule(genericModule(emptyList(), ClockSpec("a", 0, listOf("a", "b")))) }
        GenericD100().validateModule(genericModule(emptyList(), ClockSpec("a", 60, listOf("a", "b"))))
    }

    @Test fun replayRejectsForgedTransitions() {
        val module = genericModule(listOf(timed("t:a", 10, flag("k", "v")), conditional("t:c", listOf(eq("bridge", "open")), 5, flag("k2", "v"))))
        val adapter = GenericD100()
        val world = initialWorld(module, hero)
        fun event(vararg f: ScheduledFiring) = WorldEvent(1, "c", 1, "t", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList(), scheduled = f.toList())
        code("INVALID_SCHEDULE_TRANSITION") { adapter.applyRecordedEvent(world, event(ScheduledFiring("t:c", "fired", listOf(flag("k2", "v")))), module) }
        code("INVALID_SCHEDULE_TRANSITION") { adapter.applyRecordedEvent(world, event(ScheduledFiring("t:a", "armed", emptyList(), 3)), module) }
        code("UNKNOWN_SCHEDULE") { adapter.applyRecordedEvent(world, event(ScheduledFiring("t:zz", "cancelled", emptyList())), module) }
        code("INVALID_SCHEDULE_EFFECT") { adapter.applyRecordedEvent(world, event(ScheduledFiring("t:a", "fired", listOf(fx("npc_move", "t:foe", value = "t:nowhere")))), module) }
    }

    // ------------------------------------------------------------ clock and contexts

    @Test fun clockFace() {
        val spec = ClockSpec("b", 60, listOf("a", "b", "c"))
        assertEquals("5 分钟", clockText(null, 5))
        assertEquals("第1日 b", clockText(spec, 0))
        assertEquals("第1日 c", clockText(spec, 60))
        assertEquals("第2日 a", clockText(spec, 179))
        assertEquals("第2日 b", clockText(spec, 180))
    }

    @Test fun planningSeesUpcomingAndFlagsNarrationSeesOnlyNotices() {
        val module = genericModule(chain, ClockSpec("a", 60, listOf("a", "b")))
        val rig = Rig(module)
        val (event, result) = rig.settle(waitFor(30))
        assertEquals(4, event.scheduled.size)
        val contexts = ContextAssembler(TestMemories())
        val turn = Turn("t", "c", 1, "m", "go", 0, TurnPhase.GATHERING)
        val planning = contexts.planning(rig.campaign, module, turn)
        assertTrue(planning.input.contains("label-t:c") && planning.input.contains("label-t:d"))
        assertFalse(planning.input.contains("label-t:a"))
        assertTrue(planning.input.contains("\"bridge\":\"closed\""))
        val upcoming = Scheduler.upcoming(rig.world, module, 5)
        assertEquals(listOf("t:c", "t:d"), upcoming.map { it.id })
        assertEquals(listOf(45L, 200L), upcoming.map { it.minute })
        val narration = contexts.narration(rig.campaign, module, turn.copy(phase = TurnPhase.COMMITTED, result = result))
        assertTrue(narration.input.contains("鼓声") && narration.system.contains(ContextAssembler.NOTICE_GUIDE))
        assertTrue(narration.input.contains("第1日 a"))
        assertFalse(narration.input.contains("label-t") || narration.input.contains("closed"))
        val quiet = contexts.narration(rig.campaign, module, turn.copy(phase = TurnPhase.COMMITTED, result = result.copy(notices = emptyList())))
        assertFalse(quiet.system.contains(ContextAssembler.NOTICE_GUIDE))
    }

    // ------------------------------------------------------------ replay

    @Test fun thousandRandomGenericProposalsReplayIdentically() {
        val r = Random(42)
        val places = listOf("t:hall", "t:yard", "t:far")
        val events = (0 until 120).map { i ->
            val id = "t:r%03d".format(i)
            when (i % 3) {
                0 -> timed(id, r.nextLong(0, 40000), flag("k${i % 7}", "v$i"), notice = if (i % 2 == 0) "n$i" else null)
                1 -> timed(id, r.nextLong(0, 40000), fx("npc_status", "t:foe", value = if (i % 2 == 0) "busy" else "idle"), conditions = listOf(Condition("npc_at", targetId = "t:foe", value = places[i % 3])))
                else -> conditional(id, listOf(eq("k${(i - 2) % 7}", "v${i - 2}")), r.nextInt(0, 100), fx("npc_move", "t:foe", value = places[r.nextInt(3)]))
            }.let { if (i % 5 == 0) it.copy(cancelIf = listOf(Condition("schedule_state", targetId = "t:r%03d".format((i + 3) % 120), value = "fired"))) else it }
        }
        val module = genericModule(events)
        GenericD100().validateModule(module)
        val rig = Rig(module)
        repeat(1000) {
            val here = rig.world.locationId
            val proposal = if (r.nextInt(10) == 0) {
                ActionProposal("move", emptyList(), null, "normal", listOf("t:rule"), emptyList(), listOf(fx("move", if (here == "t:hall") "t:yard" else "t:hall"), fx("time", "world").copy(amount = 10)), emptyList(), publicSummary = "m")
            } else waitFor(r.nextInt(1, 120))
            rig.settle(proposal)
        }
        assertEquals(rig.world, rig.replayed())
        assertTrue(rig.world.schedule.values.any { it.state == "fired" })
        assertTrue(rig.world.schedule.values.any { it.state == "skipped" || it.state == "cancelled" })
        assertTrue(rig.events.any { it.scheduled.isNotEmpty() })
        assertEquals(rig.world, Json.decodeFromString<WorldState>(Json.encodeToString(rig.world)))
    }

    @Test fun randomWuxiaRestsAndChecksReplayIdentically() {
        val r = Random(7)
        val events = (0 until 60).map { i -> timed("t:w%02d".format(i), r.nextLong(0, 30000), flag("k${i % 5}", "v$i"), notice = "n$i") }
        val module = wuxiaModule(events)
        WuxiaD20().validateModule(module)
        val rig = Rig(module)
        val dice = DiceSource { sides -> r.nextInt(1, sides + 1) }
        repeat(300) {
            val action = if (r.nextBoolean()) D20Action("rest", hero, restType = if (r.nextBoolean()) "short" else "long")
            else D20Action("check", hero, skill = "athletics", dcTier = "easy")
            rig.settle(d20(action), dice)
        }
        assertEquals(rig.world, rig.replayed())
        assertTrue(rig.world.schedule.values.any { it.state == "fired" })
    }
}
