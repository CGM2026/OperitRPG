package com.ai.assistance.operit.rpg.export

import com.ai.assistance.operit.rpg.agent.contract.*
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignRecapTest {
    @Test
    fun recapIncludesHiddenFactsScheduleStatesNpcsAndChapterTimes() {
        val module = exportModule(
            chapters = listOf(ChapterDefinition("ch1", "初入江湖"), ChapterDefinition("ch2", "夜探庄")),
            schedule = listOf(
                ScheduledEvent("bell", "钟声", atMinute = 0, effects = listOf(Effect("set_flag", "world", key = "bell", value = "rung", source = exportSource))),
                ScheduledEvent("ambush", "伏兵", atMinute = 120, effects = listOf(Effect("set_flag", "world", key = "ambush", value = "none", source = exportSource))),
            ),
            clock = ClockSpec("子时", 60, listOf("子时", "丑时")),
        )
        val publicFact = Fact("public", "掌柜姓周", Visibility(Audience.PUBLIC), false, exportSource)
        val gmFact = Fact("secret", "紫晶暗号藏在王座下", Visibility(Audience.GM), true, exportSource)
        val initial = exportWorld()
        val campaign = exportCampaign(
            "剑影",
            exportWorld(
                facts = listOf(publicFact, gmFact),
                knowledge = listOf(KnowledgeEdge("player", "public", 1, "seen", "believed")),
                npcs = listOf(NpcState("npc", "掌柜", "room", "helpful", "谨慎", "守店")),
                gameTime = 90,
            ),
            chapterId = "ch2",
        )
        val events = listOf(
            WorldEvent(
                1, "campaign", 1, "t1",
                effects = listOf(Effect("time", "world", amount = 90, source = exportSource)),
                creations = emptyList(), dice = null, visibility = Visibility(Audience.PUBLIC), sources = emptyList(),
                scheduled = listOf(ScheduledFiring("bell", "fired", emptyList())),
            ),
            WorldEvent(
                1, "campaign", 2, "t2", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList(),
                scheduled = listOf(ScheduledFiring("ambush", "cancelled", emptyList())),
                chapters = listOf(ChapterMark("ch2", "夜探庄", 2)),
            ),
        )

        val recap = CampaignRecap.markdown(campaign, module, initial, events, exportTime)

        assertTrue(recap.contains("# 剑影 · 主持复盘"))
        assertTrue(recap.contains("紫晶暗号藏在王座下"))
        assertTrue(recap.contains("（GM；玩家未知）"))
        assertTrue(recap.contains("掌柜姓周"))
        assertTrue(recap.contains("（PUBLIC；玩家已知）"))
        assertTrue(recap.contains("钟声 · 已触发"))
        assertTrue(recap.contains("伏兵 · 已取消"))
        assertTrue(recap.contains("第1日 丑时"))
        assertTrue(recap.contains("掌柜（npc）"))
        assertTrue(recap.contains("状态：helpful"))
        assertTrue(recap.contains("第 2 章 · 夜探庄"))
        assertTrue(recap.contains("第 2 号事件"))
    }
}
