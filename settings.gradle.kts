pluginManagement {
    repositories { google(); gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "D20"
include(":app", ":rpg-core", ":rpg-agent")
if (providers.gradleProperty("rpgUpgradeFixture").orNull == "true") {
    include(":upgrade-fixture", ":upgrade-tests")
}
