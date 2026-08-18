plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.translate.vqxwmn"
    // 26, not 24, since issue #34: `AlgorithmParameters "EC"` is API 26+, and
    // cryptography-kotlin constructs it eagerly in the public-key decoder's
    // constructor *and* re-checks the curve through it on every decode, so on API
    // 24-25 a P-256 public key cannot be decoded in any format — DER does not dodge
    // it. That is both sides of every handshake. Costs 0.5pp of active devices.
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // App Link host for the pairing deep link (issue #33), taken from the same
    // WEB_BASE_URL that :shared compiles into AppSecrets — so the QR, the filter that
    // claims it and the assetlinks.json served by the site all name one host, and a
    // fork that moves the web build changes one line of .env.
    //
    // `example.invalid` when unset (RFC 2606 reserves it, so it can never resolve):
    // an intent filter needs a host to be syntactically valid, and a build with no web
    // deployment should claim nothing. The canonical .env parser is in
    // shared/build.gradle.kts; this reads one key and is deliberately not a second copy
    // of it.
    manifestPlaceholders["pairingLinkHost"] = readWebBaseUrlHost()
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Roborazzi screenshot references are committed under src/test/screenshots/
// (see CLAUDE.md) so recordRoborazziDebug / verifyRoborazziDebug operate on
// the versioned baselines rather than the transient build/ output dir.
roborazzi {
  outputDir.set(file("src/test/screenshots"))
}

// Phase 5 cleanup: the AI-Studio template's unused dependencies (camera, coil,
// firebase, datastore, navigation, accompanist, location) and the androidx
// lifecycle trio (nothing in :app imports androidx.lifecycle — the VM and its
// Compose wiring come from :shared) are deleted, not commented — the version
// catalog history has them if ever needed again.
dependencies {
  implementation(project(":shared"))
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.play.services.wearable)
  // Pairing QR scanner (issue #33). Google supplies the scanning UI and — the
  // reason it was chosen over CameraX + ML Kit — it needs no CAMERA permission,
  // so the app keeps its zero-runtime-permission posture.
  implementation(libs.play.services.code.scanner)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // Only the OkHttp engine is referenced directly (AndroidAppContainer /
  // WearTranslationService build it); ktor-core, content-negotiation, the JSON
  // serializer, and kotlinx.serialization itself are used inside :shared and
  // reach the app at runtime transitively.
  implementation(libs.ktor.client.okhttp)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}


/**
 * Host of `WEB_BASE_URL` in the root `.env` (or `.env.example`), or `example.invalid`
 * when it is unset. Read at configuration time; Gradle tracks the read as a
 * configuration-cache input, so creating or editing `.env` re-runs it.
 */
fun readWebBaseUrlHost(): String {
  val envFile = rootProject.file(".env")
  val file = if (envFile.exists()) envFile else rootProject.file(".env.example")
  if (!file.exists()) return "example.invalid"
  val value = file.readLines().firstNotNullOfOrNull { line ->
    val trimmed = line.trim()
    if (trimmed.startsWith("#") || !trimmed.startsWith("WEB_BASE_URL=")) return@firstNotNullOfOrNull null
    trimmed.removePrefix("WEB_BASE_URL=").trim().trim('"').takeIf { it.isNotEmpty() }
  } ?: return "example.invalid"
  return value.substringAfter("://").substringBefore('/').substringBefore(':')
    .takeIf { it.isNotEmpty() } ?: "example.invalid"
}
