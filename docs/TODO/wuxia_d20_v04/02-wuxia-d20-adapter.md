# 02 wuxia-d20-v1 数据模型与结算

依赖 [01](01-adapter-registry-and-dice.md)。

## 目标

一个数据驱动的 d20 适配器。程序负责一切加值、难度、掷骰、扣减、上限与状态；模型只能在模组已登记的内容里选择「谁、对谁、用什么、在什么难度档」。所有名称都来自模组数据，代码不认识任何具体资源池或卡牌名。

## 格式扩展

全部新增字段带默认值 `null` 或空集合，`generic-d100-v1` 模组无需改动。

### ModuleRules.d20: D20Rules?

```kotlin
@Serializable data class D20Rules(
    val attributes: List<String>,                 // 六维键，顺序即显示顺序
    val skills: Map<String, String>,              // 技能 -> 所依属性
    val dcTiers: Map<String, Int>,                // 难度档 -> 难度，例如 easy 10, medium 13, hard 15
    val pools: Map<String, PoolRule>,             // 命名资源池
    val counters: Map<String, CounterRule>,       // 计数型资源，如战骰、吐纳点、每休一次的能力
    val conditions: Map<String, ConditionRule>,   // 状态目录
    val cards: Map<String, CardRule>,             // 卡牌目录
    val rest: RestRule,
    val saveSuccessNegatesPoolLoss: Boolean,      // 为 true 时，抵御成功的一方不受任何池扣减，施术者的反噬除外
    val defeatPoolIds: Set<String>,               // 归零即战败的池
    val concentrationAttribute: String,           // 凝神抵御所用属性
)
@Serializable data class PoolRule(
    val label: String,
    val perRoundLossCap: Int? = null,             // 同一目标每轮来自所有来源的损失上限，null 为不限
    val capGroup: String? = null,                 // 同组池共享上限；null 时以池 ID 为组
    val damageSourceOnly: Set<String> = emptySet(), // 允许扣此池的 CardEffect.tag；空集合表示任何卡都可扣
    val atZeroCondition: String? = null,          // 归零时附加的状态 ID
    val atZeroDuration: DurationSpec? = null,
    val singleHitCannotEmptyFromFull: Boolean = false, // 单次效果不能把满池直接清空，至多留 1
    val restoreOn: String,                        // "short" | "long" | "none"
)
@Serializable data class CounterRule(val label: String, val restoreOn: String) // short|long|none
@Serializable data class ConditionRule(
    val label: String,
    val text: String,                             // 给模型与玩家看的规则说明
    val blocksActions: Boolean = false,           // 失去出手、余力、应变
    val speedZero: Boolean = false,
    val attackDisadvantage: Boolean = false,      // 自身攻击落下风
    val attackedAdvantage: Boolean = false,       // 被攻击占上风
    val autoFailSaves: Set<String> = emptySet(),  // 自动失败的抵御属性
    val endsOnDamage: Boolean = false,            // 受到气血伤害时解除，伤害池由 RestRule.hitPoolId 指定
)
@Serializable data class RestRule(val shortMinutes: Int, val longMinutes: Int, val hitPoolId: String)
@Serializable data class DurationSpec(val unit: String, val value: Int = 1)
// unit: "rounds" 计数若干轮；"minutes" 按 gameTime；"until_target_turn_start" 与 "until_actor_turn_end" 在简化战斗中均表示「下一轮开始时到期」；"until_removed"
```

### 卡牌

```kotlin
@Serializable data class CardRule(
    val label: String,
    val text: String,                             // 卡面全文，进入规则检索
    val timing: String,                           // "action" | "bonus" | "reaction" | "free"
    val isSpell: Boolean,                         // 是否算术法（可被破法，受余力施术限制）
    val slotLevel: Int? = null,                   // 耗内力位的最低重数；null 为不耗
    val counterCost: Map<String, Int> = emptyMap(),
    val concentration: Boolean = false,
    val maxTargets: Int = 1,
    val resolution: String,                       // "save" | "attack" | "contest" | "auto"
    val keyAttribute: String? = null,             // 施招关键属性，用于难度或命中
    val saveAttribute: String? = null,            // resolution=save 时目标抵御属性
    val saveAdvantageWhenHostile: Boolean = false,
    val contestSkill: String? = null,             // resolution=contest 时施招方技能
    val contestDefenseSkills: Set<String> = emptySet(), // 目标可选的对抗技能，程序取目标最优者
    val tieGoesToActor: Boolean = false,          // 对抗平局默认维持原状，即施招方失败
    val requiresTargetCondition: Set<String> = emptySet(), // 目标须处于其一
    val requiresActorNotCondition: Set<String> = emptySet(),
    val onSuccess: List<CardEffect> = emptyList(),// save 时指目标抵御失败；attack 时指命中；contest/auto 时指施招成功
    val onFailure: List<CardEffect> = emptyList(),
    val onTargetSaveSuccessToActor: List<CardEffect> = emptyList(), // 反噬
    val repeatSave: Boolean = false,              // 被附加的状态每轮结束时可再抵御一次
)
@Serializable data class CardEffect(
    val type: String,                             // "pool" | "condition_add" | "condition_remove" | "counter"
    val target: String,                           // "target" | "actor"
    val pool: String? = null,
    val counter: String? = null,
    val dice: String? = null,                     // DiceExpression，正数表示损失
    val amount: Int? = null,
    val condition: String? = null,
    val duration: DurationSpec? = null,
    val tag: String,                              // 用于 PoolRule.damageSourceOnly，例如 "weapon" "charm"
    val ifTargetHasCondition: String? = null,     // 仅在目标已处该状态时生效
)
```

### 角色与世界

```kotlin
@Serializable data class D20Combatant(
    val id: String, val name: String, val level: Int,
    val scores: Map<String, Int>,                 // 属性值 1..30，加成 = floor((v-10)/2)
    val proficiencyBonus: Int,
    val saveProficiencies: Set<String>,
    val skillRanks: Map<String, Int>,             // 0 不会，1 会，2 绝活
    val armorClass: Int,
    val pools: Map<String, PoolValue>,
    val slots: Map<Int, PoolValue>,               // 重数 -> 剩余/上限
    val slotRestoreOn: String,                    // short|long
    val counters: Map<String, PoolValue>,
    val cards: Set<String>,
    val weaponAttacks: Map<String, WeaponAttack>, // 普通兵刃攻击
    val conditions: List<ActiveCondition> = emptyList(),
    val concentration: ActiveConcentration? = null,
)
@Serializable data class PoolValue(val current: Int, val max: Int)
@Serializable data class WeaponAttack(val label: String, val attribute: String, val proficient: Boolean, val damage: String, val tag: String = "weapon")
@Serializable data class ActiveCondition(val id: String, val sourceId: String, val sourceCard: String?, val expiresRound: Long?, val expiresMinute: Long?, val repeatSave: RepeatSave? = null)
@Serializable data class RepeatSave(val attribute: String, val dc: Int)
@Serializable data class ActiveConcentration(val cardId: String, val targetIds: List<String>, val expiresMinute: Long?)
```

- `ModuleWorld.d20Combatants: List<D20Combatant> = emptyList()`。`characters` 中每个玩家角色 ID 必须对应一个同 ID 的 combatant；NPC combatant 的 ID 必须与 `npcs` 中某个 ID 相同。
- `WorldState.d20: D20State? = null`：

```kotlin
@Serializable data class D20State(
    val combatants: Map<String, D20Combatant>,
    val round: Long,                              // 全局交换计数，每次提交 d20 行动后 +1
    val roundLosses: Map<String, Int>,            // key = "$targetId|$capGroup"，仅记录当前 round
)
```

`initialWorld` 在 adapter 为 wuxia 时填充 `d20`。`GenericD100` 继续使用原逻辑。

## 行动提议

`ActionProposal.d20: List<D20Action> = emptyList()`，`actionType` 新增合法值 `"d20"`。当 `actionType=="d20"` 时 `d20` 非空且至多 6 项，`skill` 必须为 null，原 `successEffects`/`failureEffects` 只允许 `move`、`reveal_fact`、`knowledge`、`npc_status`、`npc_relation`、`objective`、`time` 这些叙事型效果，并在全部 d20 行动结算后统一应用成功分支，失败分支不使用。

```kotlin
@Serializable data class D20Action(
    val kind: String,          // "check" | "save" | "contest" | "weapon" | "card" | "rest"
    val actorId: String,
    val targetIds: List<String> = emptyList(),
    val skill: String? = null,         // check
    val attribute: String? = null,     // save 或无技能的属性检定
    val dcTier: String? = null,        // check/save
    val weaponId: String? = null,
    val cardId: String? = null,
    val slotLevel: Int? = null,        // 加重施展时指定，否则取卡的 slotLevel
    val advantage: Int = 0,            // -1 0 1，模型提出的情境上下风
    val advantageReason: String? = null,
    val restType: String? = null,      // short|long
)
```

列表顺序就是结算顺序。约定第一项为玩家行动，后续为 NPC 回应。

## 校验

在 `validateProposal` 中，全部失败都抛出带大写错误码的异常：

- actor 与 target 必须是 `d20.combatants` 中的 ID。NPC actor 与 target 必须与玩家同处当前地点。
- actor 不能处于任一 `blocksActions` 状态。
- 每个 actor 在一次提议中至多一个 `action`、一个 `bonus`、一个 `reaction` 时机。`weapon`、`check`、`contest` 计作 `action`；`save` 不占时机，只能作为模组动作或卡牌的一部分出现，不能由模型单独为 NPC 提出。
- `card`：actor 必须拥有该卡；内力位或计数足够；`slotLevel` 不低于卡的 `slotLevel`，也不超过 actor 拥有的最高重数；目标数不超过 `maxTargets`；`requiresTargetCondition`、`requiresActorNotCondition` 成立。
- 余力施术限制：同一 actor 若本提议中有 `timing=bonus` 且 `isSpell` 的卡，则其 `action` 时机的卡必须 `slotLevel==null && isSpell`，即小术，否则报 `BONUS_SPELL_RULE`。
- `rest` 只能由玩家 actor 提出，且该提议只能有这一项。
- `dcTier` 必须存在于 `dcTiers`。
- `advantage` 不为 0 时 `advantageReason` 必须非空。

## 结算

`resolve` 掷骰并生成**具体**效果，事件只记录结果，重放时不再掷骰。

- 属性加成 `floor((score-10)/2)`。技能加值 = 属性加成 + `proficiencyBonus * rank`。抵御加值 = 属性加成 + 擅长时加火候。
- 上下风：程序收集 actor 的状态、目标的 `attackedAdvantage`，再加上模型给出的 `advantage`。任一上风与任一下风同时存在即抵消。占上风或落下风时掷两颗 d20 取高或取低，两颗骰都记录。
- `check`：d20 + 技能加值，结果不低于难度即成功。
- `save`（由卡触发）：目标掷 d20 + 抵御加值，不低于施招方难度即成功。难度 = 8 + 火候 + 施招方关键属性加成。`autoFailSaves` 命中时直接失败，仍记录「自动失败」。
- `contest`：双方各掷，比较总值；平局视 `tieGoesToActor`。
- `weapon` 与 `resolution=attack` 的卡：d20 + 属性加成 + 熟练时加火候，对目标守御。自然 20 为重击，伤害骰数量翻倍，加值不翻倍；自然 1 必失。
- 池扣减：
  1. 若 `saveSuccessNegatesPoolLoss` 且该效果的承受方刚抵御成功，扣减为 0。
  2. 校验 `damageSourceOnly`，不符则报 `POOL_SOURCE_FORBIDDEN`，这是模组数据错误。
  3. 按 `capGroup` 读取 `roundLosses`，截断到剩余上限。
  4. `singleHitCannotEmptyFromFull` 时，若扣减前为满值，至多扣到 1。
  5. 不低于 0。
  6. 归零时附加 `atZeroCondition`。若池属于 `defeatPoolIds`，记录 `D20Change(type="defeated")`；对 NPC 同时把 `NpcState.status` 设为 `"defeated"`。
- 状态附加：同 ID 同来源的状态刷新到期时间，不叠加。`repeatSave` 记录抵御属性与难度。
- 凝神：施展须凝神的卡时，若 actor 已在凝神，先结束旧的，并移除旧卡附加的全部状态。actor 受到 `hitPoolId` 伤害时，以 `concentrationAttribute` 抵御，难度为 max(10, 伤害的一半向下取整)，失败即结束凝神。
- `endsOnDamage` 的状态在承受 `hitPoolId` 伤害后移除。
- 一次提议全部结算后，`round` +1；然后处理到期：`expiresRound <= round` 的状态移除；`repeatSave` 的状态逐一再抵御，成功则移除，并记录骰点；`roundLosses` 清空。
- `rest`：短休把 `restoreOn=short` 的池、计数及 `slotRestoreOn=short` 的内力位回满，并推进 `shortMinutes` 分钟；长休把 short 与 long 全部回满，推进 `longMinutes`。休整移除 `until_removed` 以外、按分钟到期的状态。

## 事件

`WorldEvent` 新增 `rolls: List<RollRecord> = emptyList()` 与 `d20Changes: List<D20Change> = emptyList()`。`eventVersion` 对 d20 事件使用 2，`GenericD100` 继续使用 1 且只接受 1。

```kotlin
@Serializable data class RollRecord(val actorId: String, val purpose: String, val expression: String, val faces: List<Int>, val modifier: Int, val total: Int, val target: Int?, val success: Boolean?)
@Serializable data class D20Change(val type: String, val combatantId: String, val key: String? = null, val delta: Int? = null, val condition: ActiveCondition? = null, val value: String? = null)
// type: pool | slot | counter | condition_add | condition_remove | concentration_set | concentration_clear | defeated | round_advance | minutes_advance
```

`applyRecordedEvent` 只按 `d20Changes` 与叙事效果改状态，并在应用后调用 `validateState`。

## 规划上下文

`WuxiaD20.planningRuleIndex` 返回：难度档、技能与属性对应、状态目录的 label 与 text、当前在场 combatant 拥有的卡牌 ID 与 label、玩家剩余池与内力位。卡牌全文通过 `rules.search` 检索，模组须把每张卡的 `text` 同时写进规则资料。`ContextAssembler.SYSTEM` 需追加 d20 提议说明，作为 02 的一部分完成，原句保持不变。

## 公开投影

`PublicCampaign` 增加 `d20Player`：玩家 combatant 的全部字段；以及 `d20Visible`：在场 NPC 的名字、状态 label 列表。NPC 的守御、池数值、卡牌不公开。

## 验收测试

新增 `WuxiaD20Test`，使用测试内构造的最小模组，覆盖：

- 检定成功与失败、上下风抵消、自然 20 与自然 1
- 抵御成功时池扣减为 0，反噬照扣
- 每轮上限跨两个施术者共享，并在下一轮清空
- 满池单次不能清空
- 归零附加状态，并在下一轮开始到期
- 余力施术限制、时机重复、失措者不能行动
- 凝神互斥与受伤后的凝神抵御
- 状态每轮末再抵御
- 短休与长休的恢复与时间推进
- 1000 次随机提议的事件重放：重放所得状态与在线结算逐字段相同
