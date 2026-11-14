import test from "node:test";
import assert from "node:assert/strict";
import worker, { validate, validateSources, storeBatch, storeSources } from "../src/worker.js";

// An R2-shaped bucket in memory: put honours onlyIf etagDoesNotMatch "*" (returns null when the object exists).
function bucket() {
  const files = new Map();
  return {
    files,
    put: async (key, value, options = {}) => {
      if (options.onlyIf?.etagDoesNotMatch === "*" && files.has(key)) return null;
      files.set(key, value);
      return { key };
    },
  };
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

test("one batch per install per day lands as a file; retries are harmless", async () => {
  const b = bucket();
  const when = new Date("2026-11-10T12:00:00Z");
  assert.deepEqual(await storeBatch(b, good(), when), { stored: true });
  assert.deepEqual(await storeBatch(b, good(), when), { stored: false });
  assert.deepEqual([...b.files.keys()], [`microml/2026-11-10/${install}.json`]);
  const saved = JSON.parse(b.files.get(`microml/2026-11-10/${install}.json`));
  assert.equal(saved.received, "2026-11-10");
  assert.equal(saved.counters["identity:search_same_top"], 12);
  assert.deepEqual(await storeBatch(b, good(), new Date("2026-11-11T01:00:00Z")), { stored: true });
});

const record = () => ({ source: "knigavuhe", key: "/book/123-dune/", title: "Дюна", authors: ["Фрэнк Герберт"], narrators: ["Иван Иванов"], year: 1965, cover: "https://example.org/c.jpg", duration_sec: 61000 });

test("a source record batch passes only when it is public book data", () => {
  assert.equal(validateSources({ schema: 1, records: [record()] }), null);
  for (const mutate of [
    (r) => { r.query = "what the user typed"; },
    (r) => { r.source = "Some Site!"; },
    (r) => { r.title = ""; },
    (r) => { r.title = "x".repeat(201); },
    (r) => { r.authors = Array(6).fill("a"); },
    (r) => { r.year = 1999.5; },
    (r) => { r.cover = "javascript:alert(1)"; },
    (r) => { r.cover = "https://exa mple.org/c.jpg"; },
    (r) => { r.duration_sec = -1; },
  ]) {
    const r = record();
    mutate(r);
    assert.notEqual(validateSources({ schema: 1, records: [r] }), null, JSON.stringify(r).slice(0, 80));
  }
  assert.notEqual(validateSources({ schema: 1, records: [] }), null);
  assert.notEqual(validateSources({ schema: 1, records: Array(101).fill(record()) }), null);
  assert.notEqual(validateSources({ schema: 1, records: [record()], install }), null);
});

test("source records are written as one file per upload, without any install id", async () => {
  const b = bucket();
  assert.deepEqual(await storeSources(b, { schema: 1, records: [record(), record()] }, new Date("2026-11-10T12:00:00Z"), "abc"), { stored: true, records: 2 });
  const saved = JSON.parse(b.files.get("sources/2026-11-10/abc.json"));
  assert.equal(saved.records.length, 2);
  assert.equal("install" in saved, false);
});

test("HTTP surface: health, size limits, validation errors, both routes, rate limit", async () => {
  const env = { CONTRIB: bucket() };
  const post = (path, body) => worker.fetch(new Request("https://x" + path, { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) }), env);
  assert.equal((await worker.fetch(new Request("https://x/v1/health"), env)).status, 200);
  assert.equal((await worker.fetch(new Request("https://x/other"), env)).status, 404);
  assert.equal((await post("/v1/batch", "{")).status, 400);
  assert.equal((await post("/v1/batch", { ...good(), extra: 1 })).status, 422);
  assert.equal((await post("/v1/batch", "x".repeat(70000))).status, 413);
  assert.equal((await post("/v1/batch", good())).status, 200);
  assert.equal((await post("/v1/sources", { schema: 1, records: [{ ...record(), query: "x" }] })).status, 422);
  assert.equal((await post("/v1/sources", { schema: 1, records: [record()] })).status, 200);
  assert.equal((await post("/v1/sources", "x".repeat(140000))).status, 413);
  assert.equal([...env.CONTRIB.files.keys()].filter((k) => k.startsWith("sources/")).length, 1);
  const limited = { CONTRIB: bucket(), LIMITER: { limit: async () => ({ success: false }) } };
  assert.equal((await worker.fetch(new Request("https://x/v1/batch", { method: "POST", body: JSON.stringify(good()) }), limited)).status, 429);
});
