import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
val signing = Properties().apply { rootProject.file("local.properties").inputStream().use(::load) }
android {
    namespace = "com.ai.assistance.operit.upgradefixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.cgm2026.operitrpg"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-fixture"
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
            isDebuggable = true // Test fixture only: allows run-as to observe the completed seed marker.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("permanent")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
dependencies {
    implementation(project(":rpg-core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
}
