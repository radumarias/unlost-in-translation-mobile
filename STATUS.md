# STATUS — what is built, how sure we are, and what is left

Companion to [`HANDOFF.md`](HANDOFF.md). HANDOFF tells you how to build, test and work in this
repo. **This file tells you how much to trust each part of it**, so you know where you can
change things confidently and where you have to be careful.

Written 2026-08-03 against `main` = `f6dd0f3`, updated the same day for the §4.1 floor-raise
(Room and image-loading tests). Every claim below was checked against the code and the test
results on that commit, not recalled.

---

## 1. How to read the confidence levels

| | Level | What it means |
|---|---|---|
| **A** | **Proven** | An automated test executes this behaviour and asserts on it. The test ran green on this commit. If you break it, the build breaks. |
| **B** | **Reviewed, one frame proven** | Compiles, was written and read carefully, and appears in the single Roborazzi frame — or is layout so simple the compiler is most of the check. No test executes the interaction. |
| **C** | **Compiles, never executed** | Nothing has ever run this code — not a test, not CI, not this machine. It needs a device, a browser, a watch or a live API key. Correctness here rests on reading it. |
| **D** | **Written, never judged** | Content rather than logic. Structurally checked by tests, but no human competent to assess the *quality* has looked at it. |

**The honest summary:** the network layer and the view model are A, and so now are Room and the
image downscaler. The UI is mostly B. Every remaining platform integration — Android launchers,
camera hand-off, TTS, Wear, all the web browser seams except localStorage — is **C**. The 15
locales are **D**.

That distribution is a consequence of the environment, not neglect: there is no CI, no device
attached, and the Gemini key is on a 20-requests-per-day free tier.

---

## 2. Feature status

### Translation core

| Feature | Level | What pins it |
|---|---|---|
| Google free endpoint (`translate_a/single`) | **A** | URL params, query encoding, blank short-circuit, non-200, response parsing, `sl=auto` detection element — `KtorTranslationApiTest`, `NetworkParsersTest` |
| Gemini translate / validate / rewrite / vision | **A** | 45 MockEngine tests: exact prompt bodies, JSON schema keys, `inlineData` part shape, all three vision prompt variants |
| **Key never leaves in the URL** | **A** | `gemini_sends_the_key_in_a_header_and_never_in_the_url`, `geminiTimeout_message_does_not_contain_the_key`. This was finding F0 — the key used to be printed on screen by a timeout |
| Retry / backoff / quota handling | **A** | Burst 429 retries, exhausted quota never retries, 5xx retries, both models exhaust their caps, client errors do not retry |
| Model fallback `gemini-3.5-flash` → `gemini-3-flash-preview` | **A** | 5 tests including the network-failure path |
| Hand-written serializers and parsers | **A** | 31 parser tests. A text-only request still carries no `inlineData` key |
| Translate pipeline (publish, persist, broadcast, round trip, validation) | **A** | `happyPath_translates_persists_broadcasts_roundTrips_validates` plus staleness guards on both children |
| Auto-detect | **A** | 6 tests: uses the free endpoint, round-trips against the detected language, skips the round trip on an unsupported language, refuses to let Auto become the target |
| Tone / situation | **A** | 5 tests: forces the Gemini path, reaches both calls, flips AI Enhanced, reselect is a no-op |
| Conversation context in prompts | **A** | Capped at 10 turns, labelled by speaker language, translate tab sends none |
| Sanity check + idiom | **A** | Parser defaults and VM state |
| Rewrite | **A** | 6 tests: never automatic, sends the warning, directed reload keeps its alternatives, apply re-translates |
| Camera / gallery OCR *(the API and VM halves)* | **A** | 5 VM tests + the request-shape tests |
| Hand-over for reply | **A** | 4 tests, including that it costs no call where swapping costs two |
| String catalogue mechanics | **A** | 18 tests: locale resolution, `pt-BR` → `pt`, placeholders preserved, every supported language has a catalogue, no banned glyphs, no blanks |

### User interface

| Area | Level | Note |
|---|---|---|
| Initial translate tab, exact pixels | **A** | `MainAppScreenScreenshotTest` + `verifyRoborazziDebug`. This is the **only** frame under test |
| Result card, validation banner, tools row | **B** | Not in the captured frame (needs a translation) |
| Validation details sheet + rewrite panel | **B** | |
| History / saved rows | **B** | |
| Conversation screen, draft dialog | **B** | |
| Dual-headset dialog | **B** | |
| Language picker | **B** | |
| Full-screen flashcard *(PR #27)* | **B** | A Dialog with one Text. Never rendered in a test |
| Help drawer *(PR #28)* | **B** | Section list and capability filter are plain Kotlin; the accordion is not exercised |

### Platform integrations — C unless marked otherwise

Nothing at level C in this table has ever run.

| Component | Level | Status |
|---|---|---|
| `RoomTranslationHistoryRepository` (+ DAO, entity, DB) | **A** | 7 Robolectric tests over an in-memory database — `RoomTranslationHistoryRepositoryTest` in `:app`. Ids, newest-first ordering, field mapping, the `timestamp = 0` "now" branch, star on/off, delete, clear-all, `REPLACE` on a duplicate id |
| `AndroidImageLoading` (downscale, Base64) | **A** | 6 tests — `AndroidImageLoadingTest`. **These found a real bug**: the function returned null for *every* image, so camera and gallery OCR could never have worked on Android. Fixed with the tests. The sampling maths, `NO_WRAP`, and both null paths are now pinned |
| `TTSHelper` incl. dual-headset WAV + `MediaPlayer.setPreferredDevice` | **C** | Needs audio hardware |
| `AndroidAudioOutputCatalog` | **C** | Needs a device with headsets |
| `MainActivity` voice launcher (`RecognizerIntent`) | **C** | Activity-scoped result launcher |
| `MainActivity` gallery + camera launchers, `CameraCapture`, `FileProvider` | **C** | The hand-off to the camera app is exactly what cannot be faked. The decode *behind* those launchers is now A, but the launchers themselves are not |
| Wear: `WearSyncManager`, `WearTranslationService` | **C** | Needs a paired watch |
| `WebVoiceInput` | **C** | Headless Chrome reaches the recognition service but never transcribes, so the `onresult` transcript extraction is the single unexercised line |
| `WebSpeechSynthesizer`, `WebPlatformActions`, `WebAudioOutputCatalog`, `WebImagePicker` | **C** | Browser APIs behind `js()` interop |
| `LocalStorageHistoryRepository` | **A** | The one exception — 5 tests in `wasmJsTest`, including corrupt-data recovery |

### Content — D

| | Level | Note |
|---|---|---|
| 15 locales, ~150 fields each | **D** | Machine-authored. Tests check *structure* — placeholders present, nothing blank, no untranslated leftovers, no glyphs the wasm canvas cannot draw. **Nothing checks whether a sentence reads naturally.** Romanian is the one the owner can judge |
| Help drawer prose | **D** | Same, and it makes factual claims about the app. It was deliberately rewritten rather than copied from the web build, but nobody has checked it against the app's actual behaviour on screen |
| Gemini prompt wording | **B** | Pinned as exact strings by tests, so it cannot drift silently. Whether the model *answers well* was demonstrated live once (2026-07-17, "break a leg" → correct warning) and not since — the free-tier quota makes repeated live testing expensive |

---

## 3. Known imperfections — real, and deliberately not fixed

None of these are regressions. Each was found, assessed, and left. **Do not "discover" them again.**

| Thing | Where | Why it is still there |
|---|---|---|
| The dual-headset device-choosing expression is repeated | 10 sites across `ui/` | Cosmetic duplication |
| `starred.map { }` rebuilds per recomposition | `TranslateScreen.kt:309` | Small list, measurable cost is nil |
| `conversationHistory.reversed()` alongside `reverseLayout = true` | `ConversationScreen.kt:131` | Two reversals cancel out; it works, it is just redundant |
| `messages` is a `tryEmit` SharedFlow with no replay | `TranslateViewModel:69` | A message emitted with no collector is dropped. In practice the collector is up before anything can emit |
| localStorage ids are `max + 1` | `LocalStorageHistoryRepository` | Reuses ids after a delete. Single-user, cosmetic. Owner's decision to leave |
| Design §4 pipeline consolidation is partial | `prepareConversationPart` | `publishTranslation` was extracted and the image path reuses it; the conversation path keeps its own orchestration because it drives the draft flow. Recorded in `02-design.md` §4 |
| R8 / `isMinifyEnabled = false` on release | `app/build.gradle.kts` | Security finding F1. **Owner decided to leave it indefinitely — do not re-propose.** Also physically blocked: release signing needs env vars and a keystore that are not here, and `proguard-rules.pro` has no keep rules, so enabling R8 would strip reflective code and crash release invisibly to unit tests |

### Deliberate deviations — these look like bugs and are not

From the web build: rewrite is a button not automatic · the translate tab sends no history ·
auto-detect stays selected after detecting · detection asks for an ISO 639-1 code · `Language.autoDetect`
is outside `supportedLanguages` · the captured image is not persisted · prompts list 16 languages ·
**draft/approve is half-ported** (the hand-over yes, holding the result back no) · the UI language
follows the **device**, not the selected source language · help text rewritten, not copied.

Full reasoning: `features/web-feature-port/01-feature-inventory.md` and `03-i18n.md`.

---

## 4. What is left

Nothing is assigned. **Do not start any of it without the owner asking.** Ordered by value per
unit of risk, which is not the order they were listed in historically.

### 4.1 Raise the floor — done

The three items that stood here (test `RoomTranslationHistoryRepository`, test
`AndroidImageLoading`, delete `_partnerAText`/`_partnerBText`) were completed on 2026-08-03.
The image tests turned up a genuine bug — see the platform table in §2.

### 4.2 Cleanups that were already logged

1. **Design §4 consolidation** — fold `prepareConversationPart`'s orchestration into the shared
   helper. Behaviour-preserving; §10 tests pin both pipelines, so the tests are the safety net.
2. **Web polish list**, each independent and small: TTS fallback for an unsupported language
   (Android falls back to `Locale.US`, browsers vary) · `release()` vs `stop()` semantics ·
   `share()` falls back to the clipboard silently and should say so · `VoiceInputException`
   carries English strings from the platform layer and should carry codes the catalogue can
   translate · no localStorage size cap · `user-scalable=no` in `index.html` is an accessibility
   problem · `WearSyncManager` could use `flagLabel`.
3. The cosmetic items in §3 (duplication, redundant reversal, recomposition allocation).

### 4.3 Tier C features — not started

Theme toggle · version badge · language endonyms in the picker (note: `Language.name` must stay
English because it goes into the prompts, so this needs a *display* name separate from the prompt
name) · PWA install and offline fallback — **PWA is blocked by the deploy gate and should not be
built until that gate moves**.

### 4.4 Owner-only

- **Public web deploy sign-off.** The Gemini key is extractable from the wasm bundle. Not crossed.
- **Closing issue #1.** All its code is merged. Closing is the owner's call.
- **Issue #15 (Ray-Ban Display spike): the owner declined it** on 2026-08-03 — *"ray-ban cred ca
  e mai bine sa nu facem noi"*. Do not start it.

### 4.5 Manual checks owed — the only way to move things off C and D

These are the real remaining verification. In rough order of what is most likely to be broken:

1. **Android, camera OCR** — take a photo of foreign text. Exercises the `FileProvider` hand-off,
   which nothing else does. (Before 2026-08-03 this check would have failed for a reason that had
   nothing to do with the hand-off — see the image-loading row in §2.)
2. **Android, gallery OCR** — same via the photo picker.
3. **Android, Wear** — once-fire sync to a watch, and the notification.
4. **Android, dual headsets** — assign two, check each side hears only its own, and that the test
   tone names the right language.
5. **Browser, `localhost` only** — the file input, and `capture=environment` on a mobile browser.
6. **Browser, real microphone** — the one unexercised line in `WebVoiceInput`.
7. **Browser, TTS by ear.**
8. **Open the flashcard and the help drawer once** — neither overlay has a rendering test.
9. **Read the Romanian locale on device.** It is machine-authored prose.

---

## 5. Rules that hold regardless of what you are doing

Repeated from HANDOFF because breaking one of these is worse than shipping nothing.

1. **No public web deployment without explicit owner sign-off.** Localhost only. The key ships
   inside the bundle.
2. **Never read or commit `.env`.**
3. **Never apply the kotlinx-serialization plugin to `:shared`**, never add `@Serializable` there.
   It crashes the FIR compiler alongside the Compose plugin on wasmJs.
4. **`:app:verifyRoborazziDebug` stays green.** When a UI change is deliberate, re-record, **look
   at the PNG**, and commit the baseline in its own commit.
5. **No user-visible string inline in a composable** — add a field to `Strings.kt` and translate it
   in all 15 locales.
6. **The request budget: a translation costs 2 Gemini calls, a photo 2, a rewrite 1 on demand.**
   The key is free tier, ~20/day/model, and the owner does not pay. Nothing new may add a call to
   the per-message path. Test against the free Google engine; touch Gemini only via MockEngine.
7. **The plan docs bind.** `features/full-kmp-restructure/*`. Escalate contradictions; record
   accepted deviations inline in `02-design.md`.

---

## 6. If you want to raise confidence, do it in this order

| From → to | Do this |
|---|---|
| Overlays: B → A | Roborazzi can capture a dialog window, but it needs the dialog's own root rather than `onRoot()`. Budget an hour for the machinery before deciding it is worth it |
| Result card: B → A | A second Roborazzi frame with a seeded translation. Needs a fake container so the render is deterministic — the current test uses the real Room DB in its empty state |
| Locales: D → C | Nothing automatable. A speaker reads them |
| Live Gemini behaviour: B → A | Not achievable on the free tier. Accept it |

---

## 7. Verification recipe

```bash
./gradlew :shared:testAndroidHostTest :shared:wasmJsTest :app:assembleDebug \
          :app:testDebugUnitTest :app:verifyRoborazziDebug :webApp:compileKotlinWasmJs \
          --rerun-tasks --continue
```

Expected after the §4.1 floor-raise: **152** JVM · **157** wasm · **16** `:app` · Roborazzi green ·
both builds green. The JVM and wasm counts are unchanged by that work, which is itself the proof
that the deleted `_partnerAText`/`_partnerBText` flows really were dead.

`--rerun-tasks` is not optional when you are reporting numbers: Gradle caches hard enough that a
docs-only change reports everything `UP-TO-DATE` and proves nothing.

Counts come from `*/build/test-results/*/*.xml`. Where the numbers live:

| Suite | Tests |
|---|---|
| `KtorTranslationApiTest` | 45 |
| `NetworkParsersTest` | 31 |
| `TranslateViewModelTest` | 55 |
| `StringsTest` | 18 |
| `LanguageTest` | 3 |
| `LocalStorageHistoryRepositoryTest` | 5 (wasm only) |
| `RoomTranslationHistoryRepositoryTest` | 7 (`:app`) |
| `AndroidImageLoadingTest` | 6 (`:app`) |
| `:app` | 16, of which 1 is the screenshot |
