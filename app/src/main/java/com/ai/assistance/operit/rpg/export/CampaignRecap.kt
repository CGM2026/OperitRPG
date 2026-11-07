package com.ai.assistance.operit.rpg.export

import com.ai.assistance.operit.rpg.agent.contract.Audience
import com.ai.assistance.operit.rpg.agent.contract.Campaign
import com.ai.assistance.operit.rpg.agent.contract.ValidatedModule
import com.ai.assistance.operit.rpg.agent.contract.Visibility
import com.ai.assistance.operit.rpg.agent.contract.WorldEvent
import com.ai.assistance.operit.rpg.agent.contract.WorldState
import com.ai.assistance.operit.rpg.agent.rules.clockText
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * GM recap. Unlike [CampaignReport] this reads every fact, including hidden ones, records whether the
 * player knows each fact, and reconstructs event game times from the recorded time effects.
 */
object CampaignRecap {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun markdown(
        campaign: Campaign,
        module: ValidatedModule,
        initial: WorldState,
        events: List<WorldEvent>,
        exportedAt: LocalDateTime,
    ): String {
        val orderedEvents = events.sortedBy { it.seq }
        val gameTimes = eventGameTimes(initial, orderedEvents)
        val out = StringBuilder()
        out.append("# ").append(campaign.title).append(" · 主持复盘").append('\n')
        out.append("模组：").append(module.manifest.title).append(' ').append(module.manifest.version)
            .append("　导出时间：").append(exportedAt.format(TIME)).append('\n')

        out.append('\n').append("## 事实").append('\n')
        if (campaign.world.facts.isEmpty()) out.append("（无）").append('\n')
        campaign.world.facts.forEach { fact ->
            val known = campaign.world.knowledge.any { it.knowerId == "player" && it.factId == fact.id }
            out.append("- ").append(fact.text).append("（").append(visibility(fact.visibility))
                .append("；玩家").append(if (known) "已知" else "未知").append("）").append('\n')
        }

        out.append('\n').append("## 计划事件").append('\n')
        val transitions = orderedEvents.flatMap { event ->
            event.scheduled.map { firing -> event.seq to firing }
        }.filter { (_, firing) -> firing.state in setOf("fired", "cancelled") }
        if (transitions.isEmpty()) out.append("（无）").append('\n')
        transitions.forEach { (seq, firing) ->
            val definition = module.world.schedule.firstOrNull { it.id == firing.scheduleId }
                ?: error("UNKNOWN_SCHEDULE:${firing.scheduleId}")
            val state = if (firing.state == "fired") "已触发" else "已取消"
            out.append("- ").append(definition.label).append(" · ").append(state).append(" · ")
                .append(clockText(module.world.clock, gameTimes.getValue(seq))).append('\n')
        }

        out.append('\n').append("## NPC 最终状态").append('\n')
        if (campaign.world.npcs.isEmpty()) out.append("（无）").append('\n')
        campaign.world.npcs.forEach { npc ->
            out.append("- ").append(npc.name).append("（").append(npc.id).append("）· ")
                .append("位置：").append(npc.locationId).append(" · 状态：").append(npc.status)
                .append(" · 目标：").append(npc.goal).append('\n')
        }

        out.append('\n').append("## 章节推进").append('\n')
        val chapters = module.rules.chapters
        if (chapters.isEmpty()) out.append("（无）").append('\n')
        chapters.firstOrNull()?.let { first ->
            out.append("- 第 1 章 · ").append(first.title).append(" · 第 0 号事件 · ")
                .append(clockText(module.world.clock, initial.gameTime)).append('\n')
        }
        orderedEvents.forEach { event ->
            event.chapters.forEach { mark ->
                out.append("- 第 ").append(mark.ordinal).append(" 章 · ").append(mark.title)
                    .append(" · 第 ").append(event.seq).append(" 号事件 · ")
                    .append(clockText(module.world.clock, gameTimes.getValue(event.seq))).append('\n')
            }
        }
        return out.toString()
    }

    private fun eventGameTimes(initial: WorldState, events: List<WorldEvent>): Map<Long, Long> {
        var time = initial.gameTime
        return events.associate { event ->
            time += event.effects.filter { it.type == "time" }.sumOf { it.amount?.toLong() ?: 0L }
            event.seq to time
        }
    }

    private fun visibility(value: Visibility): String = when (value.audience) {
        Audience.PUBLIC -> "PUBLIC"
        Audience.GM -> "GM"
        Audience.NPC -> "NPC${value.subjectId?.let { ":$it" }.orEmpty()}"
        Audience.FACTION -> "FACTION${value.subjectId?.let { ":$it" }.orEmpty()}"
    }
}
