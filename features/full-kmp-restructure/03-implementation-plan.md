# Full KMP Restructure — Implementation Plan

Execution plan for [01-architecture.md](01-architecture.md) + [02-design.md](02-design.md). Nothing here is implemented yet; each phase is independently shippable and leaves the repo green (builds + tests pass, Android app fully functional).

## 1. Summary

Five phases: (0) lock the toolchain and record UI baselines → (1) refactor inside `:app` until the code is common-ready without any KMP machinery → (2) introduce `:shared` and move code, Android-only → (3) add the wasmJs target and `:webApp`, web MVP running → (4) web parity/polish → (5) tests, docs, cleanup. The risky compatibility work (AGP 9 + KMP + CMP versions) is isolated in Phase 2, after Phase 1 has already paid down all code-level portability debt with the existing build proving no regressions.

## 2. Analysis Findings (drive the plan)

1. `MainActivity.kt` (1867 LOC) is a monolith: activity, all screens, dialogs, voice input, Wear side-effect. Must be decomposed before anything can move.
2. `TranslateViewModel` is `AndroidViewModel` only to reach Room; it also duplicates the full translate→round-trip→validate pipeline and validation-JSON parsing across `triggerTranslation`/`prepareConversationPart`.
3. JVM-only APIs in shared-bound code: OkHttp, `org.json`, `java.util.Locale`, `android.util.Log`, `BuildConfig`, `System.currentTimeMillis`, `Toast`.
4. Room has no wasmJs target → repository abstraction required.
5. Secrets mechanism (Secrets Gradle Plugin → `BuildConfig`) is Android-only.
6. Dead deps: Retrofit, converter-moshi, Moshi (+ KSP codegen), logging-interceptor.
7. No Gradle wrapper; AGP 9.1.1 needs Gradle ≥ 9.3.1 (9.1.0 fails with an explicit version-check error; 9.3.1 verified working locally).
8. CORS verified OK for both endpoints from a browser origin (2026-07-13) — no proxy needed.
9. Only trivial tests exist; Roborazzi is configured with references in `app/src/test/screenshots/` — the natural regression net for the decomposition.

## 3. Proposed Changes (grouped by module)

- **Build/root**: commit wrapper 9.3.1; add KMP/CMP/Ktor/serialization/lifecycle-KMP/kotlinx-browser entries to the version catalog; add `:shared`, `:webApp` to `settings.gradle.kts`; secrets codegen task.
- **`:shared` (new)**: everything in design §1 commonMain/androidMain/wasmJsMain trees.
- **`:app` (shrinks)**: keeps MainActivity (thin), `AndroidVoiceInput`, `WearTranslationService`, manifest, signing, Robolectric/Roborazzi tests; drops moved code and the Secrets plugin.
- **`:webApp` (new)**: wasm entry point + `index.html`.

## 4. Refactoring Opportunities (reuse before new code)

- Pipeline consolidation in the VM (design §4) — removes ~80 duplicated lines and both copies of validation parsing.
- `BottomNavBar` tab items: 3 near-identical 30-line blocks → one `TabItem` composable.
- Validation parsing already written twice → becomes the single `ValidationResult` parser (moves to `network/`, gains tests).
- `TranslationEntity` field set is reused verbatim as the `Translation` domain model; Room schema untouched (user data preserved).
- Existing `.env`/`.env.example` contract and placeholder guard reused by the new secrets codegen.

## 5. New Code Requirements (cannot be obtained by refactoring)

- Web platform impls: `LocalStorageHistoryRepository`, `WebSpeechSynthesizer`, (Phase 4) `WebVoiceInput`, `WebAppContainer`.
- `webApp` entry (`main.kt`, `index.html`, unsupported-browser banner).
- Secrets Gradle codegen task (~30 lines).
- Fakes + new unit tests (commonTest, wasmJsTest).

## 6. Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| AGP 9.1.1 ↔ `com.android.kotlin.multiplatform.library` friction (Room/KSP inside `:shared`) | Medium | Blocks Phase 2 | Fallback: `com.android.library` + `androidTarget()`; second fallback: Room impl stays in `:app`, only the interface in `:shared` |
| CMP ↔ Kotlin 2.2.10 matrix mismatch | Medium | Kotlin bump ripples (KSP, Compose plugin, Robolectric) | Verify matrix first task of Phase 2; bump Kotlin+KSP+compose-plugin as one commit |
| Behavior regressions decomposing 1867-line UI | Medium | User-visible bugs | Phase 0 Roborazzi baselines; decomposition commits are move-only (no logic edits); emulator smoke per phase |
| org.json → kotlinx.serialization parsing drift (nulls, lenient defaults) | Medium | Wrong translations/validation UX | Parser unit tests written against **captured real responses** before the port (Phase 1 task 1.3) |
| Flag emoji not rendering on wasm canvas | High | Cosmetic (language rows/titles) | Phase 3 check; fallback: uppercase language codes on web, or bundle an emoji font (Phase 4) |
| Gemini key extractable from web bundle | Certain | Key abuse if deployed publicly | Ship-decision gate in Phase 4; personal use acceptable; proxy noted as future work |
| Free Google endpoint breaks/rate-limits (unofficial) | Low/ongoing | Core feature down on both platforms | Pre-existing risk, unchanged by this work; error states already surface it |
| `localStorage` limits / multi-tab races | Low | History loss on web | ~5MB ample; single-tab documented; versioned key (`_v1`) allows format evolution |
| Breaking changes for consumers | — | None external | App ID, manifest services, DB schema, release signing all unchanged |

## 7. Implementation Order

Rule for every phase: land as a reviewable PR; `:app:assembleDebug` + all tests green before merge; no phase starts until the previous one is merged.

### Phase 0 — Foundation (S)
1. Commit Gradle wrapper 9.3.1 (`gradle wrapper` from a ≥9.3.1 dist; commit `gradlew*`, `gradle/wrapper/*`).
2. Record Roborazzi baselines for the current main screen states (add a `MainAppScreen` screenshot test if none covers it) — these gate Phase 1.
3. Update CLAUDE.md build section (wrapper now exists).

**Verify:** `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:recordRoborazziDebug`

### Phase 1 — In-place decoupling inside `:app` (L) — no KMP yet
1. **UI decomposition** (move-only, one commit per screen): split `MainActivity.kt` per design §8 map into `ui/screens/` + `ui/components/` (still in `:app`); collapse bottom-bar tab triplication.
2. **`Language.locale` → `languageTag`**; convert at TTS/RecognizerIntent call sites via `Locale.forLanguageTag`.
3. **Capture real endpoint responses** (Google nested-array incl. multi-sentence; Gemini validation fenced + unfenced) as test fixtures; write parser tests against the *current* org.json logic's observed outputs.
4. **Port `TranslationService` → Ktor + kotlinx.serialization** behind new `TranslationApi` interface returning `ValidationResult`; timeouts/messages per design §5; fixtures from 1.3 must pass unchanged. Remove OkHttp direct use, `org.json`, and dead deps (Retrofit/Moshi/logging-interceptor).
5. **ViewModel refactor**: plain `ViewModel` + constructor injection (`api`, `repository`, `broadcaster`); introduce `TranslationHistoryRepository` with Room impl; consolidate the duplicated pipeline; move Wear sync from `LaunchedEffect` into VM; `Toast` → snackbar.
6. **Platform interfaces** (`SpeechSynthesizer`, `VoiceInput`, `AudioOutputCatalog`, `TranslationBroadcaster`, `PlatformCapabilities`, `AppLog`) with Android impls; wire a hand-built `AppContainer` in MainActivity; `DualHeadsetSetupDialog` rewritten against `AudioOutputCatalog`.

**Verify:** `./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug` (baselines unchanged) + emulator smoke: translate, swap-conflict, history/star/delete/clear, conversation draft confirm/cancel, TTS, voice input, dual-headset dialog lists devices.

### Phase 2 — Introduce `:shared`, Android-only (M)
1. Verify CMP/Kotlin/AGP matrix; pin versions in the catalog (bump Kotlin/KSP together if required).
2. Create `:shared` (android target only at first) with `com.android.kotlin.multiplatform.library`; fallbacks per risk table.
3. Move code per design §1: commonMain-bound code first compiled as android source, then flip source sets to commonMain incrementally, fixing JVM-isms the compiler surfaces (`System.currentTimeMillis`, remaining `Log`, etc.).
4. Room + KSP move into `:shared` androidMain (`kspAndroid`); DB name/schema untouched.
5. Secrets codegen task replaces the Secrets plugin; `:app` drops it.
6. `:app` reduced to entry point + Android bridges; depends on `:shared`.

**Verify:** same commands + smoke as Phase 1 — behavior byte-identical; upgrade-in-place install on emulator confirms history survives (`adb install -r`).

### Phase 3 — wasmJs target + `:webApp` MVP (M)
1. Add `wasmJs { browser() }` to `:shared`; make commonMain actually compile for wasm (this is where stragglers surface).
2. wasmJsMain impls: `LocalStorageHistoryRepository`, `WebSpeechSynthesizer`, no-op broadcaster, `capabilities(voiceInput=false, dualHeadset=false, wearSync=false)`.
3. Create `:webApp`: `main.kt` + `index.html` + banner.
4. MVP acceptance in Chrome: translate EN→ES via Google path; round-trip renders; Gemini validation renders (with key) or clean "not configured" error (without); history/saved tabs persist across reload; TTS speaks; conversation mode works via typed input; no mic/headset icons visible.

**Verify:** `./gradlew :webApp:wasmJsBrowserDevelopmentRun` + scripted browser smoke (screenshot-verified); `./gradlew :shared:wasmJsTest`.

### Phase 4 — Web parity & polish (M)
1. `WebVoiceInput` via `SpeechRecognition` (feature-detected; Chromium-only) → `capabilities.voiceInput` true where available.
2. Flag-emoji decision from Phase 3 findings (bundle font vs text codes).
3. Error/empty states pass on web (offline, endpoint failure).
4. Production bundle: `./gradlew :webApp:wasmJsBrowserDistribution`; measure size; document.
5. **Gate:** deployment (e.g., GitHub Pages) only with owner sign-off re: bundled Gemini key.

### Phase 5 — Tests, docs, cleanup (S/M)
1. commonTest suite per design §10 (parsers, VM behaviors with fakes).
2. Delete now-unused catalog entries; `./gradlew :app:dependencies` sanity pass.
3. Rewrite CLAUDE.md (new module map, web run commands, test matrix) + README run-on-web section; note docs deviations back into these feature files.

## 8. Review Checklist (per session planning rules)

- [x] Design principles considered — SOLID (VM injection, interface seams), KISS (manual DI, no nav lib), DRY (pipeline/parsing/tab consolidation), YAGNI (no IndexedDB/Koin/dynamic color/iOS), CoC (KMP source-set layout, version catalog), LoD (UI talks to `AppContainer` interfaces, not platform APIs)
- [x] Existing code reuse maximized (§4; UI/logic ported, not rewritten)
- [x] Changes grouped into logical, reviewable chunks (phases = PRs; move-only commits separated from logic commits)
- [x] Risks and breaking changes identified (§6; no external breaking changes)
- [x] Plan is incremental — the Android app ships from `main` after every phase
