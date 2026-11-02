# 跑团核心

纯 JVM Kotlin 模块，Android/Room/模型供应商实现均在 app。固定 Kotlin 2.2.21、serialization 1.9.0、coroutines 1.10.2，沿用上游版本。

独立测试无需配置 Android 项目。在仓库目录运行（JDK 21）：

```text
gradlew.bat -p rpg-core test --console=plain
```

`GameStore.transaction` 必须把 aggregate 的读取、校验、变更和保存放进同一数据库事务；函数块中没有网络请求。内存实现通过编码副本模拟事务回滚，Android 版本必须另行验证磁盘事务与进程恢复。

`TurnCoordinator` 是状态变更入口。自由输入用 `submit`；UI 已明确选择的移动、治疗等用 `submitAction`，直接走同一白名单与结算路径。按 clientMessageId 去重。重复结算读取原结果，叙述失败仅重试叙述。恢复不启动模型调用。

内置模组 JSON 是版本化标识、引用索引与内部真相；`ScenarioDefinition.load` 检查引用，`RainyDock` 固定动作定义与纯 reducer 实现完整效果，不提供脚本执行器。资源实际字节 SHA-256 用于存档兼容校验。每次世界动作记录一个 `ACTION_RESOLVED` 原子事件，包含 actionId 和可选骰点；该事件可由同一 reducer 重放所有时间、资源、线索、位置与结局变化。

UI 使用 `PublicCampaignView`。NPC 闲谈使用单独认知和同 NPC 的交谈历史，不发送玩家其他线索。上下文超限先移除最旧历史，核心使用保守的12000字符上限，供应商桥接仍需进行真实 token 估算。叙述的已知禁词、工具标签与明显 HP 矛盾会被拒绝；自由文本其他幻觉仍须内容验收。

`FakeModelGateway` 仅由调试入口显式选择。它支持动作 ID 和常见中文示范指令，输出来自已提交机械结果，不能代替真实模型内容验收。

2026-11-02：JDK 21.0.13、Gradle 8.13，21 项 JVM 测试通过，覆盖 G01—G06、d100 边界、严格协议、30 轮、并发去重、确认/取消、失效回调、恢复矩阵、叙述失败不重掷、事务回滚及 NPC 隔离。
