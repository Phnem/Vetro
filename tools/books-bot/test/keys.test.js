import assert from "node:assert/strict";
import { test } from "node:test";
import { newApiKey as apiNewApiKey, sha256Hex as apiSha256Hex } from "../../books-api/src/access.js";
import { newApiKey as botNewApiKey, sha256Hex as botSha256Hex } from "../src/keys.js";

test("keys.js matches tools/books-api/src/access.js format, id, prefix, and sha256Hex", async () => {
  for (const tier of ["basic", "pro", "ultra"]) {
    const orig = await apiNewApiKey(tier);
    const bot = await botNewApiKey(tier);

    const plainRe = new RegExp(`^vk_${tier}_[a-z2-7]{32}$`);
    assert.match(orig.plain, plainRe);
    assert.match(bot.plain, plainRe);

    assert.match(orig.id, /^k_[0-9a-f]{12}$/);
    assert.match(bot.id, /^k_[0-9a-f]{12}$/);

    assert.equal(orig.prefix, orig.plain.slice(0, 12) + "…");
    assert.equal(bot.prefix, bot.plain.slice(0, 12) + "…");
    assert.equal(bot.prefix.length, orig.prefix.length);

    assert.equal(await botSha256Hex(orig.plain), orig.hash);
    assert.equal(await apiSha256Hex(bot.plain), bot.hash);
    assert.equal(bot.hash, await botSha256Hex(bot.plain));
    assert.match(bot.hash, /^[0-9a-f]{64}$/);
  }
});
