# Web → mobile feature port: inventory

`unlost-in-translation` (Next.js/TypeScript) and this repo are two builds of the
same product. The web build carried AI features this one lacked; this one
carries platform features (voice input, TTS, dual-headset, Wear, Room, the free
Google backend, Gemini retry/fallback) the web build lacks.

This document records what was surveyed, what was ported, and what was left. The
port itself is described in [02-implementation-record.md](02-implementation-record.md).

## Structural difference that shaped everything

The web build's AI lives in four Next.js server routes that hold the API key.
This build has no server — it calls Gemini directly from `KtorTranslationApi`.
Every route therefore became a method on `TranslationApi`, and the key-exposure
gate is unchanged: the wasm target still must not be served publicly
(`features/security-audit/01-audit-findings.md`, README).

The prompts are the asset. They are copied verbatim except where noted, per the
design §5 rule that prompts are product behavior.

## Ported

| # | Feature | Web source | Extra Gemini calls |
|---|---|---|---|
| A1 | Tone presets (7) | `page.tsx` `TONES`, injected in all routes | 0 — into existing prompts |
| A2 | Situation presets (5), outranking tone | `page.tsx` `SITUATIONS` | 0 — into existing prompts |
| A3 | Conversation context in the prompt | `api/translate/route.ts` `historyText` | 0 — into existing prompts |
| A4 | Sanity check (perceived intent, prose, in the source language) | `api/translate/route.ts` `sanity_check` | 0 — folded into `validateTranslation` |
| A5 | Idiom explanation + native equivalent | `api/translate/route.ts` `idiom_explanation` | 0 — folded into `validateTranslation` |
| A6 | Auto-detect source language | `page.tsx` `isSourceAutoDetect` | 0 — the free Google endpoint takes `sl=auto` |
| A7 | Rewrite flow (direction + alternative directions) | `api/rewrite/route.ts` | 1, **on demand only** |
| A8 | Camera / image OCR translation | `api/vision/route.ts` | 1 per photo |

A typed translation still costs two Gemini requests, exactly as before the port.
That was the binding constraint: the key is on the free tier (~20 requests per
day per model) and the owner does not pay for it.

## Not ported, with reasons

| Web feature | Why not |
|---|---|
| `api/intent/route.ts` | Dead code — the web client never calls it; superseded by `translate`'s non-`skipChecks` mode. |
| Favourites / "offline phrasebook" | This build already has starred history, backed by Room / localStorage rather than a `localStorage` key. |
| Copy to clipboard, share | Already here, behind `PlatformActions`. |
| Shareable URL state (`?text=&src=…`) | Web-only concept; the mobile shell has no address bar. |
| PWA install + offline fallback | Would apply to `:webApp`, but the public-deploy gate is not crossed. Revisit if it ever is. |
| Draft/approve flow on the translate tab | **Half-ported, deliberately** — see below. |
| Theme toggle, version badge, language endonyms | Tier C. |

**Tier B has since been done** — PRs #27, #28, #29. The flashcard and the help drawer were
ported in full; the translate tab's draft/approve flow was **half-ported on purpose**:

- **Ported:** the hand-over. The web build's approve step swaps the two languages so the
  other person can answer, which is the part that carries the value. It is now a button on
  the result card (`handOverForReply`) and costs no request.
- **Not ported:** holding the translation back as a draft until it is approved. The
  translate tab is a lookup surface — the answer should appear at once, and the cultural
  warning already arrives before you say anything. Conversation mode already has a real
  draft flow for the case where a turn is genuinely being sent to someone. Inverting the
  translate pipeline to add an approval step would make the common case slower to serve the
  rare one.

The help drawer's text was **rewritten rather than copied** for the same reason the
deviations below exist: the web build's help describes its auto-detect swapping and
conflates the back-translation with the perceived meaning, neither of which is true here.

**UI i18n has since been done** — see [03-i18n.md](03-i18n.md). It was deferred out of
this port and landed afterwards in PRs #24–#26, with **15 locales** rather than the
web build's 13: the catalogue is keyed by the *device* language, so it covers every
language this app can translate into, including the three (Hindi, Dutch, Turkish) the
web build has no UI locale for.

## Deviations from the web build

Each is deliberate; the reasoning is in the commit that introduced it.

1. **Rewrite is a button, not automatic.** The web build fires the opening
   rewrite on every warning. On the free tier that is an unrequested third
   request per message.
2. **The translate tab sends no conversation history.** The web build has one
   live `interactions` session; this build's translate tab has no session, so its
   history flow is the persisted log and stale rows would be fed to the model as
   context. Only conversation mode sends turns.
3. **Auto-detect stays selected after detection.** The web build overwrites its
   picker with the detected language. Sticky suits the case auto-detect exists
   for — several messages from someone whose language you do not know — and the
   language bar reports what was detected instead.
4. **Detection asks for an ISO 639-1 code**, not a language name off a fixed
   list. Everything on this side is keyed by code.
5. **Auto-detect is a source-only option** and is not in
   `Language.supportedLanguages`. It has no BCP-47 tag, so it must never reach
   TTS or the target picker. This also sidesteps the web build's broken
   both-auto guard (`page.tsx` passes an English sentence as an i18n *key*, so
   the user gets `alert(undefined)`).
6. **The captured image is not persisted.** The Room schema stays at v1.
7. **The supported-language list in prompts is this build's sixteen**, not the
   web's thirteen.

## Rough edges in the web build that were not carried over

- `getStr` misused as a message formatter (`page.tsx` language guards) — both
  error paths show `alert(undefined)`.
- Errors surface as native `alert()`; this build has a snackbar.
- Base64 images held in React state uncompressed; this build downscales to
  1536px before upload.
- `handleSubmit` is a ~140-line function doing validation, two flows, three
  fetches and state mutation.
