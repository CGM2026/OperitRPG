# 合成 v1 覆盖升级夹具

这是测试用数据生产 APK，不是曾经发布的 0.1.0 APK。它使用与正式应用相同的 applicationId 与永久签名、versionCode 1，专门构造旧 Room schema 1、旧偏好文件及已玩内容，然后由实际 0.2.0 release APK 覆盖安装。生产 release 的 R8 与资源缩减保持开启，不增加测试桥或为测试保留整个生产包。

只有指定 `-PrpgUpgradeFixture=true` 才包含本模块。构建 release 使用本机 local.properties 中现有四项 RELEASE 签名配置；夹具 release 为 debuggable，仅供 emulator 的 run-as 等待种子完成。正式应用仍为非 debuggable。

夹具 Activity 为 com.ai.assistance.operit.upgradefixture.FixtureActivity。首次启动异步写入：

- rpg-v1.db，schema 1 和 identity hash b231275c24184551212cba9d44162bcd
- 两个固定 ID 战役：fixture-played-v1 与 fixture-interrupted-v1，均已推进四步，第四步观察骰点为 30，并保存 c1/c2 两条线索
- 第一个战役四轮完整，第二个第四轮已结算、旁白未完成，有一次失败请求记录
- rpg-ui 的选中战役为 fixture-interrupted-v1
- model_configs.preferences_pb 含旧 config_list/config_ID、DEEPSEEK 配置和未知字段，API Key 为固定假值
- files/upgrade-fixture-expected.json，保存完整初始记录、旧偏好文件 SHA-256、旧模型 fingerprint 与签名摘要
- 最后写 files/upgrade-fixture-ready.txt，内容为 READY_V1；失败写 upgrade-fixture-error.txt，仅记录异常类型

两份玩法存档都明确绑定 DEMO 模型，旧供应商配置只供读取兼容性检查，不会用于请求。夹具没有互联网权限。种子通过未修改的 rpg-core 规则 reducer 计算检定、线索、事件并校验重放；直接 SQLite 建立旧 schema，不通过新 Room 写库。已存在数据库时不会覆盖重种，测试本身也不会生成或修复种子。

升级流程为全新 emulator 安装夹具、启动并等 READY_V1、`adb install -r` 实际新 release、安装同证书 instrumentation APK，最后只执行 UpgradeCompatibilityTest。测试先核对版本 2、名称二十面骰、签名与非 debuggable 标志；启动前逐字比较数据库 recordJson 与原始快照，核对 schema/identity、原选中战役和旧偏好文件字节摘要。

随后测试通过可见 UI 启动正式 MainActivity、恢复原中断旁白和继续另一战役的移动。测试只使用 Android SQLite/JSONObject 和 UiAutomator 观察，不依赖被 R8 混淆的 target core/Room/ModelStore 类。恢复前后比较状态、骰点、线索、事件和回合 ID，不增加检定；显式恢复恰新增一次 DEMO 叙述尝试。继续后原四轮原样保留，时间从 4 到 5。模型页需能看到旧配置与完整地址，Key 保持遮罩，读取不重写旧偏好。

真实截图、夹具预期、升级后的观察记录与结果写入目标应用 external-files/upgrade-review，可由 emulator 工作流取回。此轮覆盖的是数据/schema/偏好和运行中的升级兼容，不代表历史 0.1.0 UI 或原 APK 自身已被重放。跨版本备份格式由单独的纯 JVM 旧格式夹具和现有备份测试覆盖；仪器测试不为此新增生产可见的 API 保留点。
