# HANDOFF — read this first

Entry point for anyone (human or agent) picking this repo up cold. Written 2026-08-01.
Everything here was verified against the repo and `gh` on that date, not recalled.

If this file disagrees with the code, **the code is right and this file is stale** — fix the file.

---

## 1. Status right now

| | |
|---|---|
| `main` | `68c6974` — `origin/main` identical, nothing unpushed |
| Working tree | clean |
| In flight | nothing — no open PR, no half-done branch |
| CI | **none**. "Green" always means the local gate suite in §5 |
| Open issues | **#1** (KMP restructure — code complete, owner keeps it open), **#15** (Ray-Ban Display spike — not started) |
| Last work | web→mobile AI feature port, PRs #16–#22, merged 2026-07-27 |

The seven merged feature branches (`feature/web-port-*`, `docs/web-feature-port`) still exist on
`origin`. They are merged; deleting them is housekeeping, not work.

---

## 2. What this project is

Kotlin Multiplatform translation app (Compose Multiplatform, Material 3). Google-Translate-style UI
with speech in/out, a dual-headset conversation mode, history, Wear OS on Android, plus AI features
(cultural warning, tone/situation, sanity check, idiom explanation, rewrite, camera OCR). The same
Compose UI runs on Android and in the browser via Kotlin/Wasm.

| Module | What it holds |
|---|---|
| `:shared` | everything portable: UI (`ui/`), `TranslateViewModel`, models, `network/`, platform seams, plus `androidMain` (Room, TTS) and `wasmJsMain` (localStorage, Web Speech, file input) |
| `:app` | thin Android entry: `MainActivity` (~115 lines), `AndroidAppContainer`, Wear OS code |
| `:webApp` | wasm entry: `main.kt` + `index.html` |

Architecture, capability gating, the translation pipeline and the two backends are described in
[`CLAUDE.md`](CLAUDE.md). Read that next; this file does not repeat it.

**Binding design docs** — `features/full-kmp-restructure/{01-architecture,02-design,03-implementation-plan}.md`.
They are contracts, not notes. Deviations get recorded inline in `02-design.md`, never invented silently.

---

## 3. What has been done

### Arc 1 — full KMP restructure (issue #1)

| PR | Phase |
|---|---|
| #2 | Gradle wrapper / toolchain foundation + Roborazzi baselines |
| #5 | in-place decoupling inside `:app` (no KMP yet) |
| #6 | extract `:shared`, Android target only |
| #7 | wasmJs target + `:webApp` MVP |
| #8 | web parity & polish — voice input, glyph fallback, error states, prod bundle |
| #9 | tests, cleanup, docs |

All merged. The goal ("runs on the web with one shared Compose UI, zero behaviour change on Android")
is met. **Issue #1 is open on purpose** — closing it is the owner's call. What stands between "done"
and "closed" is not code: the public-deploy sign-off (§6) and the owner's manual device smoke (§8).

### Arc 2 — security audit + Gemini resilience

Findings and remediation record: [`features/security-audit/01-audit-findings.md`](features/security-audit/01-audit-findings.md).

| PR | What |
|---|---|
| #10 | **F0**: the Gemini key travelled in the URL and Ktor puts the full URL in its timeout exception message, which the UI rendered on screen. Moved to the `x-goog-api-key` header. Plus quota-aware retry and a stale-validation guard |
| #11 | **F2** translation text out of Wear logcat, **F4** `GoogleResponseParser` bounds check |
| #12 | **F3** exclude the translation DB from Android backup/transfer; commit the audit doc |
| #13 | issue #3: model fallback + honest error copy (`GeminiErrorParser` surfaces Google's own message) |
| #14 | clear message when Gemini returns no usable candidate (found by review) |

**F1** (`isMinifyEnabled = false` on release) was **deliberately deferred by the owner — do not
re-propose it.** R8 buys little here and release signing is not set up on this machine.

### Arc 3 — web→mobile AI feature port

Records: [`features/web-feature-port/01-feature-inventory.md`](features/web-feature-port/01-feature-inventory.md)
and [`02-implementation-record.md`](features/web-feature-port/02-implementation-record.md).

Ported the AI features of the TypeScript web build (`github.com/radumarias/unlost-in-translation`)
into `:shared`, in Kotlin, for both targets.

| PR | Feature | Extra Gemini calls |
|---|---|---|
| #16 | tone (7 presets) + situation (5 presets) | 0 — prompt text only |
| #17 | prior conversation turns fed into the prompt (last 10) | 0 — prompt text only |
| #18 | sanity check + idiom explanation | 0 — folded into the validation call that already runs |
| #19 | rewrite flow (direction + alternatives), **on demand** | 1, only when the user presses the button |
| #20 | auto-detect source language | 0 — rides `sl=auto` on the free Google endpoint |
| #21 | camera / gallery OCR translation | 1 per photo |
| #22 | docs | — |

Scope was the owner's: **Tier A only**. Tier B (draft/approve on the translate tab, full-screen
flashcard, help drawer) and Tier C (theme toggle, version badge, PWA, endonyms) were excluded.

### Arc 4 — UI internationalisation

Record: [`features/web-feature-port/03-i18n.md`](features/web-feature-port/03-i18n.md).

| PR | What |
|---|---|
| #24 | `ui/i18n/Strings.kt` — every shared UI string in one data class, defaulted to English; composables read `LocalStrings.current` |
| #25 | The view model's own snackbars and error fallbacks, plus three strings that were sitting in the platform entry points |
| #26 | The 15 non-English locales |

Keyed by **device** language via `Locale.current` — already multiplatform, so it needed no
container member and no `expect`/`actual`. A locale overrides only what it translates and falls
back to English field by field. English stayed the default, so **the Roborazzi baselines were
never re-recorded** — the concern that deferred this work turned out not to apply.

---

## 4. Setting up a fresh clone

Three files the build wants are **gitignored and therefore absent from a clone**. Each fails
differently, and one of them stops Gradle before it configures anything at all.

### 4.1 `local.properties` — blocks *everything*

Both `:app` and `:shared` declare an Android target (`shared/build.gradle.kts:69`, `compileSdk = 36`),
so with no Android SDK **Gradle cannot even configure the project** — including the wasm-only tasks.

- On a machine with the SDK: `sdk.dir=/path/to/Android/Sdk` in `local.properties` (on this repo's
  home machine it is `D:\Android\Sdk`), or export `ANDROID_HOME`.
- On a bare Linux box: install cmdline-tools, then
  `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, then export `ANDROID_HOME`.
- **If you cannot install the SDK, you cannot build this repo at all.** Say so plainly rather than
  reporting a partial run as green.

### 4.2 `debug.keystore` — blocks `:app:assembleDebug`

`app/build.gradle.kts:45` still carries `signingConfig = signingConfigs.getByName("debugConfig")`,
pointing at a `debug.keystore` that is gitignored (`.gitignore:19`).

Either generate one:

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

or delete that one line locally. **Do not commit the removal** — the README documents it as a local
step and the owner's machine has the keystore.

### 4.3 `.env` — only matters for live API calls

`shared/build.gradle.kts:20-42` reads the root `.env` and generates `com.example.Secrets`; with no
`.env` it falls back to `.env.example`, whose value is the placeholder `MY_GEMINI_API_KEY`.

- **Tests do not care** — every network test uses Ktor `MockEngine`. The full gate is green with the
  placeholder.
- **Live Gemini calls throw** with the placeholder. The free Google endpoint still works, so the app
  translates; only the AI-Enhanced path, validation, rewrite and OCR fail.
- **Never open `.env` and never commit it.** On the owner's machine it holds a real key.

### 4.4 Everything else

Gradle wrapper is committed and pinned (`distributionSha256Sum`) — run everything through `./gradlew`
(`.\gradlew.bat` on Windows). Java 11. No manual dependency installation; the wasm test task pulls
its own Chrome-for-testing via the Kotlin plugin, but a system Chrome/Chromium is what
`:shared:wasmJsTest` drives in headless mode.

Toolchain: Gradle 9.5.0 · AGP 9.3.0 · Kotlin 2.3.20 · Compose Multiplatform 1.11.1 · Ktor 3.0.3 ·
Room 2.7.0 · minSdk 24 / compileSdk 36 / targetSdk 36. Versions live in `gradle/libs.versions.toml`.

---

## 5. How to test

### The gate

One command, `--continue` so a single failure does not hide the rest:

```bash
./gradlew :shared:testAndroidHostTest :shared:wasmJsTest :app:assembleDebug \
          :app:testDebugUnitTest :app:verifyRoborazziDebug :webApp:compileKotlinWasmJs --continue
```

Windows: `.\gradlew.bat` with the same arguments.

| Task | What it proves | Expected |
|---|---|---|
| `:shared:testAndroidHostTest` | `commonTest` on the JVM — parsers, `KtorTranslationApi` with MockEngine, `TranslateViewModel` with fakes, the string catalogue | **148 tests, 0 failures** |
| `:shared:wasmJsTest` | the same `commonTest` **plus** `wasmJsTest` in headless Chrome | **153 tests, 0 failures** |
| `:app:testDebugUnitTest` | Robolectric + Roborazzi | **3 tests, 0 failures** |
| `:app:verifyRoborazziDebug` | Android UI is pixel-identical to the committed baselines | green |
| `:app:assembleDebug` | the Android app still builds | green |
| `:webApp:compileKotlinWasmJs` | the web target still compiles | green |

Counts come from `*/build/test-results/*/*.xml` (`tests="…"` attributes). Note the `:shared` host task
is **`testAndroidHostTest`**, not `testDebugUnitTest` — that is the KMP library plugin's name.

### `--rerun-tasks` is not optional when it matters

Gradle caches hard. After a commit-only or docs-only change everything reports `UP-TO-DATE` and proves
nothing. Add `--rerun-tasks` whenever you need evidence the tests actually executed — before merging,
and any time you are reporting numbers to the owner.

### Roborazzi

Baselines: `app/src/test/screenshots/` (`…MainAppScreenScreenshotTest.mainAppScreen_initialTranslateTab.png`,
`greeting.png`).

`verifyRoborazziDebug` **failing on a UI change is expected**. The procedure:

1. Run `./gradlew :app:recordRoborazziDebug`.
2. **Open the regenerated PNG and look at it.** Confirm the only difference is the one you intended.
3. Commit the baseline in **its own commit**, separate from the code commit.

Never re-record to make a red gate go green without looking at the image — that is exactly how a
regression gets baked into the baseline.

### If your box cannot run part of it

Report the reduced gate honestly, naming what did not run and why. `:shared:testAndroidHostTest`
covers the great majority of the logic (parsers, API, view model), so a run without Chrome or without
a device still says something — just not everything.

### Known-benign noise (not failures)

`VolumeUp`/`ArrowBack` icon deprecations · Room `fallbackToDestructiveMigration` · `ExperimentalWasmJsInterop`
· LF/CRLF line-ending warnings.

---

## 6. Hard rules — do not break these

1. **Public web deployment needs explicit owner sign-off.** The Gemini key is compiled into the wasm
   bundle and is extractable from it. Do not deploy, do not set up Pages or a deploy workflow, do not
   tunnel or ngrok the dev server. Browser testing is **localhost only**. This gate is written into
   issue #1 itself and has not been crossed.
2. **Never read or commit `.env`.** You may check that it exists. Any script that touches a live key
   must scrub it from its output (`key=<REDACTED>`, `AIza…` → `<REDACTED_KEY>`).
3. **Never apply the kotlinx-serialization compiler plugin to `:shared`,** and never add
   `@Serializable` there. It crashes the FIR compiler when co-resident with the Compose plugin on
   wasmJs. Every serializer in `network/` is hand-written; new DTOs get hand-written `KSerializer`s in
   the `GeminiDtos.kt` style.
4. **Android stays behaviour-identical unless a UI change is deliberate** — `:app:verifyRoborazziDebug`
   must be green on every commit (see the re-record procedure above).
4b. **No user-visible string goes inline in a composable.** They live in `ui/i18n/Strings.kt`; adding
   one means adding a field, then translating it in `ui/i18n/locales/`. `StringsTest` fails the build
   if a supported language has no catalogue, if a locale drops a `{0}` placeholder, or if one uses a
   glyph the wasm canvas cannot draw.
5. **The plan docs are binding.** `features/full-kmp-restructure/*` govern structure. Contradictions
   get escalated to the owner, not resolved by inventing a deviation. Any accepted deviation is
   recorded inline in `02-design.md`.
6. **The request budget.** The Gemini key is on the **free tier: ~20 requests per day per model**, and
   the owner does not pay for it — do not suggest billing. A translation costs **2** Gemini calls
   (primary + validation); a photo costs **2**; a rewrite costs **1**, on demand. Tone, situation,
   conversation context, sanity check, idiom explanation and auto-detect were all engineered to cost
   **zero** extra. **Nothing new may add a call to the per-message path.** For live testing use the
   free Google engine, which is unlimited; touch Gemini only through MockEngine in tests.
7. **One PR per logical unit**, branched off `main`, gate green before merge.

---

## 7. Deliberate deviations — do not "fix" these

Each was a decision with a reason. They look like bugs to a fresh reader.

**From the web build** (full reasoning in the feature inventory):

1. **Rewrite is a button, not automatic.** The web build fires it on every warning; that is an
   unrequested third Gemini call per message on a 20/day key.
2. **The translate tab sends no conversation history.** Its history flow is the *persisted* log, not
   a live session — stale unrelated rows would poison the prompt. Only conversation mode sends turns.
3. **Auto-detect stays selected after detection** (the web build overwrites its picker). The language
   bar reports the detection as `Auto - French` instead.
4. **Detection asks for an ISO 639-1 code**, not a language name — everything here is keyed by code.
5. **`Language.autoDetect` is not in `supportedLanguages`.** It has no BCP-47 tag, so it must never
   reach TTS or the target picker. `pickerLanguages(includeAuto)` splices it into the source picker only.
6. **The captured image is not persisted** — the Room schema stays at v1.
7. **Prompts list this build's 16 languages,** not the web build's 13.

**From the design docs:**

- **§4's single-pipeline-helper consolidation is still partial.** `publishTranslation` was extracted
  and the image path reuses it, but `prepareConversationPart` keeps its own orchestration because it
  drives the draft flow. Recorded in `02-design.md` §4.

**Pre-existing findings, reviewed and left alone** (not regressions — they predate all of the above):
stale `_validationState` write without a staleness guard · `messages` SharedFlow `tryEmit` drops with
no collector · dead `_partnerAText`/`_partnerBText` · dual-headset routing expression duplicated ×5 ·
`starred.map` + `contains` rebuilt per recomposition · `conversationHistory.reversed()` redundant with
`reverseLayout` · localStorage id = max+1 reuses ids after delete (cosmetic, single-user; owner's call).

**Already investigated and rejected — do not re-chase:** prompt injection into the validation prompt
(constrained server-side by `responseMimeType=application/json`, parser coerces to inert primitives,
nothing acts without a user tap, single-user local app) · "the key is in the wasm bundle" (known,
documented, owner-gated) · the webpack "user aborted a request" overlay (proven to be browser
extensions, not this app) · Wear service `exported="true"` (behind the Play Services signature-checked
binder) · the debug keystore password (standard, keystore not in git).

---

## 8. Manual checks owed — only the owner can do these

None of these can be exercised by the test suite. They are the real remaining verification.

**From the feature port (newest, most important):**

1. **Android device — camera OCR.** Camera button → Camera → take a photo of foreign text → confirm
   the text and its translation land on the card. This exercises the `FileProvider` hand-off to the
   camera app, which no test covers.
2. **Android device — gallery OCR.** Camera button → Gallery → pick a photo with text → same check.
3. **Browser, on `localhost` only.** Camera button → the file input opens; on a mobile browser confirm
   `capture=environment` opens the rear camera.

**Older, still outstanding:**

4. Web voice input with a real microphone (headless Chrome reaches the recognition service but never
   transcribes, so the transcript extraction in the `js()` `onresult` handler is the one unexercised line).
5. Web TTS by ear.
6. Android: Wear once-fire sync, and dual-headset tone timing/labels.
7. **Read the translations.** The 15 locales are machine-authored; the tests check structure
   (placeholders, coverage, glyphs) but nothing can judge whether a string reads naturally. Romanian
   is the obvious one to check on device — set the phone's language and look at the app.

Worth checking on device while you are there, since it is cheap: pick situation "Medical" and confirm
AI Enhanced flips on with the snackbar; trigger a warning (e.g. "break a leg") and confirm the
validation sheet shows the sanity check and idiom sections and that the rewrite button works.

---

## 9. What is left

**Nothing is assigned. Do not start any of this without the owner asking.**

| Item | Notes |
|---|---|
| **Tier B features** | Draft/approve on the translate tab, full-screen flashcard, help drawer. Excluded from the port's scope by the owner |
| **Tier C features** | Theme toggle, version badge, PWA, language endonyms. Same |
| **Design §4 consolidation** | Behaviour-preserving refactor of `prepareConversationPart`; §10 tests pin both pipelines |
| **Issue #15** | Ray-Ban Display spike via the Meta Wearables Device Access Toolkit — opened by the owner 2026-07-27, not started. Read the issue body; the SDK situation changed since the first write-up |
| **Issue #1 close-out** | Code is complete. Closing is the owner's call — **do not close it yourself** |
| **Web polish candidates** | TTS unsupported-language fallback (Android falls back to `Locale.US`, browsers vary) · `release()` vs `stop()` semantics · `share()` silent clipboard fallback needs user feedback · i18n-ready error codes for `VoiceInputException` · localStorage size cap · `user-scalable=no` in `index.html` (a11y) · `WearSyncManager` could use `flagLabel` |
| **Deploy** | Only after owner sign-off. Consider a key proxy first |

---

## 10. Working agreement

- One PR per logical unit, branched off `main`. The repo has no CI — run the §5 gate locally.
- **Standing owner authorization** (2026-07-16, unchanged): run all the tests, and merge the PR
  yourself when the gate is green.
  `gh pr merge <N> --repo radumarias/unlost-in-translation-mobile --merge`
- Commit messages end with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
  PR bodies end with the Claude Code generated-with line.
- Report to the owner in **Romanian**, terse. A caveman-mode hook may be active — obey it.
- Update `CLAUDE.md` when the architecture, the container surface or the API surface changes.
- Record any design deviation inline in `features/full-kmp-restructure/02-design.md`.

---

## 11. Traps — each of these has already cost this project real time

- **Wasm Ktor Js engine throws `kotlin.Error`** ("Fail to fetch"), not an `Exception`. `apiCall`
  normalizes it. Do not remove that.
- **`apiCall` being `inline` is load-bearing** — a non-suspend lambda wraps suspend calls.
- **`HttpRequestTimeoutException` extends `IOException`**, not `CancellationException` (verified
  against decompiled Ktor 3.0.3). The test `requestTimeout_surfaces_as_an_ordinary_exception` is the
  empirical guard — keep it.
- **The wasm canvas ships no emoji font** — hence the `extendedGlyphs` capability flag. `→` (U+2192)
  is missing too. Use ASCII in shared UI strings.
- **Kotlin 2.3.20 wasm yarn**: `resolution()` changes are not task inputs. Run
  `kotlinWasmNpmInstall --rerun-tasks`, then `kotlinWasmUpgradeYarnLock`.
- **`gemini-3-flash` does not exist** in the live API. The real fallback id is
  `gemini-3-flash-preview`. `ListModels` is quota-free — use it to check model ids, never
  `generateContent`.
- **Blind retry on 429 tripled traffic** (9 → 54 requests) and burned a whole day's quota. The retry
  policy distinguishes `RESOURCE_EXHAUSTED` (no retry — the quota is gone for hours) from burst 429s
  and 5xx (retry, 3 attempts, exponential backoff). Do not simplify it away.
- **PowerShell 5.1**: no `&&`; multiline strings to `git`/`gh` need a file plus `-F`/`--body-file`;
  the working directory drifts, so set it explicitly.
- **Long subagent runs can stall or die.** Relaunch narrower rather than waiting.

---

## 12. Command crib sheet

```bash
# the gate (add --rerun-tasks when you need proof it really ran)
./gradlew :shared:testAndroidHostTest :shared:wasmJsTest :app:assembleDebug \
          :app:testDebugUnitTest :app:verifyRoborazziDebug :webApp:compileKotlinWasmJs --continue

# one test class / one test
./gradlew :shared:testAndroidHostTest --tests "com.example.network.KtorTranslationApiTest"

# Roborazzi: re-record ONLY for an intended UI change, then LOOK at the PNG, then commit it alone
./gradlew :app:recordRoborazziDebug

# web dev server — localhost only (8080 default; it is occupied on the owner's machine, which uses 8081)
./gradlew :webApp:wasmJsBrowserDevelopmentRun

# production web bundle (build only — do NOT publish it anywhere)
./gradlew :webApp:wasmJsBrowserDistribution

# Android install on a connected device
./gradlew :app:installDebug

# merge a green PR (standing authorization)
gh pr merge <N> --repo radumarias/unlost-in-translation-mobile --merge

# secret hygiene — all three must come back empty/expected
git log --all --oneline -- .env
git log -p --all -S "AIza" --oneline
git check-ignore -v .env
```
