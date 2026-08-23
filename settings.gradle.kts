pluginManagement {
    repositories {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "relay"

include(":relay:llm")
include(":relay:ondevice")
include(":relay:agent-core")
include(":relay:memory")
include(":relay:orchestra")
include(":relay:artifacts")
include(":relay:ui-kit")
include(":samples:assistant")
include(":samples:playground")
include(":samples:clip")
include(":samples:werewolf")
