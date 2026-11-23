import assert from "node:assert/strict";
import { test } from "node:test";
import { processEvent, SUB_GRACE_MS } from "../src/issue.js";
import { purgeOldUsage, sendExpiryReminders } from "../src/reminders.js";
import { checkout, getOrderRow, makeEnv, mockWebhook, T0 } from "./helpers.js";

const DAY = 86_400_000;
const count = (env, t, where = "1") => env.sqlite.prepare(`SELECT COUNT(*) AS n FROM ${t} WHERE ${where}`).get().n;
const keyRow = (env, id) => env.sqlite.prepare("SELECT * FROM api_keys WHERE id = ?").get(id);
const noSleep = async () => {};

async function placed(env, over, opts) {
  const res = await checkout(env, over, opts);
  assert.equal(res.status, 200, JSON.stringify(res.data));
  return getOrderRow(env, res.data.orderId);
}

/** Событие провайдера применяется в боевом режиме (выдача не зависит от переходника), потом режим возвращается. Сеть и паузы подменены. */
async function live(env, event, deps = {}) {
  const was = env.BILLING_MODE;
  env.BILLING_MODE = "live";
  try {
    return await processEvent(env, "mock", event, { sleep: noSleep, fetchImpl: async () => new Response("{}"), ...deps });
  } finally {
    env.BILLING_MODE = was;
  }
}
const paid = (order, over = {}) => ({ type: "paid", checkoutRef: order.checkout_ref, providerRef: "pay_" + order.id, amountMinor: order.amount_minor, currency: "USD", tier: order.tier, ...over });

test("the price is computed on the server: subscription from tiers, packs from the grid", async () => {
  const grid = { basic: { 30: 3, 90: 9, 180: 15, 365: 29 }, pro: { 30: 7, 90: 20, 180: 40, 365: 75 }, ultra: { 30: 12, 90: 35, 180: 69, 365: 129 } };
  const env = makeEnv();
  let n = 0;
  for (const [plan, row] of Object.entries(grid)) {
    const sub = await placed(env, { plan, email: `s${n++}@example.com` });
    assert.equal(sub.amount_minor, row[30] * 100, `${plan} subscription`);
    assert.equal(sub.billing, "subscription");
    assert.equal(sub.days, 30);
    for (const [days, usd] of Object.entries(row)) {
      const order = await placed(env, { plan, method: "usdt", days: Number(days), email: `p${n++}@example.com` });
      assert.equal(order.amount_minor, usd * 100, `${plan} ${days} days`);
      assert.equal(order.billing, "one_time");
      assert.equal(order.days, Number(days));
    }
  }
  env.sqlite.exec("UPDATE tiers SET price_usd_minor = 999 WHERE name = 'pro'");
  assert.equal((await placed(env, { plan: "pro", email: "x1@example.com" })).amount_minor, 999, "the subscription follows tiers.price_usd_minor");
  assert.equal((await placed(env, { plan: "pro", method: "lava", days: 90, email: "x2@example.com" })).amount_minor, 2000, "packs do not");
});

test("expectedAmountUsd is only a cross-check: a mismatch is 400 price_mismatch and writes nothing", async () => {
  const env = makeEnv();
  assert.equal((await checkout(env, { plan: "pro", method: "usdt", days: 90, expectedAmountUsd: 20 })).status, 200);
  assert.equal((await checkout(env, { plan: "pro", email: "b@example.com", expectedAmountUsd: 7 })).status, 200);
  const before = count(env, "web_orders");
  for (const expectedAmountUsd of [19, 21, 0, 7.01]) {
    const res = await checkout(env, { plan: "pro", method: "usdt", days: 90, email: "c@example.com", expectedAmountUsd });
    assert.equal(res.status, 400, String(expectedAmountUsd));
    assert.deepEqual(res.data, { error: "price_mismatch" });
  }
  for (const expectedAmountUsd of ["20", null, NaN, -1]) {
    const res = await checkout(env, { plan: "pro", method: "usdt", days: 90, email: "c@example.com", expectedAmountUsd });
    assert.equal(res.status, 400);
    assert.equal(res.data.error, "bad_field");
  }
  assert.equal(count(env, "web_orders"), before);
});

test("billing and days must fit the method: card = subscription 30, lava|usdt = pack 30/90/180/365", async () => {
  const env = makeEnv();
  const bad = async (over, field) => {
    const res = await checkout(env, { email: "z@example.com", ...over });
    assert.equal(res.status, 400, JSON.stringify(over));
    assert.equal(res.data.field, field);
  };
  await bad({ method: "card", billing: "one_time" }, "billing");
  await bad({ method: "usdt", billing: "subscription" }, "billing");
  await bad({ method: "lava", billing: "subscription" }, "billing");
  await bad({ billing: undefined }, "billing");
  await bad({ billing: "monthly" }, "billing");
  await bad({ method: "card", days: 90 }, "days");
  await bad({ method: "card", days: undefined }, "days");
  await bad({ method: "usdt", days: 45 }, "days");
  await bad({ method: "usdt", days: "90" }, "days");
  await bad({ method: "lava", days: 0 }, "days");
  await bad({ method: "lava", days: 730 }, "days");
  assert.equal(count(env, "web_orders"), 0);
  for (const days of [30, 90, 180, 365]) assert.equal((await checkout(env, { email: `d${days}@example.com`, method: "lava", days })).status, 200, String(days));
});

test("packs: a new key lasts paid_at + days; repeat purchases add days to the same key, from now when expired", async () => {
  const env = makeEnv();
  const first = await placed(env, { plan: "basic", method: "usdt", days: 90 });
  assert.equal((await live(env, paid(first), { now: T0 })).result, "issued");
  const keyId = getOrderRow(env, first.checkout_ref).key_id;
  assert.equal(keyRow(env, keyId).expires_at, T0 + 90 * DAY);

  const second = await placed(env, { plan: "basic", method: "usdt", days: 30 });
  assert.equal(second.kind, "renew");
  assert.equal((await live(env, paid(second), { now: T0 + 10 * DAY })).result, "renewed");
  assert.equal(keyRow(env, keyId).expires_at, T0 + 120 * DAY, "max(now, expires_at) + 30 days: unexpired days are kept");

  env.sqlite.prepare("UPDATE api_keys SET expires_at = ? WHERE id = ?").run(T0 - DAY, keyId);
  const third = await placed(env, { plan: "basic", method: "lava", days: 365 });
  assert.equal(third.kind, "renew");
  assert.equal((await live(env, paid(third), { now: T0 + 5 * DAY })).result, "renewed");
  assert.equal(keyRow(env, keyId).expires_at, T0 + 5 * DAY + 365 * DAY, "an expired key counts from now");
  assert.equal(count(env, "api_keys"), 1);
});

test("subscription: the first payment sets the period end plus a grace, every confirmed renewal moves it by a month", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "pro" });
  const P1 = T0 + 30 * DAY;
  const P2 = T0 + 61 * DAY;
  assert.equal((await live(env, paid(order, { subscriptionId: "sub_1", periodEnd: P1 }), { now: T0 })).result, "issued");
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  assert.equal(keyRow(env, keyId).expires_at, P1 + SUB_GRACE_MS);
  const sub = env.sqlite.prepare("SELECT * FROM web_subscriptions").get();
  assert.equal(sub.sub_ref, "sub_1");
  assert.equal(sub.status, "active");
  assert.equal(sub.order_id, order.id);

  const renewal = { type: "renewed", subRef: "sub_1", providerRef: "inv_2", amountMinor: 700, currency: "USD", periodEnd: P2 };
  const res = await live(env, renewal, { now: P1 });
  assert.equal(res.result, "renewed");
  assert.equal(res.keyId, keyId);
  assert.equal(keyRow(env, keyId).expires_at, P2 + SUB_GRACE_MS);
  assert.equal(count(env, "api_keys"), 1);
  assert.equal(count(env, "web_renewals"), 1);

  assert.equal((await live(env, renewal, { now: P1 })).result, "duplicate", "a replayed delivery extends nothing");
  assert.equal(keyRow(env, keyId).expires_at, P2 + SUB_GRACE_MS);
  assert.equal(count(env, "web_renewals"), 1);

  const early = await live(env, { ...renewal, providerRef: "inv_3", periodEnd: P1 }, { now: P1 });
  assert.equal(early.result, "renewed");
  assert.equal(keyRow(env, keyId).expires_at, P2 + SUB_GRACE_MS, "an older period end never shortens the key");
});

test("subscription renewals without a period end add 30 days; test mode adds one day", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "basic" });
  await live(env, paid(order, { subscriptionId: "sub_a" }), { now: T0 });
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  assert.equal(keyRow(env, keyId).expires_at, T0 + 30 * DAY);
  await live(env, { type: "renewed", subRef: "sub_a", providerRef: "i1", amountMinor: 300, currency: "USD" }, { now: T0 + 30 * DAY });
  assert.equal(keyRow(env, keyId).expires_at, T0 + 60 * DAY);

  const env2 = makeEnv();
  const o2 = await placed(env2, { plan: "basic" });
  await processEvent(env2, "mock", paid(o2, { subscriptionId: "sub_t" }), { now: T0, sleep: noSleep });
  const k2 = getOrderRow(env2, o2.checkout_ref).key_id;
  await processEvent(env2, "mock", { type: "renewed", subRef: "sub_t", providerRef: "i1", amountMinor: 300, currency: "USD" }, { now: T0, sleep: noSleep });
  assert.equal(keyRow(env2, k2).expires_at, T0 + 2 * DAY, "test keys: one day per payment");
});

test("renewal checks: unknown subscription, other amount, revoked key", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "pro" });
  await live(env, paid(order, { subscriptionId: "sub_2" }), { now: T0 });
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  const exp = keyRow(env, keyId).expires_at;
  const ev = { type: "renewed", subRef: "sub_2", providerRef: "i1", amountMinor: 700, currency: "USD" };
  assert.equal((await live(env, { ...ev, subRef: "nope" })).result, "unknown_order");
  assert.equal((await live(env, { ...ev, amountMinor: 1 })).result, "rejected");
  assert.equal((await live(env, { ...ev, currency: "EUR", providerRef: "i2" })).result, "rejected");
  assert.equal(env.sqlite.prepare("SELECT note FROM web_subscriptions").get().note, "renewal_mismatch");
  assert.equal(keyRow(env, keyId).expires_at, exp);
  assert.equal(count(env, "web_renewals"), 0);
  assert.equal((await live(env, { ...ev, providerRef: "" })).result, "ignored");

  env.sqlite.prepare("UPDATE api_keys SET status = 'revoked' WHERE id = ?").run(keyId);
  assert.equal((await live(env, { ...ev, providerRef: "i3" })).result, "rejected", "a revoked key is never extended");
  assert.equal(env.sqlite.prepare("SELECT note FROM web_subscriptions").get().note, "key_revoked");
  assert.equal(keyRow(env, keyId).expires_at, exp);
});

test("cancel stops renewals but keeps the key until the paid period ends; revoked closes the subscription", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "pro" });
  await live(env, paid(order, { subscriptionId: "sub_3", periodEnd: T0 + 30 * DAY }), { now: T0 });
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  const exp = keyRow(env, keyId).expires_at;
  await live(env, { type: "canceled", subRef: "sub_3" }, { now: T0 + 5 * DAY });
  const sub = () => env.sqlite.prepare("SELECT * FROM web_subscriptions").get();
  assert.equal(sub().status, "canceled");
  assert.equal(sub().canceled_at, T0 + 5 * DAY);
  assert.equal(keyRow(env, keyId).status, "active");
  assert.equal(keyRow(env, keyId).expires_at, exp, "access runs to the end of the paid period");

  await live(env, { type: "revoked", subRef: "sub_3" }, { now: T0 + 31 * DAY });
  assert.equal(sub().status, "revoked");
  assert.equal(keyRow(env, keyId).expires_at, exp, "a revoked subscription does not cut a key that another payment may have extended");
  assert.equal((await live(env, { type: "renewed", subRef: "sub_3", providerRef: "late", amountMinor: 700, currency: "USD" })).result, "ignored");
  assert.equal((await live(env, { type: "canceled", subRef: "nope" })).result, "unknown_order");
});

test("a second checkout for a key that is paid by an active subscription is 409 subscription_active; after cancel it is a renewal", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "pro" });
  await live(env, paid(order, { subscriptionId: "sub_4" }), { now: T0 });
  for (const over of [{ plan: "pro" }, { plan: "pro", method: "usdt", days: 90 }]) {
    const res = await checkout(env, over);
    assert.equal(res.status, 409, JSON.stringify(over));
    assert.deepEqual(res.data, { error: "subscription_active" });
  }
  await live(env, { type: "canceled", subRef: "sub_4" });
  const again = await placed(env, { plan: "pro", method: "usdt", days: 30 });
  assert.equal(again.kind, "renew");
});

test("the webhook route understands subscription events (mock signs them like Polar would)", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "basic" });
  const base = { orderId: order.checkout_ref, amountMinor: 300, currency: "USD", tier: "basic" };
  assert.equal((await mockWebhook(env, { type: "paid", paymentId: "p1", subscriptionId: "sub_w", ...base })).data.result, "issued");
  assert.equal((await mockWebhook(env, { type: "renewed", paymentId: "i1", subscriptionId: "sub_w", amountMinor: 300, currency: "USD" })).data.result, "renewed");
  assert.equal((await mockWebhook(env, { type: "renewed", paymentId: "i1", subscriptionId: "sub_w", amountMinor: 300, currency: "USD" })).data.result, "duplicate");
  assert.equal((await mockWebhook(env, { type: "canceled", subscriptionId: "sub_w" })).status, 200);
  assert.equal(env.sqlite.prepare("SELECT status FROM web_subscriptions").get().status, "canceled");
});

test("refunds: a renewal payment takes back its period, the initial payment revokes the key; both cancel the subscription at the provider", async () => {
  const env = makeEnv({ MOCK_CANCELLED: [] });
  const order = await placed(env, { plan: "pro" });
  await live(env, paid(order, { subscriptionId: "sub_5", periodEnd: T0 + 30 * DAY }), { now: T0 });
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  await live(env, { type: "renewed", subRef: "sub_5", providerRef: "inv_9", amountMinor: 700, currency: "USD", periodEnd: T0 + 60 * DAY }, { now: T0 + 30 * DAY });
  const exp = keyRow(env, keyId).expires_at;

  // возврат платежа-продления: событие без заказа, по id платежа
  assert.equal((await mockWebhook(env, { type: "refunded", paymentId: "inv_9" })).data.result, "refunded");
  assert.equal(env.sqlite.prepare("SELECT status FROM web_renewals").get().status, "refunded");
  assert.equal(keyRow(env, keyId).expires_at, exp - DAY, "test mode: a refunded payment takes back one day");
  assert.equal(keyRow(env, keyId).status, "active");
  assert.deepEqual(env.MOCK_CANCELLED, ["sub_5"], "the subscription is cancelled at the provider");
  assert.equal(env.sqlite.prepare("SELECT status FROM web_subscriptions").get().status, "canceled");
  assert.equal((await mockWebhook(env, { type: "refunded", paymentId: "inv_9" })).data.result, "duplicate");
  assert.equal((await mockWebhook(env, { type: "refunded", paymentId: "nothing" })).data.result, "unknown_order");

  // возврат первого платежа
  assert.equal((await mockWebhook(env, { type: "refunded", paymentId: "pay_" + order.id })).data.result, "refunded");
  assert.equal(keyRow(env, keyId).status, "revoked");
  assert.equal(getOrderRow(env, order.checkout_ref).status, "refunded");
});

test("a failed provider-side cancellation is flagged on the order for the owner", async () => {
  const env = makeEnv();
  const order = await placed(env, { plan: "pro" });
  await processEvent(env, "mock", paid(order, { subscriptionId: "sub_6" }), { now: T0, sleep: noSleep });
  const { mock } = await import("../src/providers/mock.js");
  const original = mock.cancelSubscription;
  mock.cancelSubscription = async () => {
    throw new Error("provider down");
  };
  try {
    assert.equal((await processEvent(env, "mock", { type: "refunded", checkoutRef: order.checkout_ref, providerRef: "r" }, { now: T0 })).result, "refunded");
  } finally {
    mock.cancelSubscription = original;
  }
  assert.equal(getOrderRow(env, order.checkout_ref).note, "refunded_cancel_failed");
});

test("the key e-mail carries the plan limits, the term and the key", async () => {
  const sent = [];
  const env = makeEnv({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "k", MAIL_FROM: "x@y.z" });
  const order = await placed(env, { plan: "pro", lang: "ru" });
  await live(env, paid(order), { now: T0, fetchImpl: async (_u, init) => (sent.push(JSON.parse(init.body)), new Response("{}")) });
  assert.match(sent[0].text, /Лимиты: 5 запр\.\/с, 120 запр\.\/мин, 20,000 запр\.\/сутки \(UTC\)\./);
  assert.match(sent[0].text, /vk_pro_[a-z2-7]{32}/);
  assert.match(sent[0].text, /действует до 2026-12-23 12:00 UTC/);
});

// --- напоминания и очистка -----------------------------------------------------------------------------------------

async function packKey(env, days = 30, over = {}) {
  const order = await placed(env, { plan: "basic", method: "usdt", days, ...over });
  await live(env, paid(order, { providerRef: "p" + order.id }), { now: T0 });
  return getOrderRow(env, order.checkout_ref);
}
const mailEnv = () => makeEnv({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "k", MAIL_FROM: "x@y.z" });
const spy = () => {
  const sent = [];
  return { sent, sleep: noSleep, fetchImpl: async (_u, init) => (sent.push(JSON.parse(init.body)), new Response("{}")) };
};

test("reminders: a pack key gets one e-mail three days before it ends, and again after it is extended", async () => {
  const env = mailEnv();
  const order = await packKey(env, 30);
  const s = spy();
  env.BILLING_MODE = "live";
  assert.equal(await sendExpiryReminders(env, T0 + 26 * DAY, s), 0, "four days left: too early");
  assert.equal(await sendExpiryReminders(env, T0 + 27.5 * DAY, s), 1);
  assert.equal(s.sent.length, 1);
  assert.equal(s.sent[0].subject, "Your Vetro API key expires soon");
  assert.deepEqual(s.sent[0].to, ["buyer@example.com"]);
  assert.doesNotMatch(s.sent[0].text, /vk_basic_[a-z2-7]{32}/, "the reminder never contains the key");
  assert.equal(await sendExpiryReminders(env, T0 + 28 * DAY, s), 0, "once per expiry value");
  assert.equal(await sendExpiryReminders(env, T0 + 28 * DAY, s), 0);

  env.BILLING_MODE = "test";
  const again = await placed(env, { plan: "basic", method: "usdt", days: 30 });
  await live(env, paid(again, { providerRef: "p2" }), { now: T0 + 28 * DAY });
  env.BILLING_MODE = "live";
  assert.equal(await sendExpiryReminders(env, T0 + 28.5 * DAY, s), 0, "extended: out of the window");
  assert.equal(await sendExpiryReminders(env, T0 + 57.5 * DAY, s), 1, "a new term, a new reminder");
  assert.equal(keyRow(env, order.key_id).status, "active");
});

test("reminders: none for subscriptions, revoked keys, test mode, or when no mail transport is set", async () => {
  const env = mailEnv();
  const sub = await placed(env, { plan: "pro", email: "sub@example.com" });
  await live(env, paid(sub, { subscriptionId: "sub_r" }), { now: T0 });
  await packKey(env, 30, { email: "pack@example.com" });
  env.sqlite.exec("UPDATE api_keys SET expires_at = " + (T0 + 29 * DAY) + " WHERE tier = 'pro'");
  const s = spy();
  env.BILLING_MODE = "live";
  assert.equal(await sendExpiryReminders(env, T0 + 28 * DAY, s), 1, "only the pack key; the subscription renews itself");
  assert.deepEqual(s.sent[0].to, ["pack@example.com"]);

  const env2 = mailEnv();
  await packKey(env2, 30);
  env2.sqlite.exec("UPDATE api_keys SET status = 'revoked'");
  env2.BILLING_MODE = "live";
  assert.equal(await sendExpiryReminders(env2, T0 + 28 * DAY, spy()), 0);

  const env3 = mailEnv();
  await packKey(env3, 30);
  const s3 = spy();
  assert.equal(await sendExpiryReminders(env3, T0 + 28 * DAY, s3), 0, "test mode");
  env3.BILLING_MODE = "live";
  env3.MAIL_TRANSPORT = undefined;
  assert.equal(await sendExpiryReminders(env3, T0 + 28 * DAY, s3), 0, "no transport");
  assert.equal(s3.sent.length, 0);
  assert.equal(count(env3, "web_reminders"), 0, "nothing is marked, so the reminder is not lost");
});

test("a reminder that failed to send is retried by the next cron run", async () => {
  const env = mailEnv();
  await packKey(env, 30);
  env.BILLING_MODE = "live";
  const failing = { fetchImpl: async () => new Response("no", { status: 500 }), sleep: noSleep };
  assert.equal(await sendExpiryReminders(env, T0 + 28 * DAY, failing), 0);
  assert.equal(count(env, "web_reminders"), 0);
  const ok = spy();
  assert.equal(await sendExpiryReminders(env, T0 + 28.1 * DAY, ok), 1);
  assert.equal(ok.sent.length, 1);
});

test("usage counters older than USAGE_RETENTION_DAYS are deleted; unset or tiny values delete nothing", async () => {
  const env = makeEnv();
  const day = (n) => new Date(T0 - n * DAY).toISOString().slice(0, 10);
  for (const n of [1, 59, 61, 100]) env.sqlite.prepare("INSERT INTO usage_daily (key_id, day, route, requests) VALUES ('k_aaaaaaaaaaaa', ?, 'search', 5)").run(day(n));
  assert.equal(await purgeOldUsage(env, T0), 0, "not configured: keep everything");
  for (const v of ["0", "3", "abc", "30.5", ""]) assert.equal(await purgeOldUsage({ ...env, USAGE_RETENTION_DAYS: v }, T0), 0, v);
  assert.equal(count(env, "usage_daily"), 4);
  assert.equal(await purgeOldUsage({ ...env, USAGE_RETENTION_DAYS: "60" }, T0), 2);
  assert.deepEqual(env.sqlite.prepare("SELECT day FROM usage_daily ORDER BY day").all().map((r) => r.day), [day(59), day(1)]);
});

test("the quota is a daily reset: no monthly counters or pools exist in tiers, api_keys or usage_daily", () => {
  const env = makeEnv();
  for (const t of ["tiers", "api_keys", "usage_daily"]) {
    const cols = env.sqlite.prepare(`PRAGMA table_info(${t})`).all().map((c) => c.name);
    assert.ok(cols.length > 0);
    assert.ok(!cols.some((c) => /month|pool|balance|credit/i.test(c)), `${t}: ${cols.join(",")}`);
  }
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM tiers WHERE sold = 1").get().n, 3, "limits are per second, per minute and per UTC day for every sold tier");
});
