// Доступ к API каталога (docs/adr/0003-api-access-keys-and-tiers.md): ключи, тарифы, лимиты и учёт расхода.
//
//   vk_<тариф>_<32 символа>   ключ стороннего клиента; в D1 лежит только SHA-256, лимиты берутся из тарифа (tiers)
//   va_<данные>.<подпись>     токен установки приложения (HMAC); выдаётся после аттестации (фаза 2), лимитов тарифов не имеет
//
// authorize() вызывается до маршрутизации: /v1/health открыт, остальное требует ключ при ACCESS_MODE=enforce.

const te = new TextEncoder();
const KEY_CACHE_SECONDS = 60;
const MISSING_KEY_CACHE_SECONDS = 30;
const FLUSH_EVERY_ADMITS = 10;
const FLUSH_ALARM_MS = 60_000;

/** Тип запроса по пути: по нему считается расход и решается, доступен ли маршрут тарифу. */
export function routeOf(path) {
  if (path === "/v1/health") return "health";
  if (path === "/v1/search") return "search";
  if (path === "/v1/books" || /^\/v1\/books\/\d{9}$/.test(path)) return "books";
  if (/^\/covers\/(small|large)\/\d{9}\.avif$/.test(path)) return "covers";
  if (path === "/v1/manifest" || path.startsWith("/v1/index/")) return "index";
  if (path.startsWith("/v1/auth/")) return "auth";
  return "other";
}

// --- криптография ----------------------------------------------------------------------------------------------

export const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
export const unb64url = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

/** HMAC-SHA256 от `data` ключом `secret` (строка); `data` — Uint8Array. */
export async function macOf(secret, data) {
  return new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret, "sign"), data));
}

export async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", te.encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const hmacKey = (secret, usage) => crypto.subtle.importKey("raw", te.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, [usage]);

/** Токен установки: `va_<payload>.<подпись>`; payload = {i: id установки, e: срок в секундах Unix}. */
export async function issueAppToken(secret, installId, ttlSeconds, nowMs = Date.now()) {
  const payload = b64url(te.encode(JSON.stringify({ i: installId, e: Math.floor(nowMs / 1000) + ttlSeconds })));
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(secret, "sign"), te.encode(payload));
  return `va_${payload}.${b64url(sig)}`;
}

/** → {ok: true, installId} | {ok: false, error}. Подпись сверяется постоянным по времени `subtle.verify`. */
export async function verifyAppToken(secret, token, nowMs = Date.now()) {
  if (!secret) return { ok: false, error: "invalid_key" };
  const body = token.slice(3);
  const dot = body.indexOf(".");
  if (dot < 1) return { ok: false, error: "invalid_key" };
  const payload = body.slice(0, dot);
  let valid = false;
  try {
    valid = await crypto.subtle.verify("HMAC", await hmacKey(secret, "verify"), unb64url(body.slice(dot + 1)), te.encode(payload));
  } catch {
    return { ok: false, error: "invalid_key" };
  }
  if (!valid) return { ok: false, error: "invalid_key" };
  let data;
  try {
    data = JSON.parse(new TextDecoder().decode(unb64url(payload)));
  } catch {
    return { ok: false, error: "invalid_key" };
  }
  if (!data || typeof data.i !== "string" || !(data.e * 1000 > nowMs)) return { ok: false, error: "expired_key" };
  return { ok: true, installId: data.i };
}

const BASE32 = "abcdefghijklmnopqrstuvwxyz234567";

/** Новый ключ стороннего клиента: показать `plain` владельцу один раз, в базу идут `id`, `hash`, `prefix`. */
export async function newApiKey(tier) {
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const secret = [...raw].map((b) => BASE32[b & 31]).join("");
  const plain = `vk_${tier}_${secret}`;
  const id = "k_" + [...crypto.getRandomValues(new Uint8Array(6))].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { id, plain, hash: await sha256Hex(plain), prefix: plain.slice(0, 12) + "…" };
}

// --- разбор запроса ------------------------------------------------------------------------------------------------

function credentialOf(request, url, route) {
  const auth = request.headers.get("authorization");
  if (auth && /^bearer /i.test(auth)) return { value: auth.slice(7).trim(), fromQuery: false };
  const header = request.headers.get("x-api-key");
  if (header) return { value: header.trim(), fromQuery: false };
  // <img> не умеет заголовков: на обложках ключ допустим в ?key=, на остальных маршрутах нет (он попал бы в журналы и кэши).
  const q = url.searchParams.get("key");
  if (q && route === "covers") return { value: q.trim(), fromQuery: true };
  return null;
}

function hostOf(value) {
  try {
    return new URL(value).hostname.toLowerCase();
  } catch {
    return null;
  }
}

/** Ключ со списком источников принимается, только если браузер прислал Origin/Referer из списка (точное имя или `*.домен`). */
export function originAllowed(allowedJson, request) {
  if (!allowedJson) return true;
  let list;
  try {
    list = JSON.parse(allowedJson);
  } catch {
    return false;
  }
  if (!Array.isArray(list) || !list.length) return true;
  const host = hostOf(request.headers.get("origin") ?? "") ?? hostOf(request.headers.get("referer") ?? "");
  if (!host) return false;
  return list.some((rule) => {
    const r = String(rule).toLowerCase();
    return r.startsWith("*.") ? host === r.slice(2) || host.endsWith(r.slice(1)) : host === r;
  });
}

function deny(status, error, extra = {}, headers = {}) {
  return {
    response: new Response(JSON.stringify({ error, ...extra }), {
      status,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
    }),
  };
}

// --- ключ и тариф ------------------------------------------------------------------------------------------------

const KEY_SQL =
  "SELECT k.id, k.tier, k.status, k.expires_at, k.allowed_origins, t.per_second, t.per_minute, t.per_day, t.max_limit, t.max_ids, " +
  "t.allow_covers, t.allow_index FROM api_keys k JOIN tiers t ON t.name = k.tier WHERE k.key_hash = ?1";

/** Ключ и лимиты его тарифа; результат (в том числе «такого ключа нет») кэшируется на краю: перебор ключей не ходит в D1 каждый раз. */
async function loadKey(env, hash) {
  const cacheKey = new Request(`https://cache.invalid/access/key/${hash}`);
  const hit = await caches.default.match(cacheKey);
  if (hit) {
    const v = await hit.json();
    return v.missing ? null : v;
  }
  const row = (await env.DB.prepare(KEY_SQL).bind(hash).first()) ?? null;
  const ttl = row ? KEY_CACHE_SECONDS : MISSING_KEY_CACHE_SECONDS;
  await caches.default.put(
    cacheKey,
    new Response(JSON.stringify(row ?? { missing: true }), { headers: { "cache-control": `public, max-age=${ttl}` } }),
  );
  return row;
}

/** Отозванные установки приложения (панель: installs.status = 'revoked'); список маленький и кэшируется на краю на 60 с. */
async function installRevoked(env, installId) {
  if (!env.DB) return false;
  const cacheKey = new Request("https://cache.invalid/access/revoked-installs");
  const hit = await caches.default.match(cacheKey);
  let ids;
  if (hit) {
    ids = await hit.json();
  } else {
    const { results } = await env.DB.prepare("SELECT id FROM installs WHERE status = 'revoked'").all();
    ids = results.map((r) => r.id);
    await caches.default.put(cacheKey, new Response(JSON.stringify(ids), { headers: { "cache-control": `public, max-age=${KEY_CACHE_SECONDS}` } }));
  }
  return ids.includes(installId);
}

async function admit(env, key, route, now) {
  if (!env.METER) return { ok: true }; // нет привязки (локально, тесты): без учёта
  const stub = env.METER.get(env.METER.idFromName(key.id));
  const res = await stub.fetch("https://meter/admit", {
    method: "POST",
    body: JSON.stringify({
      keyId: key.id, route, now,
      limits: { per_second: key.per_second, per_minute: key.per_minute, per_day: key.per_day },
    }),
  });
  return res.json();
}

/**
 * Решение по запросу. Возвращает {route} (пропустить как есть), {response} (отказ) или {route, request, headers, principal}
 * (пропустить, заменив запрос на урезанный по тарифу и добавив заголовки лимитов в ответ).
 */
export async function authorize(request, env, now = Date.now()) {
  const url = new URL(request.url);
  const route = routeOf(url.pathname);
  if (route === "health" || route === "other" || route === "auth") return { route };
  const enforce = (env.ACCESS_MODE ?? "open") === "enforce";
  const cred = credentialOf(request, url, route);
  if (!cred) return enforce ? deny(401, "missing_key") : { route };

  // ключ из адреса не должен попадать ни в кэш обложек, ни дальше по коду
  let req = request;
  if (cred.fromQuery) {
    url.searchParams.delete("key");
    req = new Request(url, request);
  }

  if (cred.value.startsWith("va_")) {
    const t = await verifyAppToken(env.APP_TOKEN_SECRET, cred.value, now);
    if (!t.ok) return deny(401, t.error);
    if (await installRevoked(env, t.installId)) return deny(403, "install_revoked");
    return { route, request: req, principal: { kind: "app", id: t.installId, tier: "app" } };
  }
  if (!cred.value.startsWith("vk_")) return deny(401, "invalid_key");

  const key = await loadKey(env, await sha256Hex(cred.value));
  if (!key) return deny(401, "invalid_key");
  if (key.status !== "active") return deny(403, "key_revoked");
  if (key.expires_at && key.expires_at <= now) return deny(403, "key_expired");
  if (!originAllowed(key.allowed_origins, request)) return deny(403, "origin_not_allowed");
  if ((route === "covers" && !key.allow_covers) || (route === "index" && !key.allow_index)) {
    return deny(403, "tier_forbids_route", { tier: key.tier, route });
  }

  const verdict = await admit(env, key, route, now);
  if (!verdict.ok) {
    return deny(429, "rate_limited", { scope: verdict.scope, retry_after: verdict.retry }, { "retry-after": String(verdict.retry) });
  }

  // потолки тарифа: лишнее обрезается, а не отклоняется (клиент получает меньше, но ответ рабочий)
  if (route === "search") {
    const asked = parseInt(url.searchParams.get("limit") ?? "", 10);
    if (key.max_limit && !(asked <= key.max_limit)) {
      url.searchParams.set("limit", String(key.max_limit));
      req = new Request(url, req);
    }
  } else if (route === "books" && url.pathname === "/v1/books" && key.max_ids) {
    const ids = (url.searchParams.get("ids") ?? "").split(",");
    if (ids.length > key.max_ids) {
      url.searchParams.set("ids", ids.slice(0, key.max_ids).join(","));
      req = new Request(url, req);
    }
  }

  const headers = {};
  for (const [scope, v] of Object.entries(verdict.remaining ?? {})) {
    headers[`x-ratelimit-limit-${scope}`] = String(v.limit);
    headers[`x-ratelimit-remaining-${scope}`] = String(v.left);
  }
  return { route, request: req, headers, principal: { kind: "key", id: key.id, tier: key.tier } };
}

// --- Durable Object: счётчики одного ключа -------------------------------------------------------------------------

function secondsToUtcMidnight(now) {
  return Math.max(1, 86400 - Math.floor((now % 86_400_000) / 1000));
}

/**
 * Один объект на ключ: секунда, минута и сутки (UTC) считаются в памяти, в хранилище — раз в несколько запросов и по будильнику,
 * в D1 (usage_daily) — раз в минуту при активности. Потеряться может хвост до FLUSH_EVERY_ADMITS запросов при вытеснении объекта.
 */
export class KeyMeter {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.keyId = null;
    this.day = null; // {d, n, denied, routes: {route: n}}
    this.sec = { t: 0, n: 0 };
    this.min = { t: 0, n: 0 };
    this.sinceSave = 0;
    this.alarmSet = false;
  }

  async fetch(request) {
    const { keyId, route, now, limits } = await request.json();
    if (this.keyId !== keyId) {
      this.keyId = keyId;
      await this.state.storage.put("keyId", keyId); // будильник может сработать в новом экземпляре объекта: имя ключа должно пережить вытеснение
    }
    const today = new Date(now).toISOString().slice(0, 10);
    if (!this.day) this.day = (await this.state.storage.get("day")) ?? { d: today, n: 0, denied: 0, routes: {} };
    if (this.day.d !== today) {
      await this.flushToD1();
      this.day = { d: today, n: 0, denied: 0, routes: {} };
    }
    const s = Math.floor(now / 1000);
    const m = Math.floor(now / 60_000);
    if (this.sec.t !== s) this.sec = { t: s, n: 0 };
    if (this.min.t !== m) this.min = { t: m, n: 0 };

    let denied = null;
    if (limits.per_second && this.sec.n >= limits.per_second) denied = { scope: "second", retry: 1 };
    else if (limits.per_minute && this.min.n >= limits.per_minute) denied = { scope: "minute", retry: 60 - Math.floor((now % 60_000) / 1000) };
    else if (limits.per_day && this.day.n >= limits.per_day) denied = { scope: "day", retry: secondsToUtcMidnight(now) };

    if (denied) {
      this.day.denied++;
    } else {
      this.sec.n++;
      this.min.n++;
      this.day.n++;
      this.day.routes[route] = (this.day.routes[route] ?? 0) + 1;
    }
    await this.touch(now);

    const left = (limit, used) => (limit ? Math.max(0, limit - used) : null);
    const remaining = {};
    if (limits.per_minute) remaining.minute = { limit: limits.per_minute, left: left(limits.per_minute, this.min.n) };
    if (limits.per_day) remaining.day = { limit: limits.per_day, left: left(limits.per_day, this.day.n) };
    return Response.json({ ok: !denied, ...(denied ?? {}), remaining });
  }

  async touch(now) {
    this.sinceSave++;
    if (this.sinceSave >= FLUSH_EVERY_ADMITS) {
      this.sinceSave = 0;
      await this.state.storage.put("day", this.day);
    }
    if (!this.alarmSet) {
      this.alarmSet = true;
      await this.state.storage.setAlarm(now + FLUSH_ALARM_MS);
    }
  }

  async alarm() {
    this.alarmSet = false;
    if (!this.keyId) this.keyId = await this.state.storage.get("keyId");
    if (!this.day) this.day = await this.state.storage.get("day");
    if (!this.day) return;
    await this.state.storage.put("day", this.day);
    await this.flushToD1();
  }

  /** Абсолютные значения за сутки: повторная запись безопасна. */
  async flushToD1() {
    const db = this.env?.DB;
    if (!db || !this.day || !this.keyId) return;
    const rows = Object.entries(this.day.routes);
    if (this.day.denied) rows.push(["_denied", this.day.denied]);
    for (const [route, n] of rows) {
      await db
        .prepare(
          "INSERT INTO usage_daily (key_id, day, route, requests) VALUES (?1, ?2, ?3, ?4) " +
            "ON CONFLICT(key_id, day, route) DO UPDATE SET requests = excluded.requests",
        )
        .bind(this.keyId, this.day.d, route, n)
        .run();
    }
  }
}
