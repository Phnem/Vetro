import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import worker, { matchExpr } from "../src/worker.js";
import { norm } from "../src/norm.js";

// --- fakes: D1 over real SQLite (FTS5 included), R2 over a Map, the Cache API as a no-op ---------------------------
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
    async get(key, opts) {
      const f = files.get(key);
      if (!f) return null;
      const etag = `"${f.etag}"`;
      const base = {
        size: f.bytes.length, httpEtag: etag,
        writeHttpMetadata: (h) => h.set("content-type", f.type),
      };
      if (opts?.onlyIf?.get("if-none-match") === etag) return base; // no body = precondition matched
      const range = opts?.range?.get("range")?.match(/bytes=(\d+)-(\d+)/);
      if (range) {
        const offset = +range[1], length = +range[2] - +range[1] + 1;
        return { ...base, body: new Blob([f.bytes.slice(offset, offset + length)]).stream(), range: { offset, length } };
      }
      return { ...base, body: new Blob([f.bytes]).stream() };
    },
  };
}

const SCHEMA = `
CREATE TABLE works (id INTEGER PRIMARY KEY, ru TEXT, en TEXT, ra TEXT, ea TEXT, alt TEXT, x TEXT, y INTEGER, f INTEGER, cv INTEGER, d TEXT);
CREATE VIRTUAL TABLE fts USING fts5(ru, en, ra, ea, alt, x, tokenize='unicode61 remove_diacritics 2', prefix='2 3');`;

function env({ limiter } = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec(SCHEMA);
  const ins = sqlite.prepare("INSERT INTO works VALUES (?,?,?,?,?,?,?,?,?,?,?)");
  const rows = [
    [771, "Фунты лиха в Париже и Лондоне", "Down and Out in Paris and London", "Джордж Оруэлл", "George Orwell", "", "", 1933, 1, 4242, '{"type":"novel"}'],
    [5, "Чайка", "The Seagull", "Антон Чехов", "Anton Chekhov", "", "chayka", 1896, 1, 7, "{}"],
    [6, "Ёлка", null, "Иван Иванов", null, "", "елка", null, 0, 0, "{}"],
  ];
  for (const r of rows) {
    ins.run(...r);
    sqlite.prepare("INSERT INTO fts(rowid, ru, en, ra, ea, alt, x) VALUES (?,?,?,?,?,?,?)").run(r[0], r[1], r[2], r[3], r[4], r[5], r[6]);
  }
  const files = new Map([
    ["index/manifest.json", { bytes: new TextEncoder().encode('{"version":1}'), type: "application/json", etag: "m1" }],
    ["index/base/bookindex-v1.sqlite.zst", { bytes: new Uint8Array(100).map((_, i) => i), type: "application/zstd", etag: "b1" }],
    ["covers/small/000000771.avif", { bytes: new Uint8Array([1, 2, 3]), type: "image/avif", etag: "c1" }],
  ]);
  return { DB: fakeD1(sqlite), BOOKS: fakeR2(files), LIMITER: limiter };
}

const ctx = { waitUntil() {} };
const get = (e, path, headers = {}, method = "GET") =>
  worker.fetch(new Request("https://api.vetrostudio.bid" + path, { method, headers }), e, ctx);

test("norm matches the shared golden cases", () => {
  const cases = JSON.parse(readFileSync(new URL("../../../book-dictionary/v2/tests/golden_norm.json", import.meta.url), "utf8"));
  for (const c of cases) assert.equal(norm(c.in), c.norm, c.in);
});

test("matchExpr builds an AND of quoted prefixes", () => {
  assert.equal(matchExpr(["a", "bc"]), '"a"* "bc"*');
  assert.equal(matchExpr(["a", "bc"], " OR "), '"a"* OR "bc"*');
});

test("health and method guard", async () => {
  const e = env();
  assert.deepEqual(await (await get(e, "/v1/health")).json(), { ok: true });
  assert.equal((await get(e, "/v1/health", {}, "POST")).status, 405);
  assert.equal((await get(e, "/nope")).status, 404);
});

test("manifest is served with ETag and 304 on revalidation", async () => {
  const e = env();
  const res = await get(e, "/v1/manifest");
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { version: 1 });
  assert.match(res.headers.get("cache-control"), /max-age=300/);
  const again = await get(e, "/v1/manifest", { "if-none-match": res.headers.get("etag") });
  assert.equal(again.status, 304);
});

test("index files are immutable, support ranges, and reject path tricks", async () => {
  const e = env();
  const full = await get(e, "/v1/index/base/bookindex-v1.sqlite.zst");
  assert.equal(full.status, 200);
  assert.match(full.headers.get("cache-control"), /immutable/);
  const part = await get(e, "/v1/index/base/bookindex-v1.sqlite.zst", { range: "bytes=10-19" });
  assert.equal(part.status, 206);
  assert.equal(part.headers.get("content-range"), "bytes 10-19/100");
  assert.deepEqual([...new Uint8Array(await part.arrayBuffer())], [10, 11, 12, 13, 14, 15, 16, 17, 18, 19]);
  assert.equal((await get(e, "/v1/index/base/..%2Fsecret")).status, 404);
  assert.equal((await get(e, "/v1/index/other/x")).status, 404);
});

test("covers: only 9-digit ids of the two sizes", async () => {
  const e = env();
  assert.equal((await get(e, "/covers/small/000000771.avif?v=4242")).status, 200);
  assert.equal((await get(e, "/covers/small/000000999.avif")).status, 404);
  assert.equal((await get(e, "/covers/huge/000000771.avif")).status, 404);
  assert.equal((await get(e, "/covers/small/771.avif")).status, 404);
});

test("search: titles, authors, folded and transliterated forms", async () => {
  const e = env();
  const ids = async (q) => (await (await get(e, "/v1/search?q=" + encodeURIComponent(q))).json()).items.map((i) => i.id);
  assert.deepEqual(await ids("оруэлл"), ["000000771"]);
  assert.deepEqual(await ids("down paris"), ["000000771"]);
  assert.deepEqual(await ids("chayka"), ["000000005"]);
  assert.deepEqual(await ids("Ёлка"), ["000000006"]);
  assert.deepEqual(await ids("x"), []); // too short
  const first = (await (await get(e, "/v1/search?q=оруэлл")).json()).items[0];
  assert.deepEqual(first, { id: "000000771", ru: "Фунты лиха в Париже и Лондоне", en: "Down and Out in Paris and London",
    ra: "Джордж Оруэлл", ea: "George Orwell", y: 1933, f: 1, cv: 4242 });
});

test("search loosens to OR when AND finds almost nothing", async () => {
  const e = env();
  const items = (await (await get(e, "/v1/search?q=" + encodeURIComponent("чайка оруэлл"))).json()).items.map((i) => i.id);
  assert.deepEqual(items.sort(), ["000000005", "000000771"]);
});

test("book detail and batch", async () => {
  const e = env();
  const one = await (await get(e, "/v1/books/000000771")).json();
  assert.equal(one.id, "000000771");
  assert.deepEqual(one.detail, { type: "novel" });
  assert.equal((await get(e, "/v1/books/000000999")).status, 404);
  const many = await (await get(e, "/v1/books?ids=5,6,abc,771")).json();
  assert.deepEqual(many.items.map((i) => i.id).sort(), ["000000005", "000000006", "000000771"]);
});

test("rate limit answers 429 and is not cached", async () => {
  const e = env({ limiter: { limit: async () => ({ success: false }) } });
  assert.equal((await get(e, "/v1/search?q=чайка")).status, 429);
  assert.equal((await get(e, "/v1/books/000000771")).status, 429);
  assert.equal((await get(e, "/v1/health")).status, 200); // cheap routes are never throttled
});
