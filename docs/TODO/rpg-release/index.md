---
fork: https://github.com/CGM2026/OperitRPG
---

# 二十面骰构建与发布

0.2.0 / versionCode 2 为专用跑团应用。名称改为“二十面骰”，applicationId 继续为 `io.github.cgm2026.operitrpg`，namespace 为 `com.ai.assistance.operit`，使用 0.1.0 原有永久签名，数据库 `rpg-v1.db` 和 schema 版本 1 保持不变。

默认工程只包含 app 和 rpg-core。依赖仅保留 Compose、AndroidX activity/lifecycle/core、Room/kapt、serialization、coroutines、OkHttp 和 DataStore preferences。没有原生模块、NDK/CMake、JNI、ObjectBox、终端、浏览器、语音、FFmpeg、模型包或 JS 资产构建。release 启用 R8 与资源压缩；最低 Android 26，compile/target SDK 36。专用 app 的全部 JVM 测试正常执行，没有测试源码筛选开关。

发布入口仍为 Actions 的 D20 signed release，输入 version_name/version_code；两者必须比最新正式版本递增。首个专用版使用 0.2.0、2。标签仍为 `rpg-v<version>`，APK 仍为 `operit-rpg-<version>.apk`，让 0.1.0 的应用内更新可以直接找到新包。

四个 Secrets 沿用已有值，不生成新 key：RPG_KEYSTORE_BASE64、RPG_STORE_PASSWORD、RPG_KEY_ALIAS、RPG_KEY_PASSWORD。缺失任一项立即失败。签名字段写入 runner 临时 keystore 与 local.properties，结束后删除；缓存仅保存 Gradle 用户目录。

CI 使用 JDK 21、SDK platform 36 和 build-tools 35.0.0，依次运行 core/app 完整单测、release lint 与压缩构建。发布前核对包名、版本、target SDK、apksigner 签名、APK SHA256，签名必须与已发布 0.1.0 的证书一致。APK 必须小于 50 MiB，不得包含 lib/ 目录、任何 .so 或未批准的重型 assets。验证通过后发布 APK、update-manifest.json、SHA256SUMS 和 signature.txt。发布说明从同目录 RELEASE_NOTES.md 读取。

首次专用版先把发布流程的 publish 设置为 false，取得 d20-release-candidate artifact。然后在相同 commit 启动 D20 signed upgrade on x86_64，传入 candidate_run_id；流程核对来源成功与 head 一致，再安装 artifact 中的实际 APK。升级测试通过后发布这些已验证文件，不重新构建替代 APK。以后的正式发布也应先完成对应候选包的升级验证。publish 输入默认 true，维护者须按上述顺序选择候选构建和正式发布。

更新接口与原版本保持相同仓库和文件命名。用户确认后下载，校验字节数、SHA256、包名、版本及已安装应用的签名。每个下载使用独立 UUID 文件并响应协程取消，旋转后可安全重试；安装权限设置返回后继续系统安装确认。专用版下载限制也收紧为小于 50 MiB。

本地纯 JVM 更新测试通过仓库中的 `rpg-core/extra-tests.init.gradle` 直接引用原文件，覆盖旧版到专用版协议、地址/重定向、大小/hash 和下载取消。Android/R8 的实际构建结果以 0.2.0 的 Actions 为准。

x86_64 升级验收使用独立的旧 schema 小 fixture APK：同 applicationId、同永久签名、versionCode 1，种入旧版数据库与模型偏好，再覆盖安装专用 release 验证数据。原来的 0.1.0 APK 仅含 arm64，不能直接在 x86_64 模拟器运行。fixture 是测试工具，不属于默认工程或发布产物。
