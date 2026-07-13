# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android translation app (Kotlin + Jetpack Compose, Material 3) exported from Google AI Studio ("Remix: Translate"). Google Translate–style UI with speech input/output, dual-headset conversation mode, translation history, and Wear OS integration. Single `:app` module; all code lives under the `com.example` namespace (application ID is `com.aistudio.translate.vqxwmn`).

## Build & Test Commands

The Gradle wrapper (`gradlew`, pinned to Gradle 9.3.1) is committed — run all commands through it. On first run it downloads the distribution (checksum-verified via `distributionSha256Sum`).

```bash
./gradlew :app:assembleDebug              # build debug APK
./gradlew :app:testDebugUnitTest          # JVM unit tests (JUnit4 + Robolectric)
./gradlew :app:testDebugUnitTest --tests "com.example.ExampleRobolectricTest"   # single test class
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (needs device/emulator)
./gradlew :app:recordRoborazziDebug       # record Roborazzi screenshot references
./gradlew :app:verifyRoborazziDebug       # verify screenshots against references
```

- Unit tests run with Robolectric (`@Config(sdk = [36])`, Android resources enabled). Roborazzi screenshot references live in `app/src/test/screenshots/`.
- Toolchain: Gradle 9.3.1 (wrapper), AGP 9.1.1, Kotlin 2.2.10, KSP (Room + Moshi codegen), Java 11, compileSdk/targetSdk 36, minSdk 24. Versions are centralized in `gradle/libs.versions.toml`.

## Local Setup (from README)

1. Create `.env` in the project root with `GEMINI_API_KEY=<your key>` (see `.env.example`). The Secrets Gradle Plugin reads `.env` (falling back to `.env.example`) and exposes it as `BuildConfig.GEMINI_API_KEY`. Never commit `.env`.
2. The debug build type references a `debug.keystore` that is **not in the repo** — remove `signingConfig = signingConfigs.getByName("debugConfig")` from `app/build.gradle.kts` to run locally (README step; don't commit that removal).
3. Release signing comes from env vars: `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`.

## Architecture

MVVM-ish single-activity Compose app. `TranslateViewModel` is the central state holder; almost all UI lives in `MainActivity.kt` (~1900 lines: `MainAppScreen` with bottom tabs "translate"/"history"/"saved", conversation split-screen, `LanguagePickerDialog`, `ConversationDraftDialog`).

**Translation pipeline** (`TranslateViewModel.triggerTranslation`): primary translation → persist to Room → two parallel follow-ups launched as child coroutines:
1. Round-trip back-translation (always via Google) shown as a confidence check.
2. Gemini "cultural validation" — returns JSON (`isWarning`, `explanation`, tone `suggestions`) parsed into `ValidationState`.

The same three-step flow is duplicated for conversation mode in `prepareConversationPart` (draft → confirm/cancel via `ConversationDraft`).

**Two translation backends** in `network/TranslationService` (a plain object using OkHttp + org.json — Retrofit/Moshi are declared as dependencies but not actually used here):
- `translateGoogle`: unofficial free endpoint `translate.googleapis.com/translate_a/single` (no API key). Default path.
- `translateGemini` / `validateTranslationGemini`: Gemini REST API (`gemini-3.5-flash:generateContent`), used when the "AI Enhanced" toggle is on and for validation. Both throw if `GEMINI_API_KEY` is unset/placeholder.

**Key components:**
- `data/` — Room (`translation_database`, v1, destructive migration): history + starred translations, exposed as Flows.
- `model/Language` — hardcoded list of 16 supported languages; `Language.getByCode` falls back to English.
- `speech/TTSHelper` — TextToSpeech wrapper. For dual-headset routing it synthesizes speech to a temp WAV, then plays it through `MediaPlayer.setPreferredDevice` to target a specific audio output; plain `tts.speak` otherwise. Speech *input* uses the system `RecognizerIntent` activity (no in-app recording despite the `RECORD_AUDIO` permission).
- `wear/` — `WearSyncManager` pushes every successful translation to watches (DataClient path `/active_translation`) and posts a wearable notification; `WearTranslationService` (a `WearableListenerService` declared in the manifest) handles `/translate_request` messages from the watch and replies on `/translate_response`.
- Dual-headset mode (`DualHeadsetModel`, `DualHeadsetSetupDialog`): assigns an audio output device ID per conversation partner; TTS output is routed to the matching headset.
