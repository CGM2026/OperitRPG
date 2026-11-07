package com.ai.assistance.operit.rpg.ui.home

import com.ai.assistance.operit.rpg.domain.CampaignShelf
import com.ai.assistance.operit.rpg.domain.Shelf
import com.ai.assistance.operit.rpg.domain.ShelfCampaign
import com.ai.assistance.operit.rpg.domain.ShelfGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {
    private fun campaign(
        id: String,
        title: String,
        updatedAt: Long,
        groupId: String? = null,
        pinned: Boolean = false,
        hidden: Boolean = false,
    ) = ShelfCampaign(
        campaignId = id,
        title = title,
        subtitle = "地点",
        updatedAt = updatedAt,
        groupId = groupId,
        pinned = pinned,
        hidden = hidden,
        avatarGlyph = null,
        avatarPair = null,
    )

    @Test
    fun pinnedSectionComesFirstGroupsFollowSortKeyAndUngroupedIsLast() {
        val shelf = Shelf(
            groups = listOf(
                ShelfGroup("late", "后组", sortKey = 20, collapsed = true),
                ShelfGroup("early", "前组", sortKey = 10, collapsed = false),
            ),
            campaigns = listOf(
                campaign("u", "未分组战役", 5),
                campaign("g-late", "后组战役", 3, groupId = "late"),
                campaign("g-early", "前组战役", 4, groupId = "early"),
                campaign("pin", "置顶战役", 1, groupId = "late", pinned = true),
                campaign("hidden", "隐藏战役", 9, groupId = "early", hidden = true),
            ),
        )

        val sections = buildSections(shelf, "")

        assertEquals(listOf("pinned", "group:early", "group:late", "ungrouped"), sections.map { it.key })
        assertEquals(listOf("pin"), sections[0].campaigns.map { it.campaignId })
        assertEquals(listOf("g-early"), sections[1].campaigns.map { it.campaignId })
        assertEquals(listOf("g-late"), sections[2].campaigns.map { it.campaignId })
        assertEquals(listOf("u"), sections[3].campaigns.map { it.campaignId })
        assertTrue(sections[2].collapsed)
        assertTrue(sections.none { section -> section.campaigns.any { it.hidden } })
    }

    @Test
    fun campaignsInsideASectionAreNewestFirst() {
        val shelf = Shelf(
            groups = emptyList(),
            campaigns = listOf(
                campaign("old", "旧", 1),
                campaign("new", "新", 3),
                campaign("mid", "中", 2),
            ),
        )

        val sections = buildSections(shelf, "")

        assertEquals(listOf("new", "mid", "old"), sections.single().campaigns.map { it.campaignId })
    }

    @Test
    fun searchFiltersTitlesCaseInsensitivelyAndNeverRevealsHidden() {
        val shelf = Shelf(
            groups = listOf(ShelfGroup("g", "组", 1, false)),
            campaigns = listOf(
                campaign("a", "Sword of the North", 3, groupId = "g"),
                campaign("b", "剑影", 2),
                campaign("c", "SWORD hidden", 1, groupId = "g", hidden = true),
            ),
        )

        val sections = buildSections(shelf, " sword ")

        assertEquals(listOf("a"), sections[0].campaigns.map { it.campaignId })
        assertTrue(sections.none { section -> section.campaigns.any { it.campaignId == "c" } })

        val chinese = buildSections(shelf, "剑")
        assertEquals(listOf("剑影"), chinese.flatMap { it.campaigns }.map { it.title })
    }

    @Test
    fun emptySegmentsAreOmittedButGroupsStayVisible() {
        val shelf = Shelf(
            groups = listOf(ShelfGroup("empty", "空组", 1, false)),
            campaigns = listOf(campaign("only", "唯一的战役", 1)),
        )

        val sections = buildSections(FakeShelf(shelf).load(), "")

        assertEquals(listOf("group:empty", "ungrouped"), sections.map { it.key })
        assertTrue(sections[0].campaigns.isEmpty())
        assertEquals(listOf("only"), sections[1].campaigns.map { it.campaignId })
    }
}

private class FakeShelf(private val current: Shelf) : CampaignShelf {
    override fun load(): Shelf = current

    override fun createGroup(name: String): String = "created"

    override fun renameGroup(groupId: String, name: String) = Unit

    override fun deleteGroup(groupId: String) = Unit

    override fun setGroupCollapsed(groupId: String, collapsed: Boolean) = Unit

    override fun moveToGroup(campaignId: String, groupId: String?) = Unit

    override fun setPinned(campaignId: String, pinned: Boolean) = Unit

    override fun setHidden(campaignId: String, hidden: Boolean) = Unit

    override fun rename(campaignId: String, title: String) = Unit

    override fun setAvatar(campaignId: String, glyphId: String?, pairId: String?) = Unit

    override fun delete(campaignId: String) = Unit
}
