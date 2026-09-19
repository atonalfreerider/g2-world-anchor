// g2-bridge Android - root settings.
//
// NOTE: This project has NOT been Gradle-synced or device-tested in this repo
// (no Android toolchain / glasses in the build environment). Treat it as a
// correct-by-construction starting point; the BLE protocol bytes are a faithful
// port of a verified reference implementation. See android/README.md.

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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "g2-bridge"
include(":app")
