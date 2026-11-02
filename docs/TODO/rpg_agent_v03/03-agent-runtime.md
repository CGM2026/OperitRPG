# 03 Agent 循环与专用工具

状态：待实施。运行时 Agent 负责纯 Kotlin 调度、协议和规则执行，Android 层负责网络/Room/界面适配。

## 模块边界

正常构建增加 rpg-agent JVM 模块；rpg-core 保留旧引擎。新模块不引用 Android API，也不通过旧 RainyDock 数据类型表达新世界。

```text
app/
	rpg/integration/	统一入口与两引擎 UI adapter
	rpg/agent/data/	新 Room 数据库与 repository
	rpg/agent/import/	系统文件选择与模组安装
	rpg/agent/ui/	模组、规则出处、记事和人物投影
	rpg/model/	已有文本连接及新旧 transport adapter
	rpg/update/	原更新通道
rpg-core/
	原引擎与原存档契约
rpg-agent/
	contract/	版本化模组、事件、提案与工具协议
	engine/	状态机、调度、事务端口、预算
	rules/	规则适配器与类型化效果
	tools/	工具注册、权限、参数与结果约束
	memory/	检索契约、上下文装配、纠错与摘要
	npc/	状态、关系、知识与游戏时钟
```

首批接口：CampaignSession、AgentStore、ModuleCatalog、RuleAdapter、RuleRepository、MemoryRepository、NpcRepository、ToolRegistry、TextModelPort、ContextAssembler、DiceSource。共享实现只在实际有相同语义时提取，不修改旧 DTO 以强行复用。

现有 OperitModelGateway 的四协议 HTTP 编解码可以复用，但调用形状需要拆成独立的文本请求 transport。旧 plan/narrate adapter 保留现有预算与输出约束；新 adapter 接收 ContextPacket 和 AgentStep。不能继续假设所有新请求都有旧 history JSON 字段。

## 一个受限但真实的自主循环

玩家输入 → 建立持久化回合 → 装配场景/权限/记忆 → 模型决定查询或提案 → 程序执行获准查询 → 模型根据结果继续 → 冻结并校验行动 → 必要确认 → 原子结算 → 公开叙述 → 保存。

模型能够自主选择查询工具并利用返回结果修正下一步，这一段是 Agent 循环。世界写入仍归程序统一结算，不把每次函数请求直接当修改授权。

AgentStep v1 是严格 JSON 联合类型，kind 只能为 read_tools、propose_action、clarify、answer。各类型有固定字段，不允许额外字段、重复 JSON key 或把自然语言段落当 JSON 修补。read_tools 只能提交已注册的工具名和 schema 参数；propose_action 引用世界实体、规则证据与类型化效果。

首版通过已有文本接口承载此协议，四家供应商采用相同语义。供应商原生 tool_calls 在旧路径仍拒绝，在新文本路径也明确不支持；不能失败后静默切换模式。这仍然是真正的工具执行，只是工具传输格式由应用定义。未来若增加原生 function calling，应作为显式能力配置和单独 adapter，实现等价合同测试，而不是恢复整个 Operit 工具框架。

Planner、Narrator、NPC speaker 是不同的上下文角色，不是三个独立常驻模型进程。默认复用一个用户选定的模型，减少配置与成本；角色权限仍严格分开。

## 首批工具清单

- rules.search(query,ruleScope)：返回最多 5 个授权 RuleEvidence
- rules.read(sourceId,anchor)：读取指定版本的获准原文片段，不支持任意文件路径或 URL
- memory.search(query,entityIds,timeRange)：查询当前战役有效记事与历史，返回来源和有效状态
- world.inspect(entityIds)：读取角色有权知道的当前人物、地点、物品或目标投影
- npc.read(npcId)：读取 RoleContext 允许的 NPC 状态/知识，不因为传入 GM 字样扩大权限
- action.propose(actionType,targetIds,ruleRefs,parameters,stakes)：验证并创建冻结提案，不掷骰、不改世界
- npc.propose_update(npcId,typedChange,evidenceRefs)：形成随主行动提交的候选 NPC 变化
- world.propose_entity(entityType,definition,evidenceRefs)：仅在模组策略允许时生成候选新实体，不能修改锁定真相

宿主内部操作而非任意模型工具：commit_action、roll_dice、apply_effects、persist_event、derive_memory、export_backup、check_update。骰点在正式事务中产生并随结果保存，禁止模型用独立 roll 工具不断试骰。

read_tools 只容纳前五项只读工具。后三项提案能力通过 AgentStep.propose_action 统一调度：一个主行动可包含经校验的 NPC 更新和实体创建候选，整批效果最多 16 项、新建实体最多 3 个。提案工具不会单独提交，避免模型把多个工具请求变成多笔世界写入。

所有查询按当前战役与角色绑定范围。模型不能传 campaignId、actorRole 或存储路径来扩大访问；模型可引用的实体只能来自该回合获准范围。更新安装、删除战役和文件导出始终由用户界面发起。

工具结果记录 invocationId、toolSchemaVersion、argumentHash、revision、sourceRefs、状态和有界输出。保存公开执行摘要与必要证据，不保存或展示模型隐藏思维。重复查询可用同一 revision 的已记录结果，跨 revision 不复用过期结果。

## 明确预算与停止条件

每个玩家回合最多 4 次规划调用、8 次只读工具执行、一次世界提交和一次叙述调用；NPC 决策计入同一规划预算，不额外无限生成子 Agent。每批查询最大 3 个工具，初版按顺序执行以简化版本一致性。

单次模型请求最长 60 秒，单轮自动执行总时长上限 180 秒，不含等待玩家确认。工具单次输出不超过 8 KiB，一轮工具输出总量不超过 32 KiB；ContextAssembler 仍有独立输入预算。超过限制停止并保存阶段，明确提示原因，不偷偷换模型或继续重试。

连续两次完全相同的工具与参数、同一错误重复、无法得到新信息、没有可用规则、未知工具、越权参数或格式错误，都在相应边界中止。格式错误不自动修补，不追加付费自纠错调用；用户可重试当前尚未提交阶段。

导入整理和章节摘要有各自持久任务与预算，不算“免费附带”。每项开始前显示将调用所选模型，设置调用次数上限；具体费用只在配置了可核验计价时估算，否则显示 token usage/估算量，不虚构金额。

## 新状态机

RECEIVED → GATHERING → PROPOSED → AWAITING_CONFIRMATION 或 READY → COMMITTED → NARRATING → COMPLETE。

澄清/无状态规则问答可由 GATHERING 到 COMPLETE。取消只允许未提交阶段进入 CANCELLED。每个阶段可记录明确的可恢复错误，中断不能把 COMMITTED 改回 READY。

计划冻结当前 revision、module/rules hash、规则证据、实体版本、效果及条件、模型配置指纹和用户确认内容。用户确认后只执行该计划；状态改变导致 STALE_PROPOSAL，需要明确重新规划。

确认用于复合/歧义行动、临时裁定、影响超出原表达的提案。普通清晰动作不增加“查一次规则确认一次”的弹窗。公开确认内容通过 PublicProjection 构造，不能泄露 NPC 秘密或 GM 私有规划。

提交后 NarrativeInput 只包含公开结果、当前授权状态、证据和检索记忆。旁白不是新的状态事实来源。若旁白描述了未提交变化，标记生成失败或要求修正，不能反向修改数据库去迎合文本。

## 示例验收轨迹

玩家：“上次船工答应帮我，现在能不能请他带我过河？”

模型查 memory.search 找到承诺原回合，world.inspect 查询当前地点与船工状态，rules.search 查询当前模组的渡河/交涉依据。它据此提出交涉或移动提案，而不是因为一句“答应过”直接宣布成功。

宿主检查船工是否在场、关系与资源条件、规则及承诺有效性。若提案使用临时裁定，先显示阈值与后果供确认。提交时保存骰点、时间、资源、NPC 关系与事件；旁白从这个结果生成。

应用在提交后断网，重开只继续该结果的叙述。玩家的承诺记忆、该 NPC 的知情关系和骰点都保持一致。

## 本模块验收

- 模型能在测试对话中连续自主选择两个不同查询工具，并引用实际结果构成提案
- 未注册工具、伪造角色、任意 URL/文件路径、越权知识请求及重复写入都被拒绝
- 查询次数、输出大小、请求时长和成本相关计数可验证，取消能结束当前网络调用
- 任意阶段注入进程终止，已提交骰点和效果保持一次，未提交阶段可明确恢复或取消
- 将恶意指令放在模组文本、规则片段、NPC 发言中，不改变工具注册与访问范围
- GM 内部内容不出现在公开上下文、界面进度、规则出处或错误信息中
