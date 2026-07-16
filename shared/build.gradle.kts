plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

// --- Secrets codegen (design §6) ---
// Reads the root .env (falling back to .env.example) and generates
// `com.example.Secrets` into commonMain. Replaces the AGP Secrets plugin +
// BuildConfig.GEMINI_API_KEY so the key is reachable from common code. .env stays
// gitignored and is only ever read here.
val secretsOutputDir = layout.buildDirectory.dir("generated/secrets/commonMain/kotlin")

val generateSecrets by tasks.registering {
  val envFile = rootProject.file(".env")
  val exampleFile = rootProject.file(".env.example")
  // Both files feed the source selection, so both must be inputs — otherwise the
  // configuration cache would not notice a .env being created or deleted and
  // could reuse a stale key. Only existing files can be declared as inputs.
  if (envFile.exists()) inputs.file(envFile)
  if (exampleFile.exists()) inputs.file(exampleFile)
  val outDir = secretsOutputDir
  outputs.dir(outDir)
  doLast {
    // .env is a shell-style KEY=value file, not a Java .properties file — parse
    // it directly so backslashes/quotes are taken literally (Properties would
    // apply its own escaping and mangle some values).
    val sourceFile = if (envFile.exists()) envFile else exampleFile
    val key = sourceFile.readLines()
      .firstNotNullOfOrNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || "=" !in trimmed) return@firstNotNullOfOrNull null
        val (name, value) = trimmed.split("=", limit = 2)
        if (name.trim() == "GEMINI_API_KEY") value.trim().trim('"') else null
      }
      ?: "MY_GEMINI_API_KEY"
    // Escape so any character in the key is a valid Kotlin string literal.
    val escaped = key
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("$", "\${'$'}")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
    val pkgDir = outDir.get().dir("com/example").asFile
    pkgDir.mkdirs()
    pkgDir.resolve("Secrets.kt").writeText(
      """
      package com.example

      /** Generated from the root .env (or .env.example) by the :shared build. Do not edit. */
      internal object Secrets {
          const val GEMINI_API_KEY: String = "$escaped"
      }
      """.trimIndent() + "\n"
    )
  }
}

kotlin {
  @Suppress("UnstableApiUsage")
  android {
    namespace = "com.example.shared"
    compileSdk = 36
    minSdk = 24
    // Opt into the host-side (JVM) unit-test compilation so commonTest is wired
    // to a real target and runs via :shared:testAndroidHostTest.
    withHostTest {}
  }

  // Phase 3: web target. The browser() runner drives :webApp; the module itself
  // exposes commonMain + wasmJsMain to the web build.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }

  sourceSets {
    commonMain {
      kotlin.srcDir(secretsOutputDir)
      dependencies {
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        // Compose Multiplatform (design §1: shared UI in commonMain). Provided by
        // the compose-multiplatform plugin's `compose` extension, not the catalog.
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.materialIconsExtended)
        implementation(compose.ui)
        // Multiplatform ViewModel (design §3: shared TranslateViewModel) + the
        // compose `viewModel {}` factory used by App.kt. Epoch millis that used
        // System.currentTimeMillis() now come from the stdlib kotlin.time.Clock
        // (no extra dependency).
        implementation(libs.androidx.lifecycle.viewmodel)
        implementation(libs.androidx.lifecycle.viewmodel.compose.kmp)
      }
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.androidx.room.runtime)
      implementation(libs.androidx.room.ktx)
    }
    wasmJsMain.dependencies {
      implementation(libs.ktor.client.js)
      implementation(libs.kotlinx.browser)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
      implementation(libs.kotlinx.coroutines.test)
      implementation(libs.ktor.client.mock)
    }
  }
}

dependencies {
  add("kspAndroid", libs.androidx.room.compiler)
}

// Ensure the generated Secrets.kt exists before anything reads the commonMain
// source dir — Kotlin compilations and the KSP tasks (which are not
// KotlinCompilationTask and so need to be wired explicitly).
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
  dependsOn(generateSecrets)
}
