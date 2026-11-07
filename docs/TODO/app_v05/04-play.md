# 04 对话页（游玩界面）重做

## 界面（已与用户确认）

- 顶栏：返回、战役头像（32dp）、战役标题，下方一行小字为当前时辰与地点的可读名称（地点用名称，不显示 id）；右侧为玩家角色头像（32dp，`AvatarKind.CHARACTER`，种子为角色 id），点开人物卡抽屉。
- 消息流（新的在下）：
  - 主持叙述：阅读式正文，无气泡，`NarrationFontFamily`（衬线），正文字号 16sp、行距 1.75。
  - 玩家输入：靠右的小气泡（面色底、圆角 12dp），最大宽度 80%。
  - 掷骰：每条 `RollRecord` 一行小标签（细边框、骰子图标、12sp 次要文字），格式「{purpose} {total} 对 {target} · 成功/失败」，无 target 时只显示总值。点开就地展开一行明细：骰面、加值、表达式。
  - 本地桌面指令回执（`notices`）：居中的小号次要文字。
  - 进行中：叙述位置显示一行"主持中…"与取消按钮；可恢复/需确认的回合在该回合下方显示对应的小按钮（继续 / 确认 / 取消）。
  - 顶部可"加载更早"时，滚到顶自动加载。
- 底部输入栏：左侧 `+`，中间圆角输入框（占位"你要做什么"），右侧发送箭头。`+` 打开底部菜单：桌面指令（停、降档、升档，调用现有 table command 路径），以及"规则查询"入口（现有 searchRules）。
- 人物卡抽屉（ModalBottomSheet，可拉到全高）：头像与姓名；资源池（气血/自制/意志等，模组定义的名字，显示 当前/上限 与细进度条）；状态（名称 + 剩余时长）；法术位/次数类资源；物品；已知线索与目标（沿用现有 facts/objectives）。只读。
- 旧游玩界面里的模型绑定、笔记修订、文档挂载、备份导入导出等按钮移到顶栏右侧"⋯"菜单的二级页，不出现在主界面。
- 不放说明段落。

## 数据

- `AgentTurnUi` 增加 `rolls: List<RollUi>`，`RollUi(purpose, expression, faces, modifier, total, target, success)`，来自 `CommittedResult.rolls`（rpg-agent `contract/Models.kt` 第 50 行、`D20Models.kt` 的 `RollRecord`）。
- `AgentPlayUi.location` 改为地点的可读名称（从世界实体里取 name）；新增 `playerCharacterId`、`sheet: CharacterSheetUi?`。`CharacterSheetUi` 从玩家对应 combatant 的 d20 状态构造（读 rpg-agent 的 D20Models 中的状态类；名称与上限取模组规则定义）。
- 不修改 rpg-agent 引擎；只在 App 侧做投影。

## 代码组织

- 新建 `rpg/ui/play/`：`PlayScreen.kt`、`RollChip.kt`、`CharacterSheet.kt`、`PlayInputBar.kt`；继续使用现有 `AgentUiController`（`AgentViewModel` 实现），不另起 ViewModel。
- `AgentScreen.kt` 里旧的游玩界面删除，由 `PlayScreen` 取代；新建战役/模组库界面暂留在 `AgentScreen.kt`。

## 验收

- CI 绿。
- JVM 单元测试：`RollUi` 文本格式（有/无 target、成功/失败）；`CharacterSheetUi` 由一个构造的 d20 状态投影出正确的池名、当前值与上限。
