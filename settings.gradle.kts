pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "KmpTest"

// shared Kotlin Multiplatform modules (Android + desktop)
include(":shared:domain", ":shared:data", ":shared:ui")
// platform apps
include(":android", ":desktop")
