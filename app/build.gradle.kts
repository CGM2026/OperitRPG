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
        versionCode = providers.gradleProperty("rpgVersionCode").orElse("2").get().toInt()
        versionName = providers.gradleProperty("rpgVersionName").orElse("0.2.0").get()
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
    testBuildType = providers.gradleProperty("androidTestBuildType").orElse("debug").get()
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
// The optimized-release smoke has no references to target APIs that R8 may rename.
// Default debug instrumentation and all JVM test sources remain complete.
if (providers.gradleProperty("rpgUpgradeSmoke").orNull == "true") {
    require(android.testBuildType == "release") { "Upgrade smoke must target the optimized release" }
    val upgradeTests = fileTree("src/androidTest/java") {
        include("com/ai/assistance/operit/rpg/upgrade/**/*.kt")
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (name == "compileReleaseAndroidTestKotlin" || name == "kaptGenerateStubsReleaseAndroidTestKotlin") {
            setSource(upgradeTests)
            setIncludes(listOf("**/rpg/upgrade/**/*.kt"))
            doFirst {
                require(sources.files.isNotEmpty() && sources.files.all { it in upgradeTests.files }) {
                    "Release upgrade instrumentation must contain only black-box upgrade tests"
                }
            }
        }
    }
}

tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst { require(releaseSigningAvailable) {
        "Release requires the existing permanent RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD"
    } }
}
dependencies {
    implementation(project(":rpg-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
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
    // AndroidX test tracing references this annotation; release test shrinking resolves it.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.27.0")
    androidTestImplementation(libs.test.espresso)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
}
