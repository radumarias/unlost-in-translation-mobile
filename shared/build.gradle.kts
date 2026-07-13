plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
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
      implementation(libs.kotlinx.coroutines.core)
      implementation(libs.kotlinx.serialization.json)
      implementation(libs.ktor.client.core)
      implementation(libs.ktor.client.content.negotiation)
      implementation(libs.ktor.serialization.kotlinx.json)
    }
    androidMain.dependencies {
      implementation(libs.ktor.client.okhttp)
      implementation(libs.androidx.room.runtime)
      implementation(libs.androidx.room.ktx)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

dependencies {
  add("kspAndroid", libs.androidx.room.compiler)
}
