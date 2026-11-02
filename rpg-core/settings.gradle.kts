pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        kotlin("jvm") version "2.2.21"
        kotlin("plugin.serialization") version "2.2.21"
    }
}
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "operit-rpg-core"
