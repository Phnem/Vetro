import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import worker, { validate, store, purge } from "../src/worker.js";

// A D1-shaped wrapper over node:sqlite: enough of prepare/bind/run/all/batch for the worker.
function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  const wrap = (sql) => {
    let args = [];
    const statement = {
      bind: (...a) => { args = a; return statement; },
      run: async () => { const r = db.prepare(sql).run(...args); return { meta: { changes: Number(r.changes) } }; },
      all: async () => ({ results: db.prepare(sql).all(...args) }),
      exec: () => db.prepare(sql).run(...args),
    };
    return statement;
  };
  return { prepare: wrap, batch: async (list) => { for (const s of list) s.exec(); }, raw: db };
}

const install = "0123456789abcdef0123456789abcdef";
const good = () => ({
  schema: 1, install, packs: { identity: "identity-2026-11-10-stable" },
  taste: [{ type: "ANIME", version: "experience-2026-11-10-stable", events: 40, weights: Array(11).fill(0.5) }],
  pairs: [{ features_version: "identity-features-v2", pack: "identity-2026-11-10-stable", source: "picker_choice", label: 1,
            kinds: [0, 0], features: Array(42).fill(0.25) }],
  genres: ["Slice of Life", "Sci-Fi & Fantasy"],
  counters: { "identity:search_same_top": 12 },
});

test("a well-formed batch passes", () => assert.equal(validate(good()), null));

test("free text and identifiers are rejected", () => {
  for (const mutate of [
    (b) => { b.title = "Death Note"; },
    (b) => { b.install = "user@example.com"; },
    (b) => { b.genres = ["write me at user@example.com"]; },
    (b) => { b.genres = ["x".repeat(60)]; },
    (b) => { b.genres = ["https://evil.example/a"]; },
    (b) => { b.counters = { "my title is here": 3 }; },
    (b) => { b.pairs[0].features = Array(41).fill(0); },
    (b) => { b.pairs[0].features[3] = Number.NaN; },
    (b) => { b.pairs[0].source = "free_text"; },
    (b) => { b.taste[0].weights = Array(11).fill(1e9); },
    (b) => { b.taste[0].events = 3; },
    (b) => { b.pairs = Array(51).fill(b.pairs[0]); },
  ]) {
    const b = good();
    mutate(b);
    assert.notEqual(validate(b), null, JSON.stringify(b).slice(0, 80));
  }
});

test("one batch per install per day; retries are harmless", async () => {
  const db = d1();
  const when = new Date("2026-11-10T12:00:00Z");
  assert.deepEqual(await store(db, good(), when), { stored: true });
  assert.deepEqual(await store(db, good(), when), { stored: false });
  assert.equal(db.raw.prepare("SELECT COUNT(*) n FROM pairs").get().n, 1);
  assert.equal(db.raw.prepare("SELECT value FROM counters WHERE key='identity:search_same_top'").get().value, 12);
  assert.equal(db.raw.prepare("SELECT week FROM taste").get().week, "2026-W46");
});

test("pairs are stored without any install id", async () => {
  const db = d1();
  await store(db, good(), new Date("2026-11-10T12:00:00Z"));
  const columns = db.raw.prepare("PRAGMA table_info(pairs)").all().map((c) => c.name);
  assert.ok(!columns.includes("install"));
});

test("a genre reaches the candidate view only with 20 installs, rare ones are purged", async () => {
  const db = d1();
  const when = new Date("2026-11-10T12:00:00Z");
  for (let i = 0; i < 20; i++) {
    const b = good();
    b.install = i.toString(16).padStart(32, "0");
    b.genres = i === 0 ? ["Slice of Life", "Rarity"] : ["Slice of Life"];
    await store(db, b, when);
  }
  const view = db.raw.prepare("SELECT text, installs FROM genre_candidates").all();
  assert.deepEqual(view.map((r) => r.text), ["Slice of Life"]);
  await purge(db, new Date("2026-12-20T00:00:00Z"));
  const left = db.raw.prepare("SELECT DISTINCT text FROM genre_strings").all().map((r) => r.text);
  assert.deepEqual(left, ["Slice of Life"]);
});

test("HTTP surface: health, size limit, validation errors", async () => {
  const env = { DB: d1() };
  const post = (body) => worker.fetch(new Request("https://x/v1/batch", { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) }), env);
  assert.equal((await worker.fetch(new Request("https://x/v1/health"), env)).status, 200);
  assert.equal((await worker.fetch(new Request("https://x/other"), env)).status, 404);
  assert.equal((await post("{")).status, 400);
  assert.equal((await post({ ...good(), extra: 1 })).status, 422);
  assert.equal((await post("x".repeat(70000))).status, 413);
  assert.equal((await post(good())).status, 200);
});
