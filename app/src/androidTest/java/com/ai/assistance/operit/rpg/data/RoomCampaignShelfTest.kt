package com.ai.assistance.operit.rpg.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.assistance.operit.rpg.agent.data.AgentDatabase
import com.ai.assistance.operit.rpg.agent.data.CampaignMetaRow
import com.ai.assistance.operit.rpg.agent.data.CampaignRow
import com.ai.assistance.operit.rpg.agent.data.RoomCampaignShelf
import com.ai.assistance.operit.rpg.agent.data.TurnRow
import com.ai.assistance.operit.rpg.domain.CampaignShelf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomCampaignShelfTest {
    private lateinit var db: AgentDatabase
    private lateinit var shelf: CampaignShelf

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AgentDatabase::class.java).build()
        shelf = RoomCampaignShelf(db)
        db.dao().insertCampaign(campaign("a"))
        db.dao().insertCampaign(campaign("b"))
        db.dao().insertCampaignMeta(CampaignMetaRow("a", null, false, false, null, null, null, 500L, false))
        db.dao().insertCampaignMeta(CampaignMetaRow("b", null, false, false, null, null, null, 0L, false))
    }

    @After fun cleanup() {
        db.close()
    }

    @Test fun groupsAndCampaignFieldsRoundTrip() {
        val first = shelf.createGroup("第一卷")
        val second = shelf.createGroup("第二卷")
        assertNotEquals(first, second)
        shelf.renameGroup(first, "序章")
        shelf.setGroupCollapsed(first, true)

        val groups = shelf.load().groups
        assertEquals(listOf("序章", "第二卷"), groups.map { it.name })
        assertTrue(groups.first { it.id == first }.collapsed)
        assertFalse(groups.first { it.id == second }.collapsed)

        shelf.moveToGroup("a", first)
        shelf.setPinned("a", true)
        shelf.setHidden("b", true)
        shelf.setFinished("a", true)
        shelf.rename("a", "新标题")
        shelf.setAvatar("a", "sword", "ink")
        shelf.setAvatar("b", "lantern", null)

        val loaded = shelf.load().campaigns.associateBy { it.campaignId }
        val a = loaded.getValue("a")
        assertEquals("新标题", a.title)
        assertEquals("test:room", a.subtitle)
        assertEquals(500L, a.updatedAt)
        assertEquals(first, a.groupId)
        assertTrue(a.pinned)
        assertFalse(a.hidden)
        assertTrue(a.finished)
        assertFalse(b.finished)
        assertEquals("sword", a.avatarGlyph)
        assertEquals("ink", a.avatarPair)
        val b = loaded.getValue("b")
        assertTrue(b.hidden)
        assertEquals("lantern", b.avatarGlyph)
        assertNull(b.avatarPair)

        shelf.setPinned("a", false)
        shelf.setHidden("b", false)
        shelf.setFinished("a", false)
        shelf.setAvatar("a", null, null)
        shelf.moveToGroup("a", null)
        val reset = shelf.load().campaigns.associateBy { it.campaignId }
        assertFalse(reset.getValue("a").pinned)
        assertFalse(reset.getValue("b").hidden)
        assertFalse(reset.getValue("a").finished)
        assertNull(reset.getValue("a").avatarGlyph)
        assertNull(reset.getValue("a").avatarPair)
        assertNull(reset.getValue("a").groupId)
    }

    @Test fun deleteGroupReturnsCampaignsToUngrouped() {
        val group = shelf.createGroup("临时")
        shelf.moveToGroup("a", group)
        shelf.moveToGroup("b", group)
        assertEquals(group, shelf.load().campaigns.first { it.campaignId == "a" }.groupId)

        shelf.deleteGroup(group)

        assertTrue(shelf.load().groups.isEmpty())
        assertTrue(shelf.load().campaigns.all { it.groupId == null })
    }

    @Test fun deleteCampaignRemovesItsRows() {
        val group = shelf.createGroup("分组")
        shelf.moveToGroup("a", group)
        shelf.setPinned("a", true)
        db.dao().insertTurn(TurnRow("t1", "a", "client-1", 1L, "COMPLETE", "{}"))

        shelf.delete("a")

        assertEquals(listOf("b"), shelf.load().campaigns.map { it.campaignId })
        assertNull(db.dao().campaignMeta("a"))
        assertNull(db.dao().turn("a", "t1"))
    }

    private fun campaign(id: String) = CampaignRow(
        id, "战役$id", "test-module", "1.0.0", "hash", "{}", 0L, 0L, "test:room",
        "{}", "{}", 0L, false, "", "", 100L, null, 1L, null, 0, false, null, null
    )
}
