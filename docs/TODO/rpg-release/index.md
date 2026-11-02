---
fork: https://github.com/CGM2026/OperitRPG
---

# 二十面骰构建与发布

0.2.0 / versionCode 2 为专用跑团应用。名称改为“二十面骰”，applicationId 继续为 `io.github.cgm2026.operitrpg`，namespace 为 `com.ai.assistance.operit`，使用 0.1.0 原有永久签名，数据库 `rpg-v1.db` 和 schema 版本 1 保持不变。

默认工程只包含 app 和 rpg-core。依赖仅保留 Compose、AndroidX activity/lifecycle/core、Room/kapt、serialization、coroutines、OkHttp 和 DataStore preferences。构建不再安装 NDK/CMake、Rust 或 Node，不再下载手工依赖、模型包或构建 JS 资产。release 启用 R8 与资源压缩；最低 Android 26，compile/target SDK 36。core 与专用 app 的 JVM 测试全量运行，旧 rpgOnlyTests 参数已移除。

发布入口仍为 Actions 的 D20 signed release，输入 version_name/version_code；两者必须比最新正式版本递增。首个专用版使用 0.2.0、2。标签仍为 `rpg-v<version>`，APK 仍为 `operit-rpg-<version>.apk`，让 0.1.0 的应用内更新可以直接找到新包。

四个 Secrets 沿用已有值，不生成新 key：RPG_KEYSTORE_BASE64、RPG_STORE_PASSWORD、RPG_KEY_ALIAS、RPG_KEY_PASSWORD。缺失任一项立即失败。签名字段写入 runner 临时 keystore 与 local.properties，结束后删除；缓存仅保存 Gradle 用户目录。

CI 使用 JDK 21、SDK platform 36 和 build-tools 35.0.0，依次运行 core/app 完整单测、release lint 与压缩构建。生成候选前核对包名、版本、target SDK、apksigner 签名、APK SHA256，签名必须与已发布 0.1.0 的证书一致。APK 必须小于 50 MiB；原生白名单只允许 `lib/{arm64-v8a,armeabi-v7a,x86,x86_64}/libandroidx.graphics.path.so`，每个文件小于 64 KiB、总量小于 256 KiB，并核对 ELF 架构。其余原生库与未批准的重型 assets 继续拒绝。检查结果保存在候选的 apk-content-audit.json。发布说明从同目录 RELEASE_NOTES.md 读取。

这项白名单对应 Compose 的标准绘图依赖：当前 [ui-graphics-android 1.10.4 官方 POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.10.4/ui-graphics-android-1.10.4.pom) 依赖 graphics-path 1.0.1；[AndroidX 官方说明](https://developer.android.com/jetpack/androidx/releases/graphics) 记载该库通过原生组件将 PathIterator 功能提供到旧版 Android。Google Maven 官方 AAR 的四个 so 分别为 10,096、7,252、9,284、10,760 字节，合计 37,392 字节。保留该绘图库不需要安装 NDK，也不引入旧框架原生模块。

正式发布按以下顺序进行：

1. 在 [D20 signed release](../../../.github/workflows/rpg-release.yml) 输入版本号与递增 versionCode，明确选择 publish=false。等待 run 成功，取得 d20-release-candidate artifact，其中包含签名 APK、update-manifest.json、SHA256SUMS、signature.txt、RELEASE_NOTES.md 与记录来源 run/head 的 source.json。publish 输入默认 true，候选阶段须显式改为 false。
2. 在同一个 commit 运行 [D20 signed upgrade on x86_64](../../../.github/workflows/rpg-upgrade-smoke.yml)，candidate_run_id 指向第一步。流程要求候选 run 成功且 head 完全一致，下载其 artifact 并核对 SHA256、大小与签名。覆盖安装的对象是这个已下载的 APK；fixture 和仪表测试 APK 单独构建。
3. 升级测试成功后检查 d20-upgrade-evidence：candidate.json 中的来源 run/head/APK SHA256 应与候选一致，upgrade-review 中的截图和 JSON 记录数据保留、恢复叙述及继续行动的结果。
4. 将受测 artifact 内的同一个 APK、manifest、SHA256SUMS 与 signature.txt 上传为正式 GitHub Release，沿用候选的发布说明。发布时不重新编译，不用新构建的 APK 替换受测文件，也不为发布重新运行一次构建流程。

后续版本沿用此流程。正式更新渠道继续使用同一仓库的 latest release、rpg-v 标签与 operit-rpg APK 文件名。

更新接口与原版本保持相同仓库和文件命名。用户确认后下载，校验字节数、SHA256、包名、版本及已安装应用的签名。每个下载使用独立 UUID 文件并响应协程取消，旋转后可安全重试；安装权限设置返回后继续系统安装确认。专用版下载限制也收紧为小于 50 MiB。

本地纯 JVM 更新测试通过仓库中的 `rpg-core/extra-tests.init.gradle` 直接引用原文件，覆盖旧版到专用版协议、地址/重定向、大小/hash 和下载取消。Android/R8 的实际构建结果以 0.2.0 的 Actions 为准。

x86_64 升级验收使用独立的旧 schema 小 fixture APK：同 applicationId、同永久签名、versionCode 1，种入旧版数据库与模型偏好，等待 READY_V1 标记后强停，再以保留数据的方式覆盖安装候选 release。历史 0.1.0 APK 仅含 arm64，因此模拟器使用合成旧 schema fixture；fixture 是测试工具，不属于默认工程或发布产物。

仅升级工作流启用 rpgUpgradeFixture、rpgUpgradeSmoke 与 androidTestBuildType=release。其 release 仪表测试仅编译 rpg/upgrade 下的黑盒测试，通过 UI、SQLite 和 JSON 核对结果；正式 APK 仍经过 R8。默认 debug 的 Room/Compose 仪表测试和全部 JVM 测试保持完整。
