# 首版发布验收记录

雨夜跑团 0.1.0 已于 2026-11-02 发布。对应源码提交为 `e05d34c89acc83ee4307ceedacbf137b61a297bb`，Android versionCode 为 1，应用身份为 `io.github.cgm2026.operitrpg`。

- [下载 APK](https://github.com/CGM2026/OperitRPG/releases/download/rpg-v0.1.0/operit-rpg-0.1.0.apk)，404,672,345 字节，约 405 MB。
- [发布页与校验文件](https://github.com/CGM2026/OperitRPG/releases/tag/rpg-v0.1.0)。
- [成功构建](https://github.com/CGM2026/OperitRPG/actions/runs/41384315607)。
- [手机验收步骤](ACCEPTANCE.md)。

## 已通过

共 51 项自动测试通过，0 失败、0 忽略：核心规则和回合恢复 29 项，Android 跑团单测 22 项。Android 单测覆盖备份 9 项、更新策略 5 项、下载校验及取消 5 项、私密日志作用域 2 项、真实 AppLogger 集成 1 项。

测试针对本次跑团模块。发布任务通过显式 `rpgOnlyTests` 属性限定 Android 测试源码，完整产品源码仍参与编译。上游失配的通用 provider 测试未计入通过范围；正常开发构建仍保留原有全部测试源码。

正式发布检查 `lintVitalRelease`、完整 release APK 构建、APK v2 签名检查、包名和版本检查、更新清单生成与 GitHub 发布全部通过。已修复上游孤立日文翻译，保留发布检查。

发布后核对了 APK 大小、GitHub 资产摘要、更新清单与长期签名。通过 HTTP 范围读取核验实际 APK，确认包含 AndroidManifest.xml、classes.dex、45 个 ARM64 原生库条目，且内置 `rainy-dock-v1.json` 与发布源码逐字节一致。该检查未在本机完整下载 APK；完整文件摘要使用 GitHub 上传资产的 SHA256 与构建清单对照。

APK SHA256：`4702896cab9937e58432a3299053963effb64d8fce6b153614d36e7ce442fc3b`。

证书 SHA256：`8838939d69b547bbe6a27483da9819e07fe9e2799b66e5e3b51360df9cbbc373`。

## 待实机验收

当前没有连接实际手机，也未使用真实模型账号。首次安装、页面布局与键盘、系统权限返回、进程终止恢复、覆盖升级后保留存档，以及真实模型连续 30 次交互，仍需按手机验收步骤验证。Room 的 5 项设备测试与 Compose 的 3 项设备测试已编写，尚未执行，不计入上述 51 项。

首次发布尚无更高版本，安装后应显示当前已是最新版。后续发布使用相同包名和长期签名、递增的版本号；手机在用户主动检查并确认后下载安装。完整覆盖升级留待真实第二版验证，本次未额外发布同功能的演示版本。
