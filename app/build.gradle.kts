import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val signingKeys = listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
val releaseSigningAvailable = signingKeys.all { !localProperties.getProperty(it).isNullOrBlank() } &&
    localProperties.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it).isFile } == true

android {
    namespace = "com.ai.assistance.operit"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.cgm2026.operitrpg"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("rpgVersionCode").orElse("3").get().toInt()
        versionName = providers.gradleProperty("rpgVersionName").orElse("0.3.0").get()
        resValue("string", "rpg_app_name", "二十面骰")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseSigningAvailable) create("release") {
            storeFile = rootProject.file(localProperties.getProperty("RELEASE_STORE_FILE"))
            storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst { require(releaseSigningAvailable) {
        "Release requires the existing permanent RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD"
    } }
}
dependencies {
    implementation(project(":rpg-agent"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.json.jvm)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.uiautomator)
    androidTestImplementation(libs.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
