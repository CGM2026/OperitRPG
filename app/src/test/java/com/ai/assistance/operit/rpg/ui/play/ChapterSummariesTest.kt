package com.ai.assistance.operit.rpg.ui.play

import com.ai.assistance.operit.rpg.agent.contract.Audience
import com.ai.assistance.operit.rpg.agent.contract.ContextPacket
import com.ai.assistance.operit.rpg.agent.contract.MemoryItem
import com.ai.assistance.operit.rpg.agent.contract.MemoryRepository
import com.ai.assistance.operit.rpg.agent.contract.RoleContext
import com.ai.assistance.operit.rpg.agent.contract.TextModelPort
import com.ai.assistance.operit.rpg.agent.contract.TextModelReply
import com.ai.assistance.operit.rpg.agent.contract.Usage
import com.ai.assistance.operit.rpg.agent.rules.Chapters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSummariesTest {
    private class FakePort(private val reply: String) : TextModelPort {
        var packet: ContextPacket? = null
        override suspend fun generate(attemptId: String, packet: ContextPacket): TextModelReply {
            this.packet = packet
            return TextModelReply(reply, Usage(0, 0))
        }
        override fun cancel(attemptId: String) = Unit
    }

    private class FakeMemories : MemoryRepository {
        val saved = mutableListOf<MemoryItem>()
        override fun search(role: RoleContext, query: String, entityIds: List<String>, limit: Int) = emptyList<MemoryItem>()
        override fun recent(role: RoleContext, limit: Int) = saved.take(limit)
        override fun save(item: MemoryItem) { saved += item }
    }

    private fun request(narrations: List<String>) = ChapterSummaries.Request("c", "c:two", 4, 12, narrations)

    @Test
    fun inputJoinsNarrationsInOrder() = runTest {
        val port = FakePort("摘要")
        ChapterSummaries.generate(request(listOf("第一回合", "第二回合")), port, FakeMemories())

        assertEquals("第一回合\n\n第二回合", port.packet?.input)
        assertEquals(ChapterSummaries.PURPOSE, port.packet?.purpose)
        assertTrue(port.packet?.system.orEmpty().contains("${ChapterSummaries.MAX_SUMMARY_CHARS} 字"))
    }

    @Test
    fun inputKeepsEachTurnPrefixWhenTooLong() = runTest {
        val long = "甲".repeat(ChapterSummaries.MAX_INPUT_CHARS)
        val narrations = listOf(long, "乙".repeat(300))
        val port = FakePort("摘要")
        ChapterSummaries.generate(request(narrations), port, FakeMemories())

        val expected = narrations.joinToString("\n\n") { it.take(ChapterSummaries.TURN_EXCERPT_CHARS) }
        assertEquals(expected, port.packet?.input)
        assertEquals("甲".repeat(200) + "\n\n" + "乙".repeat(200), port.packet?.input)
    }

    @Test
    fun resultIsSavedAsPublicChapterSummaryWithinLimit() = runTest {
        val memories = FakeMemories()
        val answer = "丙".repeat(ChapterSummaries.MAX_SUMMARY_CHARS + 50)
        ChapterSummaries.generate(request(listOf("第一回合")), FakePort(answer), memories)

        val item = memories.saved.single()
        assertEquals(Chapters.SUMMARY_KIND, item.kind)
        assertEquals(Audience.PUBLIC, item.visibility.audience)
        assertEquals("c", item.campaignId)
        assertEquals(listOf("c:two"), item.entityIds)
        assertEquals(4L, item.fromSeq)
        assertEquals(12L, item.toSeq)
        assertEquals(ChapterSummaries.MAX_SUMMARY_CHARS, item.text.length)
        assertEquals("ACTIVE", item.status)
    }
}
