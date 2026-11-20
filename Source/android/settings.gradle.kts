pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "StormVR"
include(":app")
// Nucleo de streaming do Moonlight/Artemis (usado pela tela "Moonlight" do app)
include(":moonlightcore")
