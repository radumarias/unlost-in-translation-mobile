<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/8008e98f-c039-426d-9f62-94f61088ffb2

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device

## Run on the Web (Compose Multiplatform / Kotlin Wasm)

The app also runs in the browser: the whole UI and view model live in the
`:shared` KMP module, and `:webApp` hosts them on a wasm canvas.

```bash
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # dev server (default port 8080)
./gradlew :webApp:wasmJsBrowserDistribution     # production bundle -> webApp/build/dist/wasmJs/productionExecutable/
```

Notes:

- Requires a WasmGC-capable browser (recent Chrome/Edge/Firefox; the page shows
  a banner when unsupported). Voice input additionally needs Chromium's
  SpeechRecognition and a secure context (localhost counts).
- History/saved translations persist in `localStorage`; Wear sync and
  dual-headset routing are Android-only and their UI is hidden on the web.
- The Gemini key from `.env` is compiled into the web bundle and is extractable
  from it. **Do not deploy the production bundle publicly without accepting
  that** — the "AI Enhanced" toggle and cultural validation simply report a
  clean "not configured" error when the key is absent.
