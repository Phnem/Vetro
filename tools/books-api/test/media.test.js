import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker from "../src/worker.js";
import { routeOf } from "../src/access.js";

globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };

function fakeD1(sqlite) {
  return {
    prepare(sql) {
      const stmt = sqlite.prepare(sql);
      const make = (args) => ({
        all: async () => ({ results: stmt.all(...args) }),
        first: async () => stmt.get(...args) ?? null,
      });
      return { ...make([]), bind: (...args) => make(args) };
    },
  };
}

function fakeR2(files) {
  return {
    async get(key) {
      const f = files.get(key);
      if (!f) return null;
      return { size: f.length, httpEtag: '"e"', writeHttpMetadata: (h) => h.set("content-type", "image/avif"), body: new Blob([f]).stream() };
    },
  };
}

// The same shape media-dictionary/cloud/build_media_server.py writes.
const SCHEMA = `
CREATE TABLE media (n INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, t TEXT NOT NULL, ru TEXT, en TEXT, ro TEXT, orig TEXT, alt TEXT, x TEXT,
  y INTEGER, st TEXT, pop REAL, f INTEGER NOT NULL DEFAULT 0, cv INTEGER NOT NULL DEFAULT 0, d TEXT);
CREATE VIRTUAL TABLE media_fts USING fts5(ru, en, ro, orig, alt, x, tokenize='unicode61 remove_diacritics 2', prefix='2 3');`;

function env() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(SCHEMA);
  const rows = [
    // n, id, t, ru, en, ro, orig, alt, x, y, st, pop, f, cv, d
    [1, "A00000050", "anime", "Атака титанов", "Attack on Titan", "Shingeki no Kyojin", "進撃の巨人", "AoT", "ataka titanov", 2013, "tv", 900, 3, 11, '{"gen":["Action"],"desc":{"t":"Humanity vs titans","l":"en"},"fr":{"t":"Attack on Titan","n":5}}'],
    [10000001, "M00000001", "movie", null, "Naruto", null, "Naruto", null, "", null, "movie", 0, 0, 0, null],
    [20000001, "S00000001", "tv_series", "Наруто", "Naruto", null, "Naruto", null, "naruto", 2002, "tv", 800, 1, 5, '{"ext":{"tmdb":46260}}'],
    [20000002, "S00000002", "tv_series", "Во все тяжкие", "Breaking Bad", null, "Breaking Bad", null, "vo vse tyazhkie", 2008, "tv", 700, 1, 6, null],
  ];
  const ins = sqlite.prepare("INSERT INTO media VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
  const fts = sqlite.prepare("INSERT INTO media_fts(rowid, ru, en, ro, orig, alt, x) VALUES (?,?,?,?,?,?,?)");
  for (const r of rows) {
    ins.run(...r);
    fts.run(r[0], r[3], r[4], r[5], r[6], r[7], r[8]);
  }
  const files = new Map([["covers/media/small/A00000050.avif", new Uint8Array([1, 2, 3])]]);
  return { DB: fakeD1(sqlite), BOOKS: fakeR2(files) };
}

const ctx = { waitUntil() {} };
const get = (path, e = env()) => worker.fetch(new Request("https://api.vetrostudio.bid" + path), e, ctx);

test("media routes are counted with the matching book routes (limits and tier checks are shared)", () => {
  assert.equal(routeOf("/v1/media/search"), "search");
  assert.equal(routeOf("/v1/media/A00000050"), "books");
  assert.equal(routeOf("/v1/media"), "books");
  assert.equal(routeOf("/covers/media/small/A00000050.avif"), "covers");
  assert.equal(routeOf("/covers/media/small/000000771.avif"), "other");
  assert.equal(routeOf("/v1/media/search/extra"), "other");
});

test("search: popular title first, folded and transliterated forms, type filter", async () => {
  let body = await (await get("/v1/media/search?q=naruto")).json();
  assert.deepEqual(body.items.map((i) => i.id), ["S00000001", "M00000001"]); // the series is far more popular than the film
  body = await (await get("/v1/media/search?q=naruto&type=movie")).json();
  assert.deepEqual(body.items.map((i) => i.id), ["M00000001"]);
  body = await (await get("/v1/media/search?q=ataka%20titanov")).json();
  assert.equal(body.items[0].id, "A00000050");
  body = await (await get("/v1/media/search?q=" + encodeURIComponent("атака титанов"))).json();
  assert.equal(body.items[0].id, "A00000050");
  assert.deepEqual(Object.keys(body.items[0]).sort(), ["cv", "en", "f", "id", "kind", "orig", "ro", "ru", "st", "t", "y"]);
  body = await (await get("/v1/media/search?q=breaking%20bad")).json();
  assert.equal(body.items[0].en, "Breaking Bad");
});

test("search: bad type is a 400, a short query is empty, limit is clamped", async () => {
  assert.equal((await get("/v1/media/search?q=naruto&type=book")).status, 400);
  assert.deepEqual((await (await get("/v1/media/search?q=a")).json()).items, []);
  const res = await get("/v1/media/search?q=naruto&limit=1");
  assert.equal((await res.json()).items.length, 1);
});

test("title card: detail JSON, missing title, bad id", async () => {
  const card = await (await get("/v1/media/A00000050")).json();
  assert.equal(card.id, "A00000050");
  assert.equal(card.alt, "AoT");
  assert.deepEqual(card.detail.gen, ["Action"]);
  assert.equal(card.detail.fr.n, 5);
  assert.equal((await get("/v1/media/A99999999")).status, 404);
  assert.equal((await get("/v1/media/a00000050")).status, 404);
  assert.equal((await get("/v1/media/12345")).status, 404);
});

test("batch: valid ids only, no duplicates, unknown skipped", async () => {
  const body = await (await get("/v1/media?ids=A00000050,A00000050,S00000002,bad,X1,Z00000009")).json();
  assert.deepEqual(body.items.map((i) => i.id).sort(), ["A00000050", "S00000002"]);
  assert.deepEqual((await (await get("/v1/media?ids=")).json()).items, []);
});

test("media covers: only id shapes of the two sizes, 404 when absent", async () => {
  const ok = await get("/covers/media/small/A00000050.avif");
  assert.equal(ok.status, 200);
  assert.equal(ok.headers.get("content-type"), "image/avif");
  assert.equal((await get("/covers/media/large/A00000050.avif")).status, 404);
  assert.equal((await get("/covers/media/huge/A00000050.avif")).status, 404);
  assert.equal((await get("/covers/media/small/50.avif")).status, 404);
});

test("find: every item says which database it came from, kind filters to one database", async () => {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(SCHEMA);
  sqlite.exec(`CREATE TABLE works (id INTEGER PRIMARY KEY, ru TEXT, en TEXT, ra TEXT, ea TEXT, alt TEXT, x TEXT, y INTEGER, f INTEGER, cv INTEGER, d TEXT);
    CREATE VIRTUAL TABLE fts USING fts5(ru, en, ra, ea, alt, x, tokenize='unicode61 remove_diacritics 2', prefix='2 3');`);
  const m = sqlite.prepare("INSERT INTO media VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
  const mf = sqlite.prepare("INSERT INTO media_fts(rowid, ru, en, ro, orig, alt, x) VALUES (?,?,?,?,?,?,?)");
  for (const r of [
    [1, "A00000050", "anime", "Атака титанов", "Attack on Titan", null, null, null, "ataka titanov", 2013, "tv", 900, 1, 3, null],
    [20000001, "S00000620", "tv_series", "Атака титанов", "Attack on Titan", null, null, null, "ataka titanov", 2013, "tv", 500, 0, 0, null],
    [10000001, "M00000010", "movie", "Атака титанов: фильм", "Attack on Titan: The Movie", null, null, null, "", 2015, "movie", 100, 0, 0, null],
  ]) { m.run(...r); mf.run(r[0], r[3], r[4], r[5], r[6], r[7], r[8]); }
  sqlite.prepare("INSERT INTO works VALUES (?,?,?,?,?,?,?,?,?,?,?)").run(900, "Атака титанов. Книга", null, "Автор", null, "", "", 2020, 0, 0, "{}");
  sqlite.prepare("INSERT INTO fts(rowid, ru, en, ra, ea, alt, x) VALUES (?,?,?,?,?,?,?)").run(900, "Атака титанов. Книга", null, "Автор", null, "", "");
  const e = { DB: fakeD1(sqlite), BOOKS: fakeR2(new Map()) };
  const q = encodeURIComponent("атака титанов");

  const all = await (await get(`/v1/find?q=${q}`, e)).json();
  assert.equal(all.kind, "all");
  assert.deepEqual(all.items.map((i) => i.kind), ["anime", "tv", "movie", "book"]); // the best of each database, interleaved
  assert.deepEqual(all.counts, { anime: 1, tv: 1, movie: 1, book: 1 });
  assert.equal(all.items.find((i) => i.kind === "book").id, "000000900");

  for (const kind of ["anime", "tv", "movie", "book"]) {
    const only = await (await get(`/v1/find?q=${q}&kind=${kind}`, e)).json();
    assert.equal(only.kind, kind);
    assert.equal(only.items.length, 1, kind);
    assert.deepEqual(only.items.map((i) => i.kind), [kind]);
  }
  assert.equal((await get(`/v1/find?q=${q}&kind=manga`, e)).status, 400);
  assert.deepEqual((await (await get("/v1/find?q=a", e)).json()).items, []);
  assert.equal(routeOf("/v1/find"), "search");
});
