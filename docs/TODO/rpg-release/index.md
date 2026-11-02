---
fork: https://github.com/CGM2026/OperitRPG
---

# 雨夜跑团发布与更新

独立应用身份为 `io.github.cgm2026.operitrpg`，首版 `0.1.0` / versionCode `1`。上游源码 namespace 保持原值。应用与原版可同时安装，各自保存资料。

发布入口为 Actions 的 **RPG signed release**，手动输入 `version_name` 和 `version_code`。首次使用 `0.1.0`、`1`。以后两者都必须递增。发布标签为 `rpg-v<version_name>`；同一标签不覆盖。也可更新 app/build.gradle.kts 中的默认 versionCode 后推送对应 `rpg-v*` 标签发布。正式安装应始终使用 release APK。

仓库必须配置四个 Actions Secrets：

- `RPG_KEYSTORE_BASE64`：永久发布 PKCS12 keystore 的 base64 内容
- `RPG_STORE_PASSWORD`：keystore 密码
- `RPG_KEY_ALIAS`：签名别名
- `RPG_KEY_PASSWORD`：签名私钥密码

缺失任一项会在依赖下载前失败。签名材料仅写入 runner 临时目录，结束时删除。发布构建不使用 debug 签名、不执行上游证书轮换。发布者须离线备份永久 keystore；丢失后不能覆盖安装已有版本。

完整构建使用 JDK 21、Node 22、Android SDK 36、NDK 25.1.8937393、CMake 3.22.1、Rust 1.88.0。流程保留上游 terminal 子模块、libs/subpack/jniLibs 三套外部依赖、native ripgrep、WebChat 和 ToolPkg 资产。外部归档沿用上游固定 Google Drive 文件 id，通过上游检查解压到规定目录；下载链接或权限失效时构建会失败。归档来源目前没有内容 SHA256 锁，首轮成功后可记录稳定归档校验值。

构建运行 rpg-core 测试并生成完整 release APK。发布前用 apksigner 检查签名，用 apkanalyzer 对照包名和版本，再生成 `update-manifest.json`、`SHA256SUMS` 与 `signature.txt`。上游其他 workflows 在本 fork 跳过，仍保留原定义供查阅。

## 应用更新接口

设置页面调用 `com.ai.assistance.operit.rpg.update.RpgUpdateButton()`。用户点击“检查新版”才请求 GitHub latest release 和该标签中的 manifest；确认“下载并安装”才下载 APK。应用显示下载进度，核对 SHA256、大小、包名、versionCode、versionName 和当前已安装版本的签名证书。系统未知来源安装设置返回后继续安装，取消或失败可重试。

更新只接受规定 GitHub HTTPS 域名和本仓库对应版本的 APK，逐次验证重定向；manifest 最大 1 MiB，APK 最大 2 GiB。APK 保存在应用私有缓存，通过现有 FileProvider 和 `REQUEST_INSTALL_PACKAGES` 打开系统安装页面。系统最终安装仍由用户确认。调试包身份带 `.debug`，与正式更新通道身份不同。

## 检查记录

本地通过全部 workflow YAML 解析、发布流程内嵌 Python 语法检查和 `git diff --check`。本机缺少完整 Android SDK/NDK，Android 编译与手机安装验证由首轮 Actions 产物和设备验收完成；静态检查不代表已完成设备验收。

设备验收需从已签名首版实际安装，再发布更高版本，检查版本说明、下载确认、未知来源返回、系统安装确认和资料保留；同时检查断网重试、错误 SHA256、错误包名、错误签名和较旧版本被拒绝。
