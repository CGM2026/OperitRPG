# 01 适配器注册与通用骰源

## 原实现

- `AgentCoordinator` 内 `private val adapter=GenericD100()`。
- `ContextAssembler.planning` 直接 `GenericD100().availableActions(...)`。
- `ModuleZipImporter.validate`、`ModuleZipImporter` 的草稿校验、app 的 `AgentRepositories`、`AgentBackupService` 各自 `GenericD100().validateModule(...)`。
- `fun interface DiceSource { fun d100(): Int }`，调用方以 `DiceSource { 12 }` 形式构造。

## 新实现

### 适配器注册

新增 `rpg-agent/.../rules/RuleAdapters.kt`：

```kotlin
object RuleAdapters {
    fun forRules(adapterId: String, adapterVersion: String): RuleAdapter
    fun forModule(module: ValidatedModule): RuleAdapter = forRules(module.rules.adapterId, module.rules.adapterVersion)
}
```

- `generic-d100-v1` 版本 `1` 返回 `GenericD100`。
- 未知组合抛出 `IllegalArgumentException("UNKNOWN_RULE_ADAPTER")`，与现有错误码一致。
- 第 02 步再注册 `wuxia-d20-v1`。本步不得预留占位实现。

所有上述硬编码位置改为通过 `RuleAdapters.forModule(module)` 取得适配器。`AgentCoordinator` 每次使用时按当前战役模组取适配器，不再持有单一字段。

`RuleAdapter` 接口新增：

```kotlin
fun planningRuleIndex(module: ValidatedModule): JsonObject
```

`ContextAssembler.planning` 改为把它的返回值放入 `Rules:` 段，代替当前固定的 `PlanningRuleIndex`。`GenericD100` 的实现输出与现有 `PlanningRuleIndex` 完全相同的字段，保证旧提示词行为不变。

### 通用骰源

```kotlin
fun interface DiceSource {
    /** 返回 1..sides 的整数。 */
    fun roll(sides: Int): Int
}
```

- `GenericD100.resolve` 改为 `dice.roll(100)`，并继续 `require(value in 1..100)`。
- app `AgentViewModel` 改为 `DiceSource { sides -> random.nextInt(sides) + 1 }`，`random` 仍为 `SecureRandom`。
- 测试中的 `DiceSource { 12 }` 改为 `DiceSource { _ -> 12 }`，语义不变。
- 新增 `rules/DiceExpression.kt`：解析并掷 `NdM`、`NdM+K`、`NdM-K`、纯整数 `K`，N 在 1..20，M 属于 {4,6,8,10,12,20,100}，K 在 -50..50。返回 `DiceOutcome(expression, faces: List<Int>, modifier, total)`。非法表达式抛出 `IllegalArgumentException("INVALID_DICE")`。不支持其它语法。

## 不做

- 不改 `rpg-core` 的 `DiceSource`。
- 不改事件、计划、模组格式。

## 验收

- 现有 `rpg-agent` 测试全部通过，仅做因骰源签名变化必需的机械修改。
- 新增测试：`RuleAdapters` 对已知与未知 ID 的行为；`DiceExpression` 的解析边界、非法输入与确定性掷骰（固定骰源序列）。
- `GenericD100.planningRuleIndex` 的输出与原 `PlanningRuleIndex` 序列化结果逐字节一致，有测试断言。
