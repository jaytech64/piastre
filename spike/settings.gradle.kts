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

// The spike consumes the real parsing module directly, so a capture week
// exercises the actual parser rather than only collecting raw payloads. The
// module now lives in the main build as :capture:parsing; Gradle substitutes it
// for the coordinates below.
includeBuild("..") {
    dependencySubstitution {
        substitute(module("io.github.jaytech64.piastre:piastre-parsing"))
            .using(project(":capture:parsing"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "piastre-spike"
include(":app")
