# Web → mobile feature port: implementation record

Six PRs, each independently green against the full local gate. Feature list and
deviations: [01-feature-inventory.md](01-feature-inventory.md).

| PR | Feature | Merged as |
|---|---|---|
| #16 | Tone + situation presets | `feat: port tone and situation presets from the web build` |
| #17 | Conversation context in prompts | `feat: feed prior conversation turns into the translation prompt` |
| #18 | Sanity check + idiom explanation | `feat: add sanity check and idiom explanation to cultural validation` |
| #19 | Rewrite flow | `feat: add the on-demand rewrite flow from the web build` |
| #20 | Auto-detect source language | `feat: add auto-detect as a source language option` |
| #21 | Camera / gallery OCR translation | `feat: translate text in a photo (camera / gallery OCR)` |

## What changed structurally

### `model/TranslationContext.kt` (new)

`TranslationContext(tone, situation, history)` plus the `Tone` / `Situation`
preset tables and `ContextTurn`. Everything in it becomes prompt text, so it
never costs a request. `needsAiModel` is the one behavioural bit: a non-default
tone or situation forces the Gemini path, because Google's endpoint takes a bare
string and can honour neither.

### `network/TranslationApi.kt`

Four methods added, two extended:

```kotlin
suspend fun detectAndTranslateGoogle(text, targetLang): DetectedTranslation
suspend fun detectAndTranslateGemini(text, targetLangName, context): DetectedTranslation
suspend fun translateImage(imageBase64, mimeType, userLangName, otherLangName, isSourceAutoDetect): ImageTranslationResult
suspend fun rewriteMessage(originalText, sourceLangName, targetLangName, warning, direction, context): RewriteResult

// extended with a defaulted `context: TranslationContext`
suspend fun translateGemini(...)
suspend fun validateTranslation(...)
```

`translateGoogle`'s signature was deliberately left alone — a large body of
MockEngine tests pins it, and detection needed a different return type.

`ValidationResult` gained `sanityCheck` and `idiomExplanation`, both defaulted,
so a model that ignores the added schema keys still parses.

### `network/GeminiDtos.kt`

`GeminiPart` became `text: String?` / `inlineData: GeminiInlineData?`. The
hand-written serializer emits only the field that is set: Gemini rejects a part
carrying both, and a null `text` is not the same as an absent one. A text-only
request stays byte-identical to what it sent before images existed
(`a_text_only_request_still_carries_no_inlineData_key`).

Three parsers added alongside `GeminiParser`, all with the same fence-stripping
and lenient reads: `GeminiDetectionParser`, `GeminiRewriteParser`,
`GeminiVisionParser`. Each rejects exactly one thing — an answer with no usable
text — because an empty card reads as a broken app.

`GoogleResponseParser.extractWithDetection` reads element 2 of the outer array,
which is where the free endpoint reports the language it detected.

### `ui/viewmodel/TranslateViewModel.kt`

New state: `tone`, `situation`, `detectedSourceLanguage`, `rewriteState`,
`isReadingImage`. New actions: `setTone`, `setSituation`, `requestRewrite`,
`applyRewrite`, `dismissRewrite`, `onImageInputResult`.

`translatePrimary` now returns a `PrimaryTranslation` carrying the language
actually translated *from* (which auto-detect only learns after the call) and
the name to use in the validation prompt.

`publishTranslation` was extracted from `triggerTranslation` — publish, persist,
broadcast, then round trip and validation in parallel — so the image entry point
reuses it rather than adding a third copy. This narrows, but does not close, the
design §4 consolidation deviation: `prepareConversationPart` still has its own
orchestration, deliberately, because it targets the draft flow.

### `platform/`

`ImagePicker` / `PickedImage` / `ImageSource` / `UnavailableImagePicker`, a
`PlatformCapabilities.imageInput` flag, and an `imagePicker` member on
`AppContainer`. Shaped exactly like voice input, including the part where
Android binds the Unavailable implementation because the live path has to be
Activity-scoped launchers reached through an `App()` callback.

`App()` gained `launchImageInput: (ImageSource) -> Unit`, defaulted to a no-op
so a host that does not support images does not have to say so twice.

## Verification at the end of the port

```
:shared:testAndroidHostTest    126 tests, 0 failures   (65 before the port)
:shared:wasmJsTest             131 tests, 0 failures
:app:testDebugUnitTest         3 tests, 0 failures
:app:verifyRoborazziDebug      green (baseline re-recorded twice: #16, #21)
:app:assembleDebug             green
:webApp:compileKotlinWasmJs    green
```

Run with `--rerun-tasks` on merged `main` — Gradle caches aggressively enough
that a green run without it proves less than it looks.

## Owed manual checks

Neither of these can be exercised by the test suite.

1. **Android**, on a device: camera and gallery paths end to end, including the
   `FileProvider` hand-off to the camera app.
2. **Web**, on `localhost` only (the key ships extractable in the wasm bundle):
   the file input in a browser, and `capture=environment` on a mobile browser.

## Still open

- UI i18n (13 locales), deferred deliberately — see the inventory.
- Tier B and C features, out of the agreed scope.
- The design §4 pipeline consolidation, now narrowed but still recorded.
