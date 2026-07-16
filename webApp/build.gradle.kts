plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.kotlin.compose)
}

kotlin {
  // Web-only module (design §3: :webApp stays free of AGP). The browser()
  // distribution bundles the Kotlin/Wasm output + the index.html resource.
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
  wasmJs {
    browser {
      commonWebpackConfig {
        outputFileName = "webApp.js"
      }
    }
    binaries.executable()
  }

  sourceSets {
    wasmJsMain.dependencies {
      implementation(project(":shared"))
      implementation(compose.runtime)
      implementation(compose.ui)
      // main.kt references TranslateViewModel (a ViewModel subtype) directly for
      // the voice-input routing; :shared exposes lifecycle as `implementation`,
      // so this module needs its own compile-time dependency.
      implementation(libs.androidx.lifecycle.viewmodel)
    }
  }
}
