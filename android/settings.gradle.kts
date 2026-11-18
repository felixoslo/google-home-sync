pluginManagement {
    repositories {
        maven { url = uri("../../tools/maven/repository") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Local maven (.m2) repository:
        maven { url = uri("../../tools/maven/repository") }
        // Remote repositories:
        google()
        mavenCentral()
    }
}

rootProject.name = "Google Home API Sample App"
include(":app")
 