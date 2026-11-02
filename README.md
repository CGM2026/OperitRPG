# 二十面骰

一个专用的 Android 单人文字跑团应用，由 OperitRPG“雨夜跑团”精简而来。0.2.0 移除了通用手机 Agent、终端、浏览器、本地推理引擎、语音、虚拟形象和插件系统，只保留跑团所需功能。

应用支持 Android 8.0 及以上系统，使用黑体、低饱和配色和 28dp 页面留白。当前内置原创短模组《雨夜旧码头》，沿用简化百分骰规则；应用改名不会改变已经进行的游戏。

## 安装与更新

从 [Releases](https://github.com/CGM2026/OperitRPG/releases) 下载 APK。已有“雨夜跑团”0.1.0 时，在旧应用内检查新版并覆盖安装即可，已有战役继续使用。不要先卸载旧应用，卸载会删除其私有存档。

二十面骰继续使用应用身份 `io.github.cgm2026.operitrpg` 和原发布签名。下载地址与更新清单协议保持兼容。应用中点击“设置 → 检查新版”，确认后下载安装，无需通过电脑传文件。手机需要能够访问 GitHub。

## 游玩

新建战役，选择“离线演示”即可体验完整短团，不需要 API Key。真实 AI 主持可在专用模型设置中配置 OpenAI Chat 兼容、OpenAI Responses、Anthropic 或 Gemini 文本接口。模型费用由你选择的供应商计费，应用不附带密钥。

AI 解释行动并生成叙述；程序执行骰点、生命、资源、时间和结局。每次行动保存到本机，已结算的检定不会因断线重试而重掷。支持角色、线索、地点查看，以及完整战役备份导入导出。

旧模型配置文件保留，兼容的文本接口继续使用原 ID 和指纹。包含 OAuth、多密钥、自定义请求头或参数的旧配置会明确提示重新配置，原记录不会被自动删除或切换协议。模型设置不会自动发送请求；离线演示始终是明确选择。

## 开发与验证

- [专用版范围和实现进度](docs/TODO/dedicated_d20_v02/index.md)
- [手机验收指南](docs/TODO/rpg_prototype_v01/ACCEPTANCE.md)
- [模型兼容说明](docs/TODO/dedicated_d20_v02/02-model-gateway.md)
- [发布配置](docs/TODO/rpg-release/index.md)
- [升级验证夹具](upgrade-fixture/README.md)
- [0.1.0 发布记录](docs/TODO/rpg_prototype_v01/RELEASE_ACCEPTANCE.md)

默认构建只包含 `app` 与 `rpg-core`。JDK 21、Android SDK 36、Gradle Wrapper 即可构建，不需要 NDK、Rust、Node、原生库归档或外部模型资产。核心测试可独立执行 `gradlew -p rpg-core test`；完整测试、发布检查和 APK 构建由 Actions 执行。release 构建开启代码及资源压缩，CI 要求 APK 小于 50 MiB、不得含 `.so` 或通用工具资产。

升级验证使用同包名、同签名的旧 schema1 小型测试安装包，保存有固定骰点、线索和中断回合的数据，再覆盖安装实际候选 APK。夹具不属于用户发行包；它验证数据与应用升级兼容，实际旧 ARM64 APK 在用户手机上的行为仍需实机验收。

## 来源与许可

本项目由 [Operit Android](https://github.com/AAswordman/Operit) 改造，保留原有 Git 历史与版权声明，沿用 [LGPL-3.0](LICENSE)。新增代码使用相同许可，第三方组件遵循各自许可。原完整框架保留于 `rpg-v0.1.0` 标签和历史提交中。

发布私钥保存在维护者私有目录与 GitHub 加密 Secrets，公开仓库不包含私钥或 API Key。系统备份只纳入战役数据库和页面选择，不包含模型凭据。
