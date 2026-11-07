# 模组制作规范（外部 Agent 版）

本目录是给**外部 AI agent / 人类作者**使用的模组包制作规范。App 的模组导入链路只负责校验、预览和安装；它不会根据一份设定文档自动生成模组，也不会替你补字段、补哈希或修正剧情。能被导入的模组包，必须由作者把全部数据写完整。

本规范只描述**格式与规则**，不包含任何具体模组的剧情。示例只用于展示结构；`ExampleModules.kt` 中的两个示例是代码内演示包，不随本规范分发剧情。

- `README.md`：本文件，概览与工作流。
- `format.md`：逐类字段表、严格 JSON、全部 id/条件/效果/可见性/证据/计划事件/章节/内容分档/d20 规则。
- `checklist.md`：按导入校验顺序排列的自检清单与错误码。
- `agent-skill.md`：可直接作为外部 agent 系统提示/技能说明的文本。

## 1. 模组包是什么

一个模组包是一个 `.zip` 文件，内部只有数据文件，没有可执行代码：

```text
module/manifest.json          包的清单、版本、文件哈希
module/world.json             世界、地点、人物、物品、事实、目标、行动、d20 战斗者、计划事件
module/rules.json             规则适配器、难度、效果白名单、证据、内容分档、章节、d20 规则
module/sources/*.md|*.txt     原始资料（规则书、设定、主持资料等）
```

ZIP 中**不能有目录条目**，不能有以上三类之外的路径，不能有脚本、图片、二进制、临时文件、`.DS_Store` 等。每个 source 文件名必须匹配 `module/sources/[A-Za-z0-9_-]+\.(md|txt)`。

支持的两个规则适配器：

| `rules.adapterId` | `rules.adapterVersion` | 说明 |
| --- | --- | --- |
| `generic-d100-v1` | `1` | 通用百分骰；调查/交涉/移动/使用物品/等待/技能检定 |
| `wuxia-d20-v1` | `1` | 数据驱动 d20；属性、技能、DC 档、池、计数器、状态、卡牌、武器、休息 |

`manifest.rulesetId` 必须等于 `rules.adapterId`，`manifest.rulesetVersion` 必须等于 `rules.adapterVersion`。

## 2. 外部 Agent 的工作流

按下面顺序做。不要先写 manifest 再改 world/rules，否则哈希会全部失效。

### 步骤 1：读原始资料，建立 sources

- 先收集你被授权使用的原始资料：规则、设定、主持笔记、人物关系、时间线等。
- 把要引用的原文放进 `module/sources/`。规则放一个文件，设定/主持秘密放另一个文件，便于控制可见性。
- **原文必须逐字保留**。可以在 source 里加标题、分段、作者注释，但 `RuleEvidence.quote` 必须能在最终 source 文本中逐字找到，不能改标点、空格、换行、全半角、省略号或翻译。
- 不要把主持秘密写进玩家可见的公开 source；公开规则和 GM 设定应分文件。
- 不要编造资料中不存在的句子、数字、人物或因果。模组只能使用原始资料支持的内容；不够写就缩小模组，而不是补写“原文”。

### 步骤 2：写 `module/sources/*`

- 每个 source 是一个 UTF-8 文本文件，路径必须在 `module/sources/` 下。
- source 的 SHA-256 会同时出现在 `manifest.files`、`RuleEvidence.source.sourceHash` 中；任何改动都要重算。
- 建议把 source 当成“可被引用的证据库”，不是小说正文。每个可执行规则/行动至少要有一条证据。

### 步骤 3：写 `module/world.json` 与 `module/rules.json`

- 先定 `moduleId`（例如 `my-module`，必须匹配 `[a-z][a-z0-9-]{2,63}`），所有实体 id 以 `moduleId:` 开头。
- 所有 JSON 必须是严格 JSON：双引号、无注释、无尾逗号、无重复 key、未知字段拒绝。
- 完整字段、默认值、范围与错误码见 `format.md`。不要凭“看起来合理”猜字段。
- 秘密事实用 `Visibility(GM)`；玩家可见内容不得包含秘密。需要揭示时用 `reveal_fact` 效果，而不是把秘密直接写进公开字段。
- 章节标题、行动标题（会成为 `publicSummary`）、地点描述、NPC 名字、目标标题、内容分档标签都按“玩家可能看到”处理，不能剧透。
- 计划事件（schedule）的 `label` 不展示给玩家，但 `notice` 会作为“征兆”展示，不能泄密。

### 步骤 4：计算 manifest 与哈希

- `module/manifest.json` 列出除自身以外的**每一个** ZIP 条目，逐项写 `path`、`size`（字节数）、`sha256`（小写十六进制）。
- 哈希对象是 ZIP 中该文件的**实际字节**，不是重新格式化后的 JSON，也不是字符数。`world.json` / `rules.json` 建议使用紧凑、稳定的 UTF-8 编码。
- `manifest.files` 的路径集合必须正好等于 ZIP 中除 `module/manifest.json` 外的全部路径；多一个少一个都失败。
- `manifest` 自身不参与 `files` 列表，也没有自哈希。引擎会再对规范化后的 manifest 计算内容哈希，作者不需要提供。
- `module/rules.json.rules.evidence[].source.sourceHash` 必须等于对应 source 文件的 SHA-256；`manifest.files` 中的同一文件也必须一致。

### 步骤 5：自检

逐项执行 `checklist.md`。最低限度：

1. 解压后路径只有允许的三类；
2. 三个 JSON 能被严格解析；
3. 每个 evidence 的 quote 是对应 source 的逐字子串；
4. 每个 id 带 `moduleId:` 前缀且全局唯一；
5. 每个 effect 的 `source` 与某条证据的 `source` 完全相等（含 hash/anchor/authority），或属于计划事件允许的来源；
6. manifest 的 size/sha256 与真实文件一致；
7. `generic-d100-v1` / `wuxia-d20-v1` 各自的规则块完整。

### 步骤 6：打包

- 用普通 ZIP 打包，条目使用正斜杠 `/`，不要写目录条目，不要写额外文件。
- 压缩包不超过 20 MiB；单条目不超过 5 MiB；解压后总大小不超过 50 MiB；条目数不超过 2000。
- 修改任何文件后，必须回到步骤 4 重算哈希再打包。**不要复用旧 manifest**。

### 步骤 7：导入

App 的导入流程会先校验（`ModuleZipImporter.validate`），通过后显示预览；只有用户确认后才安装。校验失败不会安装，也不会产生半成品。

## 3. 边界与不可协商项

- **只导入，不生成**：App 不根据设定文本自动编写 `world.json` / `rules.json` / manifest。外部 agent 必须交付完整包。
- **不得编造原文**：证据必须来自 source；source 必须是真实提供的资料。没有依据的行动/效果宁可不写。
- **证据逐字**：`RuleEvidence.quote` 是 source 的子串，不是摘要、不是改写、不是“意思相近”。
- **秘密只能 GM 可见**：`Visibility(GM)` 的事实、证据、设定不得进入玩家可见字段或 `notice`。
- **玩家可见字段不得泄露秘密**：包括行动标题/公开摘要、地点描述、NPC 名字、目标标题、章节标题、内容分档标签、奖励/征兆文本。
- **章节标题不得剧透**：标题会显示给玩家；用“第一章 雪夜来客”这类不含真相的标题，不要写“真凶是……”。
- **严格 JSON**：未知字段、重复 key、类型错误、枚举大小写错误都会导致导入失败。
- **不写代码**：ZIP 内没有脚本入口；所有行为由数据与两个规则适配器解释。
- **版本不可原地覆盖**：同一 `moduleId + version` 若内容哈希不同，安装会报 `MODULE_VERSION_CONFLICT`。修改内容必须提升 `version`。

## 4. 版本与兼容

- `manifest.formatVersion` 必须为 `1`。
- `manifest.engineApiVersion` 必须为 `1`（对应 `AGENT_API_VERSION`）。
- `manifest.version` 必须匹配 `[0-9]+\.[0-9]+\.[0-9]+`（三段十进制数字，不支持预发布/构建后缀）。
- `adapterVersion` 当前固定为 `1`。
- 事件 schema：`generic-d100-v1` 写版本 1，`wuxia-d20-v1` 写版本 2；这是引擎内部记录，作者不需要写事件。

## 5. 相关源码（只读核对）

- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/contract/Models.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/contract/D20Models.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/module/ModuleZipImporter.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/module/ExampleModules.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/RuleAdapters.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/GenericD100.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/WuxiaD20.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/NarrativeEffects.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/Scheduler.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/Chapters.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/rules/DiceExpression.kt`
- `rpg-agent/src/main/kotlin/com/ai/assistance/operit/rpg/agent/tools/AgentProtocol.kt`（`StrictJson`）
- `app/src/main/java/com/ai/assistance/operit/rpg/agent/data/AgentRepositories.kt`（安装期补充校验）
