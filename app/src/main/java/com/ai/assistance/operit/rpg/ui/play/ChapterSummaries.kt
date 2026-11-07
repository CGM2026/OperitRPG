package com.ai.assistance.operit.rpg.ui.play

import com.ai.assistance.operit.rpg.agent.contract.Audience
import com.ai.assistance.operit.rpg.agent.contract.ContextPacket
import com.ai.assistance.operit.rpg.agent.contract.MemoryItem
import com.ai.assistance.operit.rpg.agent.contract.MemoryRepository
import com.ai.assistance.operit.rpg.agent.contract.TextModelPort
import com.ai.assistance.operit.rpg.agent.contract.Visibility
import com.ai.assistance.operit.rpg.agent.rules.Chapters

/**
 * Generates the recap of the chapter the campaign just left. The caller collects the public narration of every
 * turn in that chapter's event range so this file stays free of storage and screen concerns.
 */
object ChapterSummaries {
    const val PURPOSE = "chapter_summary"
    const val MAX_SUMMARY_CHARS = 300
    const val TURN_EXCERPT_CHARS = 200
    const val MAX_INPUT_CHARS = 6000

    data class Request(
        val campaignId: String,
        val chapterId: String,
        val fromSeq: Long,
        val toSeq: Long,
        val narrations: List<String>,
    )

    private const val SYSTEM = "你是跑团章节记录员。根据给出的上一章公开叙述，写一段 ${MAX_SUMMARY_CHARS} 字以内的章节摘要。" +
        "只写已经发生的公开事实，按时间顺序概括；不添加推测、心理活动、评价或未出现的人物、物品和结果。直接输出摘要正文。"

    /** Full narrations in order; only when the joined text is too long does each turn keep its first 200 characters. */
    fun buildInput(narrations: List<String>): String {
        val joined = narrations.joinToString("\n\n")
        if (joined.length <= MAX_INPUT_CHARS) return joined
        return narrations.joinToString("\n\n") { it.take(TURN_EXCERPT_CHARS) }
    }

    fun buildPacket(request: Request): ContextPacket = ContextPacket(
        purpose = PURPOSE,
        system = SYSTEM,
        input = buildInput(request.narrations),
        maxOutputTokens = 600,
    )

    /** Calls the narrator model, enforces the 300-character result and stores it as a public chapter summary. */
    suspend fun generate(
        request: Request,
        narrator: TextModelPort,
        memories: MemoryRepository,
    ): MemoryItem {
        require(request.chapterId.isNotBlank()) { "INVALID_CHAPTER" }
        require(request.fromSeq >= 1 && request.toSeq >= request.fromSeq) { "INVALID_CHAPTER_RANGE" }
        val id = "chapter-summary:${request.campaignId}:${request.chapterId}"
        val reply = narrator.generate(id, buildPacket(request))
        val text = clip(reply.text)
        require(text.isNotBlank()) { "EMPTY_CHAPTER_SUMMARY" }
        val item = MemoryItem(
            id = id,
            campaignId = request.campaignId,
            text = text,
            entityIds = listOf(request.chapterId),
            fromSeq = request.fromSeq,
            toSeq = request.toSeq,
            visibility = Visibility(Audience.PUBLIC),
            status = "ACTIVE",
            kind = Chapters.SUMMARY_KIND,
        )
        memories.save(item)
        return item
    }

    private fun clip(text: String): String = text.trim().take(MAX_SUMMARY_CHARS).trimEnd()
}
