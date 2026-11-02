# 0.3 实施记录

状态：0.3.0 可安装测试版已通过自动与模拟器验收，真实模型由用户验收。本轮未调用付费模型；真实模型长团质量、实体手机表现及完整长期目标没有标为完成。安装与验收细节见 [测试报告](RELEASE_ACCEPTANCE.md)。

开发分支 feat/longform-agent-v03。下属分工全部为 GPT 6.1 太阳模型、中等推理度：agent_engine_v03 负责纯 Kotlin 契约、规则与 Agent；agent_data_v03 负责资料、数据库、检索与备份；agent_ui_v03 负责手机交互；主 Agent 负责模型连接、旧版适配、集成和发行验证。

版本预算为 0.3.0 / versionCode 3；安装包以 GitHub prerelease 测试版交付，不替代旧版稳定更新。用户可在手机直接打开测试版链接覆盖安装；应用身份、永久签名和后续稳定更新保持不变。

不可改变的旧版基线：rpg-v1.db schema1、旧 CampaignRecord/Plan 序列化与 hash、rainy-dock-v1.json、format1 备份、应用 ID io.github.cgm2026.operitrpg、签名 SHA256 8838939d69b547bbe6a27483da9819e07fe9e2799b66e5e3b51360df9cbbc373。

本次安装包对应提交 `9b59d2430e01a740bbf5067fed60960cb45fc28c`。构建任务 [41428485628](https://github.com/CGM2026/OperitRPG/actions/runs/41428485628) 通过 108 项 JVM 测试，生成 1,976,439 字节永久签名 APK。双系统验收任务 [41429127892](https://github.com/CGM2026/OperitRPG/actions/runs/41429127892) 在 API 26 与 API 36 各通过 8 项数据测试、旧存档覆盖升级、新长团完整界面及独立进程恢复。

发布使用这份已测试 APK，不另行重建。旧战役继续使用冻结兼容引擎；新引擎已接通模组、规则检索、事件与对话记忆、NPC 状态及专用工具调用。离场 NPC 自主调度、模型自动摘要、结构化承诺抽取与主张审核界面仍待后续实现。
