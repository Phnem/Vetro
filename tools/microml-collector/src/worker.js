// Vetro MicroML collector: a Cloudflare Worker that accepts anonymous, schema-checked data from consenting
// installs and writes it as plain JSON files into an R2 bucket the owner can open:
//   microml/<day>/<install>.json   numbers and counters for training the on-device models (POST /v1/batch)
//   sources/<day>/<id>.json        public book records that other book sites returned and the catalogue lacks (POST /v1/sources)
// No search text, no collection titles, no IP address, no account. See README.md for the exact data list.

const SCHEMA = 1;
const MAX_BODY = 64 * 1024;
const MAX_SOURCES_BODY = 128 * 1024;
const MAX_RECORDS = 100;
const MAX_PAIRS = 50;
const MAX_GENRES = 50;
const MAX_COUNTERS = 60;
const FEATURE_SETS = { "identity-features-v2": 42 };
const TASTE_WEIGHTS = 11;
const PAIR_SOURCES = new Set(["picker_choice", "source_rejected", "dedupe_confirm"]);
const TYPES = new Set(["ANIME", "MOVIE", "TV"]);
const INSTALL = /^[0-9a-f]{32}$/;
const VERSION = /^[A-Za-z0-9._-]{1,48}$/;
const COUNTER_KEY = /^[a-z]{2,16}:[a-z0-9_]{1,48}$/;
// Letters, digits, space and a few joiners: a genre name, not a sentence, an address or a nickname.
const GENRE = /^[\p{L}\p{N}][\p{L}\p{N} &'.\-]{0,38}[\p{L}\p{N}]$/u;

const json = (value, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const finite = (x, lo, hi) => typeof x === "number" && Number.isFinite(x) && x >= lo && x <= hi;
const round = (x) => Math.round(x * 1e5) / 1e5;

export function validate(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "body";
  const allowed = new Set(["schema", "install", "packs", "taste", "pairs", "genres", "counters"]);
  for (const key of Object.keys(body)) if (!allowed.has(key)) return `unknown field ${key}`;
  if (body.schema !== SCHEMA) return "schema";
  if (typeof body.install !== "string" || !INSTALL.test(body.install)) return "install";
  const packs = body.packs ?? {};
  if (typeof packs !== "object" || Array.isArray(packs)) return "packs";
  for (const [name, version] of Object.entries(packs)) {
    if (!["identity", "catalog", "experience"].includes(name) || typeof version !== "string" || !VERSION.test(version)) return "packs";
  }
  const taste = body.taste ?? [];
  if (!Array.isArray(taste) || taste.length > 3) return "taste";
  for (const t of taste) {
    if (!t || !TYPES.has(t.type) || typeof t.version !== "string" || !VERSION.test(t.version)) return "taste entry";
    if (!Number.isInteger(t.events) || t.events < 10 || t.events > 1e6) return "taste events";
    if (!Array.isArray(t.weights) || t.weights.length !== TASTE_WEIGHTS || !t.weights.every((w) => finite(w, -50, 50))) return "taste weights";
  }
  const pairs = body.pairs ?? [];
  if (!Array.isArray(pairs) || pairs.length > MAX_PAIRS) return "pairs";
  for (const p of pairs) {
    const size = FEATURE_SETS[p?.features_version];
    if (!size || !PAIR_SOURCES.has(p.source) || (p.label !== 0 && p.label !== 1)) return "pair entry";
    if (!Array.isArray(p.features) || p.features.length !== size || !p.features.every((x) => finite(x, -20, 200))) return "pair features";
    if (!Array.isArray(p.kinds) || p.kinds.length !== 2 || !p.kinds.every((k) => Number.isInteger(k) && k >= 0 && k <= 5)) return "pair kinds";
    if (typeof p.pack !== "string" || !VERSION.test(p.pack)) return "pair pack";
  }
  const genres = body.genres ?? [];
  if (!Array.isArray(genres) || genres.length > MAX_GENRES || !genres.every((g) => typeof g === "string" && GENRE.test(g))) return "genres";
  const counters = body.counters ?? {};
  if (typeof counters !== "object" || Array.isArray(counters)) return "counters";
  const entries = Object.entries(counters);
  if (entries.length > MAX_COUNTERS) return "counters";
  for (const [key, value] of entries) if (!COUNTER_KEY.test(key) || !Number.isInteger(value) || value < 0 || value > 1e6) return "counter entry";
  return null;
}

const SOURCE_ID = /^[a-z0-9][a-z0-9._-]{1,39}$/;
const RECORD_FIELDS = new Set(["source", "key", "title", "authors", "narrators", "genres", "year", "cover", "duration_sec"]);
const text = (x, max) => typeof x === "string" && x.trim().length > 0 && x.length <= max;
const names = (x) => Array.isArray(x) && x.length <= 5 && x.every((n) => text(n, 100));

/** Book records that another site returned for a search: public catalogue data, never the search text itself. */
export function validateSources(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return "body";
  for (const key of Object.keys(body)) if (key !== "schema" && key !== "records") return `unknown field ${key}`;
  if (body.schema !== SCHEMA) return "schema";
  if (!Array.isArray(body.records) || body.records.length === 0 || body.records.length > MAX_RECORDS) return "records";
  for (const r of body.records) {
    if (!r || typeof r !== "object" || Array.isArray(r)) return "record";
    for (const key of Object.keys(r)) if (!RECORD_FIELDS.has(key)) return `unknown record field ${key}`;
    if (typeof r.source !== "string" || !SOURCE_ID.test(r.source)) return "record source";
    if (!text(r.key, 200) || !text(r.title, 200)) return "record text";
    if (!names(r.authors ?? [])) return "record authors";
    if (!names(r.narrators ?? [])) return "record narrators";
    if (r.genres != null && !(Array.isArray(r.genres) && r.genres.length <= 10 && r.genres.every((g) => typeof g === "string" && GENRE.test(g)))) return "record genres";
    if (r.year != null && !(Number.isInteger(r.year) && r.year >= 0 && r.year <= 2100)) return "record year";
    if (r.duration_sec != null && !(Number.isInteger(r.duration_sec) && r.duration_sec >= 0 && r.duration_sec <= 1_000_000)) return "record duration";
    if (r.cover != null && !(text(r.cover, 400) && /^https?:\/\/[^\s]+$/.test(r.cover))) return "record cover";
  }
  return null;
}

const dayOf = (now) => now.toISOString().slice(0, 10);

/** One batch per install per day; a second one is acknowledged and dropped, so retries are harmless. */
export async function storeBatch(bucket, body, now = new Date()) {
  const key = `microml/${dayOf(now)}/${body.install}.json`;
  const saved = await bucket.put(key, JSON.stringify({ received: dayOf(now), ...body }), {
    onlyIf: { etagDoesNotMatch: "*" },
    httpMetadata: { contentType: "application/json" },
  });
  return { stored: saved !== null };
}

export async function storeSources(bucket, body, now = new Date(), id = crypto.randomUUID()) {
  await bucket.put(`sources/${dayOf(now)}/${id}.json`, JSON.stringify({ received: dayOf(now), records: body.records }), {
    httpMetadata: { contentType: "application/json" },
  });
  return { stored: true, records: body.records.length };
}

async function readBody(request, limit) {
  if (Number(request.headers.get("content-length") ?? 0) > limit) return { response: json({ error: "too large" }, 413) };
  const raw = await request.text();
  if (raw.length > limit) return { response: json({ error: "too large" }, 413) };
  try { return { body: JSON.parse(raw) }; } catch { return { response: json({ error: "json" }, 400) }; }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health") return json({ ok: true, schema: SCHEMA });
    const route = url.pathname === "/v1/batch" ? "batch" : url.pathname === "/v1/sources" ? "sources" : null;
    if (request.method !== "POST" || !route) return json({ error: "not found" }, 404);
    if (env.LIMITER) {
      // The limiter looks at the address only to count; nothing is stored here.
      const { success } = await env.LIMITER.limit({ key: request.headers.get("cf-connecting-ip") ?? "anon" });
      if (!success) return json({ error: "rate limited" }, 429);
    }
    const { body, response } = await readBody(request, route === "batch" ? MAX_BODY : MAX_SOURCES_BODY);
    if (response) return response;
    const problem = route === "batch" ? validate(body) : validateSources(body);
    if (problem) return json({ error: problem }, 422);
    try {
      return json(route === "batch" ? await storeBatch(env.CONTRIB, body) : await storeSources(env.CONTRIB, body));
    } catch {
      return json({ error: "storage" }, 503);
    }
  },
};
