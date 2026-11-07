package com.ai.assistance.operit.rpg.export

import com.ai.assistance.operit.rpg.agent.contract.Audience
import com.ai.assistance.operit.rpg.agent.contract.Campaign
import com.ai.assistance.operit.rpg.agent.contract.MemoryItem
import com.ai.assistance.operit.rpg.agent.contract.RollRecord
import com.ai.assistance.operit.rpg.agent.contract.Turn
import com.ai.assistance.operit.rpg.agent.contract.TurnPhase
import com.ai.assistance.operit.rpg.agent.contract.ValidatedModule
import com.ai.assistance.operit.rpg.agent.contract.WorldEvent
import com.ai.assistance.operit.rpg.agent.rules.Chapters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Player-facing Markdown report. Only PUBLIC material is read here: turn input, narration, public
 * rolls, recorded chapter marks and PUBLIC chapter summaries. Hidden facts never enter the report.
 */
object CampaignReport {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun markdown(
        campaign: Campaign,
        module: ValidatedModule,
        characterName: String,
        turns: List<Turn>,
        events: List<WorldEvent>,
        memories: List<MemoryItem>,
        exportedAt: LocalDateTime,
    ): String {
        val chapters = module.rules.chapters
        val summaries = memories.filter {
            it.kind == Chapters.SUMMARY_KIND && it.status == "ACTIVE" && it.visibility.audience == Audience.PUBLIC
        }
        fun summaryFor(chapterId: String): String? =
            summaries.lastOrNull { it.entityIds.contains(chapterId) }?.text

        val orderedEvents = events.sortedBy { it.seq }
        val out = StringBuilder()
        out.append("# ").append(campaign.title).append('\n')
        out.append("模组：").append(module.manifest.title).append(' ').append(module.manifest.version)
            .append("　角色：").append(characterName)
            .append("　导出时间：").append(exportedAt.format(TIME)).append('\n')

        if (chapters.isNotEmpty()) {
            val first = chapters.first()
            chapter(out, 1, first.title, summaryFor(first.id))
        }
        turns.filter { it.phase == TurnPhase.COMPLETE }.sortedBy { it.ordinal }.forEach { turn ->
            out.append('\n')
            out.append("**你**：").append(turn.playerText).append('\n')
            turn.narration?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
            turn.result?.rolls.orEmpty().forEach { roll ->
                out.append('`').append(rollSummary(roll)).append('`').append('\n')
            }
            val eventSeq = turn.result?.eventSeq
            if (eventSeq != null) {
                orderedEvents.filter { it.seq == eventSeq }.flatMap { it.chapters }.forEach { mark ->
                    chapter(out, mark.ordinal, mark.title, summaryFor(mark.chapterId))
                }
            }
        }
        return out.toString()
    }

    /** Same label as the conversation roll chip: 「{purpose} {total} 对 {target} · 成功/失败」. */
    private fun rollSummary(roll: RollRecord): String = buildString {
        append(roll.purpose); append(' '); append(roll.total)
        if (roll.target != null) {
            append(" 对 "); append(roll.target)
            when (roll.success) {
                true -> append(" · 成功")
                false -> append(" · 失败")
                null -> Unit
            }
        }
    }

    private fun chapter(out: StringBuilder, ordinal: Int, title: String, summary: String?) {
        out.append('\n').append("## 第 ").append(ordinal).append(" 章 · ").append(title).append('\n')
        if (!summary.isNullOrBlank()) out.append("> ").append(summary).append('\n')
    }
}
