/**
 * End-to-end smoke test for the relay. Dependency-free: Node 22's built-in
 * WebSocket client against a local `wrangler dev`, which runs the real Durable
 * Object and its real SQLite in workerd. Nothing is deployed.
 *
 *   npx wrangler dev          # terminal 1
 *   node scripts/smoke.mjs    # terminal 2
 */
const BASE = process.env.RELAY_BASE ?? "http://127.0.0.1:8787";
const WS_BASE = BASE.replace(/^http/, "ws");

let failures = 0;

function check(name, ok, detail = "") {
    if (ok) {
        console.log(`  ok    ${name}`);
    } else {
        failures += 1;
        console.log(`  FAIL  ${name}${detail ? " — " + detail : ""}`);
    }
}

const randomSid = () =>
    Buffer.from(crypto.getRandomValues(new Uint8Array(16))).toString("base64url");

function connect(sid, member) {
    return new Promise((resolve, reject) => {
        const ws = new WebSocket(`${WS_BASE}/s/${sid}?m=${member}`);
        const inbox = [];
        ws.addEventListener("message", (e) => inbox.push(e.data));
        ws.addEventListener("open", () => resolve({ ws, inbox }));
        ws.addEventListener("error", () => reject(new Error("refused")));
    });
}

/** The relay is a network: give a frame a moment to make the round trip. */
const settle = (ms = 250) => new Promise((r) => setTimeout(r, ms));

async function main() {
    console.log(`relay smoke test against ${BASE}\n`);

    check("health returns ok", (await (await fetch(`${BASE}/health`)).text()) === "ok");
    check("a short session id is rejected", (await fetch(`${BASE}/s/short`)).status === 400);
    check("an unknown path is 404", (await fetch(`${BASE}/nope`)).status === 404);
    check(
        "a valid id without an upgrade header is 426",
        (await fetch(`${BASE}/s/${randomSid()}?m=aaaaaaaa`)).status === 426,
    );

    // --- fan-out ---
    const sid = randomSid();
    const a = await connect(sid, "member-aaaa");
    const b = await connect(sid, "member-bbbb");
    await settle();

    a.ws.send('{"type":"hello","lang":"ro"}');
    await settle();
    check("B receives what A sent", b.inbox.length === 1 && b.inbox[0].includes('"lang":"ro"'));
    check("A does not receive its own frame", a.inbox.length === 0);

    b.ws.send('{"type":"hello","lang":"en"}');
    await settle();
    check("A receives what B sent", a.inbox.length === 1 && a.inbox[0].includes('"lang":"en"'));

    // --- a third member is refused ---
    let refused = false;
    try {
        await connect(sid, "member-cccc");
    } catch {
        refused = true;
    }
    check("a third member is refused", refused);

    // --- reconnect replay ---
    b.ws.close();
    await settle();
    a.ws.send('{"type":"turn","id":"t1"}');
    a.ws.send('{"type":"turn","id":"t2"}');
    await settle();

    const bAgain = await connect(sid, "member-bbbb");
    await settle();
    const replayed = bAgain.inbox.join("|");
    check(
        "B is replayed the turns it missed",
        replayed.includes('"t1"') && replayed.includes('"t2"'),
        replayed,
    );
    check(
        "the replay does not include B's own frames",
        !replayed.includes('"lang":"en"'),
        replayed,
    );

    // --- frame cap ---
    const big = await connect(randomSid(), "member-dddd");
    const closed = new Promise((resolve) =>
        big.ws.addEventListener("close", (e) => resolve(e.code)),
    );
    big.ws.send("x".repeat(17 * 1024));
    check("an oversized frame closes the socket with 1009", (await closed) === 1009);

    a.ws.close();
    bAgain.ws.close();

    console.log(failures === 0 ? "\nall checks passed" : `\n${failures} check(s) FAILED`);
    process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
