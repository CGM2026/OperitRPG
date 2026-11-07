package com.ai.assistance.operit.rpg.export

import com.ai.assistance.operit.rpg.agent.contract.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignReportTest {
    private fun turn(id: String, ordinal: Long, eventSeq: Long, input: String, narration: String, rolls: List<RollRecord> = emptyList()) = Turn(
        id = id,
        campaignId = "campaign",
        ordinal = ordinal,
        clientMessageId = "message-$id",
        playerText = input,
        baseRevision = eventSeq - 1,
        phase = TurnPhase.COMPLETE,
        result = CommittedResult(id, eventSeq, true, null, "公开结果", emptyList(), rolls = rolls),
        narration = narration,
    )

    @Test
    fun reportContainsOnlyPublicContentInChapterOrder() {
        val module = exportModule(chapters = listOf(ChapterDefinition("ch1", "初入江湖"), ChapterDefinition("ch2", "夜探庄")))
        val gmFact = Fact("secret", "紫晶暗号藏在王座下", Visibility(Audience.GM), true, exportSource)
        val campaign = exportCampaign("剑影", exportWorld(facts = listOf(gmFact)), chapterId = "ch2")
        val turns = listOf(
            turn("t1", 1, 1, "我推门进店", "店里灯火昏黄。"),
            turn(
                "t2", 2, 2, "我留意四周", "更深处传来脚步声。",
                rolls = listOf(RollRecord("hero", "察觉", "1d20+2", listOf(15), 2, 17, 15, true)),
            ),
        )
        val events = listOf(
            WorldEvent(1, "campaign", 1, "t1", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList()),
            WorldEvent(1, "campaign", 2, "t2", emptyList(), emptyList(), null, Visibility(Audience.PUBLIC), emptyList(), chapters = listOf(ChapterMark("ch2", "夜探庄", 2))),
        )
        val memories = listOf(
            MemoryItem("s1", "campaign", "你初入江湖。", listOf("ch1"), 1, 1, Visibility(Audience.PUBLIC), "ACTIVE", kind = "CHAPTER_SUMMARY"),
            MemoryItem("s2", "campaign", "隐藏摘要", listOf("ch1"), 1, 1, Visibility(Audience.GM), "ACTIVE", kind = "CHAPTER_SUMMARY"),
        )

        val report = CampaignReport.markdown(campaign, module, "少侠", turns, events, memories, exportTime)

        assertTrue(report.contains("# 剑影"))
        assertTrue(report.contains("模组：江湖测试 1.0.0　角色：少侠　导出时间：2026-11-08 09:30"))
        assertTrue(report.contains("## 第 1 章 · 初入江湖"))
        assertTrue(report.contains("> 你初入江湖。"))
        assertTrue(report.contains("**你**：我推门进店"))
        assertTrue(report.contains("店里灯火昏黄。"))
        assertTrue(report.contains("`察觉 17 对 15 · 成功`"))
        assertTrue(report.contains("## 第 2 章 · 夜探庄"))
        assertFalse(report.contains("紫晶暗号"))
        assertFalse(report.contains("隐藏摘要"))
    }

    @Test
    fun moduleWithoutChaptersHasNoChapterHeadings() {
        val module = exportModule()
        val campaign = exportCampaign("无章", exportWorld())
        val turns = listOf(turn("t1", 1, 1, "我坐下", "茶还温着。"))

        val report = CampaignReport.markdown(campaign, module, "少侠", turns, emptyList(), emptyList(), exportTime)

        assertFalse(report.contains("## 第"))
        assertTrue(report.contains("**你**：我坐下"))
        assertTrue(report.contains("茶还温着。"))
    }
}
