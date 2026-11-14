// Vetro MicroML collector: a Cloudflare Worker that accepts anonymous, schema-checked batches of
// numbers and counters from consenting installs and writes them to D1. No titles, no free text
// except short genre strings, no IP address, no account. See README.md for the exact data list.

const SCHEMA = 1;
const MAX_BODY = 64 * 1024;
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

function isoWeek(date) {
  const d = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
  const day = d.getUTCDay() || 7;
  d.setUTCDate(d.getUTCDate() + 4 - day);
  const start = new Date(Date.UTC(d.getUTCFullYear(), 0, 1));
  return `${d.getUTCFullYear()}-W${String(Math.ceil(((d - start) / 86400000 + 1) / 7)).padStart(2, "0")}`;
}

export async function store(db, body, now = new Date()) {
  const day = now.toISOString().slice(0, 10);
  const week = isoWeek(now);
  // One batch per install per day. A second one is acknowledged and dropped, so retries are harmless.
  const first = await db.prepare("INSERT OR IGNORE INTO uploads(install, day) VALUES (?, ?)").bind(body.install, day).run();
  if (!first.meta.changes) return { stored: false };
  const statements = [];
  for (const t of body.taste ?? []) {
    statements.push(db.prepare(
      "INSERT INTO taste(install, week, type, version, events, weights) VALUES (?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(install, week, type) DO UPDATE SET version = excluded.version, events = excluded.events, weights = excluded.weights",
    ).bind(body.install, week, t.type, t.version, t.events, JSON.stringify(t.weights.map(round))));
  }
  // Pairs carry no install id: a row cannot be traced back to a device.
  for (const p of body.pairs ?? []) {
    statements.push(db.prepare(
      "INSERT INTO pairs(day, features_version, pack, source, label, kind_a, kind_b, features) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
    ).bind(day, p.features_version, p.pack, p.source, p.label, p.kinds[0], p.kinds[1], JSON.stringify(p.features.map(round))));
  }
  for (const g of new Set(body.genres ?? [])) {
    statements.push(db.prepare("INSERT OR IGNORE INTO genre_strings(text, install, first_day) VALUES (?, ?, ?)").bind(g, body.install, day));
  }
  for (const [key, value] of Object.entries(body.counters ?? {})) {
    statements.push(db.prepare(
      "INSERT INTO counters(day, key, value) VALUES (?, ?, ?) ON CONFLICT(day, key) DO UPDATE SET value = value + excluded.value",
    ).bind(day, key, value));
  }
  for (const [pack, version] of Object.entries(body.packs ?? {})) {
    statements.push(db.prepare(
      "INSERT INTO counters(day, key, value) VALUES (?, ?, 1) ON CONFLICT(day, key) DO UPDATE SET value = value + 1",
    ).bind(day, `pack:${pack}_${version.replace(/[^a-z0-9]/gi, "_").toLowerCase().slice(0, 40)}`));
  }
  if (statements.length) await db.batch(statements);
  return { stored: true };
}

export async function purge(db, now = new Date(), threshold = 20) {
  const day = (offset) => new Date(now.getTime() - offset * 86400000).toISOString().slice(0, 10);
  await db.batch([
    db.prepare("DELETE FROM uploads WHERE day < ?").bind(day(3)),
    // A genre string that did not reach the installs threshold within 30 days is not kept.
    db.prepare(
      "DELETE FROM genre_strings WHERE first_day < ? AND text IN (SELECT text FROM genre_strings GROUP BY text HAVING COUNT(*) < ?)",
    ).bind(day(30), threshold),
    db.prepare("DELETE FROM taste WHERE week < ?").bind(isoWeek(new Date(now.getTime() - 120 * 86400000))),
    db.prepare("DELETE FROM pairs WHERE day < ?").bind(day(400)),
  ]);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/v1/health") return json({ ok: true, schema: SCHEMA });
    if (request.method !== "POST" || url.pathname !== "/v1/batch") return json({ error: "not found" }, 404);
    if (Number(request.headers.get("content-length") ?? 0) > MAX_BODY) return json({ error: "too large" }, 413);
    const text = await request.text();
    if (text.length > MAX_BODY) return json({ error: "too large" }, 413);
    let body;
    try { body = JSON.parse(text); } catch { return json({ error: "json" }, 400); }
    const problem = validate(body);
    if (problem) return json({ error: problem }, 422);
    try {
      return json(await store(env.DB, body));
    } catch {
      return json({ error: "storage" }, 503);
    }
  },
  async scheduled(_event, env) {
    await purge(env.DB);
  },
};
