# 独立升级验证工具

只在 `-PrpgUpgradeFixture=true` 时参与工程。它作为独立 Android application 打包现有 `app/src/androidTest/java/com/ai/assistance/operit/rpg/upgrade` 黑盒测试，自带完整测试运行库，不依赖 `app` 或 `rpg-core`，不压缩。

工具 applicationId 为 `io.github.cgm2026.operitrpg.test`，使用已有永久签名；manifest 中的 AndroidJUnitRunner 指向正式应用 `io.github.cgm2026.operitrpg`。安装工具 APK 使用 `adb install -r -t`。

升级工作流只构建旧 schema fixture 与此工具，产品 APK 必须下载自已验证的候选 artifact，不重新构建或替换，也不为测试保留生产类。
