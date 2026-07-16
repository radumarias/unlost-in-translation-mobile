// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.roborazzi) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
  alias(libs.plugins.compose.multiplatform) apply false
}

// Dependabot alerts #1/#2: the wasm dev toolchain (webpack-dev-server/karma)
// pulls a vulnerable transitive `ws` (< 8.21.0 — DoS + uninitialized-memory
// advisories). Dev-machine-only (never in the shipped wasm bundle), but pin it
// forward via a yarn resolution in kotlin-js-store/wasm/yarn.lock.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin> {
  extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension>()
    .resolution("ws", "^8.21.0")
}
