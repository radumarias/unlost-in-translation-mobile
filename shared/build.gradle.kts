plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  // NOTE: the kotlinx-serialization compiler plugin is deliberately NOT applied:
  // every serializer is hand-written (see LenientJson kdoc) because the plugin
  // crashes the FIR compiler when co-resident with the Compose plugin on wasmJs.
  // The kotlinx-serialization-json RUNTIME dependency below is still used.
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

// --- Secrets codegen (design §6) ---
// Reads the root .env (falling back to .env.example) and generates
// `com.example.Secrets` into commonMain. Replaces the AGP Secrets plugin +
// BuildConfig.GEMINI_API_KEY so the key is reachable from common code. .env stays
// gitignored and is only ever read here.
//
// Each entry is name -> fallback used when .env does not define it. Issue #33 added
// RELAY_BASE_URL: the relay hostname is deployment configuration, never a literal in
// the code, so moving from a workers.dev subdomain to a production domain is a config
// change rather than a release.
val secretKeys = mapOf(
  "GEMINI_API_KEY" to "MY_GEMINI_API_KEY",
  "RELAY_BASE_URL" to "",
)

val secretsOutputDir = layout.buildDirectory.dir("generated/secrets/commonMain/kotlin")

val generateSecrets by tasks.registering {
  val envFile = rootProject.file(".env")
  val exampleFile = rootProject.file(".env.example")
  // Both files feed the source selection, so both must be inputs — otherwise the
  // configuration cache would not notice a .env being created or deleted and
  // could reuse a stale key. Only existing files can be declared as inputs.
  if (envFile.exists()) inputs.file(envFile)
  if (exampleFile.exists()) inputs.file(exampleFile)
  val keys = secretKeys
  // The fallbacks are part of the output, so a change to one has to invalidate the task.
  inputs.property("secretKeys", keys.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
  val outDir = secretsOutputDir
  outputs.dir(outDir)
  doLast {
    // .env is a shell-style KEY=value file, not a Java .properties file — parse
    // it directly so backslashes/quotes are taken literally (Properties would
    // apply its own escaping and mangle some values).
    val sourceFile = if (envFile.exists()) envFile else exampleFile
    val defined = sourceFile.readLines().mapNotNull { line ->
      val trimmed = line.trim()
      if (trimmed.startsWith("#") || "=" !in trimmed) return@mapNotNull null
      val (name, value) = trimmed.split("=", limit = 2)
      name.trim() to value.trim().trim('"')
    }.toMap()
    val constants = keys.entries.joinToString("\n") { (name, fallback) ->
      // Escape so any character in the value is a valid Kotlin string literal.
      val escaped = (defined[name]?.takeIf { it.isNotEmpty() } ?: fallback)
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\${'$'}")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
      """    const val $name: String = "$escaped""""
    }
    val pkgDir = outDir.get().dir("com/example").asFile
    pkgDir.mkdirs()
    pkgDir.resolve("Secrets.kt").writeText(
      """
      |package com.example
      |
      |/** Generated from the root .env (or .env.example) by the :shared build. Do not edit. */
      |internal object Secrets {
      |$constants
      |}
      """.trimMargin() + "\n"
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
        // Issue #33: session ids are bearer credentials, so they come from a CSPRNG.
        // No provider artifact is needed for this one — see the catalog comment.
        implementation(libs.cryptography.random)
        // Draws the pairing QR. Compose Multiplatform, wasmJs klib included, so the
        // pairing dialog is common code like everything else in the feature.
        implementation(libs.qrose)
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
    // The link transport is common code, but MockEngine does not speak WebSocket, so
    // proving it needs a real server. Ktor's CIO server is JVM-only and test-only; the
    // browser half of the same code path is covered by the Phase 0 spike and, from
    // issue #33 Phase 5 on, by the two-phone manual check.
    getByName("androidHostTest").dependencies {
      implementation(libs.ktor.server.cio)
      implementation(libs.ktor.server.websockets)
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
