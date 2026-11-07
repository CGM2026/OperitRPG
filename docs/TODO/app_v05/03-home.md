# 03 首页：战役列表与分组

## 界面（已与用户确认）

- 只有一张战役列表，没有底部标签栏。顶栏：左侧标题"跑团"，右侧两个图标按钮：搜索、设置。
- 列表分段：置顶段（有置顶战役时显示，段名"置顶"）→ 用户分组（按 sortKey，段头可点开/收起，收起状态持久化）→ "未分组"段（有内容时显示）。隐藏的战役不出现在列表中，只在设置 → 已隐藏 里出现（本步只需在数据层支持，设置页入口在 05 之后做）。
- 每行：44dp 头像（`AvatarKind.CAMPAIGN`；有手动选择用手动的，否则 `pick(CAMPAIGN, campaignId)`）、标题、右侧相对时间（今天显示 HH:mm，昨天，本周显示周几，更早显示 M月d日）、第二行一行省略的最近进度文本（本步用战役当前地点或最近一条公开叙述的前 40 字，取已有数据中现成的一项）。
- 右下角圆形 `+` 按钮：沿用现有"导入模组 → 新建战役"流程入口（本步只把入口挪到这里，流程界面不重做）。
- 搜索：顶栏变为输入框，按标题过滤，含已隐藏的不显示。
- 长按战役行 → 底部弹出菜单：置顶/取消置顶、重命名、移动到分组（列出分组 + "新建分组" + "移出分组"）、换头像（打开头像选择）、隐藏、删除（二次确认，说明不可恢复）。导出入口在 06 步加。
- 长按分组段头 → 菜单：重命名、新建分组、删除分组（确认；组内战役回到未分组）。
- 头像选择（底部弹窗）：上半部分为 12 个意象网格，下半部分为 24 组配色网格（每格显示该配色下当前意象的小头像，下方配色名），顶部实时预览；"用默认"按钮清除手动选择。
- 文案短，不放说明段落。

## 视觉基础（新建 `rpg/ui/theme`）

- 浅色：页面底 `#F5F2EC`（纸色），卡片/面 `#FBF9F5`，主文字 `#2A2622`，次文字 `#6E665E`，分隔线 `#E4DED4`，强调 `#A0543A`（赭）。
- 深色（跟随系统）：页面底 `#1C1A18`，面 `#25221F`，主文字 `#EDE7DF`，次文字 `#A39A90`，分隔线 `#3A3531`，强调 `#D98A66`。
- 字体：界面用系统无衬线；叙述正文用系统衬线（`FontFamily.Serif`），供 04 步使用。
- 提供 `D20Theme { }`，MainActivity 用它包住全部内容，并删除 MainActivity 里写死的背景色与浅色状态栏设置（状态栏随主题明暗）。

## 数据（数据库按全新应用重建）

- `AgentDatabase` 版本重置为 1，数据库文件名改为 `d20-v05.db`，删除 `AGENT_MIGRATION_1_2` 及所有迁移代码；删除 `app/schemas/com.ai.assistance.operit.rpg.agent.data.AgentDatabase/` 下旧 schema 文件（新 schema 由 CI 产物提交，见 index）。
- 新表：
  - `campaign_group(id TEXT PK, name TEXT, sortKey INTEGER, collapsed INTEGER)`
  - `campaign_meta(campaignId TEXT PK → campaign.id ON DELETE CASCADE, groupId TEXT NULL → campaign_group.id ON DELETE SET NULL, pinned INTEGER, hidden INTEGER, displayTitle TEXT NULL, avatarGlyph TEXT NULL, avatarPair TEXT NULL, lastPlayedAt INTEGER)`
- 新建战役时同时写入 campaign_meta（默认未分组、未置顶、未隐藏、无手动头像）。

## 接口（两边严格按此签名实现/调用）

文件 `app/src/main/java/com/ai/assistance/operit/rpg/domain/CampaignShelf.kt`：

```kotlin
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
```

实现类 `RoomCampaignShelf(db: AgentDatabase)` 放在 `rpg/agent/data/`；所有方法同步执行，调用方负责放到 IO 线程。

## 首页代码

- `rpg/ui/home/HomeScreen.kt`（新界面）与 `HomeViewModel.kt`（只依赖 `CampaignShelf` 与现有新建战役入口）。
- 旧 `AgentScreen` 中的首页列表部分由新首页取代；进入战役后的对话界面暂时沿用旧 `AgentScreen` 的游玩部分（04 步重做）。
- 删除不再被引用的旧首页代码，不留两套首页。

## 验收

- CI 绿。
- JVM 单元测试：`HomeViewModel` 的分段规则（置顶段、分组顺序、未分组、隐藏不显示、搜索过滤）用一个假的 `CampaignShelf` 测试。
- androidTest：`RoomCampaignShelf` 的增删改与删除分组后战役回到未分组（CI 不跑 androidTest，写好即可）。
