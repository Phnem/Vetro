import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker from "../src/worker.js";
import {
  KeyMeter, authorize, issueAppToken, newApiKey, originAllowed, routeOf, sha256Hex, verifyAppToken,
} from "../src/access.js";

// --- подставные окружение: D1 на настоящем SQLite, кэш в памяти, R2 из Map, Durable Object на подставном хранилище -------------
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

function fakeState() {
  const kv = new Map();
  return {
    kv,
    storage: {
      get: async (k) => kv.get(k),
      put: async (k, v) => { kv.set(k, structuredClone(v)); },
      setAlarm: async () => {},
    },
  };
}

function meterNamespace(env) {
  const objects = new Map();
  return {
    objects,
    idFromName: (name) => name,
    get(id) {
      if (!objects.has(id)) objects.set(id, new KeyMeter(fakeState(), env));
      const o = objects.get(id);
      return { fetch: (url, init) => o.fetch(new Request(url, init)) };
    },
  };
}

const bytes = (s) => new TextEncoder().encode(s);
function fakeR2() {
  const files = new Map([
    ["index/manifest.json", { b: bytes('{"version":2}'), type: "application/json" }],
    ["covers/small/000000771.avif", { b: new Uint8Array([1, 2, 3]), type: "image/avif" }],
  ]);
  return {
    async get(key) {
      const f = files.get(key);
      if (!f) return null;
      return { size: f.b.length, httpEtag: '"e"', writeHttpMetadata: (h) => h.set("content-type", f.type), body: new Blob([f.b]).stream() };
    },
  };
}

function env(extra = {}) {
  edgeCache.clear();
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(readFileSync(new URL("../schema/access.sql", import.meta.url), "utf8"));
  const e = { DB: fakeD1(sqlite), sqlite, BOOKS: fakeR2(), ACCESS_MODE: "enforce", ...extra };
  e.METER = meterNamespace(e);
  return e;
}

/** Выдать ключ тарифа tier напрямую в базу; возвращает открытый ключ. */
async function addKey(e, tier, fields = {}) {
  const k = await newApiKey(tier);
  e.sqlite
    .prepare("INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, created_at, expires_at, allowed_origins) VALUES (?,?,?,?,?,?,?,?)")
    .run(k.id, k.hash, k.prefix, tier, fields.status ?? "active", Date.now(), fields.expires_at ?? null, fields.allowed_origins ?? null);
  return { ...k };
}

const T0 = Date.UTC(2026, 10, 13, 12, 0, 0, 0);
const req = (path, headers = {}) => new Request("https://api.vetrostudio.bid" + path, { headers });
const bearer = (key) => ({ authorization: `Bearer ${key}` });
const errorOf = async (gate) => (await gate.response.json()).error;

test("access.sql and bot.sql load in order, twice, and tie a key to its application and order", () => {
  const db = new DatabaseSync(":memory:");
  const load = (name) => db.exec(readFileSync(new URL(`../schema/${name}`, import.meta.url), "utf8"));
  load("access.sql"); load("bot.sql"); load("access.sql"); load("bot.sql");
  db.exec("PRAGMA foreign_keys = ON");
  assert.deepEqual(db.prepare("SELECT name FROM tiers WHERE sold = 1 ORDER BY sort").all().map((r) => r.name), ["basic", "pro", "ultra"]);
  const now = Date.now();
  db.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (111, 'buyer', ?)").run(now);
  db.prepare(
    "INSERT INTO applications (customer_id, app_name, description, kind, origins, tier, status, terms_accepted_at, created_at, updated_at) " +
      "VALUES (1, 'My Reader', 'Reading tracker', 'website', '[\"reader.example\"]', 'pro', 'new', ?, ?, ?)",
  ).run(now, now, now);
  db.prepare("INSERT INTO orders (application_id, customer_id, tier, stars, invoice_payload, created_at) VALUES (1, 1, 'pro', 400, 'p-1', ?)").run(now);
  db.prepare("INSERT INTO api_keys (id, key_hash, key_prefix, tier, customer_id, application_id, created_at) VALUES ('k_1', 'h', 'vk_pro_x…', 'pro', 1, 1, ?)").run(now);
  db.prepare("UPDATE orders SET charge_id = 'charge-1', status = 'paid', key_id = 'k_1' WHERE id = 1").run();
  // повторная доставка того же платежа не создаёт второй оплаченный заказ
  assert.throws(() => db.prepare("INSERT INTO orders (application_id, customer_id, tier, stars, invoice_payload, charge_id, created_at) VALUES (1, 1, 'pro', 400, 'p-2', 'charge-1', ?)").run(now));
  assert.throws(() => db.prepare("INSERT INTO applications (customer_id, app_name, description, kind, tier, created_at, updated_at) VALUES (1, 'x', 'y', 'website', 'gold', ?, ?)").run(now, now)); // нет такого тарифа
});

test("routeOf classifies every public path", () => {
  assert.equal(routeOf("/v1/health"), "health");
  assert.equal(routeOf("/v1/search"), "search");
  assert.equal(routeOf("/v1/books"), "books");
  assert.equal(routeOf("/v1/books/000000771"), "books");
  assert.equal(routeOf("/covers/large/000000771.avif"), "covers");
  assert.equal(routeOf("/v1/manifest"), "index");
  assert.equal(routeOf("/v1/index/base/bookindex-v2.sqlite.zst"), "index");
  assert.equal(routeOf("/v1/auth/challenge"), "auth");
  assert.equal(routeOf("/nope"), "other");
});

test("a new key is shaped vk_<tier>_<32>, only its hash is stored", async () => {
  const k = await newApiKey("pro");
  assert.match(k.plain, /^vk_pro_[a-z2-7]{32}$/);
  assert.match(k.id, /^k_[0-9a-f]{12}$/);
  assert.equal(k.hash, await sha256Hex(k.plain));
  assert.ok(k.prefix.startsWith("vk_pro_") && k.prefix.endsWith("…") && !k.prefix.includes(k.plain.slice(14)));
});

test("app tokens verify, expire and reject tampering", async () => {
  const secret = "s3cret";
  const t = await issueAppToken(secret, "install-1", 3600, T0);
  assert.deepEqual(await verifyAppToken(secret, t, T0 + 1000), { ok: true, installId: "install-1" });
  assert.equal((await verifyAppToken(secret, t, T0 + 3601_000)).error, "expired_key");
  assert.equal((await verifyAppToken("other", t, T0)).error, "invalid_key");
  const [head, sig] = t.split(".");
  const forged = head.slice(0, -2) + (head.endsWith("AA") ? "BB" : "AA") + "." + sig;
  assert.equal((await verifyAppToken(secret, forged, T0)).ok, false);
  assert.equal((await verifyAppToken(undefined, t, T0)).ok, false);
  assert.equal((await verifyAppToken(secret, "va_garbage", T0)).ok, false);
});

test("origin allow-list: exact host, wildcard subdomains, Referer fallback", () => {
  const r = (headers) => new Request("https://api.vetrostudio.bid/v1/search", { headers });
  const list = JSON.stringify(["vetrostudio.bid", "*.partner.example"]);
  assert.equal(originAllowed(null, r({})), true);
  assert.equal(originAllowed(list, r({ origin: "https://vetrostudio.bid" })), true);
  assert.equal(originAllowed(list, r({ origin: "https://app.partner.example" })), true);
  assert.equal(originAllowed(list, r({ origin: "https://partner.example" })), true);
  assert.equal(originAllowed(list, r({ referer: "https://vetrostudio.bid/vap.html" })), true);
  assert.equal(originAllowed(list, r({ origin: "https://evil.example" })), false);
  assert.equal(originAllowed(list, r({ origin: "https://notvetrostudio.bid" })), false);
  assert.equal(originAllowed(list, r({})), false); // ключ «для браузера» с сервера не работает
});

test("an app token has no rate limit", async () => {
  const e = env({ APP_TOKEN_SECRET: "test-secret" });
  const { token } = await issueAppToken("test-secret", "install-1", 3600, T0).then((t) => (typeof t === "string" ? { token: t } : t));
  for (let i = 0; i < 700; i++) {
    const gate = await authorize(req("/v1/search?q=a", bearer(token)), e, T0 + i);
    assert.equal(gate.response, undefined, "request " + (i + 1));
  }
});

test("open mode lets anonymous calls through, enforce mode asks for a key, health is always open", async () => {
  const open = env({ ACCESS_MODE: "open" });
  assert.equal((await authorize(req("/v1/search?q=a"), open, T0)).response, undefined);
  const strict = env();
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a"), strict, T0)), "missing_key");
  assert.equal(await errorOf(await authorize(req("/v1/manifest"), strict, T0)), "missing_key");
  assert.equal(await errorOf(await authorize(req("/covers/small/000000771.avif"), strict, T0)), "missing_key");
  assert.equal((await authorize(req("/v1/health"), strict, T0)).response, undefined);
  // в открытом режиме приложенный, но неверный ключ всё равно отказ: это явная ошибка клиента
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer("vk_pro_" + "a".repeat(32))), open, T0)), "invalid_key");
});

test("a valid key passes with rate-limit headers; unknown, revoked and expired keys do not", async () => {
  const e = env();
  const good = await addKey(e, "pro");
  const gate = await authorize(req("/v1/search?q=a&limit=10", bearer(good.plain)), e, T0);
  assert.equal(gate.response, undefined);
  assert.equal(gate.principal.tier, "pro");
  assert.equal(gate.headers["x-ratelimit-limit-day"], "20000");
  assert.equal(gate.headers["x-ratelimit-remaining-day"], "19999");
  assert.equal(gate.headers["x-ratelimit-limit-minute"], "120");
  // тот же ключ через X-Api-Key
  assert.equal((await authorize(req("/v1/search?q=a", { "x-api-key": good.plain }), e, T0 + 1000)).response, undefined);

  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer("vk_pro_" + "b".repeat(32))), e, T0)), "invalid_key");
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer("nonsense")), e, T0)), "invalid_key");
  const revoked = await addKey(e, "pro", { status: "revoked" });
  const rv = await authorize(req("/v1/search?q=a", bearer(revoked.plain)), e, T0);
  assert.equal(rv.response.status, 403);
  assert.equal(await errorOf(rv), "key_revoked");
  const expired = await addKey(e, "pro", { expires_at: T0 - 1 });
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer(expired.plain)), e, T0)), "key_expired");
});

test("tiers gate routes: basic has no covers or index, pro no index, ultra everything", async () => {
  const e = env();
  const [basic, pro, ultra] = [await addKey(e, "basic"), await addKey(e, "pro"), await addKey(e, "ultra")];
  const cover = "/covers/small/000000771.avif";
  const index = "/v1/index/base/bookindex-v2.sqlite.zst";
  assert.equal(await errorOf(await authorize(req(cover, bearer(basic.plain)), e, T0)), "tier_forbids_route");
  assert.equal(await errorOf(await authorize(req("/v1/manifest", bearer(basic.plain)), e, T0)), "tier_forbids_route");
  assert.equal((await authorize(req(cover, bearer(pro.plain)), e, T0)).response, undefined);
  assert.equal(await errorOf(await authorize(req(index, bearer(pro.plain)), e, T0)), "tier_forbids_route");
  assert.equal((await authorize(req(index, bearer(ultra.plain)), e, T0)).response, undefined);
  assert.equal((await authorize(req("/v1/search?q=a", bearer(basic.plain)), e, T0)).response, undefined);
});

test("limits: per second and per minute, each with Retry-After and a reset", async () => {
  const e = env();
  const basic = await addKey(e, "basic"); // 2/s, 30/min, 2000/day
  const call = (now) => authorize(req("/v1/search?q=a", bearer(basic.plain)), e, now);

  assert.equal((await call(T0)).response, undefined);
  assert.equal((await call(T0 + 100)).response, undefined);
  const burst = await call(T0 + 200); // третий запрос в ту же секунду
  assert.equal(burst.response.status, 429);
  const body = await burst.response.json();
  assert.deepEqual([body.error, body.scope, body.retry_after], ["rate_limited", "second", 1]);
  assert.equal(burst.response.headers.get("retry-after"), "1");
  assert.equal((await call(T0 + 1000)).response, undefined); // следующая секунда свободна

  // минута: 30 запросов, по 2 в секунду (3 уже принято)
  let n = 3;
  for (let s = 2; n < 30; s++) for (let i = 0; i < 2 && n < 30; i++, n++) assert.equal((await call(T0 + s * 1000 + i)).response, undefined);
  const minute = await call(T0 + 20_000);
  assert.equal(minute.response.status, 429);
  assert.equal((await minute.response.json()).scope, "minute");
  assert.equal((await call(T0 + 61_000)).response, undefined); // новая минута
});

test("limits: the daily quota resets at UTC midnight", async () => {
  const e = env();
  e.sqlite.prepare("UPDATE tiers SET per_day = 5, per_minute = 0, per_second = 0 WHERE name = 'basic'").run();
  const k = await addKey(e, "basic");
  const day = (now) => authorize(req("/v1/search?q=a", bearer(k.plain)), e, now);
  for (let i = 0; i < 5; i++) assert.equal((await day(T0 + i * 1000)).response, undefined);
  const over = await day(T0 + 6000);
  assert.equal(over.response.status, 429);
  const body = await over.response.json();
  assert.equal(body.scope, "day");
  assert.equal(body.retry_after, 43200 - 6); // до полуночи UTC: T0 = 12:00:00, запрос в 12:00:06
  assert.equal((await day(T0 + 12 * 3600_000 + 1000)).response, undefined); // следующие сутки
});

test("tier ceilings trim limit= and ids= instead of failing", async () => {
  const e = env();
  const basic = await addKey(e, "basic"); // limit 20, ids 10
  const limitOf = (gate) => new URL(gate.request.url).searchParams.get("limit");
  assert.equal(limitOf(await authorize(req("/v1/search?q=a&limit=50", bearer(basic.plain)), e, T0)), "20");
  assert.equal(limitOf(await authorize(req("/v1/search?q=a&limit=5", bearer(basic.plain)), e, T0 + 1000)), "5");
  assert.equal(limitOf(await authorize(req("/v1/search?q=a", bearer(basic.plain)), e, T0 + 3000)), "20");
  const ids = Array.from({ length: 30 }, (_, i) => i + 1).join(",");
  const b = await authorize(req(`/v1/books?ids=${ids}`, bearer(basic.plain)), e, T0 + 10_000);
  assert.equal(new URL(b.request.url).searchParams.get("ids").split(",").length, 10);
});

test("?key= works on covers only and never reaches the handler", async () => {
  const e = env();
  const pro = await addKey(e, "pro");
  const c = await authorize(req(`/covers/small/000000771.avif?v=7&key=${pro.plain}`), e, T0);
  assert.equal(c.response, undefined);
  assert.equal(new URL(c.request.url).searchParams.has("key"), false);
  assert.equal(new URL(c.request.url).searchParams.get("v"), "7");
  assert.equal(await errorOf(await authorize(req(`/v1/search?q=a&key=${pro.plain}`), e, T0)), "missing_key");
});

test("a key bound to origins only works from them", async () => {
  const e = env();
  const k = await addKey(e, "pro", { allowed_origins: JSON.stringify(["vetrostudio.bid"]) });
  const ok = await authorize(req("/v1/search?q=a", { ...bearer(k.plain), origin: "https://vetrostudio.bid" }), e, T0);
  assert.equal(ok.response, undefined);
  const bad = await authorize(req("/v1/search?q=a", { ...bearer(k.plain), origin: "https://evil.example" }), e, T0 + 1000);
  assert.equal(await errorOf(bad), "origin_not_allowed");
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer(k.plain)), e, T0 + 2000)), "origin_not_allowed");
});

test("revocation takes effect once the 60 s key cache expires", async () => {
  const e = env();
  const k = await addKey(e, "pro");
  assert.equal((await authorize(req("/v1/search?q=a", bearer(k.plain)), e, T0)).response, undefined);
  e.sqlite.prepare("UPDATE api_keys SET status = 'revoked' WHERE id = ?").run(k.id);
  edgeCache.clear(); // то, что делает край через 60 с
  assert.equal(await errorOf(await authorize(req("/v1/search?q=a", bearer(k.plain)), e, T0 + 61_000)), "key_revoked");
});

test("the meter writes absolute per-route usage to D1 on the alarm and survives a day change", async () => {
  const e = env();
  const k = await addKey(e, "pro");
  for (let i = 0; i < 3; i++) await authorize(req("/v1/search?q=a", bearer(k.plain)), e, T0 + i * 1000);
  await authorize(req("/v1/books/000000771", bearer(k.plain)), e, T0 + 5000);
  await authorize(req("/covers/small/000000771.avif", bearer(k.plain)), e, T0 + 6000);
  await e.METER.objects.get(k.id).alarm();
  const rows = e.sqlite.prepare("SELECT route, requests FROM usage_daily WHERE key_id = ? ORDER BY route").all(k.id);
  assert.deepEqual(rows.map((r) => [r.route, r.requests]), [["books", 1], ["covers", 1], ["search", 3]]);

  await e.METER.objects.get(k.id).alarm(); // повтор не удваивает
  assert.equal(e.sqlite.prepare("SELECT sum(requests) n FROM usage_daily WHERE key_id = ?").get(k.id).n, 5);

  // новые сутки: вчерашнее сброшено в D1, счёт начался заново
  const gate = await authorize(req("/v1/search?q=a", bearer(k.plain)), e, T0 + 24 * 3600_000);
  assert.equal(gate.headers["x-ratelimit-remaining-day"], "19999");
  assert.equal(
    e.sqlite.prepare("SELECT requests FROM usage_daily WHERE key_id = ? AND day = '2026-11-13' AND route = 'search'").get(k.id).requests, 3,
  );
});

test("the Worker answers 401 without a key (with CORS), and 200 with one", async () => {
  const e = env();
  const ctx = { waitUntil() {} };
  const get = (path, headers = {}) => worker.fetch(new Request("https://api.vetrostudio.bid" + path, { headers }), e, ctx);

  assert.equal((await get("/v1/health")).status, 200);
  const no = await get("/v1/manifest");
  assert.equal(no.status, 401);
  assert.equal(no.headers.get("access-control-allow-origin"), "*");
  assert.equal((await no.json()).error, "missing_key");

  const k = await addKey(e, "ultra");
  const yes = await get("/v1/manifest", bearer(k.plain));
  assert.equal(yes.status, 200);
  assert.equal(yes.headers.get("x-ratelimit-limit-day"), "200000");
  assert.deepEqual(await yes.json(), { version: 2 });

  const pre = await worker.fetch(new Request("https://api.vetrostudio.bid/v1/search", { method: "OPTIONS" }), e, ctx);
  assert.equal(pre.status, 204);
  assert.match(pre.headers.get("access-control-allow-headers"), /authorization/);
  assert.match(pre.headers.get("access-control-allow-headers"), /x-api-key/);
});

test("the alarm still flushes usage when the Durable Object was evicted and restarted in between", async () => {
  const e = env();
  const k = await addKey(e, "pro");
  const state = fakeState();
  const first = new KeyMeter(state, e);
  const limits = { per_second: 0, per_minute: 0, per_day: 0 };
  for (let i = 0; i < 3; i++) await first.fetch(new Request("https://meter/admit", { method: "POST", body: JSON.stringify({ keyId: k.id, route: "search", now: T0 + i, limits }) }));
  const second = new KeyMeter(state, e); // вытеснили и подняли заново: в памяти пусто, в хранилище есть
  await second.alarm();
  assert.equal(e.sqlite.prepare("SELECT requests FROM usage_daily WHERE key_id = ? AND route = 'search'").get(k.id).requests, 3);
  e.sqlite.exec("DELETE FROM usage_daily");
  await new KeyMeter(state, e).alarm(); // новый экземпляр без единого запроса
  assert.equal(e.sqlite.prepare("SELECT requests FROM usage_daily WHERE key_id = ?").get(k.id).requests, 3);
});
