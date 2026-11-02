import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signing = Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }
android {
    namespace = "com.ai.assistance.operit.upgradetests"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.cgm2026.operitrpg.test"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0-upgrade-tests"
    }
    signingConfigs {
        create("permanent") {
            storeFile = rootProject.file(requireNotNull(signing.getProperty("RELEASE_STORE_FILE")))
            storePassword = signing.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = signing.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = signing.getProperty("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("permanent")
        }
    }
    // Compile the existing black-box test directly; no target app or core classes are linked.
    sourceSets.getByName("main").java.setSrcDirs(
        listOf("../app/src/androidTest/java/com/ai/assistance/operit/rpg/upgrade")
    )
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(libs.test.runner)
    implementation(libs.androidx.junit)
    implementation(libs.junit)
    implementation(libs.test.uiautomator)
    implementation(libs.test.core)
    implementation("androidx.tracing:tracing:1.1.0")
}
