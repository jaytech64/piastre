pluginManagement {
    includeBuild("build-logic")
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "depenses"

include(":app")

include(":core:model")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:testing")

include(":capture:parsing")
include(":capture:listener")

include(":feature:ledger")
include(":feature:review")
include(":feature:reports")
include(":feature:settings")
