package com.ai.assistance.operit.rpg.agent.rules

import com.ai.assistance.operit.rpg.agent.TestCatalog
import com.ai.assistance.operit.rpg.agent.TestMemories
import com.ai.assistance.operit.rpg.agent.TestRules
import com.ai.assistance.operit.rpg.agent.TestStore
import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.engine.AgentCoordinator
import com.ai.assistance.operit.rpg.agent.engine.DemoTextModelPort
import com.ai.assistance.operit.rpg.agent.memory.ContextAssembler
import kotlin.test.*

class ChapterTest {
    private val src = SourceRef("module/sources/rules.md", "hash", "rules", "module_rule")
    private val evidence = listOf(RuleEvidence("t:rule", src, "quote", Visibility(Audience.PUBLIC)))

    private fun flag(key: String, value: String) = Condition("flag_eq", key = key, value = value)

    private fun module(vararg chapters: ChapterDefinition) = ValidatedModule(
        ModuleManifest(1, "t", "1", "T", "a", "d", 1, "generic-d100-v1", "1", emptyList()), "hash",
        ModuleWorld(
            "t:hall", listOf(CharacterTemplate("t:hero", "Hero", emptyMap(), emptyMap())),
            listOf(Location("t:hall", "Hall", "d", emptyList())),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            GenerationPolicy(emptySet(), 0, emptySet()), initialFlags = mapOf("stage" to "0"),
        ),
        ModuleRules("generic-d100-v1", "1", emptySet(), emptyMap(), mapOf("normal" to 0), GenericD100.effects, evidence, "min", chapters = chapters.toList()),
        mapOf("module/sources/rules.md" to "rules"),
    )

    private fun campaign(m: ValidatedModule, chapterId: String?) = Campaign(
        "c", "chapters", VersionRef("t", "1", m.contentHash), VersionRef(m.rules.adapterId, m.rules.adapterVersion, m.contentHash),
        0, 0, initialWorld(m, "t:hero"), false, "m", "m", 0, chapterId = chapterId,
    )

    private fun code(expected: String, block: () -> Unit) = assertEquals(expected, assertFailsWith<IllegalArgumentException> { block() }.message)

    @Test fun chapterValidationRejectsBadDefinitions() {
        val adapter = GenericD100()
        adapter.validateModule(module(ChapterDefinition("t:c1", "One"), ChapterDefinition("t:c2", "Two", listOf(flag("stage", "1")))))
        code("INVALID_CHAPTER") { adapter.validateModule(module(ChapterDefinition("t:c1", "One"), ChapterDefinition("t:c1", "Two", listOf(flag("stage", "1"))))) }
        code("INVALID_CHAPTER") { adapter.validateModule(module(ChapterDefinition("", "One"))) }
        code("INVALID_CHAPTER") { adapter.validateModule(module(ChapterDefinition("t:c1", ""))) }
        code("INVALID_CHAPTER") { adapter.validateModule(module(ChapterDefinition("t:c1", "One", listOf(flag("stage", "1"))))) }
        code("INVALID_CHAPTER") { adapter.validateModule(module(ChapterDefinition("t:c1", "One"), ChapterDefinition("t:c2", "Two"))) }
        code("UNKNOWN_CONDITION") { adapter.validateModule(module(ChapterDefinition("t:c1", "One"), ChapterDefinition("t:c2", "Two", listOf(Condition("bogus"))))) }
    }

    @Test fun newCampaignStartsInFirstChapter() {
        val m = module(ChapterDefinition("t:c1", "One"), ChapterDefinition("t:c2", "Two", listOf(flag("stage", "1"))))
        val store = TestStore()
        val coordinator = AgentCoordinator(store, TestCatalog(m), TestRules(m), TestMemories(), DemoTextModelPort(m), DemoTextModelPort(m), DiceSource { 1 }, Clock { 0 })
        coordinator.create("c", "chapters", VersionRef("t", "1", m.contentHash), "t:hero", false, "m", "m")
        assertEquals("t:c1", store.campaigns.getValue("c").chapterId)
    }

    @Test fun satisfiedConditionsAdvanceConsecutivelyAndUnsatisfiedStayPut() {
        val m = module(
            ChapterDefinition("t:c1", "One"),
            ChapterDefinition("t:c2", "Two", listOf(flag("stage", "1"))),
            ChapterDefinition("t:c3", "Three", listOf(flag("night", "1"))),
        )
        val c = campaign(m, "t:c1")
        // Both later chapters' conditions hold at once, so one settlement enters c2 and then c3.
        val both = Chapters.advance(c, c.world.copy(flags = mapOf("stage" to "1", "night" to "1")), m.rules)
        assertEquals("t:c3", both.chapterId)
        assertEquals(listOf(ChapterMark("t:c2", "Two", 2), ChapterMark("t:c3", "Three", 3)), both.marks)
        val none = Chapters.advance(c, c.world, m.rules)
        assertEquals("t:c1", none.chapterId)
        assertTrue(none.marks.isEmpty())
        val one = Chapters.advance(c, c.world.copy(flags = mapOf("stage" to "1")), m.rules)
        assertEquals("t:c2", one.chapterId)
        assertEquals(listOf(ChapterMark("t:c2", "Two", 2)), one.marks)
        val last = Chapters.advance(campaign(m, "t:c3"), c.world, m.rules)
        assertEquals("t:c3", last.chapterId)
        assertTrue(last.marks.isEmpty())
    }

    @Test fun replayRestoresChapterIdFromRecordedMarks() {
        val m = module(
            ChapterDefinition("t:c1", "One"),
            ChapterDefinition("t:c2", "Two", listOf(flag("stage", "1"))),
            ChapterDefinition("t:c3", "Three", listOf(flag("stage", "2"))),
        )
        val advanced = Chapters.advance(campaign(m, "t:c1"), campaign(m, "t:c1").world.copy(flags = mapOf("stage" to "2")), m.rules)
        val event = WorldEvent(
            1, "c", 1, "t1", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList(),
            chapters = advanced.marks,
        )
        assertEquals(advanced.chapterId, Chapters.replayId(listOf(event), m.rules))
        assertEquals("t:c1", Chapters.replayId(emptyList(), m.rules))
    }

    @Test fun planningAndNarrationLeadWithChapterAndLatestSummary() {
        val m = module(
            ChapterDefinition("t:c1", "One"),
            ChapterDefinition("t:c2", "Two", listOf(flag("stage", "1"))),
        )
        val c = campaign(m, "t:c2")
        val repo = TestMemories()
        repo.save(MemoryItem("event", "c", "EVENT_TEXT", emptyList(), 0, 0, Visibility(Audience.PUBLIC), "ACTIVE"))
        repo.save(MemoryItem("old", "c", "SUMMARY_OLD", emptyList(), 1, 2, Visibility(Audience.PUBLIC), "ACTIVE", kind = Chapters.SUMMARY_KIND))
        repo.save(MemoryItem("new", "c", "SUMMARY_NEW", emptyList(), 3, 4, Visibility(Audience.PUBLIC), "ACTIVE", kind = Chapters.SUMMARY_KIND))
        val assembler = ContextAssembler(repo)
        val turn = Turn("t", "c", 1, "m", "调查", 0, TurnPhase.GATHERING)
        val planner = assembler.planning(c, m, turn)
        assertTrue(planner.input.startsWith("Chapter:Two\n"))
        assertTrue(planner.input.indexOf("SUMMARY_NEW") < planner.input.indexOf("SUMMARY_OLD"))
        assertTrue(planner.input.indexOf("SUMMARY_OLD") < planner.input.indexOf("EVENT_TEXT"))
        val narrator = assembler.narration(c, m, turn.copy(phase = TurnPhase.COMMITTED, result = CommittedResult("t", 1, true, null, "公开结果", emptyList())))
        assertTrue(narrator.input.startsWith("Chapter:Two\n"))
        assertTrue(narrator.input.indexOf("SUMMARY_NEW") < narrator.input.indexOf("SUMMARY_OLD"))
        assertTrue(narrator.input.indexOf("SUMMARY_OLD") < narrator.input.indexOf("EVENT_TEXT"))
    }
}
