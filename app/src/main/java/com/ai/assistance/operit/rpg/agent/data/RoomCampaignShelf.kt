package com.ai.assistance.operit.rpg.agent.data

import com.ai.assistance.operit.rpg.agent.contract.ValidatedModule
import com.ai.assistance.operit.rpg.agent.contract.VersionRef
import com.ai.assistance.operit.rpg.domain.CampaignShelf
import com.ai.assistance.operit.rpg.domain.Shelf
import com.ai.assistance.operit.rpg.domain.ShelfCampaign
import com.ai.assistance.operit.rpg.domain.ShelfGroup
import java.util.UUID

class RoomCampaignShelf(private val db: AgentDatabase) : CampaignShelf {
    private val dao get() = db.dao()

    override fun load(): Shelf = db.atomic {
        val metas = dao.campaignMetas().associateBy { it.campaignId }
        val groups = dao.campaignGroups().map { ShelfGroup(it.id, it.name, it.sortKey, it.collapsed) }
        // Location ids are module-scoped identifiers; the shelf shows the module's own place name instead.
        val catalog = RoomModuleCatalog(db)
        val modules = HashMap<VersionRef, ValidatedModule>()
        val campaigns = dao.campaignRows().map { campaign ->
            val meta = metas[campaign.id] ?: error("CAMPAIGN_META_MISSING:${campaign.id}")
            ShelfCampaign(
                campaignId = campaign.id,
                title = meta.displayTitle ?: campaign.title,
                subtitle = modules.getOrPut(VersionRef(campaign.moduleId, campaign.moduleVersion, campaign.moduleHash)) {
                    catalog.get(VersionRef(campaign.moduleId, campaign.moduleVersion, campaign.moduleHash))
                }.world.locations.single { it.id == campaign.locationId }.title,
                updatedAt = maxOf(campaign.updatedAt, meta.lastPlayedAt),
                groupId = meta.groupId,
                pinned = meta.pinned,
                hidden = meta.hidden,
                finished = meta.finished,
                avatarGlyph = meta.avatarGlyph,
                avatarPair = meta.avatarPair
            )
        }.sortedWith(compareByDescending<ShelfCampaign> { it.updatedAt }.thenBy { it.campaignId })
        Shelf(groups, campaigns)
    }

    override fun createGroup(name: String): String = db.atomic {
        val id = UUID.randomUUID().toString()
        dao.insertCampaignGroup(CampaignGroupRow(id, name, dao.maxGroupSortKey() + 1, false))
        id
    }

    override fun renameGroup(groupId: String, name: String) = db.atomic {
        dao.renameCampaignGroup(groupId, name)
    }

    override fun deleteGroup(groupId: String) = db.atomic {
        dao.clearCampaignGroup(groupId)
        dao.deleteCampaignGroup(groupId)
    }

    override fun setGroupCollapsed(groupId: String, collapsed: Boolean) = db.atomic {
        dao.setCampaignGroupCollapsed(groupId, collapsed)
    }

    override fun moveToGroup(campaignId: String, groupId: String?) = db.atomic {
        dao.moveCampaignToGroup(campaignId, groupId)
    }

    override fun setPinned(campaignId: String, pinned: Boolean) = db.atomic {
        dao.setCampaignPinned(campaignId, pinned)
    }

    override fun setHidden(campaignId: String, hidden: Boolean) = db.atomic {
        dao.setCampaignHidden(campaignId, hidden)
    }

    override fun setFinished(campaignId: String, finished: Boolean) = db.atomic {
        dao.setCampaignFinished(campaignId, finished)
    }

    override fun rename(campaignId: String, title: String) = db.atomic {
        dao.setCampaignDisplayTitle(campaignId, title)
    }

    override fun setAvatar(campaignId: String, glyphId: String?, pairId: String?) = db.atomic {
        dao.setCampaignAvatar(campaignId, glyphId, pairId)
    }

    override fun delete(campaignId: String) = db.atomic {
        dao.deleteCampaign(campaignId)
    }
}
