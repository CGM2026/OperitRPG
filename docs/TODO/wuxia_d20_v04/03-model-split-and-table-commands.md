# 03 规划与旁白分离模型、场外指令

依赖 [02](02-wuxia-d20-adapter.md) 与其 02b 补充。

## 原实现

- `Campaign.modelFingerprint` 是唯一的模型绑定；`AgentCoordinator` 只持有一个 `TextModelPort`，规划与旁白都经由它。
- `AgentViewModel.coordinator(fingerprint, module)` 按单一指纹构造端口。
- 玩家输入原样进入回合，没有场外指令。

## 新实现

### 双模型绑定

- `Campaign` 新增必填字段 `narratorFingerprint: String`，原 `modelFingerprint` 语义改为规划模型，名称不变以免改动冻结计划与哈希。
- `AgentCoordinator` 构造参数由 `model` 改为 `planner: TextModelPort` 与 `narrator: TextModelPort`。`request` 按 `purpose` 选择端口：`planner` 用规划端口并校验 `modelFingerprint`，`narrator` 与 `npc_speaker` 用旁白端口并校验 `narratorFingerprint`。`ModelAttempt.fingerprint` 记录实际所用指纹。
- `create` 增加 `narratorFingerprint` 参数，两者都必须非空。`rebind` 拆成 `rebindPlanner` 与 `rebindNarrator`，各自要求无待处理回合；`rebindInterruptedNarration` 只改 `narratorFingerprint`。
- 数据库：`CampaignRow` 增加 `narratorFingerprint` 列。0.4 尚未发布，直接并入 02b 的 `Migration(1,2)`：迁移时以该行原 `modelFingerprint` 填入新列，这是一次性的数据迁移而不是运行时回退。备份导出与恢复包含该字段。
- 界面：创建战役时分别选择「规划模型」与「旁白模型」，默认都选中当前第一项，用户可改；存档面板分别显示与重绑两者。

### 场外指令

玩家输入以 `//` 开头时不创建回合、不调用模型，由 `AgentViewModel` 解析：

| 指令 | 作用 |
|---|---|
| `//停` | 设置 `Campaign.sceneHold=true`。下一次旁白的系统提示追加「立即收束当前场景，淡出，转回剧情」，该回合完成后清除 |
| `//降档` | `contentLevel` 减 1，最低 0 |
| `//升档` | `contentLevel` 加 1，最高为模组声明的最高档 |

- `Campaign` 新增 `contentLevel: Int` 与 `sceneHold: Boolean`，新战役的 `contentLevel` 取模组声明的默认档。
- 模组 `ModuleRules` 新增 `contentLevels: List<ContentLevel> = emptyList()` 与 `defaultContentLevel: Int = 0`，`ContentLevel(label, narratorGuideline)`。为空时三条指令都提示「本模组未声明内容分档」并不做任何事。
- 旁白上下文在系统提示末尾追加当前档的 `narratorGuideline`。代码不包含任何具体分档文字。
- 未知的 `//` 指令提示可用指令列表，不进入回合。
- 指令执行结果作为一条本地系统消息显示在对话流中，不写入事件流，不影响重放。

## 验收

- `rpg-agent` 单测：两个端口分别被调用；指纹校验各自生效；重绑互不影响；`sceneHold` 只作用于一次旁白。
- androidTest：迁移后旧行的 `narratorFingerprint` 等于原 `modelFingerprint`；备份往返保留新字段。
- 界面：创建、重绑、三条指令的显示。
