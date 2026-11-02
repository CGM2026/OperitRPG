# 0.3 实施记录

状态：开发中。用户已授权按方案实施，并明确选择先交付可安装测试版，真实模型由用户验收。因此本轮不调用付费模型，不宣称真实模型长团质量已通过；正式公开稳定版需另行记录该项结果。

开发分支 feat/longform-agent-v03。下属分工全部为 GPT 6.1 太阳模型、中等推理度：agent_engine_v03 负责纯 Kotlin 契约、规则与 Agent；agent_data_v03 负责资料、数据库、检索与备份；agent_ui_v03 负责手机交互；主 Agent 负责模型连接、旧版适配、集成和发行验证。

版本预算为 0.3.0 / versionCode 3；安装包以 GitHub prerelease 测试版交付，不替代旧版稳定更新。用户可在手机直接打开测试版链接覆盖安装；应用身份、永久签名和后续稳定更新保持不变。

不可改变的旧版基线：rpg-v1.db schema1、旧 CampaignRecord/Plan 序列化与 hash、rainy-dock-v1.json、format1 备份、应用 ID io.github.cgm2026.operitrpg、签名 SHA256 8838939d69b547bbe6a27483da9819e07fe9e2799b66e5e3b51360df9cbbc373。

未有测试证据前，不将任何里程碑标为完成。真实模型、实体手机性能和厂商安装器范围分别报告，不以 Mock、模拟器或桌面 JVM 测试替代。
