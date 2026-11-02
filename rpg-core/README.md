# 跑团核心

纯 JVM Kotlin 模块，Android/Room/模型供应商实现均在 app。固定 Kotlin 2.2.21、serialization 1.9.0、coroutines 1.10.2，沿用上游版本。

独立测试无需配置 Android 项目。在仓库目录运行（JDK 21）：

```text
gradlew.bat -p rpg-core test --console=plain
```

连同 app 内的纯 JVM 备份、更新校验和私密日志作用域一起测试：

```text
gradlew.bat -p rpg-core -I extra-tests.init.gradle test --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false
```

该可选 init script 直接筛选仓库内的原始文件，不复制源码，也不加载 Android 更新管理器、UI 或真实 AppLogger。org.json 仅添加到本次独立测试构建；正常 Android 构建不受影响。2026-11-02 实际合跑 46 项全部通过：核心 26、备份 9、更新与日志作用域 11。

`GameStore.transaction` 必须把 aggregate 的读取、校验、变更和保存放进同一数据库事务；函数块中没有网络请求。内存实现通过编码副本模拟事务回滚，Android 版本必须另行验证磁盘事务与进程恢复。

`TurnCoordinator` 是状态变更入口。自由输入用 `submit`；UI 已明确选择的移动、治疗等用 `submitAction`，直接走同一白名单与结算路径。按 clientMessageId 去重。重复结算读取原结果，叙述失败仅重试叙述。恢复不启动模型调用。

内置模组 JSON 是版本化标识、引用索引与内部真相；`ScenarioDefinition.load` 检查引用，`RainyDock` 固定动作定义与纯 reducer 实现完整效果，不提供脚本执行器。资源实际字节 SHA-256 用于存档兼容校验。每次世界动作记录一个 `ACTION_RESOLVED` 原子事件，包含 actionId 和可选骰点；该事件可由同一 reducer 重放所有时间、资源、线索、位置与结局变化。

UI 使用 `PublicCampaignView`。NPC 闲谈使用单独认知和同 NPC 的交谈历史，不发送玩家其他线索。上下文超限先移除最旧历史，核心使用保守的12000字符上限，供应商桥接仍需进行真实 token 估算。叙述的已知禁词、工具标签与明显 HP 矛盾会被拒绝；自由文本其他幻觉仍须内容验收。

`FakeModelGateway` 仅由调试入口显式选择。它支持动作 ID 和常见中文示范指令，输出来自已提交机械结果，不能代替真实模型内容验收。

2026-11-02：JDK 21.0.13、Gradle 8.13、Java17 字节码，26 项 JVM 测试通过，覆盖 G01—G06、d100 边界、严格协议、Fake四类计划、30 轮、并发去重、确认/取消、失效回调、恢复矩阵、叙述失败不重掷、事务回滚、公开规则上下文、NPC 隔离、请求超时与控制元数据拒绝。

Android instrumentation 另提供 `RoomGameStoreTest` 五组真实临时磁盘测试：close/reopen、并发单次结算、事务回滚/唯一性、战役隔离及中断恢复。当前无设备，尚未执行；close/reopen 不能代替实际进程终止测试。
