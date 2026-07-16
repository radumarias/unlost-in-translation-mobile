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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  // Default repositoriesMode (PREFER_PROJECT): our own dependencies still resolve
  // through the settings repos below, but the Kotlin/Wasm plugin is allowed to add
  // its own project-level Node.js distribution repo (nodejs.org/dist) — the browser
  // distribution runs webpack via a downloaded Node.js. FAIL_ON_PROJECT_REPOS
  // hard-fails on that repo; PREFER_SETTINGS hides it so org.nodejs:node can't be
  // found. This mode is the working middle ground.
  repositories {
    google()
    mavenCentral()
  }
}

// Lowercase, no spaces: the Kotlin/Wasm npm/yarn step derives the package.json
// "name" from rootProject.name, and npm rejects spaces/uppercase ("Name contains
// illegal characters"). This is a Gradle label only — appId/namespace are unaffected.
rootProject.name = "my-application"

include(":app")
include(":shared")
include(":webApp")
