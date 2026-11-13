import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { authorize, b64url, issueAppToken } from "../src/access.js";
import { handleAuth, newChallenge, verifyChallenge } from "../src/auth.js";
import worker from "../src/worker.js";

const fx = JSON.parse(readFileSync(new URL("./fixtures/attestation-xiaomi13.json", import.meta.url), "utf8"));
const NOW = fx.captured_at_ms;
const SIG = "74444e41d79317d71eff7fd77367603c70fb5e365cd75c7e849170e8c5f2962a";
const SECRET = "test-secret";
const fixtureChallenge = Uint8Array.from(Buffer.from(fx.challenge, "base64"));

const edgeCache = new Map();
globalThis.caches = {
  default: {
    match: async (req) => edgeCache.get(req.url)?.clone(),
    put: async (req, res) => { edgeCache.set(req.url, res.clone()); },
  },
};

function fakeD1(sqlite) {
  return {
    prepare(sql) {
      const stmt = sqlite.prepare(sql);
      const make = (args) => ({
        all: async () => ({ results: stmt.all(...args) }),
        first: async () => stmt.get(...args) ?? null,
        run: async () => { stmt.run(...args); return { success: true }; },
      });
      return { ...make([]), bind: (...args) => make(args) };
    },
  };
}

function env(extra = {}, { allowSignature = true } = {}) {
  edgeCache.clear();
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../schema/access.sql", import.meta.url), "utf8"));
  if (allowSignature) sqlite.prepare("INSERT INTO app_signatures (package, sha256, label) VALUES ('com.phnem.vetro.perf', ?, 'debug-perf')").run(SIG);
  return { DB: fakeD1(sqlite), sqlite, APP_TOKEN_SECRET: SECRET, ACCESS_MODE: "enforce", ...extra };
}

const testDeps = { verifyChallenge: async () => fixtureChallenge };
const post = (body) => new Request("https://api.vetrostudio.bid/v1/auth/register", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) });
const register = (e, body = { challenge: "x", chain: fx.chain }, now = NOW, deps = testDeps) => handleAuth(post(body), e, now, deps);
const manifestCall = (e, token, now) =>
  authorize(new Request("https://api.vetrostudio.bid/v1/manifest", { headers: { authorization: `Bearer ${token}` } }), e, now);

test("challenges are signed, expire and cannot be forged", async () => {
  const c = await newChallenge(SECRET, NOW);
  assert.equal(c.length, 36);
  const text = b64url(c);
  assert.deepEqual(await verifyChallenge(SECRET, text, NOW + 60_000), c);
  assert.equal(await verifyChallenge(SECRET, text, NOW + 121_000), null); // две минуты вышли
  assert.equal(await verifyChallenge("other", text, NOW), null);
  const forged = Uint8Array.from(c);
  forged[3] ^= 1;
  assert.equal(await verifyChallenge(SECRET, b64url(forged), NOW), null);
  assert.equal(await verifyChallenge(SECRET, "", NOW), null);
  assert.equal(await verifyChallenge(SECRET, "AAAA", NOW), null);
  assert.notDeepEqual(await newChallenge(SECRET, NOW), c); // каждый раз новое
});

test("the real chain registers: token, install row, and the token then opens the API", async () => {
  const e = env();
  const res = await register(e);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.match(body.token, /^va_/);
  assert.match(body.install_id, /^[0-9a-f]{32}$/);
  assert.equal(body.expires_at, NOW + 30 * 86_400_000);

  const row = e.sqlite.prepare("SELECT * FROM installs WHERE id = ?").get(body.install_id);
  assert.deepEqual([row.package, row.signature, row.security_level, row.boot_state, row.tokens_issued, row.status],
    ["com.phnem.vetro.perf", SIG, "tee", "unverified", 1, "active"]);

  const gate = await manifestCall(e, body.token, NOW + 1000);
  assert.equal(gate.response, undefined);
  assert.deepEqual(gate.principal, { kind: "app", id: body.install_id, tier: "app" });

  // повторная регистрация того же телефона: тот же id, счётчик растёт
  const again = await (await register(e, undefined, NOW + 5000)).json();
  assert.equal(again.install_id, body.install_id);
  assert.equal(e.sqlite.prepare("SELECT tokens_issued FROM installs WHERE id = ?").get(body.install_id).tokens_issued, 2);
});

test("a revoked install loses its token within a minute and cannot register again", async () => {
  const e = env();
  const { token, install_id: id } = await (await register(e)).json();
  assert.equal((await manifestCall(e, token, NOW + 1000)).response, undefined);
  e.sqlite.prepare("UPDATE installs SET status = 'revoked' WHERE id = ?").run(id);
  edgeCache.clear(); // минута кэша списка отозванных
  const denied = await manifestCall(e, token, NOW + 70_000);
  assert.equal(denied.response.status, 403);
  assert.equal((await denied.response.json()).error, "install_revoked");
  const res = await register(e, undefined, NOW + 80_000);
  assert.equal(res.status, 403);
  assert.equal((await res.json()).error, "install_revoked");
});

test("registration is refused for: unknown signature, empty allow-list, tampered chain, wrong challenge, strict boot policy", async () => {
  const reason = async (res) => (await res.json()).reason;
  const noSig = env({}, { allowSignature: false });
  assert.equal(await reason(await register(noSig)), "package_not_allowed");

  const otherSig = env({}, { allowSignature: false });
  otherSig.sqlite.prepare("INSERT INTO app_signatures (package, sha256) VALUES ('com.phnem.vetro.perf', ?)").run("ab".repeat(32));
  assert.equal(await reason(await register(otherSig)), "signature_not_allowed");

  const disabled = env();
  disabled.sqlite.prepare("UPDATE app_signatures SET enabled = 0").run();
  assert.equal(await reason(await register(disabled)), "package_not_allowed");

  const tampered = fx.chain.slice();
  const leaf = Buffer.from(tampered[0], "base64");
  leaf[leaf.length - 10] ^= 0xff;
  tampered[0] = leaf.toString("base64");
  const r = await register(env(), { challenge: "x", chain: tampered });
  assert.equal(r.status, 403);
  assert.equal(await reason(r), "bad_signature");

  const wrongChallenge = await register(env(), undefined, NOW, { verifyChallenge: async () => new Uint8Array(36) });
  assert.equal(await reason(wrongChallenge), "challenge_mismatch");

  const strict = env({ ATTEST_REJECT_BOOT_STATES: "failed, unverified" }); // телефон с разблокированным загрузчиком
  assert.equal(await reason(await register(strict)), "boot_state_rejected");

  const expired = await register(env(), undefined, NOW + 400 * 86_400_000);
  assert.equal(await reason(expired), "certificate_expired");
});

test("malformed registrations are 400, an unknown challenge is 400, a missing secret is 503", async () => {
  const e = env();
  const status = async (body, deps) => (await register(e, body, NOW, deps)).status;
  assert.equal(await status("not json"), 400);
  assert.equal(await status({ challenge: "x" }), 400); // нет chain
  assert.equal(await status({ challenge: "x", chain: [fx.chain[0], "@@@"] }), 400);
  assert.equal(await status({ challenge: "x", chain: ["QUFB".repeat(2000), fx.chain[1]] }), 400); // сертификат больше 4 КБ
  assert.equal(await status("x".repeat(30_000)), 400);
  assert.equal(await status({ challenge: "AAAA", chain: fx.chain }, {}), 400); // настоящая проверка задания: чужое задание
  assert.equal((await register(env({ APP_TOKEN_SECRET: undefined }))).status, 503);
  const get = await handleAuth(new Request("https://api.vetrostudio.bid/v1/auth/register"), e, NOW);
  assert.equal(get.status, 405);
});

test("the Worker serves the challenge without a key and registers over POST with CORS", async () => {
  const e = env();
  const ctx = { waitUntil() {} };
  const res = await worker.fetch(new Request("https://api.vetrostudio.bid/v1/auth/challenge"), e, ctx);
  assert.equal(res.status, 200); // режим enforce, но без ключа: за токеном и приходят
  assert.equal(res.headers.get("access-control-allow-origin"), "*");
  const { challenge, expires_in } = await res.json();
  assert.equal(expires_in, 120);
  assert.equal((await verifyChallenge(SECRET, challenge, Date.now())).length, 36);

  // настоящая цепочка снята на другое задание: отказ (либо по заданию, либо, через неделю, по сроку промежуточного сертификата)
  const reg = await worker.fetch(new Request("https://api.vetrostudio.bid/v1/auth/register", { method: "POST", body: JSON.stringify({ challenge, chain: fx.chain }) }), e, ctx);
  assert.equal(reg.status, 403);
  assert.ok(["challenge_mismatch", "certificate_expired"].includes((await reg.json()).reason));

  const pre = await worker.fetch(new Request("https://api.vetrostudio.bid/v1/auth/register", { method: "OPTIONS" }), e, ctx);
  assert.match(pre.headers.get("access-control-allow-methods"), /POST/);
  assert.match(pre.headers.get("access-control-allow-headers"), /content-type/);
});

test("an expired or foreign app token is refused", async () => {
  const e = env();
  const good = await issueAppToken(SECRET, "install-x", 3600, NOW);
  assert.equal((await manifestCall(e, good, NOW + 1000)).response, undefined);
  assert.equal((await (await manifestCall(e, good, NOW + 3601_000)).response.json()).error, "expired_key");
  const foreign = await issueAppToken("not-our-secret", "install-x", 3600, NOW);
  assert.equal((await (await manifestCall(e, foreign, NOW + 1000)).response.json()).error, "invalid_key");
});
