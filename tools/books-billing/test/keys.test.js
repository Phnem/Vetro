import assert from "node:assert/strict";
import { test } from "node:test";
import { newApiKey as apiNewApiKey, sha256Hex as apiSha256Hex } from "../../books-api/src/access.js";
import { newApiKey, newOrderRef, sha256Hex } from "../src/keys.js";

test("keys.js matches tools/books-api/src/access.js: format, id, prefix and sha256Hex", async () => {
  for (const tier of ["basic", "pro", "ultra"]) {
    const orig = await apiNewApiKey(tier);
    const mine = await newApiKey(tier);

    const plainRe = new RegExp(`^vk_${tier}_[a-z2-7]{32}$`);
    assert.match(orig.plain, plainRe);
    assert.match(mine.plain, plainRe);
    assert.match(orig.id, /^k_[0-9a-f]{12}$/);
    assert.match(mine.id, /^k_[0-9a-f]{12}$/);
    assert.equal(mine.prefix, mine.plain.slice(0, 12) + "…");
    assert.equal(mine.prefix.length, orig.prefix.length);
    assert.equal(await sha256Hex(orig.plain), orig.hash);
    assert.equal(await apiSha256Hex(mine.plain), mine.hash);
    assert.match(mine.hash, /^[0-9a-f]{64}$/);
  }
});

test("order refs are ord_ + 32 hex and do not repeat", () => {
  const seen = new Set();
  for (let i = 0; i < 200; i++) {
    const ref = newOrderRef();
    assert.match(ref, /^ord_[0-9a-f]{32}$/);
    seen.add(ref);
  }
  assert.equal(seen.size, 200);
});
