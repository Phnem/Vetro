// Anime, films and series (cloud catalogue built by media-dictionary/cloud/build_media_server.py). Read-only.
//
//   GET /v1/media/search?q=&type=&limit=       FTS5 over titles in every language; type = anime | movie | tv (optional)
//   GET /v1/media/{id}                         one title with its detail card; id = A|M|S + 8 digits
//   GET /v1/media?ids=A00000001,M00000002      up to 40 titles, rows only
//   GET /covers/media/{small|large}/{id}.avif  covers (R2), URL carries ?v=<cv> for cache busting
//
// Rows (D1 table `media`): id, t (anime|movie|tv_series), ru, en, ro (romaji), orig, y, st (subtype), f (bit 1 cover, bit 2 restricted
// licence), cv (cover revision), d (JSON card: desc{t,l}, gen[], fr{t,i,n}, ol, seas, eps, dur, st, ext{}, cover{src,lic,w,h}).
// The helpers (json, cached, ...) come from worker.js through `h`, so this file does not import it back.

export const MEDIA_COVER_PATH = /^\/covers\/media\/(small|large)\/([A-Z]\d{8})\.avif$/;
export const MEDIA_ID_PATH = /^\/v1\/media\/([A-Z]\d{8})$/;
const ID = /^[A-Z]\d{8}$/;
const TYPES = { anime: "anime", movie: "movie", tv: "tv_series", tv_series: "tv_series", series: "tv_series" };
// bm25 is negative and smaller is better; popularity pulls well-known titles up (tuned on the real catalogue: "naruto", "breaking bad", "dune").
const POPULARITY_WEIGHT = 1.5;
const ROW = "m.id, m.t, m.ru, m.en, m.ro, m.orig, m.y, m.st, m.f, m.cv";

/** Which database a result came from. `kind` is what clients group and filter by; `t` is the raw type in the media table. */
export const KIND_OF_TYPE = { anime: "anime", movie: "movie", tv_series: "tv" };

export function mediaItem(r) {
  const o = { kind: KIND_OF_TYPE[r.t], id: r.id, t: r.t, ru: r.ru, en: r.en, ro: r.ro, orig: r.orig, y: r.y, st: r.st, f: r.f, cv: r.cv };
  for (const k of Object.keys(o)) if (o[k] === null || o[k] === undefined || o[k] === "") delete o[k];
  return o;
}

async function ftsRows(env, match, type, candidates) {
  const where = type ? "AND m.t = ?3" : "";
  const sql = `SELECT ${ROW}, m.pop, bm25(media_fts, 6, 6, 5, 4, 2, 1) AS b FROM media_fts JOIN media m ON m.n = media_fts.rowid ` +
    `WHERE media_fts MATCH ?1 ${where} ORDER BY b LIMIT ?2`;
  const stmt = env.DB.prepare(sql);
  const { results } = await (type ? stmt.bind(match, candidates, type) : stmt.bind(match, candidates)).all();
  return results;
}

export async function mediaSearch(h, env, ctx, request, url) {
  const q = h.norm(url.searchParams.get("q") ?? "");
  if (q.length < 2) return h.json({ q, items: [] }, 200, h.SEARCH_TTL);
  const rawType = url.searchParams.get("type");
  const type = rawType ? TYPES[rawType.toLowerCase()] : null;
  if (rawType && !type) return h.json({ error: "bad_type", allowed: ["anime", "movie", "tv"] }, 400, 60);
  const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") ?? "20", 10) || 20, 1), h.MAX_LIMIT);
  const tokens = q.split(" ").filter((t) => t.length <= 40).slice(0, h.MAX_TOKENS);
  if (!tokens.length) return h.json({ q, items: [] }, 200, h.SEARCH_TTL);

  const key = new Request(`https://cache.invalid/v1/media/search?q=${encodeURIComponent(tokens.join(" "))}&type=${type ?? ""}&limit=${limit}`);
  return h.cached(ctx, key, async () => {
    if (await h.throttled(env, request)) return h.json({ error: "rate_limited" }, 429, 0);
    const candidates = Math.min(limit * 4, 200); // more than asked for: popularity re-orders them below
    let rows = await ftsRows(env, h.matchExpr(tokens), type, candidates);
    if (rows.length < Math.min(limit, 5) && tokens.length > 1) {
      const seen = new Set(rows.map((r) => r.id));
      for (const r of await ftsRows(env, h.matchExpr(tokens, " OR "), type, candidates)) if (!seen.has(r.id)) rows.push(r);
    }
    rows.sort((a, b) => (a.b - POPULARITY_WEIGHT * Math.log1p(a.pop ?? 0)) - (b.b - POPULARITY_WEIGHT * Math.log1p(b.pop ?? 0)));
    return h.json({ q, items: rows.slice(0, limit).map(mediaItem) }, 200, h.SEARCH_TTL);
  });
}

const KINDS = ["all", "anime", "movie", "tv", "book"];
const ALL_ORDER = ["anime", "tv", "movie", "book"];

/**
 * One search across the three databases, or across one of them. Every item carries `kind` (anime | movie | tv | book).
 *   kind=all (default) ranks inside each database and interleaves them, so the best of each shows up first; `counts` tells how many came from each
 *   kind=anime|movie|tv|book searches only that database (the app's search directions use this)
 */
export async function find(h, env, ctx, request, url) {
  const q = h.norm(url.searchParams.get("q") ?? "");
  const kind = (url.searchParams.get("kind") ?? "all").toLowerCase();
  if (!KINDS.includes(kind)) return h.json({ error: "bad_kind", allowed: KINDS }, 400, 60);
  if (q.length < 2) return h.json({ q, kind, items: [] }, 200, h.SEARCH_TTL);
  const limit = Math.min(Math.max(parseInt(url.searchParams.get("limit") ?? "20", 10) || 20, 1), h.MAX_LIMIT);
  const tokens = q.split(" ").filter((t) => t.length <= 40).slice(0, h.MAX_TOKENS);
  if (!tokens.length) return h.json({ q, kind, items: [] }, 200, h.SEARCH_TTL);

  const key = new Request(`https://cache.invalid/v1/find?q=${encodeURIComponent(tokens.join(" "))}&kind=${kind}&limit=${limit}`);
  return h.cached(ctx, key, async () => {
    if (await h.throttled(env, request)) return h.json({ error: "rate_limited" }, 429, 0);
    const wantBooks = kind === "all" || kind === "book";
    const wantMedia = kind !== "book";
    const type = ["anime", "movie", "tv"].includes(kind) ? TYPES[kind] : null;
    const expand = async (run) => {
      const rows = await run(h.matchExpr(tokens));
      if (rows.length < Math.min(limit, 5) && tokens.length > 1) {
        const seen = new Set(rows.map((r) => r.id));
        for (const r of await run(h.matchExpr(tokens, " OR "))) if (!seen.has(r.id)) rows.push(r);
      }
      return rows;
    };
    const [books, media] = await Promise.all([
      wantBooks ? expand((m) => h.bookRows(env, m, limit)) : [],
      wantMedia ? expand((m) => ftsRows(env, m, type, Math.min(limit * (kind === "all" ? 6 : 4), 200))) : [],
    ]);
    const groups = { anime: [], movie: [], tv: [], book: books.slice(0, limit).map((r) => h.bookItem(r)) };
    media.sort((a, b) => (a.b - POPULARITY_WEIGHT * Math.log1p(a.pop ?? 0)) - (b.b - POPULARITY_WEIGHT * Math.log1p(b.pop ?? 0)));
    for (const r of media) groups[KIND_OF_TYPE[r.t]].push(mediaItem(r));
    let items;
    if (kind === "all") {
      items = [];
      for (let i = 0; items.length < limit && ALL_ORDER.some((k) => groups[k][i]); i++) {
        for (const k of ALL_ORDER) if (groups[k][i] && items.length < limit) items.push(groups[k][i]);
      }
    } else {
      items = groups[kind].slice(0, limit);
    }
    const counts = {};
    for (const it of items) counts[it.kind] = (counts[it.kind] ?? 0) + 1;
    return h.json({ q, kind, counts, items }, 200, h.SEARCH_TTL);
  });
}

export async function mediaById(h, env, ctx, request, id) {
  const key = new Request(`https://cache.invalid/v1/media/${id}`);
  return h.cached(ctx, key, async () => {
    if (await h.throttled(env, request)) return h.json({ error: "rate_limited" }, 429, 0);
    const row = await env.DB.prepare("SELECT id, t, ru, en, ro, orig, alt, y, st, f, cv, d FROM media WHERE id = ?1").bind(id).first();
    if (!row) return h.json({ error: "not_found" }, 404, 300);
    let detail = {};
    try { detail = JSON.parse(row.d ?? "{}"); } catch { /* keep an empty card */ }
    return h.json({ ...mediaItem(row), alt: row.alt || undefined, detail }, 200, h.BOOK_TTL);
  });
}

export async function mediaBatch(h, env, request, url) {
  const ids = [...new Set((url.searchParams.get("ids") ?? "").split(",").map((s) => s.trim()).filter((s) => ID.test(s)))].slice(0, h.MAX_IDS);
  if (!ids.length) return h.json({ items: [] }, 200, 60);
  if (await h.throttled(env, request)) return h.json({ error: "rate_limited" }, 429, 0);
  const marks = ids.map((_, i) => `?${i + 1}`).join(",");
  const { results } = await env.DB.prepare(`SELECT ${ROW} FROM media m WHERE m.id IN (${marks})`).bind(...ids).all();
  return h.json({ items: results.map(mediaItem) }, 200, h.BOOK_TTL);
}
