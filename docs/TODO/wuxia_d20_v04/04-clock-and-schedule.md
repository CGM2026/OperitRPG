# 04 时钟、定时事件与 NPC 日程

依赖 [03](03-model-split-and-table-commands.md)。对两种适配器都生效。

## 原实现

`WorldState.gameTime` 只随 `time` 效果前进，`NpcState.nextActionAt` 无人使用。玩家不行动时世界不变，NPC 不会离场行动，消息不会延迟传递。

## 新实现

### 模组格式

```kotlin
@Serializable data class ScheduledEvent(
    val id: String,
    val label: String,                         // 仅主持可见
    val atMinute: Long? = null,                // 定时：gameTime 达到即检查
    val conditions: List<Condition> = emptyList(), // 触发时须全部成立
    val delayMinutes: Int = 0,                 // 条件型：条件首次成立后再过多少分钟触发
    val cancelIf: List<Condition> = emptyList(),   // 任一成立即永久取消
    val effects: List<Effect>,                 // 见下方「日程效果」
    val notice: String? = null,                // 触发时给玩家与旁白的公开征兆文字
)
```

- `ModuleWorld.schedule: List<ScheduledEvent> = emptyList()`。
- `atMinute` 非空时为定时事件：时间到达时若 `conditions` 成立则触发，否则记为 `skipped`。`atMinute` 为空时为条件事件：`conditions` 首次成立的时刻记为武装，过 `delayMinutes` 后触发；武装后条件变化不影响触发。条件事件的 `conditions` 不能为空。
- `WorldState.flags: Map<String, String> = emptyMap()`，模组用来记录桥是否封闭之类的自定义状态。`ModuleWorld.initialFlags` 提供初值。
- `WorldState.schedule: Map<String, ScheduleStatus>`，`ScheduleStatus(state: String, fireAt: Long?)`，state 为 `pending`、`armed`、`fired`、`skipped`、`cancelled`。

### 新条件

在现有条件类型之外增加：

- `flag_eq`：`key` 为旗标名，`value` 为期望值
- `npc_at`：`targetId` 为 NPC，`value` 为地点
- `item_owner`：`targetId` 为物品，`value` 为持有者
- `schedule_state`：`targetId` 为日程 ID，`value` 为状态

### 日程效果

日程以主持权限执行，可以改动不在场的人与物。允许的效果类型：

- `npc_move`：`targetId` 为 NPC，`value` 为地点，地点无需与玩家相邻
- `npc_status`：同现有含义，但不要求 NPC 在场
- `transfer_item`：`value` 可为任一 NPC 或地点 ID
- `knowledge`：NPC 得知任一事实，不要求事实已公开，用于消息传递
- `objective`、`set_flag`（`key`、`value`）
- `reveal_fact`：只在模组明确需要时使用

日程效果的 `source.authority` 必须为 `module_rule`，并能在规则证据中找到。玩家行动的提议不能使用 `npc_move`、`set_flag` 以及不在场的人与物，规则保持原样。

### 执行

新增 `rules/Scheduler.kt`，两种适配器在 `resolve` 中共用：

1. 先照常结算玩家行动，得到新的 `gameTime` 与世界。
2. 反复执行直到没有可处理的日程：
   - 处理 `cancelIf` 成立者，记为 `cancelled`
   - 武装条件首次成立的条件事件，`fireAt = 当前 gameTime + delayMinutes`
   - 在 `fireAt` 或 `atMinute` 不晚于当前时间的日程中，按时间、再按 ID 取最早一个，检查并触发或跳过
3. 每轮最多处理 64 个日程，超过即报 `SCHEDULE_LOOP`，这是模组数据错误。
4. 触发结果写入同一个 `WorldEvent` 的新字段 `scheduled: List<ScheduledFiring>`，`ScheduledFiring(scheduleId, state, effects)`。重放时按记录应用，不再重新求值。
5. 公开征兆收集到 `CommittedResult.notices: List<String>`，旁白上下文与界面显示。

### 等待与休整

- 新增行动类型 `wait`，取已有含义：只推进 `time`，用于玩家「等到某时」。
- 武侠适配器的 `rest` 推进时间后同样运行日程。

### 规划上下文

规划上下文加入「接下来 5 个未触发日程」的 `label` 与时间，以及当前旗标。旁白上下文只包含本回合的 `notices`。

### 界面

角色面板旁增加「时辰」行，显示 `gameTime` 换算的时刻。换算方式由 `ModuleWorld.clock: ClockSpec?` 给出：`ClockSpec(startLabel, minutesPerUnit, labels)`。为空时显示分钟数。

## 验收

- 定时事件按时触发与跳过、条件事件延迟触发、取消
- 同一时刻多个日程的顺序稳定
- `SCHEDULE_LOOP` 保护
- 1000 次随机提议重放一致
- 玩家提议不能使用日程专用效果
- 休整跨越多个日程时逐一触发
