/**
 * uitr-relay — the two-device conversation relay (issue #33 Phase 1).
 *
 * A dumb fan-out pipe and nothing else. It does no translation, has no accounts,
 * holds no user data beyond a short replay ring, and — once #34 lands — cannot read
 * a single message it carries: every frame except the two handshake frames is
 * AES-256-GCM ciphertext whose key travelled from one phone's screen to the other
 * phone's camera, never across this wire.
 *
 * One Durable Object per session id. At most two members. Frames from one member are
 * fanned out to the other and appended to a small per-member ring so a ten-second
 * network blip does not lose a turn.
 *
 * The ring lives in the Durable Object's own SQLite, NOT in a field, because
 * hibernation — the thing that makes this free — resets in-memory state, and
 * `serializeAttachment` is lost when a connection closes, which is exactly the case
 * the ring exists for.
 */
import { DurableObject } from "cloudflare:workers";

export interface Env {
    SESSIONS: DurableObjectNamespace<SessionRelay>;
}

/**
 * 128 bits, base64url, unpadded. Issue #33 wrote this as `^[a-z0-9]{22}$`; 22
 * characters of lowercase-alphanumeric is only ~113 bits, so the character class is
 * widened to real base64url to get the 128 the design asks for. Same length, same QR
 * size, nothing else changes.
 */
const SID_PATTERN = /^[A-Za-z0-9_-]{22}$/;

/** A per-session random device id. Not an identity, and not a secret. */
const MEMBER_PATTERN = /^[A-Za-z0-9_-]{8,64}$/;

const MAX_MEMBERS = 2;
/** Generous for a turn of speech, even with base64 ciphertext overhead. */
const MAX_FRAME_CHARS = 16 * 1024;
/** Per member. Two people talking do not get twenty turns ahead of a blip. */
const RING_PER_MEMBER = 20;
const SESSION_TTL_MS = 2 * 60 * 60 * 1000;
const ALARM_INTERVAL_MS = 5 * 60 * 1000;
const RATE_WINDOW_MS = 10_000;
const RATE_MAX_FRAMES = 40;

export class SessionRelay extends DurableObject<Env> {
    constructor(ctx: DurableObjectState, env: Env) {
        super(ctx, env);
        // Runs on every wake, hence IF NOT EXISTS. This storage is private to this
        // one Durable Object — no other session, and no other Worker, can read it.
        ctx.storage.sql.exec(`
            CREATE TABLE IF NOT EXISTS ring (
                seq    INTEGER PRIMARY KEY AUTOINCREMENT,
                member TEXT    NOT NULL,
                ts     INTEGER NOT NULL,
                body   TEXT    NOT NULL
            );
            CREATE TABLE IF NOT EXISTS meta (
                k TEXT PRIMARY KEY,
                v INTEGER NOT NULL
            );
        `);
    }

    async fetch(request: Request): Promise<Response> {
        if (request.headers.get("Upgrade") !== "websocket") {
            return new Response("expected a websocket upgrade", { status: 426 });
        }

        const member = new URL(request.url).searchParams.get("m") ?? "";
        if (!MEMBER_PATTERN.test(member)) {
            return new Response("bad member id", { status: 400 });
        }

        const now = Date.now();
        const createdAt = this.readMeta("created_at");
        if (createdAt === null) {
            this.writeMeta("created_at", now);
            await this.ctx.storage.setAlarm(now + ALARM_INTERVAL_MS);
        } else if (now - createdAt > SESSION_TTL_MS) {
            return new Response("session expired", { status: 410 });
        }

        const open = this.ctx.getWebSockets();
        const members = new Set(open.map((ws) => this.memberOf(ws)));
        if (!members.has(member) && members.size >= MAX_MEMBERS) {
            return new Response("session full", { status: 409 });
        }
        // The same member reconnecting after a blip replaces its own socket rather
        // than counting as a third party.
        for (const ws of open) {
            if (this.memberOf(ws) === member) ws.close(1000, "replaced");
        }

        const pair = new WebSocketPair();
        const server = pair[1];
        // The tag is how a hibernated object still knows who a socket belongs to;
        // attachments would not survive the close this ring exists to recover from.
        this.ctx.acceptWebSocket(server, [member]);

        // Replay what the *other* member said. Duplicates are the client's problem
        // and it is equipped for them: turn ids in #33, and the #34 record counter,
        // which drops anything at or below the highest frame already accepted.
        const missed = this.ctx.storage.sql.exec<{ body: string }>(
            "SELECT body FROM ring WHERE member != ? ORDER BY seq",
            member,
        );
        for (const row of missed) server.send(row.body);

        return new Response(null, { status: 101, webSocket: pair[0] });
    }

    async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
        if (typeof message !== "string") {
            ws.close(1003, "text frames only");
            return;
        }
        if (message.length > MAX_FRAME_CHARS) {
            ws.close(1009, "frame too large");
            return;
        }
        if (!this.allowFrame()) {
            ws.close(1008, "rate limit");
            return;
        }

        const member = this.memberOf(ws);
        this.ctx.storage.sql.exec(
            "INSERT INTO ring (member, ts, body) VALUES (?, ?, ?)",
            member,
            Date.now(),
            message,
        );
        this.pruneRing(member);

        for (const peer of this.ctx.getWebSockets()) {
            if (this.memberOf(peer) === member) continue;
            try {
                peer.send(message);
            } catch {
                // A peer that died between getWebSockets() and send() is not an error
                // worth failing the sender for; it will reconnect and get the ring.
            }
        }
    }

    async alarm(): Promise<void> {
        const createdAt = this.readMeta("created_at");
        const now = Date.now();
        if (createdAt !== null && now - createdAt > SESSION_TTL_MS) {
            for (const ws of this.ctx.getWebSockets()) ws.close(1000, "session expired");
            await this.ctx.storage.deleteAll();
            return;
        }
        await this.ctx.storage.setAlarm(now + ALARM_INTERVAL_MS);
    }

    // --- helpers ---

    private memberOf(ws: WebSocket): string {
        return this.ctx.getTags(ws)[0] ?? "";
    }

    private pruneRing(member: string): void {
        this.ctx.storage.sql.exec(
            `DELETE FROM ring
              WHERE member = ?1
                AND seq NOT IN (
                    SELECT seq FROM ring WHERE member = ?1 ORDER BY seq DESC LIMIT ?2
                )`,
            member,
            RING_PER_MEMBER,
        );
    }

    /** Per-session cap. Zone-level rate limiting does not apply on workers.dev. */
    private allowFrame(): boolean {
        const now = Date.now();
        const started = this.readMeta("rate_start");
        if (started === null || now - started > RATE_WINDOW_MS) {
            this.writeMeta("rate_start", now);
            this.writeMeta("rate_count", 1);
            return true;
        }
        const count = (this.readMeta("rate_count") ?? 0) + 1;
        this.writeMeta("rate_count", count);
        return count <= RATE_MAX_FRAMES;
    }

    private readMeta(key: string): number | null {
        const rows = this.ctx.storage.sql
            .exec<{ v: number }>("SELECT v FROM meta WHERE k = ?", key)
            .toArray();
        return rows.length > 0 ? rows[0].v : null;
    }

    private writeMeta(key: string, value: number): void {
        this.ctx.storage.sql.exec(
            "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            key,
            value,
        );
    }
}

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        const url = new URL(request.url);

        if (url.pathname === "/health") {
            return new Response("ok", { status: 200 });
        }

        const match = /^\/s\/([^/]+)$/.exec(url.pathname);
        if (match === null) {
            return new Response("not found", { status: 404 });
        }
        const sid = match[1];
        if (!SID_PATTERN.test(sid)) {
            // Rejected at the edge so a malformed or probing id never allocates a
            // Durable Object, and never costs a request against the daily quota.
            return new Response("bad session id", { status: 400 });
        }

        return env.SESSIONS.get(env.SESSIONS.idFromName(sid)).fetch(request);
    },
} satisfies ExportedHandler<Env>;
