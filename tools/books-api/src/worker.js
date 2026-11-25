// Vetro Books API (api.vetrostudio.bid). Read-only, no accounts, nothing about callers is stored.
//
//   GET /v1/health
//   GET /v1/manifest                         phone-index manifest (R2 index/manifest.json)
//   GET /v1/index/{base|delta}/<file>        versioned index files, immutable (R2 index/...)
//   GET /v1/search?q=&limit=                 FTS5 over the whole server catalogue (D1)
//   GET /v1/books/{id9}                      one book with its detail card
//   GET /v1/books?ids=1,2,3                  up to 40 books, rows only
//   GET /covers/{small|large}/{id9}.avif     covers (R2), URL carries ?v=<cv> for cache busting
//   GET /v1/find?q=&kind=all|anime|movie|tv|book   one search over the three databases or just one; every item carries `kind`
//   GET /v1/media/search, /v1/media/{id}, /v1/media?ids=, /v1/media/ext?ids=, /covers/media/{small|large}/{id}.avif   anime, films, series (src/media.js)
//
// Access: every route but /v1/health needs a key when ACCESS_MODE=enforce (src/access.js, docs/adr/0003-api-access-keys-and-tiers.md).
// Bindings: BOOKS (R2), DB (D1), LIMITER (optional rate limit), METER (Durable Object KeyMeter: per-key limits and usage).
// Secrets/vars: ACCESS_MODE ("open" | "enforce"), APP_TOKEN_SECRET (HMAC of app install tokens, phase 2).

import { authorize } from "./access.js";
import { handleAuth } from "./auth.js";
import { norm } from "./norm.js";
import { MEDIA_COVER_PATH, MEDIA_ID_PATH, find, mediaBatch, mediaByExt, mediaById, mediaSearch } from "./media.js";

const COVER_PATH = /^\/covers\/(small|large)\/(\d{9})\.avif$/;
const INDEX_PATH = /^\/v1\/index\/((?:base|delta)\/[A-Za-z0-9._-]+)$/;
const BOOK_PATH = /^\/v1\/books\/(\d{9})$/;

const SEARCH_TTL = 600;
const BOOK_TTL = 86400;
const MANIFEST_TTL = 300;
const IMMUTABLE = "public, max-age=31536000, immutable";
const MAX_LIMIT = 50;
const MAX_IDS = 40;
const MAX_TOKENS = 8;

const ROW = "w.id, w.ru, w.en, w.ra, w.ea, w.y, w.f, w.cv";

// Public read-only data, no cookies or credentials: any origin may read it (the Vetro Studio site lives on another origin).
const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, HEAD, POST, OPTIONS",
  "access-control-allow-headers": "range, if-none-match, authorization, x-api-key, content-type",
  "access-control-expose-headers":
    "etag, content-range, content-length, accept-ranges, retry-after, x-ratelimit-limit-minute, x-ratelimit-remaining-minute, " +
    "x-ratelimit-limit-day, x-ratelimit-remaining-day",
  "access-control-max-age": "86400",
};

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    // Ключ и лимиты тарифа проверяются до кэша и маршрутов (docs/adr/0003): ответ из кэша тоже считается в расход ключа.
    const gate = await authorize(request, env);
    const res = gate.response ?? (await handle(gate.request ?? request, env, ctx));
    // Cached and R2 responses can carry immutable headers: copy before adding ours.
    const out = new Response(res.body, res);
    for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
    for (const [k, v] of Object.entries(gate.headers ?? {})) out.headers.set(k, v);
    return out;
  },
};

export { KeyMeter } from "./access.js";

async function handle(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (path.startsWith("/v1/auth/")) return handleAuth(request, env); // выдача токена установки по аттестации (src/auth.js)
  if (request.method !== "GET" && request.method !== "HEAD") return json({ error: "method_not_allowed" }, 405);
  try {
    let res;
    if (path === "/v1/health") res = json({ ok: true }, 200, 0);
    else if (path === "/v1/manifest") {
      res = await serveR2(env, request, "index/manifest.json", `public, max-age=${MANIFEST_TTL}, stale-while-revalidate=3600`, "application/json");
    } else if (INDEX_PATH.test(path)) {
      res = await serveR2(env, request, "index/" + path.match(INDEX_PATH)[1], IMMUTABLE, "application/octet-stream");
    } else if (COVER_PATH.test(path)) {
      const [, size, id] = path.match(COVER_PATH);
      // ключ кэша без query: иначе ?x=1, ?x=2 … шли бы мимо кэша прямо в R2
      res = await cached(ctx, url.origin + path, () => serveR2(env, request, `covers/${size}/${id}.avif`, IMMUTABLE, "image/avif"));
    } else if (MEDIA_COVER_PATH.test(path)) {
      const [, size, id] = path.match(MEDIA_COVER_PATH);
      res = await cached(ctx, url.origin + path, () => serveR2(env, request, `covers/media/${size}/${id}.avif`, IMMUTABLE, "image/avif"));
    } else if (path === "/v1/find") {
      res = await find(helpers, env, ctx, request, url);
    } else if (path === "/v1/media/search") {
      res = await mediaSearch(helpers, env, ctx, request, url);
    } else if (MEDIA_ID_PATH.test(path)) {
      res = await mediaById(helpers, env, ctx, request, path.match(MEDIA_ID_PATH)[1]);
    } else if (path === "/v1/media/ext") {
      res = await mediaByExt(helpers, env, request, url);
    } else if (path === "/v1/media") {
      res = await mediaBatch(helpers, env, request, url);
    } else if (path === "/v1/search") {
      res = await search(env, ctx, request, url);
    } else if (BOOK_PATH.test(path)) {
      res = await bookById(env, ctx, request, path.match(BOOK_PATH)[1]);
    } else if (path === "/v1/books") {
      res = await booksBatch(env, request, url);
    } else {
      res = json({ error: "not_found" }, 404, 60);
    }
    return request.method === "HEAD" ? new Response(null, res) : res;
  } catch (e) {
    return json({ error: "internal" }, 500, 0);
  }
}

// What src/media.js needs from this file (it cannot import it back).
const helpers = {
  json, cached, throttled, norm, matchExpr, bookRows: ftsRows, bookItem: item,
  SEARCH_TTL, BOOK_TTL, MAX_LIMIT, MAX_IDS, MAX_TOKENS,
};

function json(body, status = 200, ttl = 0) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": ttl > 0 ? `public, max-age=${ttl}` : "no-store",
    },
  });
}

/** Edge cache for GETs that are expensive or immutable. Only full 200 responses are stored. */
async function cached(ctx, key, produce) {
  const cache = caches.default;
  const hit = await cache.match(key);
  if (hit) return hit;
  const res = await produce();
  if (res.status === 200) ctx.waitUntil(cache.put(key, res.clone()));
  return res;
}

async function throttled(env, request) {
  if (!env.LIMITER) return false;
  const { success } = await env.LIMITER.limit({ key: request.headers.get("cf-connecting-ip") ?? "anon" });
  return !success;
}

async function serveR2(env, request, key, cacheControl, fallbackType) {
  const obj = await env.BOOKS.get(key, { range: request.headers, onlyIf: request.headers });
  if (!obj) return json({ error: "not_found" }, 404, 300);
  const headers = new Headers();
  obj.writeHttpMetadata(headers);
  headers.set("etag", obj.httpEtag);
  headers.set("cache-control", cacheControl);
  headers.set("accept-ranges", "bytes");
  if (!headers.get("content-type")) headers.set("content-type", fallbackType);
  if (!obj.body) return new Response(null, { status: 304, headers }); // If-None-Match / If-Modified-Since matched
  let status = 200;
  if (request.headers.has("range") && obj.range) {
    const r = obj.range;
    const offset = r.suffix !== undefined ? obj.size - r.suffix : (r.offset ?? 0);
    const length = r.suffix !== undefined ? r.suffix : (r.length ?? obj.size - offset);
    headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${obj.size}`);
    headers.set("content-length", String(length));
    status = 206;
  }
  return new Response(obj.body, { status, headers });
}

function pad(id) {
  return String(id).padStart(9, "0");
}

function item(r) {
  const o = { kind: "book", id: pad(r.id), ru: r.ru, en: r.en, ra: r.ra, ea: r.ea, y: r.y, f: r.f, cv: r.cv };
  for (const k of Object.keys(o)) if (o[k] === null || o[k] === undefined || o[k] === "") delete o[k];
  return o;
}

/** "a b c" -> `"a"* "b"* "c"*` (AND of prefixes); tokens are [0-9a-zа-я] only, so quoting is safe. */
export function matchExpr(tokens, join = " ") {
  return tokens.map((t) => `"${t}"*`).join(join);
}

async function ftsRows(env, match, limit) {
  const { results } = await env.DB.prepare(
    `SELECT ${ROW} FROM fts JOIN works w ON w.id = fts.rowid WHERE fts MATCH ?1 ORDER BY bm25(fts, 6, 6, 3, 3, 2, 1) LIMIT ?2`,
  ).bind(match, limit).all();
  return results;
}

async function search(env, ctx, request, url) {
  const q = norm(url.searchParams.get("q") ?? "");
  if (q.length < 2) return json({ q, items: [] }, 200, SEARCH_TTL);
  const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") ?? "20", 10) || 20, 1), MAX_LIMIT);
  const tokens = q.split(" ").filter((t) => t.length <= 40).slice(0, MAX_TOKENS);
  if (!tokens.length) return json({ q, items: [] }, 200, SEARCH_TTL);

  const key = new Request(`https://cache.invalid/v1/search?q=${encodeURIComponent(tokens.join(" "))}&limit=${limit}`);
  return cached(ctx, key, async () => {
    if (await throttled(env, request)) return json({ error: "rate_limited" }, 429, 0);
    let rows = await ftsRows(env, matchExpr(tokens), limit);
    // A long query that finds almost nothing as AND: loosen to OR so the app still gets candidates to rank.
    if (rows.length < Math.min(limit, 5) && tokens.length > 1) {
      const seen = new Set(rows.map((r) => r.id));
      for (const r of await ftsRows(env, matchExpr(tokens, " OR "), limit)) if (!seen.has(r.id)) rows.push(r);
      rows = rows.slice(0, limit);
    }
    return json({ q, items: rows.map(item) }, 200, SEARCH_TTL);
  });
}

async function bookById(env, ctx, request, id9) {
  const key = new Request(`https://cache.invalid/v1/books/${id9}`);
  return cached(ctx, key, async () => {
    if (await throttled(env, request)) return json({ error: "rate_limited" }, 429, 0);
    const row = await env.DB.prepare("SELECT id, ru, en, ra, ea, alt, y, f, cv, d FROM works WHERE id = ?1")
      .bind(parseInt(id9, 10)).first();
    if (!row) return json({ error: "not_found" }, 404, 300);
    let detail = {};
    try { detail = JSON.parse(row.d ?? "{}"); } catch { /* keep an empty card */ }
    return json({ ...item(row), alt: row.alt || undefined, detail }, 200, BOOK_TTL);
  });
}

async function booksBatch(env, request, url) {
  const ids = (url.searchParams.get("ids") ?? "").split(",").map((s) => s.trim()).filter((s) => /^\d{1,9}$/.test(s))
    .map((s) => parseInt(s, 10)).slice(0, MAX_IDS);
  if (!ids.length) return json({ items: [] }, 200, 60);
  if (await throttled(env, request)) return json({ error: "rate_limited" }, 429, 0);
  const marks = ids.map((_, i) => `?${i + 1}`).join(",");
  const { results } = await env.DB.prepare(`SELECT ${ROW} FROM works w WHERE w.id IN (${marks})`).bind(...ids).all();
  return json({ items: results.map(item) }, 200, BOOK_TTL);
}
