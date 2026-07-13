plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
  // Phase 2: Android target only. wasmJs is added in Phase 3.
  @Suppress("UnstableApiUsage")
  android {
    namespace = "com.example.shared"
    compileSdk = 36
    minSdk = 24
  }

  sourceSets {
    commonMain.dependencies {
    }
  }
}
