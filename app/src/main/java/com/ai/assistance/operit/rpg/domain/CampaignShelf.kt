package com.ai.assistance.operit.rpg.domain

data class ShelfGroup(val id: String, val name: String, val sortKey: Long, val collapsed: Boolean)
data class ShelfCampaign(
    val campaignId: String, val title: String, val subtitle: String, val updatedAt: Long,
    val groupId: String?, val pinned: Boolean, val hidden: Boolean,
    val avatarGlyph: String?, val avatarPair: String?
)
data class Shelf(val groups: List<ShelfGroup>, val campaigns: List<ShelfCampaign>)

interface CampaignShelf {
    fun load(): Shelf                       // 全部分组与战役（含隐藏，由 UI 过滤）
    fun createGroup(name: String): String   // 返回新分组 id
    fun renameGroup(groupId: String, name: String)
    fun deleteGroup(groupId: String)        // 组内战役 groupId 置空
    fun setGroupCollapsed(groupId: String, collapsed: Boolean)
    fun moveToGroup(campaignId: String, groupId: String?)
    fun setPinned(campaignId: String, pinned: Boolean)
    fun setHidden(campaignId: String, hidden: Boolean)
    fun rename(campaignId: String, title: String)
    fun setAvatar(campaignId: String, glyphId: String?, pairId: String?)  // 两者都为 null 表示恢复默认
    fun delete(campaignId: String)          // 删除战役及其全部数据
}
