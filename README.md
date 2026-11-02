# 二十面骰

一个专用的 Android 单人文字跑团应用，由 OperitRPG“雨夜跑团”精简而来。0.3.0 测试版增加可导入模组的长团 Agent：局部规则检索、持久记事、NPC 状态与专用工具循环。通用手机自动化、终端、浏览器、插件执行和本地模型仍不包含在安装包内。

应用支持 Android 8.0 及以上系统，使用黑体、低饱和配色和 28dp 页面留白。旧《雨夜旧码头》战役继续使用原引擎和数据库；新长团使用独立数据库及原创通用百分骰适配器，不是完整 CoC/D&D 规则实现。

0.2.0 安装包为 **1.76 MB**，较 0.1.0 的 404.67 MB 缩小 **99.56%**。74 项自动测试及 Android 模拟器同签名覆盖升级验收通过，详情见[发布验收报告与实拍截图](docs/TODO/dedicated_d20_v02/RELEASE_ACCEPTANCE.md)。

## 安装与更新

从 [Releases](https://github.com/CGM2026/OperitRPG/releases) 下载 APK。已有“雨夜跑团”0.1.0 时，在旧应用内检查新版并覆盖安装即可，已有战役继续使用。不要先卸载旧应用，卸载会删除其私有存档。

二十面骰继续使用应用身份 `io.github.cgm2026.operitrpg` 和原发布签名。下载地址与更新清单协议保持兼容。应用中点击“设置 → 检查新版”，确认后下载安装，无需通过电脑传文件。手机需要能够访问 GitHub。

## 游玩

新建战役，选择“离线演示”即可体验完整短团，不需要 API Key。真实 AI 主持可在专用模型设置中配置 OpenAI Chat 兼容、OpenAI Responses、Anthropic 或 Gemini 文本接口。模型费用由你选择的供应商计费，应用不附带密钥。

新长团先在“模组与资料”预览并安装示例，或从手机导入 `.rpgmodule.zip`。两个原创示例《雪线驿站失踪案》《赤岭边境巡护》分别含 6 地点、12 NPC、3 章节目标，可下载独立数据包。MD/TXT 可作为参考资料绑定战役；需要转为可执行模组时，明确选择模型发送最多 6000 字，得到可编辑 JSON 草稿，经校验与确认后激活。

0.3.0 是预发布测试版：手机从 [发布页面](https://github.com/CGM2026/OperitRPG/releases/tag/rpg-v0.3.0) 下载 APK 覆盖安装，稳定版“检查新版”不会提示预发布包。后续正式版继续使用原手机更新通道。真实模型长团表现由用户验收；离线流程演示只验证操作，不代表真实模型能力。

AI 解释行动并生成叙述；程序执行骰点、生命、资源、时间和结局。每次行动保存到本机，已结算的检定不会因断线重试而重掷。支持角色、线索、地点查看，以及完整战役备份导入导出。

旧模型配置文件保留，兼容的文本接口继续使用原 ID 和指纹。包含 OAuth、多密钥、自定义请求头或参数的旧配置会明确提示重新配置，原记录不会被自动删除或切换协议。模型设置不会自动发送请求；离线演示始终是明确选择。

## 开发与验证

- [专用版范围和实现进度](docs/TODO/dedicated_d20_v02/index.md)
- [0.3 长团设计与实现](docs/TODO/rpg_agent_v03/index.md)
- [0.3 手机界面与验收流程](docs/TODO/rpg_agent_v03/05-phone-ui.md)
- [0.3 运行时保证与实际限制](rpg-agent/README.md)
- [手机验收指南](docs/TODO/rpg_prototype_v01/ACCEPTANCE.md)
- [模型兼容说明](docs/TODO/dedicated_d20_v02/02-model-gateway.md)
- [发布配置](docs/TODO/rpg-release/index.md)
- [升级验证夹具](upgrade-fixture/README.md)
- [0.1.0 发布记录](docs/TODO/rpg_prototype_v01/RELEASE_ACCEPTANCE.md)

默认构建只包含 `app`、`rpg-core` 与 `rpg-agent`。JDK 21、Android SDK 36、Gradle Wrapper 即可构建，不需要 NDK、Rust、Node、旧框架原生库归档或外部模型资产。两个核心模块测试均可独立执行；完整测试、发布检查和 APK 构建由 Actions 执行。release 构建开启代码及资源压缩，CI 要求 APK 小于 50 MiB。原生库仅允许 Compose 标准绘图依赖 `libandroidx.graphics.path.so` 的四种 Android ABI，单文件小于 64 KiB、合计小于 256 KiB；旧框架原生库和通用工具资产继续禁止。

升级验证使用同包名、同签名的旧 schema1 小型测试安装包，保存有固定骰点、线索和中断回合的数据，再覆盖安装实际候选 APK。夹具不属于用户发行包；它验证数据与应用升级兼容，实际旧 ARM64 APK 在用户手机上的行为仍需实机验收。

## 来源与许可

本项目由 [Operit Android](https://github.com/AAswordman/Operit) 改造，保留原有 Git 历史与版权声明，沿用 [LGPL-3.0](LICENSE)。新增代码使用相同许可，第三方组件遵循各自许可。原完整框架保留于 `rpg-v0.1.0` 标签和历史提交中。

发布私钥保存在维护者私有目录与 GitHub 加密 Secrets，公开仓库不包含私钥或 API Key。系统备份只纳入战役数据库和页面选择，不包含模型凭据。
