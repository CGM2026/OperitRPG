---
repo: https://github.com/CGM2026/OperitRPG
status: 设计中，里程碑 1
---

# 武侠 d20 长团引擎（0.4）

## 原状

长团运行时 `rpg-agent` 只有 `GenericD100` 一种规则适配器，且被硬编码在 `AgentCoordinator`、`ContextAssembler`、`ModuleZipImporter` 与 app 的仓库、备份服务中。骰源只有 `d100()`。世界状态只有平铺的玩家资源与技能，NPC 没有战斗数值，`gameTime` 与 `NpcState.nextActionAt` 没有调度逻辑。一个战役只绑定一个模型指纹，规划和旁白共用。

## 意图

在不改动模型权限边界、事务与重放保证的前提下，增加第二种规则适配器 `wuxia-d20-v1`，让数据型模组能表达：六维检定、抵御、对抗、攻击、命名资源池、内力位、计数型资源、状态与持续、凝神、卡牌化功法，以及「一次交换」式的简化战斗。随后增加时钟与 NPC 日程，并允许规划与旁白使用不同模型。

## 范围决定

- 用户确认本版按全新软件对待，不要求 0.3 长团存档与 0.3 模组继续可用。`GenericD100` 与两个原创示例保留并继续通过测试，因为维持它们的成本很低，也可作为回归样本。
- 旧《雨夜旧码头》引擎 `rpg-core` 不在本次作用域内，不做修改。
- 代码中不出现任何具体模组的剧情、人物或成人内容。资源池、状态、卡牌的名称与数值全部来自模组数据。具体模组包不提交到本仓库。

## 步骤

1. [适配器注册与通用骰源](01-adapter-registry-and-dice.md)
2. [wuxia-d20-v1 数据模型与结算](02-wuxia-d20-adapter.md)
3. [规划与旁白分离模型、场外指令](03-model-split-and-table-commands.md)
4. [时钟、定时事件与 NPC 日程](04-clock-and-schedule.md)
5. 手机界面，待写

## 构建与验证

本仓库默认不在 Agent 侧执行构建。新增 `.github/workflows/agent-tests.yml`，在推送 `feat/**`、`fix/**` 分支时运行 `rpg-agent`、`rpg-core` 的 JVM 测试与 `:app:compileDebugKotlin`，结果由维护者查看。
