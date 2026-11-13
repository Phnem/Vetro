import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker from "../src/worker.js";
import { APP_JS } from "../src/page.js";
import { sha256Hex } from "../../books-api/src/access.js";

const TEAM = "vetro.cloudflareaccess.com";
const AUD = "aud-123";
const OWNER = "owner@example.com";
const NOW = 1_794_600_000_000;

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

function freshEnv() {
  const sqlite = new DatabaseSync(":memory:");
  for (const f of ["../../books-api/schema/access.sql", "../../books-api/schema/bot.sql", "../schema/admin.sql"]) {
    sqlite.exec(readFileSync(new URL(f, import.meta.url), "utf8"));
  }
  sqlite.exec("INSERT INTO customers (id, telegram_id, username, first_name, created_at) VALUES (1, 111, 'buyer', 'B', 1)");
  sqlite.exec("INSERT INTO applications (id, customer_id, app_name, description, kind, tier, status, created_at, updated_at) VALUES (1, 1, 'My <b>App</b>', 'd', 'website', 'pro', 'issued', 1, 1)");
  sqlite.exec("INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, customer_id, application_id, created_at) VALUES ('k_aaaaaaaaaaaa', 'h1', 'vk_pro_abcd…', 'pro', 'active', 1, 1, 1)");
  sqlite.exec("INSERT INTO installs (id, package, signature, security_level, first_seen, last_seen) VALUES ('" + "a".repeat(32) + "', 'com.phnem.vetro', 'sig', 'tee', 1, 1)");
  return { env: { DB: fakeD1(sqlite), ACCESS_TEAM_DOMAIN: TEAM, ACCESS_AUD: AUD, OWNER_EMAIL: OWNER }, sqlite };
}

// --- подставной Cloudflare Access: настоящая пара RSA и подписанные токены ----------------------------------------------
const rsa = () => crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
const pair = await rsa();
const jwk = { ...(await crypto.subtle.exportKey("jwk", pair.publicKey)), kid: "kid1" };
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");

async function token(over = {}, key = pair.privateKey) {
  const head = b64({ alg: "RS256", kid: "kid1", typ: "JWT" });
  const body = b64({ iss: `https://${TEAM}`, aud: [AUD], email: OWNER, exp: NOW / 1000 + 600, ...over });
  const sig = Buffer.from(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(head + "." + body))).toString("base64url");
  return `${head}.${body}.${sig}`;
}

const deps = { fetchJwks: async () => ({ keys: [jwk] }), now: NOW };

async function call(env, path, { method = "GET", body, jwt, headers = {} } = {}) {
  const h = { "cf-access-jwt-assertion": jwt ?? (await token()), ...headers };
  if (method !== "GET") h["x-dash"] = "1";
  if (body !== undefined) h["content-type"] = "application/json";
  const res = await worker.fetch(new Request("https://dash.vetrostudio.bid" + path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) }), env, {}, deps);
  const text = await res.text();
  let data = null;
  try { data = JSON.parse(text); } catch { /* страница или скрипт */ }
  return { status: res.status, data, res, text };
}

test("без настроенного Access панель закрыта целиком", async () => {
  const { env } = freshEnv();
  env.ACCESS_AUD = "";
  assert.equal((await call(env, "/api/overview")).status, 503);
  assert.equal((await call(env, "/")).status, 503);
});

test("токен Access: нет, чужой aud, просрочен, чужая подпись, чужой владелец", async () => {
  const { env } = freshEnv();
  const raw = (jwt) => worker.fetch(new Request("https://dash.vetrostudio.bid/api/overview", { headers: jwt ? { "cf-access-jwt-assertion": jwt } : {} }), env, {}, deps);
  assert.equal((await raw(null)).status, 401);
  assert.equal((await raw(await token({ aud: ["other"] }))).status, 401);
  assert.equal((await raw(await token({ exp: NOW / 1000 - 10 }))).status, 401);
  assert.equal((await raw(await token({ iss: "https://evil.example" }))).status, 401);
  assert.equal((await raw(await token({ email: "someone@else.com" }))).status, 401);
  assert.equal((await raw(await token({}, (await rsa()).privateKey))).status, 401);
  assert.equal((await raw(await token())).status, 200);
});

test("страница и скрипты отдаются с CSP, в скрипте нет innerHTML", async () => {
  const { env } = freshEnv();
  const r = await call(env, "/");
  assert.equal(r.status, 200);
  assert.match(r.res.headers.get("content-security-policy"), /script-src 'self'/);
  assert.equal(APP_JS.includes("innerHTML"), false);
  assert.equal((await call(env, "/app.js")).status, 200);
});

test("изменения без заголовка панели или с чужим Origin отклоняются", async () => {
  const { env } = freshEnv();
  const bare = await worker.fetch(new Request("https://dash.vetrostudio.bid/api/keys/k_aaaaaaaaaaaa/revoke", { method: "POST", headers: { "cf-access-jwt-assertion": await token() } }), env, {}, deps);
  assert.equal(bare.status, 403);
  const evil = await call(env, "/api/keys/k_aaaaaaaaaaaa/revoke", { method: "POST", body: {}, headers: { origin: "https://evil.example" } });
  assert.equal(evil.status, 403);
});

test("новый ключ: открытый ключ показывается один раз, в базе хэш, в журнале ключа нет", async () => {
  const { env, sqlite } = freshEnv();
  const r = await call(env, "/api/keys", { method: "POST", body: { tier: "ultra", label: "site", origins: ["VetroStudio.bid", "*.example.com"] } });
  assert.equal(r.status, 200);
  assert.match(r.data.key, /^vk_ultra_[a-z2-7]{32}$/);
  const row = sqlite.prepare("SELECT key_hash, allowed_origins, expires_at, owner FROM api_keys WHERE id = ?").get(r.data.id);
  assert.equal(row.key_hash, await sha256Hex(r.data.key));
  assert.equal(row.expires_at, null);
  assert.equal(row.owner, "partner:site");
  assert.deepEqual(JSON.parse(row.allowed_origins), ["vetrostudio.bid", "*.example.com"]);
  const logged = JSON.stringify(sqlite.prepare("SELECT * FROM admin_log").all());
  assert.equal(logged.includes(r.data.key), false);
  assert.match(logged, /key\.create/);
  const list = (await call(env, "/api/keys")).text;
  assert.equal(list.includes(r.data.key), false);
  assert.equal(list.includes("key_hash"), false);
});

test("ключ: срок, неверные поля, отзыв и возврат", async () => {
  const { env, sqlite } = freshEnv();
  assert.equal((await call(env, "/api/keys", { method: "POST", body: { tier: "nope", label: "x" } })).status, 400);
  assert.equal((await call(env, "/api/keys", { method: "POST", body: { tier: "basic", label: "x", origins: ["bad host!"] } })).status, 400);
  assert.equal((await call(env, "/api/keys", { method: "POST", body: { tier: "basic", label: "x", days: -3 } })).status, 400);
  const ok = await call(env, "/api/keys", { method: "POST", body: { tier: "basic", label: "x", days: 30 } });
  assert.equal(sqlite.prepare("SELECT expires_at FROM api_keys WHERE id = ?").get(ok.data.id).expires_at > 0, true);
  assert.equal((await call(env, "/api/keys/k_aaaaaaaaaaaa/revoke", { method: "POST", body: {} })).status, 200);
  assert.equal(sqlite.prepare("SELECT status FROM api_keys WHERE id = 'k_aaaaaaaaaaaa'").get().status, "revoked");
  assert.equal((await call(env, "/api/keys/k_aaaaaaaaaaaa/restore", { method: "POST", body: {} })).status, 200);
  assert.equal(sqlite.prepare("SELECT status, revoked_at FROM api_keys WHERE id = 'k_aaaaaaaaaaaa'").get().revoked_at, null);
  assert.equal((await call(env, "/api/keys/k_bbbbbbbbbbbb/revoke", { method: "POST", body: {} })).status, 404);
  assert.equal((await call(env, "/api/keys/zzz/revoke", { method: "POST", body: {} })).status, 400);
});

test("отклонение заявки отзывает ключи и ставит в очередь экранированное уведомление", async () => {
  const { env, sqlite } = freshEnv();
  const r = await call(env, "/api/applications/1/reject", { method: "POST", body: { reason: "<script>x</script>" } });
  assert.equal(r.status, 200);
  assert.equal(sqlite.prepare("SELECT status FROM applications WHERE id = 1").get().status, "rejected");
  assert.equal(sqlite.prepare("SELECT status FROM api_keys WHERE id = 'k_aaaaaaaaaaaa'").get().status, "revoked");
  const out = sqlite.prepare("SELECT chat_id, text, sent_at FROM bot_outbox").get();
  assert.equal(out.chat_id, 111);
  assert.equal(out.sent_at, null);
  assert.equal(out.text.includes("<script>"), false);
  assert.match(out.text, /My &lt;b&gt;App&lt;\/b&gt;/);
  assert.equal((await call(env, "/api/applications/99/reject", { method: "POST", body: { reason: "x" } })).status, 404);
  assert.equal((await call(env, "/api/applications/1/reject", { method: "POST", body: {} })).status, 400);
});

test("бан покупателя отзывает его ключи, сообщение уходит в очередь", async () => {
  const { env, sqlite } = freshEnv();
  assert.equal((await call(env, "/api/customers/1/ban", { method: "POST", body: {} })).status, 200);
  assert.equal(sqlite.prepare("SELECT banned FROM customers WHERE id = 1").get().banned, 1);
  assert.equal(sqlite.prepare("SELECT status FROM api_keys WHERE id = 'k_aaaaaaaaaaaa'").get().status, "revoked");
  assert.equal((await call(env, "/api/message", { method: "POST", body: { customer_id: 1, text: "привет <i>" } })).status, 200);
  assert.equal(sqlite.prepare("SELECT text FROM bot_outbox").get().text, "привет &lt;i&gt;");
});

test("установки и подписи APK", async () => {
  const { env, sqlite } = freshEnv();
  const id = "a".repeat(32);
  assert.equal((await call(env, `/api/installs/${id}/revoke`, { method: "POST", body: {} })).status, 200);
  assert.equal(sqlite.prepare("SELECT status FROM installs WHERE id = ?").get(id).status, "revoked");
  assert.equal((await call(env, "/api/installs/short/revoke", { method: "POST", body: {} })).status, 400);
  const sha = "AB:" + "cd".repeat(30) + ":EF";
  assert.equal((await call(env, "/api/signatures", { method: "POST", body: { package: "com.phnem.vetro", sha256: "xyz" } })).status, 400);
  assert.equal((await call(env, "/api/signatures", { method: "POST", body: { package: "com.phnem.vetro", sha256: sha, label: "fdroid" } })).status, 200);
  const stored = sqlite.prepare("SELECT sha256, enabled FROM app_signatures").get();
  assert.match(stored.sha256, /^ab(cd){30}ef$/);
  assert.equal((await call(env, "/api/signatures/toggle", { method: "POST", body: { package: "com.phnem.vetro", sha256: stored.sha256, enabled: false } })).status, 200);
  assert.equal(sqlite.prepare("SELECT enabled FROM app_signatures").get().enabled, 0);
});

test("тарифы: валидация и запись, журнал", async () => {
  const { env, sqlite } = freshEnv();
  assert.equal((await call(env, "/api/tiers/pro", { method: "PATCH", body: { per_minute: -1 } })).status, 400);
  assert.equal((await call(env, "/api/tiers/pro", { method: "PATCH", body: { per_day: 1.5 } })).status, 400);
  assert.equal((await call(env, "/api/tiers/nope", { method: "PATCH", body: { per_day: 5 } })).status, 400);
  assert.equal((await call(env, "/api/tiers/pro", { method: "PATCH", body: { per_day: 12345, allow_covers: false, price_stars: 450 } })).status, 200);
  const t = sqlite.prepare("SELECT per_day, allow_covers, price_stars FROM tiers WHERE name = 'pro'").get();
  assert.deepEqual({ ...t }, { per_day: 12345, allow_covers: 0, price_stars: 450 });
  const log = (await call(env, "/api/log")).data.log;
  assert.equal(log[0].action, "tier.update");
  assert.equal(log[0].actor, OWNER);
});

test("обзор считает ключи, установки и очередь", async () => {
  const { env } = freshEnv();
  const o = (await call(env, "/api/overview")).data;
  assert.equal(o.keys.find((k) => k.tier === "pro").n, 1);
  assert.equal(o.installs[0].n, 1);
  assert.equal(o.outbox_pending, 0);
});
