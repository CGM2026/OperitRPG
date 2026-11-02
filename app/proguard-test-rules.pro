# AndroidX test tracing uses this compile-time annotation (default CLASS retention).
# It has no Android runtime behavior; packaging the JDK-oriented annotation collection
# would bring javax.lang.model dependencies into the test APK. Scope this to tests only.
-dontwarn com.google.errorprone.annotations.MustBeClosed

# The shell runner selects this black-box test by its fully qualified name.
-keep class com.ai.assistance.operit.rpg.upgrade.UpgradeCompatibilityTest { *; }
