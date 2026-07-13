# Full KMP Restructure — Architecture

Feature: run the translation app on **Web (Kotlin/Wasm + Compose Multiplatform)** and **Android** from one shared codebase.

Status: **planned, not implemented**. Companion docs: [02-design.md](02-design.md) (detailed design), [03-implementation-plan.md](03-implementation-plan.md) (phased plan).

## 1. Summary

Restructure the current single-module Android app into a Kotlin Multiplatform project:

- **`:shared`** — new KMP library module; holds all UI (Compose Multiplatform), view models, networking, domain models, and storage/platform abstractions. Targets: `android`, `wasmJs`.
- **`:app`** — the existing Android application module, slimmed down to an entry point plus Android-only integrations (Room driver, TTS/dual-headset audio routing, speech input, Wear OS).
- **`:webApp`** — new Kotlin/Wasm browser application module; entry point plus web implementations (Web Speech API, `localStorage` history).

Goals:
- Web build with the core experience: translate (Google + Gemini "AI Enhanced"), round-trip confidence check, Gemini cultural validation, language pickers, history/saved tabs, text-to-speech.
- Android app keeps 100% of current behavior (Wear sync, dual-headset routing, voice input, Room history).
- One shared Compose UI — no duplicated screens.

Non-goals (explicitly out of scope):
- iOS/desktop targets (nothing in the design blocks adding them later).
- Feature changes or redesigns; this is a port + refactor.
- Backend/proxy work. Both endpoints were verified (2026-07-13) to allow direct browser calls via CORS:
  - `translate.googleapis.com/translate_a/single` → `access-control-allow-origin: *`
  - `generativelanguage.googleapis.com` → answers `OPTIONS` preflight correctly for `POST` + `content-type`.

## 2. Current-State Analysis

Single `:app` module, all Android-specific. Key inventory (LOC as of `1a69c0e`):

| File | LOC | Portability blockers for common code |
|---|---|---|
| `MainActivity.kt` | 1867 | Monolith: activity + ALL screens/dialogs + voice-input launcher + Wear side-effect. `RecognizerIntent`, `Toast`, `Log` |
| `ui/viewmodel/TranslateViewModel.kt` | 412 | `AndroidViewModel(application)` (only used to reach Room), `android.util.Log`, direct DAO + `org.json` use |
| `network/TranslationService.kt` | 234 | OkHttp (JVM-only), `org.json` (JVM/Android-only) |
| `DualHeadsetSetupDialog.kt` | 230 | `AudioDeviceInfo`, `AudioManager` |
| `speech/TTSHelper.kt` | 116 | `TextToSpeech`, `MediaPlayer.setPreferredDevice` (stays Android) |
| `wear/WearSyncManager.kt` | 106 | Play Services Wearable (stays Android) |
| `wear/WearTranslationService.kt` | 76 | `WearableListenerService` (stays Android, declared in manifest) |
| `model/Language.kt` | 35 | `java.util.Locale` (JVM-only) |
| `data/*` (entity/DAO/database) | 76 | Room — KMP supports android/jvm/native but **not wasmJs** |
| `ui/theme/*` | 128 | `dynamicColorScheme` + `Build.VERSION` (Android-only; currently disabled by default) |

Other findings that shape the architecture:

- **Duplication**: the translate→round-trip→validate pipeline and its validation-JSON parsing exist twice (`triggerTranslation` and `prepareConversationPart`). Consolidation is a prerequisite refactor, not an afterthought.
- **Dead dependencies**: Retrofit, converter-moshi, Moshi (+ KSP codegen), logging-interceptor are declared but unused. They get removed when OkHttp/org.json are replaced.
- **Secrets**: `BuildConfig.GEMINI_API_KEY` via the (Android-only) Secrets Gradle Plugin reading `.env`/`.env.example`. Needs a KMP-compatible replacement.
- **No Gradle wrapper committed**; AGP 9.1.1 requires Gradle ≥ 9.3.1 (verified locally: 9.1.0 fails, 9.3.1 builds). Committing a wrapper is Phase 0.
- **Tests**: 2 trivial unit tests + Roborazzi reference (`app/src/test/screenshots/`). Roborazzi baselines become the regression net for the UI decomposition.

## 3. Target Module Architecture

```
unlost-in-translation-mobile/
├── app/                    com.android.application (unchanged identity)
│   └── src/main/           MainActivity (thin), WearTranslationService,
│                           Android service impls, manifest, signing, tests
├── shared/                 KMP library — the heart of the app
│   └── src/
│       ├── commonMain/     UI screens, theme, view models, TranslationApi (Ktor),
│       │                   domain models, repository + platform-service interfaces
│       ├── commonTest/     parser/view-model/domain unit tests (fakes)
│       ├── androidMain/    Room repository, TTS synthesizer, audio catalog,
│       │                   Wear broadcaster, Android AppContainer
│       └── wasmJsMain/     localStorage repository, Web Speech synthesizer,
│                           web AppContainer, capability stubs
└── webApp/                 kotlin("multiplatform") wasmJs executable
    └── src/wasmJsMain/     main.kt (ComposeViewport), index.html, web resources
```

Dependency graph (arrows = "depends on"):

```
:app ──────► :shared ◄────── :webApp
(android)   (android+wasmJs)   (wasmJs)
```

### Why three modules instead of one `:composeApp` (JetBrains wizard default)

Considered and rejected: renaming `:app` into a single KMP `composeApp` module holding all targets.

- AGP 9's blessed KMP integration is the **library** plugin (`com.android.kotlin.multiplatform.library`); combining `com.android.application` with `kotlin("multiplatform")` on AGP 9.1.1 is the riskiest compatibility surface in this migration. Keeping `:app` a plain Android application module sidesteps it entirely.
- `:app` keeps its working config untouched: signing (release env vars + debug keystore), Secrets/BuildConfig handling during transition, Robolectric/Roborazzi test setup, application ID, manifest services.
- `:webApp` stays free of AGP completely.
- Cost: two extra `build.gradle.kts` files. Accepted.

Fallback if `com.android.kotlin.multiplatform.library` misbehaves on AGP 9.1.1: use `com.android.library` + `androidTarget()` in `:shared` (legacy but still functional path).

## 4. Platform Capability Matrix

Feature gating is data-driven via a `PlatformCapabilities` value exposed to shared UI (no `expect fun isAndroid()` branching in screens).

| Feature | Android | Web (wasmJs) |
|---|---|---|
| Google translate (free endpoint) | ✔ Ktor/OkHttp engine | ✔ Ktor/Js engine (CORS verified) |
| Gemini translate + cultural validation | ✔ | ✔ (key baked into bundle — see §6 security note) |
| Round-trip confidence check | ✔ | ✔ |
| History + starred (persistent) | ✔ Room (unchanged DB) | ✔ `localStorage` (JSON) |
| Text-to-speech | ✔ `TextToSpeech` | ✔ `window.speechSynthesis` |
| Voice input | ✔ `RecognizerIntent` | ◐ `SpeechRecognition` API — Chromium-only, capability-gated (Phase 4) |
| Conversation mode (split screen, drafts) | ✔ | ✔ (same shared UI) |
| Dual-headset audio routing | ✔ | ✘ hidden (no per-device audio routing on web) |
| Wear OS sync + notifications | ✔ | ✘ no-op |

## 5. Technology Decisions

| # | Decision | Choice | Rationale / alternatives |
|---|---|---|---|
| D1 | Module layout | `:shared` + `:app` + `:webApp` | See §3. Alt: single `composeApp` (rejected: AGP 9 app+KMP risk, config churn) |
| D2 | HTTP client | **Ktor 3** (engine: OkHttp on Android, Js on wasm) | OkHttp is JVM-only. Ktor is the KMP standard, supports wasmJs, has `HttpTimeout` to reproduce current 30s/60s timeouts. Alt: expect/actual raw clients (rejected: duplicated logic) |
| D3 | JSON | **kotlinx.serialization** | `org.json` is JVM/Android-only. Google's nested-array response parsed via `JsonElement`; Gemini via `@Serializable` DTOs. Moshi is removed, not migrated to (JVM-only) |
| D4 | Persistence | Room stays (androidMain impl); **`localStorage`** impl on web; both behind `TranslationHistoryRepository` | Room does not support wasmJs. `localStorage` is sync, simple, ~5MB (plenty). Alt: IndexedDB (rejected for now: async ceremony, YAGNI), in-memory (rejected: loses history on refresh) |
| D5 | View models | `androidx.lifecycle.ViewModel` **multiplatform** artifacts (`org.jetbrains.androidx.lifecycle:lifecycle-viewmodel(-compose)`) in commonMain | Official KMP support since lifecycle 2.8; `viewModelScope` + `viewModel { }` factory work in common code. `AndroidViewModel` is dropped (it was only a Room shortcut) |
| D6 | Secrets | Small **custom Gradle codegen task** in `:shared`: reads `.env` (fallback `.env.example`) → generates `Secrets.kt` into a generated commonMain source dir. Replaces the Android-only Secrets plugin | Alt: BuildKonfig plugin (works, but adds a third-party plugin for ~30 lines of Gradle). Existing `.env` contract and placeholder-detection logic (`MY_GEMINI_API_KEY`) preserved |
| D7 | DI | Manual constructor injection through one `AppContainer` per platform | App has ~6 injectables. Koin/kotlin-inject rejected: YAGNI |
| D8 | Navigation | Keep the existing `activeBottomTab: String` state | 3 flat tabs + dialogs; Navigation library rejected: YAGNI |
| D9 | Locale handling | `Language.locale: java.util.Locale` → `Language.languageTag: String` (BCP-47); platforms convert at the edge (`Locale.forLanguageTag` on Android, raw tag on web) | Only JVM-free representation that both TTS stacks accept |
| D10 | Logging | Tiny `AppLog` expect/actual (Logcat / `console`) | `android.util.Log` appears throughout shared-bound code |
| D11 | Error handling | Keep exceptions + try/catch per current convention | Result-type migration rejected: churn without new capability |

## 6. Toolchain & Versions

Pinned today / to be added to `gradle/libs.versions.toml`:

| Component | Version | Note |
|---|---|---|
| Gradle wrapper | **9.3.1** | AGP 9.1.1 minimum; commit wrapper in Phase 0 |
| AGP | 9.1.1 | unchanged |
| Kotlin | 2.2.10 → **verify** | CMP release may require a newer 2.2.x; bump Kotlin + `kotlin.plugin.compose` together |
| Compose Multiplatform (`org.jetbrains.compose`) | latest stable 1.9.x → **verify matrix** | replaces androidx Compose BOM for `:shared`; `:app` keeps androidx activity-compose for the host activity |
| Ktor | 3.x latest | `ktor-client-core` (common), `-okhttp` (android), `-js` (wasm), `content-negotiation` optional (manual JSON is fine) |
| kotlinx-serialization | 1.9.x | plugin + `-json` artifact |
| kotlinx-browser | latest | wasm DOM/`localStorage`/Web Speech interop |
| JetBrains lifecycle (KMP) | 2.9.x | viewmodel + viewmodel-compose |
| Room | 2.7.0 | unchanged, moves to `:shared` androidMain (`kspAndroid` config) |
| Removed | — | Retrofit, converter-moshi, Moshi + codegen, logging-interceptor, Secrets Gradle Plugin |

**Version-matrix verification is the first task of Phase 2** (CMP ↔ Kotlin ↔ AGP compatibility changes quarterly; do not trust this table blindly at build time).

Browser floor (WasmGC required): Chrome/Edge 119+, Firefox 120+, Safari 18.2+. Older browsers get a blank canvas — an unsupported-browser banner in `index.html` is part of the web design.

## 7. Open Questions / Decision Points for Build Time

1. Exact CMP + Kotlin version pair (verify official compatibility matrix; prefer latest stable CMP).
2. `com.android.kotlin.multiplatform.library` behavior on AGP 9.1.1 with Room/KSP — fallback documented in §3.
3. Flag emoji (🇺🇸 …) rendering on the wasm canvas — may need a bundled emoji font or plain-text language codes on web (see risks in 03-implementation-plan.md).
4. Whether to publish the web app (e.g., GitHub Pages via `wasmJsBrowserDistribution`) — optional Phase 4 add-on, needs owner sign-off because the Gemini key ships in the bundle.

**Security note (D6/Gemini):** any API key compiled into the web bundle is extractable by visitors. Acceptable for a personal/demo deployment; a public production deployment would need a small proxy holding the key server-side (out of scope, noted for the future).
