# Full KMP Restructure — Design

Detailed design for the architecture in [01-architecture.md](01-architecture.md). Kotlin signatures below are **design contracts**, not implementation; names/packages are binding unless a build-time constraint forces a change (record deviations here).

## 1. Target Source Layout

```
shared/src/commonMain/kotlin/com/example/
├── model/
│   ├── Language.kt              # languageTag: String replaces java.util.Locale
│   └── Translation.kt           # domain model (replaces direct TranslationEntity use in UI/VM)
├── network/
│   ├── TranslationApi.kt        # interface
│   ├── KtorTranslationApi.kt    # shared impl (Ktor client injected per platform)
│   ├── GoogleResponseParser.kt  # nested-array JSON → String
│   └── GeminiDtos.kt            # @Serializable request/response + ValidationResult parsing
├── data/
│   └── TranslationHistoryRepository.kt   # interface only (impls are per-platform)
├── platform/
│   ├── SpeechSynthesizer.kt     # interface
│   ├── VoiceInput.kt            # interface
│   ├── AudioOutputCatalog.kt    # interface + AudioOutputDevice model
│   ├── TranslationBroadcaster.kt# interface (Wear sync abstraction)
│   ├── PlatformCapabilities.kt  # data class
│   └── AppLog.kt                # expect object
├── di/
│   └── AppContainer.kt          # interface bundling all of the above
└── ui/
    ├── App.kt                   # root composable: App(container: AppContainer)
    ├── theme/                   # Color.kt, Theme.kt, Type.kt (ported, no dynamic color)
    ├── viewmodel/
    │   ├── TranslateViewModel.kt
    │   └── DualHeadsetModel.kt
    ├── screens/
    │   ├── TranslateScreen.kt   # input card, result card, validation banner
    │   ├── HistoryScreen.kt     # history + saved lists (parameterized by tab)
    │   └── ConversationScreen.kt# split-screen panel
    └── components/
        ├── AppTopBar.kt         # title, headset icon, engine toggle
        ├── LanguageBar.kt       # pickers + swap
        ├── BottomNavBar.kt      # 3 tabs + mic FAB row
        ├── LanguagePickerDialog.kt
        ├── ConversationDraftDialog.kt
        ├── DualHeadsetSetupDialog.kt   # consumes AudioOutputCatalog abstraction
        └── ValidationDetailsSheet.kt

shared/src/androidMain/kotlin/com/example/
├── data/RoomHistoryRepository.kt + room/ (entity, dao, database — moved from :app)
├── platform/ (AndroidSpeechSynthesizer, AndroidAudioOutputCatalog,
│              WearTranslationBroadcaster, AppLog actual)
└── di/AndroidAppContainer.kt    # factory: fun createAppContainer(context: Context)

shared/src/wasmJsMain/kotlin/com/example/
├── data/LocalStorageHistoryRepository.kt
├── platform/ (WebSpeechSynthesizer, WebVoiceInput [Phase 4], AppLog actual)
└── di/WebAppContainer.kt        # factory: fun createAppContainer()

app/src/main/  (remains, shrinks)
├── MainActivity.kt              # ~60 lines: container + voice-input bridge + setContent { App(...) }
├── speech/AndroidVoiceInput.kt  # RecognizerIntent launcher bridge (needs Activity)
└── wear/WearTranslationService.kt  # unchanged manifest service; calls :shared TranslationApi

webApp/src/wasmJsMain/
├── kotlin/main.kt               # ComposeViewport(document.body) { App(createAppContainer()) }
└── resources/index.html         # canvas host + unsupported-browser banner
```

> **Layout deviations (recorded per the design-contract rule):**
> - There is no `di/` package. `AppContainer` (+ `PlatformCapabilities`, `PlatformActions`)
>   lives in `com.example.platform` alongside the other seams, and `WebAppContainer`
>   in `shared/wasmJsMain/.../platform/`.
> - `AndroidAppContainer` lives in **`:app`** (`app/src/main/.../platform/`), not
>   `shared/androidMain`: it wires `WearTranslationBroadcaster`, which references
>   `MainActivity`/`R` from `:app` and cannot move into `:shared` without a further
>   notification abstraction (deferred).
> - `DualHeadsetEntry` (the gated headset icon + dialog opener shared by the top bar
>   and the conversation header) lives in `components/DualHeadsetSetupDialog.kt`
>   rather than a separate file.

## 2. Domain Models (commonMain)

```kotlin
data class Language(
    val code: String,          // "en" — translate endpoints
    val name: String,          // "English" — Gemini prompts + UI
    val flag: String,          // "🇺🇸" (see web emoji risk)
    val languageTag: String,   // "en-US" — BCP-47, for TTS / voice input
) { companion object { val supportedLanguages: List<Language>; fun getByCode(code: String): Language } }
// Same 16 languages; tags: en-US es-ES fr-FR de-DE zh-CN ja-JP ko-KR it-IT pt-PT ru-RU hi-IN ar-SA ro-RO nl-NL tr-TR th-TH
// Android converts at the edge: Locale.forLanguageTag(language.languageTag)

data class Translation(          // domain mirror of today's TranslationEntity
    val id: Long = 0,
    val sourceText: String, val translatedText: String,
    val sourceLangCode: String, val sourceLangName: String,
    val targetLangCode: String, val targetLangName: String,
    val timestamp: Long, val isStarred: Boolean = false,
)
```

`TranslationEntity` (Room) stays as a private mapping detail inside `RoomHistoryRepository` (androidMain). Table/DB names unchanged → existing user data survives the refactor.

## 3. Interfaces (commonMain)

```kotlin
interface TranslationApi {
    suspend fun translateGoogle(text: String, sourceLang: String, targetLang: String): String
    suspend fun translateGemini(text: String, sourceLangName: String, targetLangName: String): String
    suspend fun validateTranslation(originalText: String, translatedText: String,
                                    sourceLangName: String, targetLangName: String): ValidationResult
}
data class ValidationResult(val isWarning: Boolean, val explanation: String, val suggestions: List<Suggestion>)
```

Key change vs today: `validateTranslation` returns a **parsed** `ValidationResult`, not a `JSONObject`. The lenient parsing rules move from the ViewModel into `GeminiDtos.kt` and are kept bug-for-bug: `optBoolean/optString` defaults, object-or-plain-string suggestion entries, blank-text filtering, markdown ```-fence stripping. This deletes the duplicated parsing blocks in `triggerTranslation` and `prepareConversationPart`.

> **Deviation (web-feature port, recorded):** `TranslationApi` grew past the
> three methods above — `detectAndTranslateGoogle`, `detectAndTranslateGemini`,
> `translateImage` and `rewriteMessage` — and the two Gemini methods take a
> defaulted `context: TranslationContext` (tone, situation, prior turns).
> `ValidationResult` gained defaulted `sanityCheck` and `idiomExplanation`.
> `translateGoogle`'s signature is unchanged on purpose: the §10 MockEngine
> suite pins it, and detection needs a different return type. `GeminiPart`
> became text-or-`inlineData` for the image call, with the serializer emitting
> only the field that is set so text-only requests stay byte-identical. See
> `features/web-feature-port/`.

```kotlin
interface TranslationHistoryRepository {
    // Actual method names (deviation, recorded): allTranslations() / starredTranslations()
    // / clearHistory() instead of observeAll() / observeStarred() / clearAll().
    fun allTranslations(): Flow<List<Translation>>  // timestamp DESC
    fun starredTranslations(): Flow<List<Translation>>
    suspend fun insert(translation: Translation): Long
    suspend fun setStarred(id: Long, isStarred: Boolean)
    suspend fun deleteById(id: Long)
    suspend fun clearHistory()                      // current semantics: deletes starred too
}

interface SpeechSynthesizer {                       // absorbs TTSHelper's public surface
    fun speak(text: String, languageTag: String, outputDeviceId: Int? = null)
    fun stop(); fun release()
}
interface VoiceInput {                              // null = cancelled/unavailable
    suspend fun recognize(languageTag: String, prompt: String): String?
}
// Deviation (Phase 3, recorded): the live voice-input path is a
// `launchVoiceInput(isConversationA: Boolean?, language: Language)` lambda that the
// platform entry point passes into App() — Android backs it with the
// RecognizerIntent launcher in MainActivity (its `container.voiceInput` stays
// `UnavailableVoiceInput`; the activity-result launcher cannot live behind this
// suspend seam without Activity plumbing).
// Phase 4 (recorded): the web entry point's lambda now routes through
// `container.voiceInput` — `WebVoiceInput` (SpeechRecognition, one-shot, aborts
// on coroutine cancellation) when `speechRecognitionAvailable()` (Chromium AND
// window.isSecureContext — insecure origins keep the constructor but recognition
// can never start), `UnavailableVoiceInput` otherwise, with
// `capabilities.voiceInput` set from the same probe. `prompt` is surfaced as a
// snackbar on web — there is no system "Speak now" dialog for it to appear in —
// and the prompt string is shared via `TranslateViewModel.voiceInputPrompt`.
// Result routing is shared: both entry points call
// `TranslateViewModel.onVoiceInputResult(spokenText, isConversationA)`
// (main mic → setSourceText + triggerTranslation; conversation →
// prepareConversationPart; blank/null ignored). Hard recognition failures
// (blocked mic, no network, unsupported language) throw `VoiceInputException`
// with a user-displayable message that the web entry point snackbars —
// Android-parity with MainActivity's catch; "no-speech"/"aborted" stay null
// (dialog-cancel equivalents). The web runs one recognition at a time: a new
// mic tap cancels the in-flight job, which aborts the browser session.
interface AudioOutputCatalog {                      // dual-headset support
    fun listOutputDevices(): List<AudioOutputDevice>          // web: emptyList()
}
data class AudioOutputDevice(val id: Int, val label: String, val isBluetooth: Boolean)

interface TranslationBroadcaster {                  // Wear abstraction; web: no-op object
    fun broadcast(sourceText: String, translatedText: String, source: Language, target: Language)
}
interface PlatformActions {                         // clipboard + share (Phase 3 addition)
    fun copyToClipboard(text: String)               // android: ClipboardManager; web: navigator.clipboard
    fun share(text: String)                         // android: ACTION_SEND chooser; web: navigator.share → clipboard fallback
}
data class PlatformCapabilities(
    val voiceInput: Boolean,        // android: true; web: SpeechRecognition feature-detect (secure context)
    val dualHeadset: Boolean,       // android: true; web: false → hides headset icon + dialog
    val wearSync: Boolean,          // android: true; web: false
    val extendedGlyphs: Boolean,    // android: true; web: false → language codes instead of flag emoji (Phase 4 task 2)
)

interface AppContainer {
    val api: TranslationApi
    val historyRepository: TranslationHistoryRepository
    val speechSynthesizer: SpeechSynthesizer
    val voiceInput: VoiceInput
    val audioOutputCatalog: AudioOutputCatalog
    val broadcaster: TranslationBroadcaster
    val log: AppLog                 // §D10 logging seam
    val platformActions: PlatformActions   // Phase 3 addition (clipboard + share)
    val capabilities: PlatformCapabilities
}
```

> **Deviation (web-feature port, recorded):** `AppContainer` gained
> `imagePicker: ImagePicker` and `PlatformCapabilities` gained
> `imageInput: Boolean` (defaulted false) for camera/gallery OCR translation.
> The seam is shaped exactly like voice input, including the part where Android
> binds `UnavailableImagePicker` because the live path has to be
> Activity-scoped result launchers — so `App()` also gained a
> `launchImageInput: (ImageSource) -> Unit` callback, defaulted to a no-op.

## 4. ViewModel Refactor

`TranslateViewModel` becomes a plain multiplatform `ViewModel` with constructor injection:

```kotlin
class TranslateViewModel(
    private val api: TranslationApi,
    private val repository: TranslationHistoryRepository,
    private val broadcaster: TranslationBroadcaster,
    private val log: AppLog,   // deviation: see note below
) : ViewModel()
```

> **Deviation (Phase 3, recorded per the design-contract rule):** the constructor
> gained a `log: AppLog` parameter. The original `android.util.Log` calls in the
> VM can't cross into commonMain, so the VM logs through the design §3 `AppLog`
> seam, injected from `AppContainer.log`. No behavior change — the same tags and
> messages, forwarded to Logcat on Android and the console on web.

- All existing `StateFlow`s, states (`TranslationState`, `ValidationState`, `ConversationDraft`, `ConversationItem`) and public methods keep their names and semantics.
- **Wear side-effect moves here** from `MainActivity`'s `LaunchedEffect(translationState)`: after a successful primary translation, the VM calls `broadcaster.broadcast(...)`. UI no longer owns platform side-effects (SRP); web build injects a no-op.
- **Pipeline consolidation (DRY)**: one private helper implements translate → persist? → parallel(round-trip, validation) with callbacks for state delivery; `triggerTranslation` (persists, updates main-screen flows) and `prepareConversationPart` (no persist, updates draft flow) both call it. Behavior preserved, including the round-trip staleness guard (`currentState.translatedText == translated`).
  - *Deviation (recorded Phase 5)*: consolidation happened only partially — `translatePrimary` and `runValidation` are shared helpers, but the parallel-launch orchestration is still written out in both `triggerTranslation` and `prepareConversationPart`, and the two copies differ deliberately (only the main path persists/broadcasts and guards round-trip staleness; the draft path targets the draft flow). Full consolidation remains a behavior-preserving refactor to commission separately; the §10 view-model tests now pin both pipelines' behavior for whoever does it.
  - *Narrowed (web-feature port)*: the main path's tail — publish, persist, broadcast, then parallel round-trip and validation — is now the shared `publishTranslation`, which the image entry point (`onImageInputResult`) reuses instead of adding a third copy. `prepareConversationPart` still has its own orchestration, so the deviation above stands.
- Construction in shared UI: `viewModel { TranslateViewModel(container.api, container.historyRepository, container.broadcaster, container.log) }` (CMP lifecycle factory lambda — works on both targets).
- Defect fixed (Phase 5, recorded): the §10 "language swap-on-conflict" test exposed that `setSourceLanguage`/`setTargetLanguage` assigned the new language *before* swapping, so picking the current target as the new source swapped two equal values and collapsed both pickers to the same language. Present verbatim since the initial AI-Studio import (all phases ported it faithfully); fixed to swap before assigning, matching this design's intended semantics.
- `System.currentTimeMillis()` (entity timestamp, `ConversationItem`) → `kotlin.time.Clock.System.now().toEpochMilliseconds()` or `TimeSource` equivalent available in common.

## 5. Networking Design (Ktor)

- One `HttpClient` per `KtorTranslationApi`, engine injected: OkHttp (Android) / Js (wasm). Timeouts via `HttpTimeout` plugin per-request: 30s Google, 60s Gemini (mirrors the two OkHttp clients today).
- `expectSuccess = false`; non-2xx handling replicates current messages ("HTTP error code: …", "Gemini server returned error: … - body").
- Google response `[[["Hola",...],...],...]` parsed with `Json.parseToJsonElement` → `GoogleResponseParser.extract(...)`: concatenates element 0 of each part of group 0, skipping empty parts — identical to the current loop. URL-encoding via Ktor's `URLBuilder`/`parameters` (replaces `URLEncoder`).
- Gemini request DTOs: `contents[].parts[].text`, `safetySettings` (4 categories, `BLOCK_NONE`), `generationConfig.responseMimeType = "application/json"` for validation only. Model id `gemini-3.5-flash` and the two prompts are copied verbatim (they are product behavior).
- API-key guard preserved: blank or `MY_GEMINI_API_KEY` → throw "Gemini API key is not configured…" before any request.
- `User-Agent: Mozilla/5.0` header: kept on Android; on wasm the browser forbids setting it — the design accepts the browser's own UA (endpoint verified to work with it).
- Error contract (Phase 4 task 3, recorded): every `TranslationApi` failure surfaces as an `Exception` — an `apiCall` wrapper in `KtorTranslationApi` normalizes non-Exception `Throwable`s, because the wasm Js engine throws `kotlin.Error` ("Fail to fetch") when offline, which escaped the VM's `catch (e: Exception)` and stranded the UI on the loading state. Exceptions (incl. cancellation) pass through unchanged, keeping the verbatim messages above; regression test in `KtorTranslationApiTest`.
- Gemini resilience (issue #3, recorded 2026-07-18): both Gemini calls share one execution path (`postGemini`). Per model, up to 3 attempts with 500ms→1s exponential backoff, retrying 5xx, burst 429s, and network I/O failures; a `RESOURCE_EXHAUSTED` 429 — the free tier's per-day, **per-model** quota (measured live 2026-07-17) — is never retried. Model fallback `gemini-3.5-flash` → `gemini-3-flash-preview` (issue #3 names `gemini-3-flash`, an id that does not exist in the live API — corrected to the model's actual id, verified live 2026-07-18) when the primary's final failure is a 404 (unknown model id: advances immediately, no retries), any 429 (the sibling has its own allowance), any 5xx, or an attempt budget spent on network failures; plain client errors (400/401/403) neither retry nor fall back. Error copy — supersedes the verbatim "Gemini server returned error…" replication above: exhausted quota → the friendly quota sentence (`GEMINI_QUOTA_MESSAGE`); otherwise `Gemini unavailable (503): <msg>` / `Gemini error (<code>): <msg>` with Google's own `error.message` extracted by `GeminiErrorParser` (raw body truncated to 200 chars when the shape is foreign). Attempt trail logged through the optional `AppLog` constructor seam (wired by both containers; the Wear service stays log-less — Google path only). No jitter, deliberately: deterministic backoff keeps the MockEngine tests exact.

## 6. Secrets (D6)

Gradle task in `:shared` (registered as a commonMain generated source dir, wired into `compileKotlinMetadata`/source-set model):

- Reads root `.env`, falling back to `.env.example` (same contract as today; `.env` stays gitignored and is **never** read by tooling other than Gradle).
- Generates `internal object Secrets { const val GEMINI_API_KEY: String = "…" }`.
- Android `BuildConfig.GEMINI_API_KEY` usage and the Secrets Gradle Plugin are removed with it.
- Placeholder value keeps working: Gemini paths throw the friendly "not configured" error; Google path unaffected.

## 7. Storage on Web

`LocalStorageHistoryRepository` (wasmJsMain):

- Single `localStorage` key `translation_history_v1` holding a JSON array of `Translation` (kotlinx.serialization).
- In-memory `MutableStateFlow<List<Translation>>` as source of truth for `observeAll/observeStarred` (mapped with the same ordering/filtering as the Room queries); every mutation rewrites the key. Loaded once at construction; corrupt/absent data → empty list (log via `AppLog`, don't crash).
- `id` assignment: max(existing)+1. Single-tab app; cross-tab sync out of scope (documented limitation).

## 8. UI Decomposition Map

`MainActivity.kt` (1867 lines) is split with **zero visual/behavioral change** (Roborazzi-verified). One exception, recorded: the Kotlin 2.2.10→2.3.20 bump (Phase 3 toolchain, required by CMP 1.11 on wasm) changed text rendering imperceptibly, so the baseline was re-recorded once at that commit; every later UI move is verified byte-identical against the re-recorded baseline. Current line ranges → target files:

| Current (MainActivity.kt) | Lines | Target (shared/ui/) |
|---|---|---|
| Activity shell, `setContent` | 68–82 | stays `:app` MainActivity.kt |
| Voice launcher + `launchVoiceInput` | 124–169 | `:app` `AndroidVoiceInput` (behind `VoiceInput`); the `pendingVoiceRequestIsA` routing state moves into shared callers |
| Wear-sync `LaunchedEffect` | 171–190 | deleted → VM `broadcaster` call (§4) |
| Conversation split-screen panel | 202–~386 | `screens/ConversationScreen.kt` |
| Translate screen (input/result/validation cards) | 387–~974 | `screens/TranslateScreen.kt` |
| History/Saved list ("RECENT/SAVED TRANSLATIONS") | 975–~1144 | `screens/HistoryScreen.kt` |
| Bottom bar (mic FAB row + 3 tabs) | 1145–1321 | `components/BottomNavBar.kt` (the 3 near-identical tab items collapse into one `TabItem` composable — DRY) |
| Language picker dialogs invocation | 1323–1347 | stays in `App.kt` scaffold state |
| Validation details overlay | 1349–1500 | `components/ValidationDetailsSheet.kt` |
| `LanguagePickerDialog` | 1503–1607 | `components/LanguagePickerDialog.kt` |
| `ConversationDraftDialog` | 1609–1867 | `components/ConversationDraftDialog.kt` |
| `DualHeadsetSetupDialog.kt` (file) | 1–230 | `components/DualHeadsetSetupDialog.kt`, rewritten against `AudioOutputCatalog` |

Shared-UI substitutions required by the move to commonMain:

- `Toast.makeText(...)` → Material3 `SnackbarHostState` (a `LaunchedEffect`-driven host already fits the scaffold). Affects voice-input status/error toasts **and** the in-screen status toasts that moved to commonMain — "Added to star history.", "Copied translation!", "Deleted from history", and the conversation "Playing translation to …'s headset…" — all now route through `viewModel.showMessage` → the shared snackbar. (`ClipboardManager`/share `Intent` are also platform-side now, behind the §3 `PlatformActions` seam; see below.)
- `android.util.Log` → `AppLog`.
- `LocalContext`-dependent bits stay platform-side behind the §3 interfaces.
- Theme: port static light/dark schemes as-is; drop the (default-off) `dynamicColor` branch — Android can layer it back later via a wrapper in `:app` (YAGNI now).
- `Icons.Default.*` / extended icons: CMP ships `material-icons-extended` for common code — imports unchanged.

Capability gating in UI: headset icon + dual-headset dialog render only if `capabilities.dualHeadset`; mic buttons only if `capabilities.voiceInput`. When `voiceInput` is false, the conversation deck swaps each speaker's mic FAB for a typed-input field feeding the same `prepareConversationPart` draft flow (Phase 3 MVP acceptance: "conversation mode works via typed input").

Flag-emoji decision (Phase 4 task 2, owner-approved): web shows uppercase language codes instead of flag emoji — the wasm canvas ships no emoji font, so flags drew as boxes (Phase 3 finding). Mechanism (data-driven per the architecture's gating rule — no expect/actual branching): `PlatformCapabilities.extendedGlyphs` (Android true / web false), threaded like the other capability flags into LanguageBar, LanguagePickerDialog, HistoryScreen, and ConversationScreen, feeding `Language.flagLabel(extendedGlyphs)`. The history row's "➔" dingbat has the same coverage gap — and so does "→" U+2192 (verified empirically) — so web uses ASCII "->", deliberately local to HistoryScreen (single call site; promote it next to `flagLabel` only if a second screen needs a direction glyph). Android rendering is untouched (Roborazzi-gated). Wear notification code in :app keeps raw `flag`/"➔" deliberately — it is Android-only. Bundling an emoji font subset was rejected for now (~100KB + unverified COLR support in skiko/CMP-wasm); revisit only if product wants real flags on web.

**Deviation (i18n, PRs #24–#26):** UI text is no longer inline in the composables. It lives in `ui/i18n/Strings.kt` — a data class whose fields default to the English wording — and reaches screens through a `LocalStrings` CompositionLocal, with 15 locales in `ui/i18n/locales/`. This adds a data-driven axis alongside `PlatformCapabilities` but needs **no new capability flag and no container member**: the locale comes from `androidx.compose.ui.text.intl.Locale.current`, which is already multiplatform, so the platform seam this would normally require does not exist. `TranslateViewModel` takes the catalogue as a constructor argument instead (it has no composable scope) and exposes it publicly, because the platform entry points render text outside the composition. Two behaviours follow the device language rather than the app's selected language — a deliberate deviation from the web build, which keys its UI off `sourceLanguage`; that source can be auto-detect here, which has no strings, and users swap it constantly. `Preset` lost its `label` field: prompt ids stay in `model/`, visible labels live only in the catalogue. Full record: `features/web-feature-port/03-i18n.md`. The glyph constraint above now also applies to translated strings, and a test enforces it across every locale.

## 9. Entry Points

- **Android**: `MainActivity.onCreate` builds `createAppContainer(context)` (androidMain) + `AndroidVoiceInput(this)` (activity-result bridge, provided into the container), then `setContent { App(container) }`. `WearTranslationService` continues to be manifest-declared in `:app` and calls the shared `TranslationApi` directly (Google path only, as today).
- **Web**: `main.kt` → `ComposeViewport(document.body) { App(createAppContainer()) }`. `index.html`: title "Translate", full-viewport canvas styling, `<noscript>`/WasmGC-unsupported banner, loads the generated `.js`.

## 10. Testing Design

| Suite | Location | Coverage |
|---|---|---|
| commonTest | `:shared` | `GoogleResponseParser` (nested arrays, empty parts, multi-sentence); Gemini validation parsing (fenced/unfenced JSON, string-vs-object suggestions, blank filtering); `Language.getByCode` fallback; `TranslateViewModel` happy path + language swap-on-conflict + round-trip staleness guard + conversation draft confirm/cancel, using `FakeTranslationApi`/`FakeHistoryRepository`/`RecordingBroadcaster` + `kotlinx-coroutines-test` |
| androidUnitTest | `:app` | existing Robolectric + Roborazzi tests (imports updated); baselines recorded in Phase 0 must still pass after decomposition |
| wasmJsTest | `:shared` | `LocalStorageHistoryRepository` round-trip + corrupt-data recovery (runs in headless browser via Kotlin test infra) |
| Manual | both | emulator smoke (translate, history, star, conversation); `:webApp:wasmJsBrowserDevelopmentRun` smoke in Chrome |

Fakes live in `shared/src/commonTest/.../fakes/` and are the reusable seam for all future VM tests.

## 11. Production Web Bundle (Phase 4 task 4, measured)

`./gradlew :webApp:wasmJsBrowserDistribution` → `webApp/build/dist/wasmJs/productionExecutable/`. Measured 2026-07-16 (Kotlin 2.3.20 / CMP 1.11.1, binaryen-optimized release):

| Artifact | Raw | Gzip (what the wire pays) |
|---|---|---|
| app `.wasm` (Kotlin + Compose code) | 8.65 MB | 3.33 MB |
| skiko `.wasm` (Skia runtime) | 4.36 MB | 1.28 MB |
| `webApp.js` loader | 539 KB | 102 KB |
| `index.html` | 2.2 KB | 1.0 KB |
| **First-load transfer (gzip)** | | **≈ 4.7 MB** |

The 1.5 MB `webApp.js.map` ships in the dist but is only fetched by DevTools. DCE is effective: the dev app `.wasm` is 26.7 MB vs 8.65 MB in release, and unused `material-icons-extended` icons are stripped by the wasm DCE (keeping the dependency costs bundle size only for icons actually referenced). Verified: the production dist served statically boots in headless Chrome and translates end-to-end (screenshot smoke). Serving requires correct `application/wasm` MIME and benefits from gzip/brotli — GitHub Pages provides both.
