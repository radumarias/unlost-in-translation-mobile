# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**Picking the work up cold?** Read [`HANDOFF.md`](HANDOFF.md) first — current state, what has been done, fresh-clone setup (three gitignored files break the build), the gate suite with expected test counts, the hard rules, and what is left.

## Project Overview

Kotlin Multiplatform translation app (Compose Multiplatform, Material 3), originally exported from Google AI Studio ("Remix: Translate") as an Android app and restructured per GitHub issue #1 (plan in `features/full-kmp-restructure/`). Google Translate–style UI with speech input/output, dual-headset conversation mode, translation history, and Wear OS integration on Android; the same UI runs in the browser on Kotlin/Wasm. All code lives under the `com.example` namespace (Android application ID is `com.aistudio.translate.vqxwmn`).

**Modules:**
- `:shared` — KMP library: the entire UI (`ui/`), view model, domain models (`model/`), networking (`network/`), platform seams (`platform/`), and per-platform implementations in `androidMain` (Room, TTSHelper) / `wasmJsMain` (localStorage, Web Speech API).
- `:app` — thin Android entry point: `MainActivity` (~115 lines: container + RecognizerIntent voice launcher + `setContent { App(...) }`), `AndroidAppContainer`, and the Wear OS code (`wear/`, manifest-declared `WearTranslationService`).
- `:webApp` — wasm entry point: `main.kt` (`ComposeViewport { App(createAppContainer(), launchVoiceInput) }`) + `index.html` (canvas host + unsupported-browser banner).

## Build & Test Commands

The Gradle wrapper (`gradlew`, pinned via `distributionSha256Sum`) is committed — run all commands through it.

```bash
./gradlew :app:assembleDebug              # build debug APK
./gradlew :app:testDebugUnitTest          # :app JVM tests (JUnit4 + Robolectric + Roborazzi)
./gradlew :app:verifyRoborazziDebug       # verify screenshots against committed baselines
./gradlew :app:recordRoborazziDebug       # re-record Roborazzi baselines (only when UI change is intended)
./gradlew :shared:testAndroidHostTest     # :shared commonTest on the JVM host
./gradlew :shared:wasmJsTest              # :shared commonTest + wasmJsTest in headless Chrome
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # web dev server (default port 8080)
./gradlew :webApp:wasmJsBrowserDistribution     # production web bundle (≈4.7 MB gzip transfer, see design §11)
./gradlew :app:connectedDebugAndroidTest  # instrumented tests (needs device/emulator)
```

**Test matrix:** `shared/src/commonTest` (parsers, `KtorTranslationApi` with MockEngine, `TranslateViewModel` with fakes from `commonTest/.../fakes/`) runs on BOTH the JVM host and ChromeHeadless; `shared/src/wasmJsTest` (localStorage repository) runs in ChromeHeadless; `:app` keeps Robolectric + Roborazzi (baselines in `app/src/test/screenshots/` — Android must stay pixel-identical unless a change is deliberate).

- Toolchain: Gradle 9.5 (wrapper), AGP 9.3, Kotlin 2.3.20, Compose Multiplatform 1.11.1, KSP2 (Room), Java 11, compileSdk/targetSdk 36, minSdk 24. Versions are centralized in `gradle/libs.versions.toml`.

## Local Setup (from README)

1. Create `.env` in the project root with `GEMINI_API_KEY=<your key>` (see `.env.example`). A codegen task in `shared/build.gradle.kts` reads `.env` (falling back to `.env.example`) and generates the internal `com.example.Secrets` object; the hand-written `AppSecrets` wrapper (checked in, NOT generated) exposes it as `AppSecrets.geminiApiKey`. Never commit `.env`. **The key is compiled into the web bundle and extractable from it — public web deployment requires owner sign-off (plan Phase 4 task 5).**
2. The debug build type references a `debug.keystore` that is **not in the repo** — remove `signingConfig = signingConfigs.getByName("debugConfig")` from `app/build.gradle.kts` to run locally (README step; don't commit that removal).
3. Release signing comes from env vars: `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`.

## Architecture

MVVM: the shared `TranslateViewModel` is the central state holder; the whole app shell is the shared `App` composable (`shared/src/commonMain/.../ui/App.kt` — bottom tabs "translate"/"history"/"saved", conversation split-screen, dialogs). Platform services reach the UI/VM only through the hand-built `AppContainer` (design §3: `api`, `historyRepository`, `speechSynthesizer`, `voiceInput`, `imagePicker`, `audioOutputCatalog`, `broadcaster`, `platformActions`, `log`, `capabilities`) — `AndroidAppContainer` in `:app`, `WebAppContainer` in `wasmJsMain`.

**Capability gating** (design §8): UI differences between platforms are data-driven via `PlatformCapabilities` (`voiceInput`, `dualHeadset`, `wearSync`, `imageInput`, `extendedGlyphs`) — no expect/actual or is-platform branching in screens. Web: voice input is feature-detected (Chromium SpeechRecognition + secure context); when false, conversation mode swaps mic FABs for typed inputs. `extendedGlyphs=false` on web renders uppercase language codes instead of flag emoji (the wasm canvas has no emoji font).

**Translation pipeline** (`TranslateViewModel.triggerTranslation`): primary translation → `publishTranslation` (persist to Room on Android / localStorage on web → Wear broadcast, no-op on web → two parallel child coroutines):
1. Round-trip back-translation (always via Google) shown as a confidence check, with a staleness guard. Skipped when auto-detect could not resolve a source language.
2. Gemini "cultural validation" — JSON (`isWarning`, `explanation`, `sanityCheck`, `idiomExplanation`, tone `suggestions`) parsed into `ValidationState`.

`onImageInputResult` (camera/gallery OCR) reuses `publishTranslation`. A similar but separate flow drives conversation mode in `prepareConversationPart` (draft → confirm/cancel via `ConversationDraft`). Known recorded deviation: design §4's single-pipeline-helper consolidation is still partial — `prepareConversationPart` has its own orchestration.

**Two translation backends** in `network/KtorTranslationApi` (Ktor 3 + kotlinx.serialization runtime; engine injected — OkHttp on Android, Js on wasm):
- `translateGoogle` / `detectAndTranslateGoogle`: unofficial free endpoint `translate.googleapis.com/translate_a/single` (no API key). Default path; `sl=auto` gives free source-language detection.
- `translateGemini` / `detectAndTranslateGemini` / `validateTranslation` / `rewriteMessage` / `translateImage`: Gemini REST API (`gemini-3.5-flash:generateContent`, falling back to `gemini-3-flash-preview`), used when the "AI Enhanced" toggle is on, when a tone/situation is set, and for validation. All throw if the key is unset/placeholder.
- **Request budget matters**: the key is on the free tier (~20/day/model). A translation costs 2 Gemini calls (primary + validation) and a photo costs 2 as well; tone, situation, conversation context, sanity check, idiom explanation and auto-detect were all built to cost **zero** extra. `rewriteMessage` is the only user-triggered call. Keep it that way — see `features/web-feature-port/`.
- Error contract (design §5): every failure surfaces as an `Exception` (`apiCall` normalizes the wasm engine's `kotlin.Error` fetch failures).
- ⚠️ All serializers are **hand-written** `KSerializer`s (`GeminiDtos.kt`, `Translation.kt`); the kotlinx-serialization compiler plugin is deliberately NOT applied to `:shared` — it crashes the FIR compiler when co-resident with the Compose plugin on wasmJs. Don't add `@Serializable` there.

**Key components:**
- `shared/commonMain/data/TranslationHistoryRepository` — interface; Room impl in `androidMain` (`translation_database`, v1, destructive migration), localStorage impl in `wasmJsMain` (single key `translation_history_v1`, corrupt-data recovery).
- `shared/commonMain/model/Language` — hardcoded list of 16 supported languages; `Language.getByCode` falls back to English (`findByCode` returns null instead); `flagLabel(extendedGlyphs)` renders flag emoji or the uppercase code. `Language.autoDetect` is deliberately **not** in `supportedLanguages` — it has no BCP-47 tag and must never reach TTS or the target picker; `pickerLanguages(includeAuto)` splices it in for the source picker only.
- `shared/commonMain/model/TranslationContext` — tone/situation presets and prior conversation turns, rendered into Gemini prompts by `KtorTranslationApi.contextBlock`. A non-default tone or situation forces the Gemini path (`needsAiModel`), because Google's endpoint cannot express either.
- `shared/androidMain/speech/TTSHelper` — TextToSpeech wrapper. For dual-headset routing it synthesizes to a temp WAV and plays it via `MediaPlayer.setPreferredDevice`; plain `tts.speak` otherwise. `wasmJsMain/platform/WebSpeechSynthesizer` wraps `window.speechSynthesis`.
- Voice input: Android uses the system `RecognizerIntent` activity (launcher in `MainActivity`); web uses `WebVoiceInput` (SpeechRecognition, one-shot, hard failures throw `VoiceInputException` with a user-displayable message). Both route results through `TranslateViewModel.onVoiceInputResult`.
- Image input (OCR translation) mirrors that seam exactly: Android uses `PickVisualMedia` + `TakePicture` launchers in `MainActivity` (photo shared with the camera app via `FileProvider`, no `CAMERA` permission, downscaled by `AndroidImageLoading` before upload); web uses `WebImagePicker` (hidden file input, `capture=environment`). Both route through `TranslateViewModel.onImageInputResult`.
- `:app/wear/` — `WearSyncManager` pushes every successful translation to watches (DataClient path `/active_translation`) and posts a wearable notification; `WearTranslationService` handles `/translate_request` messages and replies on `/translate_response`.
- Dual-headset mode (`DualHeadsetModel`, `DualHeadsetSetupDialog` vs the `AudioOutputCatalog` seam): assigns an audio output device ID per conversation partner; Android-only (`capabilities.dualHeadset`).

## Plan documents

`features/full-kmp-restructure/{01-architecture,02-design,03-implementation-plan}.md` are the binding contracts for this codebase's structure; deviations are recorded inline in 02-design.md. Read them before structural changes.

`features/web-feature-port/{01-feature-inventory,02-implementation-record}.md` record the port of the web build's AI features (tone/situation, conversation context, sanity check, idiom explanation, auto-detect, rewrite, camera OCR) from `github.com/radumarias/unlost-in-translation`, including what was deliberately *not* ported and why. Read the inventory before adding anything else from the web build — several of its behaviours were changed on purpose.
