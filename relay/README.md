# uitr-relay

The WebSocket fan-out relay for two-device conversation mode (issue #33). One Cloudflare Worker plus
one Durable Object per session. It does no translation, has no accounts, holds no key, and stores
nothing beyond a short replay ring inside each session's own private SQLite.

**This directory is not a Gradle module.** It is deployed on its own, by the repo owner, with
`wrangler deploy`. Nothing in the Kotlin build references it.

## What it does

```
GET /s/<sid>?m=<memberId>      Upgrade: websocket
GET /health                    200 "ok"
```

* `sid` — 128 bits, base64url, unpadded, 22 characters. Rejected at the edge if it does not match
  `^[A-Za-z0-9_-]{22}$`, so a probing id never allocates a Durable Object and never costs a request.
* `m` — the caller's per-session random device id (`^[A-Za-z0-9_-]{8,64}$`). Not an identity and not
  a secret; it exists so the relay can fan a frame out to *the other* member and replay the right
  half of the ring after a reconnect.
* Every text frame from one member is sent to the other and appended to that member's ring. On
  connect, a member receives the other member's ring — a ten-second blip does not lose a turn.

**The session secret never reaches this Worker.** It lives after the `#` in the QR URL, which no
browser ever sends to a server. Once #34 lands, everything except the two handshake frames is
ciphertext and the relay operator cannot read any of it.

## Limits, and why they are where they are

| Limit | Value | Why |
|---|---|---|
| Members per session | 2 | The UI has two sides; a third connection is refused with `409`. |
| Frame size | 16 384 characters | Generous for a turn of speech even with base64 ciphertext overhead. The platform would allow 32 MiB. |
| Rate | 40 frames / 10 s per session | Zone-level WAF and rate-limiting rules **do not apply to `workers.dev`**, so this has to live in the Worker. |
| Ring | 20 frames per member | Reconnect replay. Duplicates are the client's problem, and it de-dups on turn id and on the #34 record counter. |
| Session TTL | 2 h | An alarm every 5 minutes closes the sockets and wipes storage past the TTL. |

## Why the ring is in SQLite and not in a field

Hibernation is what makes this free — billable duration does not accrue while an object is
hibernating, and an idle paired conversation hibernates. But hibernation **resets in-memory state**,
and `serializeAttachment` is not a way out: it is per-connection, capped at 16 KiB, and lost when
either side closes — and a network blip *is* a close. So the ring goes in the Durable Object's own
SQLite, which is also the only storage backend the free plan offers.

Each Durable Object's storage is private to that object: one session's ring is not readable by
another session, by the Worker, or by any other Worker. What *is* shared account-wide is the quota
(100k DO requests/day, 100k row writes/day) — worth remembering before putting a second, chatty
Worker on the same account.

## Deploying — owner only

The app never hardcodes a hostname: the relay URL is build-time config (`.env` → `AppSecrets.relayBaseUrl`),
so moving from a `workers.dev` subdomain to a custom domain later is a config change, not a release.

```bash
cd relay
npm install
npx wrangler deploy --dry-run     # type-checks and bundles, deploys nothing
npx wrangler login                # interactive, once
npx wrangler deploy
```

That prints `https://uitr-relay.<your-subdomain>.workers.dev`. Put its `wss://` form in the repo root
`.env`:

```
RELAY_BASE_URL=wss://uitr-relay.<your-subdomain>.workers.dev
```

Smoke test:

```bash
curl https://uitr-relay.<your-subdomain>.workers.dev/health          # ok
curl -i https://uitr-relay.<your-subdomain>.workers.dev/s/short      # 400 bad session id
```

## Verifying it, without deploying anything

`npx wrangler dev` runs the whole thing locally on `localhost:8787` — the real Durable Object and its
real SQLite, in workerd. `scripts/smoke.mjs` drives it with two WebSocket clients using Node 22's
built-in client, so it needs no dependencies:

```bash
npx wrangler dev            # terminal 1
node scripts/smoke.mjs      # terminal 2
```

It checks the health route, the edge rejection of a malformed session id, the 426 without an upgrade
header, fan-out in both directions, that a sender never receives its own frame, that a third member
is refused, that a reconnecting member is replayed exactly the frames it missed and none of its own,
and that an oversized frame closes the socket with 1009.

### On the hostname

`workers.dev` is fine for development, and Cloudflare's own guidance is that it is *"intended for
personal or hobby projects that aren't business-critical"* — a shared free suffix that some corporate
and ISP DNS filters block wholesale. When there is a production domain, point `relay.<domain>` at the
same Worker and change `RELAY_BASE_URL`; no code changes.
