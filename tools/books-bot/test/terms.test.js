import assert from "node:assert/strict";
import { test } from "node:test";
import { termsText } from "../src/i18n.js";

test("termsText uses live tier limits from D1 and includes all required clauses in RU and EN", () => {
  const customTier = {
    name: "pro",
    title: "Pro Custom",
    per_second: 7,
    per_minute: 180,
    per_day: 33333,
    max_limit: 55,
    max_ids: 45,
    allow_covers: 1,
    allow_index: 0,
    period_days: 45,
    price_stars: 555,
  };

  const ru = termsText(customTier, "ru");
  assert.match(ru, /Pro Custom/);
  assert.match(ru, /7\/с/);
  assert.match(ru, /180\/мин/);
  assert.match(ru, /33\s*333\/сут/);
  assert.match(ru, /555 ⭐/);
  assert.match(ru, /перепродажа/i);
  assert.match(ru, /правообладателям/i);
  assert.match(ru, /без гарантий/i);
  assert.match(ru, /отозвать при злоупотреблении/i);
  assert.match(ru, /7 дней/i);
  assert.match(ru, /один раз/i);

  const en = termsText(customTier, "en");
  assert.match(en, /Pro Custom/);
  assert.match(en, /7\/s/);
  assert.match(en, /180\/min/);
  assert.match(en, /33,333\/day/);
  assert.match(en, /555 ⭐/);
  assert.match(en, /reselling/i);
  assert.match(en, /copyright holders/i);
  assert.match(en, /as-is without warranties/i);
  assert.match(en, /revoked for abuse/i);
  assert.match(en, /7 days/i);
  assert.match(en, /shown once/i);
});
