# Security audit — findings and remediation plan

**Date:** 2026-07-17 · **Auditor:** Claude Opus 4.8 (main) + 3 Sonnet audit agents
**Scope:** the whole repo at `main` (issue #1 complete, all 6 phases merged, PR #9 `75bb41b`)
**Status (updated 2026-07-17, same day):** **F0, F2, F4 fixed and merged** (PRs #10, #11); **F3
fixed** (this PR — owner decision: exclude the DB from backup and device transfer); **F1 deferred**
by owner decision to a later session (needs a real-device smoke test); F5/F5b/F7 parked with the
deploy decision.

This app is a **single-user local translation app**: Android + a Kotlin/Wasm web build of the same
Compose UI. There is no server, no accounts, no multi-tenancy. That shapes every severity below —
the realistic threat model is *device/APK access and accidental exposure*, not remote attackers.

---

## 0. What is already good (verified, do not re-litigate)

| Check | Evidence |
|---|---|
| `.env` never committed | `git log --all --oneline -- .env` → empty |
| No key material in git history | `git log -p --all -S "AIza"` → no hits |
| No keystores tracked | `git ls-files` matched no `.jks`/`.p12`/`.pem`/`keystore` |
| No CI to audit | no `.github/` directory exists |
| No cleartext HTTP | every real endpoint is `https://`; no `usesCleartextTraffic`, no `network_security_config` needed |
| Secrets codegen is sound | `shared/build.gradle.kts:20-60` — escapes `\`, `"`, `$`, newlines into the Kotlin literal; declares **both** `.env` and `.env.example` as task inputs (config-cache correctness); output lands under `build/` (gitignored) |
| Release signing not hardcoded | `app/build.gradle.kts:21-28` — `KEYSTORE_PATH`/`STORE_PASSWORD`/`KEY_PASSWORD` from env |
| Prompt injection is **not** a real risk | see §3 — analysed and rejected with evidence |

---

## 1. Known and accepted (owner-gated, not a new finding)

**The Gemini API key is compiled into the wasm bundle and is extractable by anyone who loads the
page.** This is documented, deliberate, and the reason public web deployment requires the owner's
explicit sign-off (Phase 4 task 5 gate — **not crossed**).

Practical rule while this stands: **never serve the wasm bundle off this machine** — no tunnel, no
ngrok, no GitHub Pages, no "quick demo link". `localhost` only.

The real fix, if the app is ever published: a **key proxy** — a tiny backend that holds the key and
forwards translation/validation requests, so the client never sees it. Recorded as future work in
the plan's risk section. Everything else in this document is smaller than this one item.

---

## 2. Findings

Severity is calibrated to *this* app (local, single-user, no server). Ordered by what I'd fix first.

### F0 — Gemini key leaked in timeout messages, shown on screen and logged · HIGH · **FIXED**
**`shared/src/commonMain/kotlin/com/example/network/KtorTranslationApi.kt`** — the key travelled in
the request URL (`?key=$apiKey`).

Ktor embeds the **full request URL** in its timeout exception message. `apiCall` passes exceptions
through unchanged, `runValidation` puts `e.message` into `ValidationState.Error`, and the UI renders
that string — while `log.e` writes it to logcat (Android) and `console.error` (web).

**Verified empirically before fixing** (temporary probe test, since deleted):
```
PROBE RESULT >>> containsKey=true
message=[Request timeout has expired
  [url=https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=SUPERSECRETKEY123,
   request_timeout=60000 ms]]
```
So: **every Gemini timeout printed the live API key on screen and into the logs.** A slow connection
was enough — no attacker needed. Anyone reading the screen, a shared screenshot, a bug report, the
browser console, or logcat got the key.

*Fix applied:* the key now travels in the `x-goog-api-key` header, never the URL. Google's documented
alternative — **verified against the live API** (header auth returns 429/quota, i.e. the key is
authenticated, not rejected). This removes the whole class of leak: URL-echoing errors, referrers,
proxy/CDN access logs.

*Guards added:* `gemini_sends_the_key_in_a_header_and_never_in_the_url` (asserts the key is in the
header and the URL contains neither the key nor `key=`) and `geminiTimeout_message_does_not_contain_the_key`
(the end-to-end property).

**Note:** this does NOT change §1 — the key is still *inside the wasm bundle*. It only stops the key
from leaking through error text on both platforms.

---

### F1 — R8/minification disabled on release builds · MEDIUM · deferred (owner decision)
**`app/build.gradle.kts:40`** — `isMinifyEnabled = false` in the `release` block.

The release APK therefore ships: all `Log.d` calls intact (see F2), unobfuscated class/method names,
and the Gemini key sitting in a trivially-greppable string constant. `proguardFiles(...)` is declared
right below it but does nothing while minification is off.

*Deferred 2026-07-17 (owner decision):* to a later session — the fix changes the release artifact
and needs a real-device smoke test the owner will run when the device is at hand.

*Impact:* anyone with the APK (a shared build, a device, an APK mirror site) gets a readable map of
the app and the key with `strings`/`jadx` in seconds. On Android the key extraction is roughly as bad
as the web case already is — but unlike the web case, this one is trivially fixable.

*Fix:* `isMinifyEnabled = true` + `isShrinkResources = true` on release; keep the existing
`proguardFiles` line; add keep rules for anything reflective (Ktor engines, Room, the hand-written
serializers) and verify with a real release build + smoke test. **Note:** this changes the release
artifact, so it needs a device smoke test — it cannot be waved through on Roborazzi alone.

---

### F2 — User translation text written to logcat · MEDIUM · **FIXED** (PR #11)
**`app/src/main/java/com/example/wear/WearTranslationService.kt:44`** —
`Log.d(TAG, "Wear OS translation request: '$text' ($sourceLang -> $targetLang)")`
**`:63`** — `Log.d(TAG, "Sent response back to Wear OS Node '...': $translated")`

Translation content is exactly the kind of thing users type when they're being personal — medical
questions, private messages, addresses. It lands in logcat in plaintext.

*Impact:* on Android 4.1+ apps can't read each other's logs, so the realistic exposure is: anyone with
ADB access to the device, any bug-report/`dumpstate` capture the user shares, and OEM log collectors.
Combined with F1 (logs not stripped in release), this ships to production.

*Fix applied (PR #11, `4d18ff6`):* the request log now records length + language pair, the response
log keeps the node id and drops the text. The `WearSyncManager`/`TTSHelper` `Log.d` calls are
harmless by comparison (`MainActivity.kt:52`, `WearSyncManager.kt:39,85`, `TTSHelper.kt:22` — no
user content). R8 log-stripping (F1) would add defence in depth when it lands.

---

### F3 — Translation history goes to Google cloud backup unfiltered · MEDIUM · **FIXED**
**`app/src/main/AndroidManifest.xml:9-11`** — `allowBackup="true"` with
`app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` still holding the **untouched
AI Studio template** (every rule commented out, one has a literal `TODO`).

*Impact:* the whole Room database (`translation_database` — the user's complete translation history)
is swept into Google Drive backup and into device-transfer, with no `<include>`/`<exclude>` filtering
and no user-visible mention. It's a privacy default nobody chose.

*Fix applied (owner decision 2026-07-17: exclude):* both files now exclude the whole `database`
domain (`<exclude domain="database" path="."/>`) — cloud backup below and above API 31, plus
device-to-device transfer. `path="."` rather than the DB filename so the `-wal`/`-shm` journal
files are covered too. Cost accepted: a reinstall or new device starts with an empty history.

---

### F4 — `GoogleResponseParser.extract` indexes `outer[0]` without a bounds check · LOW · **FIXED** (PR #11)
**`shared/src/commonMain/kotlin/com/example/network/GeminiDtos.kt:289`** —
`val firstGroup = outer[0].jsonArray`

`translate.googleapis.com/translate_a/single` is an **unofficial, unversioned, unauthenticated**
endpoint. An empty array (`[]`), a shape change, or a captive-portal/proxy response throws
`IndexOutOfBoundsException`.

*Impact:* limited — the call sits inside `apiCall { }`, whose `catch (t: Throwable)` normalises it to
an ordinary `Exception`, so it surfaces as an error card, not a crash. Worth fixing because the
endpoint is exactly the kind that changes shape without notice.

*Fix applied (PR #11, `e368ef4`):* `outer.getOrNull(0)?.jsonArray ?: throw
SerializationException("unexpected Google response shape: empty outer array")`, pinned by a
`commonTest` that runs on both JVM and wasm.

---

### F5 — No Content-Security-Policy on the web build · LOW
**`webApp/src/wasmJsMain/resources/index.html`** — no CSP `<meta>`, no header (the dev server sets none).

*Impact:* low *today* — the page loads exactly one same-origin script (`webApp.js`, verified: nothing
else, no CDN) and the Compose canvas doesn't render HTML, so there's no classic XSS sink. A CSP is
defence-in-depth against a future regression or a compromised dependency, and it would be a hard
requirement if the app were ever published.

*Fix (only meaningful at deploy time, alongside the key proxy):* a `default-src 'self'` CSP allowing
`connect-src` to the two API hosts, plus `wasm-unsafe-eval` for the wasm module. **Verify against a
real build** — Compose/skiko may need `'unsafe-inline'` for styles.

---

### F5b — Source maps shipped with the production web bundle · LOW
**`webApp/build/dist/wasmJs/productionExecutable/webApp.js.map`** — the production distribution
emits a source map next to `webApp.js`; no webpack config disables it.

*Impact:* only relevant if the bundle is ever published (which §1 already forbids). It would make
reverse-engineering the client glue code easier — including locating where the key is referenced.

*Fix (deploy-time, with §1's work):* `commonWebpackConfig { devtool = null }` for the production
task, or strip `*.map` in the publish step.

---

### F7 — No Gradle dependency verification · MEDIUM (defence in depth)
**repo-wide** — no `gradle/verification-metadata.xml`, no dependency locking for the JVM/Android graph.

*Impact:* a compromised upstream artifact or a poisoned Gradle cache would not be detected — nothing
checks artifact checksums or signatures. The wasm side is better off: `kotlin-js-store/wasm/yarn.lock`
pins the npm tree, and the `ws >= 8.21.0` resolution that closed Dependabot #1/#2 is **verified still
in place and effective** (installed copy: 8.21.1).

*Judgement:* real, but it's a hardening measure with real ongoing cost (the metadata file needs
maintenance on every dependency bump). Reasonable to defer on a local single-user app with no CI —
worth doing if this ever ships or gains CI.

*Fix:* `gradle --write-verification-metadata sha256,pgp` and commit the result.

---

### F8 — Debug keystore credentials hardcoded · LOW (no action needed)
**`app/build.gradle.kts:29-34`** — `debugConfig` uses the standard Android debug password
(`"android"`), pointing at `${rootDir}/debug.keystore`.

*Judgement:* **not a real issue.** These are the universally-known Android debug credentials, and the
keystore itself is not in the repo (verified: `git ls-files` matches nothing). Recorded only so a
future reader doesn't re-flag it. Keep `debug.keystore` out of git; never point release signing here.

---

### F6 — Unofficial Google Translate endpoint (accepted risk, documented for the record) · INFO
**`shared/src/commonMain/kotlin/com/example/network/KtorTranslationApi.kt:45-57`** — the default
translation path calls `translate.googleapis.com/translate_a/single?client=gtx` with a spoofed
`User-Agent: Mozilla/5.0`.

Not a vulnerability. But it is an undocumented endpoint with no stability guarantee, no terms
coverage, and no auth — it can change shape or start refusing traffic at any time (F4 is its blast
radius). It's a product/legal risk to be aware of, not something to "fix" now.

---

## 3. Prompt injection — analysed and REJECTED (do not re-chase)

User text is interpolated into the Gemini validation prompt inside quotes
(`KtorTranslationApi.kt:94-111`: `Original Text: "$originalText"`). This *looks* like a prompt
injection surface. It is not, for three independent reasons — each verified in code:

1. **Structured output constrains the model server-side.** The validation request sets
   `responseMimeType = "application/json"` (`GeminiGenerationConfig`), so Gemini is constrained to
   emit a JSON object regardless of what the text tries to talk it into.
2. **The parser coerces everything to inert primitives.** `GeminiParser.parseValidationObject`
   (`GeminiDtos.kt:334-351`) reads only `isWarning` (`booleanOrNull`, default false), `explanation`
   (`contentOrNull`, default ""), and `suggestions` (`{tone,text}` strings). There is no field that
   can carry a URL, a command, or a code path.
3. **Nothing downstream acts on it.** The only reachable "action" is `onSelectSuggestion`
   (`ValidationDetailsSheet.kt:161` → `App.kt:280-284`), which needs an explicit user tap and then
   does exactly what typing text and pressing Translate does. No navigation, no clipboard write, no
   network call, no code execution happens automatically.

And the threat model doesn't hold anyway: this is a single-user local app. The "attacker" is the user
typing into their own translate box. There is no path where a third party's untrusted text reaches
the prompt without the user choosing to type, paste, or speak it.

**Conclusion: not a risk here.** If the app ever grows a shared/multi-user surface (or auto-translates
inbound content), revisit — the mitigations above are properties of the current design, not guarantees.

---

## 4. Dependency inventory (for the record, 2026-07-17)

| Component | Version |
|---|---|
| Gradle wrapper | 9.5.0 (`distributionSha256Sum` pinned) |
| Android Gradle Plugin | 9.3.0 |
| Kotlin | 2.3.20 |
| Compose Multiplatform | 1.11.1 |
| Compose BOM (androidx) | 2024.09.00 |
| Ktor (core/okhttp/js/content-negotiation/serialization-json/mock) | 3.0.3 |
| kotlinx-serialization-json | 1.7.3 |
| kotlinx-coroutines | 1.10.2 |
| kotlinx-browser | 0.3 |
| AndroidX Room | 2.7.0 |
| AndroidX core-ktx | 1.18.0 |
| AndroidX activity-compose | 1.10.1 |
| AndroidX lifecycle-viewmodel (KMP) | 2.9.4 |
| KSP | 2.3.10 |
| play-services-wearable | 18.1.0 |
| Robolectric / Roborazzi / JUnit4 | 4.16.1 / 1.59.0 / 4.13.2 |
| OkHttp | transitive via ktor-client-okhttp 3.0.3 (4.12.x) |
| npm `ws` (wasm dev toolchain) | 8.21.1 — resolution pinned, Dependabot #1/#2 closed and effective |

No dynamic/snapshot versions. Repositories are mavenCentral/google only. No CVEs identified with
confidence in this set.

---

## 5. Remediation plan — execution record (2026-07-17)

**Done:**
1. **F0** — key moved to the `x-goog-api-key` header + 2 regression tests (PR #10, `5c5f2a6`).
2. **F2** — user text out of logcat (PR #11, `4d18ff6`).
3. **F4** — parser bounds check + `commonTest` (PR #11, `e368ef4`).
4. **F3** — DB excluded from backup and device transfer, owner decision recorded above (this PR).

**Remaining:**
- **F1** (enable R8) — deferred by owner decision to a later session: it changes the release
  artifact and can break reflective code (Ktor engines, Room, hand-written serializers) in ways
  unit tests won't catch. Needs `:app:assembleRelease` + a real-device smoke test, in its own PR.
- **F7** (dependency verification), **F5/F5b** (CSP, source maps) — hardening; parked with the
  deploy decision, not worth the maintenance cost on a local-only app.

**Not in scope without an explicit owner decision:** the key proxy (§1), publishing anything, R8
keep-rule archaeology beyond what a smoke test proves.

### Rejected / do not re-chase
- **Prompt injection** — analysed and rejected with evidence (§3).
- **"The key is in the wasm bundle"** — known, documented, owner-gated (§1). Not a new finding.
- **Debug keystore password** — standard Android debug credentials, keystore not in git (F8).
- **Wear service `exported="true"`** — `WearableListenerService` is protected by the Play Services
  binder (signature-checked); the intent filter is Google's required
  `com.google.android.gms.wearable.BIND_LISTENER`. Not an open surface. Its *logging* is F2.

---

## 6. Verification commands

```
# gates (all must pass after every step)
.\gradlew.bat :shared:testAndroidHostTest :shared:wasmJsTest :app:assembleDebug :app:testDebugUnitTest :app:verifyRoborazziDebug :webApp:compileKotlinWasmJs --continue

# after enabling R8 (F1) — a debug APK proves nothing here
.\gradlew.bat :app:assembleRelease
# then install on a device and exercise: translate, history, saved, conversation, Wear sync

# secret hygiene (should stay clean forever)
git log --all --oneline -- .env          # expect empty
git log -p --all -S "AIza" --oneline     # expect empty
git check-ignore -v .env                 # expect .gitignore:18
```
