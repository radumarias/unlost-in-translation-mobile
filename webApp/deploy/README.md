# Deploying the web build so the pairing deep link works

**Deployment itself is owner-only and is not authorised** — the Gemini key is compiled into the wasm
bundle and extractable from it (`HANDOFF.md` §6 rule 1). This file records what the deploy has to
include for the pairing QR to behave the way issue #33 describes; it is not permission to run it.

The pairing QR carries `https://<host>/j#s=…` when `WEB_BASE_URL` is set. Two things on the host
side decide whether that link works:

## 1. `/j` must serve the app

`/j` is a path with no file behind it. Cloudflare Pages picks up the `_redirects` committed next to
`index.html`, which rewrites it:

```
/j  /index.html  200
```

`200` — a rewrite — and not `301`/`302`. The entire pairing payload lives in the URL fragment, and a
redirect is the one response that can drop it. On nginx the equivalent is `try_files $uri /index.html;`.

The dev server does this through `webApp/webpack.config.d/history-api-fallback.js`, so the flow can
be exercised on localhost before anything is deployed.

## 2. `/.well-known/assetlinks.json` decides whether the app opens instead of the browser

Without this file Android does **not** hand the link to the app. Since Android 12 an unverified web
intent filter is inert until the user turns it on by hand in settings, so serving it is part of
deploying, not a nicety. With it, a phone that has the app installed opens the app; a phone that
does not opens this web build. That fallback is the entire reason the QR carries a URL.

Get the signing certificate's SHA-256 (the **release** cert — the one the installed app is signed
with; a debug-signed build needs its own entry to be tested):

```bash
keytool -list -v -keystore "$KEYSTORE_PATH" -alias <alias> | grep "SHA256:"
```

Then serve this at `https://<host>/.well-known/assetlinks.json`, with the colons kept in the
fingerprint:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.aistudio.translate.vqxwmn",
      "sha256_cert_fingerprints": ["AB:CD:…:EF"]
    }
  }
]
```

It is deliberately **not** committed under `src/wasmJsMain/resources`: a template with a placeholder
fingerprint would be served as the real thing and would fail verification silently, which is worse
than a 404 that says the file is missing.

Verify after deploying:

```bash
curl -s https://<host>/.well-known/assetlinks.json          # the JSON above
curl -s -o /dev/null -w '%{http_code}\n' https://<host>/j    # 200, not 404
adb shell pm get-app-links com.aistudio.translate.vqxwmn     # "verified" for <host>
```

## 3. `.env`

```
WEB_BASE_URL=https://<host>      # no trailing slash
```

Then rebuild. `WEB_BASE_URL` is what puts the host into the QR **and** into the manifest's App Link
filter, so the QR, the filter and the assetlinks file cannot drift apart. Left blank, the QR stays in
its compact `uitr:` form, the filter points at `example.invalid`, and nothing about the previous
behaviour changes.
