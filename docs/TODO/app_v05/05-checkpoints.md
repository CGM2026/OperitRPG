# 05 章节存档

## 目的

每回合的上下文本来就是"当前状态 + 少量记忆"，不是整段聊天记录，所以不会无限膨胀；但叙述缺乏前情连续性，玩家也没有可回退的节点。章节机制解决两件事：

1. 模组声明章节，引擎在条件满足时推进章节，并在事件流里留下章节标记——这就是存档点。
2. 进入新章节时生成上一章的公开摘要，作为之后规划与叙述的"前情提要"。

## 引擎（rpg-agent，A 部分）

1. `contract/Models.kt`：
   - 新增 `@Serializable data class ChapterDefinition(val id: String, val title: String, val enterWhen: List<Condition> = emptyList())`。
   - `ModuleRules` 末尾新增 `val chapters: List<ChapterDefinition> = emptyList()`。
   - `Campaign` 末尾新增 `val chapterId: String? = null`。
   - 新增 `@Serializable data class ChapterMark(val chapterId: String, val title: String, val ordinal: Int)`（ordinal 从 1 开始，等于该章在 chapters 中的下标 + 1）。
   - `WorldEvent` 末尾新增 `val chapters: List<ChapterMark> = emptyList()`；`CommittedResult` 末尾新增 `val chapters: List<ChapterMark> = emptyList()`。
2. 模组校验（放在通用模组校验处，所有规则适配器都生效）：chapters 非空时——id 唯一且非空、title 非空、第一章 `enterWhen` 必须为空、其余各章 `enterWhen` 非空、每个条件通过现有条件校验。chapters 为空时不启用章节（`Campaign.chapterId` 保持 null）。
3. 新建战役：chapters 非空时 `chapterId = chapters[0].id`，并且首个事件（或创建时写入的初始事件，以现有结构为准）带上第一章的 `ChapterMark`；若初始化流程不产生事件，则第一章不写标记，只设 chapterId。
4. 推进：在每次提交世界事件（玩家回合提交，以及同一事务内的计划事件触发之后）之后，以提交后的 WorldState 依次检查当前章之后的下一章：其 `enterWhen` 全部成立（用 GenericD100 的 `condition` 求值，与 Scheduler 的 `holds` 同一函数）就推进一章，可以连续推进多章；只前进不后退。推进出的 `ChapterMark` 写入本次 `WorldEvent.chapters` 与 `CommittedResult.chapters`，并更新 `Campaign.chapterId`。重放事件时以事件里的标记为准恢复 chapterId，不重新求值。
5. 上下文：规划与叙述的 required 部分在开头加一行 `Chapter:<title>`（有章节时）。另外，记忆里 kind 为 `CHAPTER_SUMMARY` 的条目在 optional 记忆中排在最前（最近一章的在前），其余规则不变。
6. 测试（rpg-agent 单元测试）：校验规则各一条；条件满足时推进且可连推两章；不满足不推进；重放后 chapterId 一致；规划/叙述上下文包含 `Chapter:` 行与摘要优先。

## App（B 部分，在 04 完成后做）

- `AgentTurnUi` 增加 `chapters: List<ChapterMark>`；`PlayScreen` 在该回合叙述之前显示分隔线「第 N 章 · 标题 · 已存档」。顶栏副标题前加当前章节名。
- 章节推进后，App 用叙述模型生成上一章摘要：输入为上一章所有回合的公开叙述（按顺序拼接，超长时只取每回合前 200 字），系统提示要求 300 字以内、只写已发生的公开事实、不加推测；结果作为 `MemoryItem(kind = "CHAPTER_SUMMARY", visibility = PUBLIC)` 保存，来源区间为该章事件序号范围。生成失败时在回合下方显示可重试的小提示，不自动重试。
- "⋯" 菜单增加「章节」页：列出已到达的章节与摘要（只读）。从存档点另开一局放在 06 做。

## 验收

CI 绿；上述单元测试通过。
