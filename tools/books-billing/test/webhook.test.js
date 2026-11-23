import assert from "node:assert/strict";
import { test } from "node:test";
import { expirePending, processEvent } from "../src/issue.js";
import { sha256Hex } from "../src/keys.js";
import { hmacSha256Hex } from "../src/signing.js";
import worker from "../src/worker.js";
import { call, checkout, getOrderRow, makeEnv, mockWebhook, paidEvent, T0 } from "./helpers.js";

const DAY = 86_400_000;
const count = (env, t, where = "1") => env.sqlite.prepare(`SELECT COUNT(*) AS n FROM ${t} WHERE ${where}`).get().n;

async function placed(env, over, opts) {
  const res = await checkout(env, over, opts);
  assert.equal(res.status, 200);
  return getOrderRow(env, res.data.orderId);
}

test("a webhook issues exactly one key with the right fields; the plain key is nowhere in D1", async () => {
  const env = makeEnv();
  const order = await placed(env);
  const res = await mockWebhook(env, paidEvent(order));
  assert.equal(res.status, 200);
  assert.deepEqual(res.data, { ok: true, result: "issued" });

  const paid = getOrderRow(env, order.checkout_ref);
  assert.equal(paid.status, "paid");
  assert.equal(paid.provider_ref, "pay_1");
  assert.equal(paid.paid_at, T0);
  assert.match(paid.key_id, /^k_[0-9a-f]{12}$/);

  const key = env.sqlite.prepare("SELECT * FROM api_keys").get();
  assert.equal(key.id, paid.key_id);
  assert.equal(key.tier, "pro");
  assert.equal(key.status, "active");
  assert.equal(key.owner, "web:test", "test mode keys are marked");
  assert.equal(key.label, "TEST My Site");
  assert.equal(key.customer_id, null);
  assert.equal(key.application_id, null);
  assert.equal(key.created_at, T0);
  assert.equal(key.expires_at, T0 + DAY, "test keys live one day");
  assert.deepEqual(JSON.parse(key.allowed_origins), ["example.com", "www.example.com"]);

  const plain = env.REVEAL.m.get(`reveal:${order.checkout_ref}`);
  assert.match(plain, /^vk_pro_[a-z2-7]{32}$/);
  assert.equal(env.REVEAL.m.ttl, 600);
  assert.equal(key.key_hash, await sha256Hex(plain));
  assert.equal(key.key_prefix, plain.slice(0, 12) + "…");

  for (const t of ["api_keys", "web_orders", "web_customers", "contract_signatures"]) {
    assert.doesNotMatch(JSON.stringify(env.sqlite.prepare(`SELECT * FROM ${t}`).all()), new RegExp(plain.slice(8)), `${t} must not hold the key`);
  }
});

test("a replayed webhook never issues a second key, sequentially or at once", async () => {
  const env = makeEnv();
  const order = await placed(env);
  const first = await mockWebhook(env, paidEvent(order));
  assert.equal(first.data.result, "issued");
  const again = await mockWebhook(env, paidEvent(order));
  assert.equal(again.status, 200);
  assert.equal(again.data.result, "duplicate");
  const other = await mockWebhook(env, paidEvent(order, { paymentId: "pay_other" }));
  assert.equal(other.data.result, "duplicate", "another payment id on a paid order issues nothing either");
  assert.equal(count(env, "api_keys"), 1);

  const env2 = makeEnv();
  const order2 = await placed(env2);
  const results = await Promise.all(Array.from({ length: 6 }, () => mockWebhook(env2, paidEvent(order2))));
  assert.equal(results.filter((r) => r.data.result === "issued").length, 1);
  assert.equal(count(env2, "api_keys"), 1);
});

test("the same payment id cannot pay two different orders: the second one is closed, no key", async () => {
  const env = makeEnv();
  const a = await placed(env, { email: "a@example.com" });
  const b = await placed(env, { email: "b@example.com" });
  assert.equal((await mockWebhook(env, paidEvent(a, { paymentId: "same" }))).data.result, "issued");
  const res = await mockWebhook(env, paidEvent(b, { paymentId: "same" }));
  assert.equal(res.status, 200);
  assert.equal(res.data.result, "rejected");
  const row = getOrderRow(env, b.checkout_ref);
  assert.equal(row.status, "failed");
  assert.equal(row.note, "duplicate_payment_ref");
  assert.equal(row.key_id, null);
  assert.equal(count(env, "api_keys"), 1);
});

test("amount, currency and tier must match the order, otherwise failed, no key, a note for the owner", async () => {
  for (const [over, note] of [
    [{ amountMinor: 1 }, "mismatch_amount"],
    [{ amountMinor: 700.5 }, "mismatch_amount"],
    [{ amountMinor: "700" }, "mismatch_amount"],
    [{ currency: "EUR" }, "mismatch_currency"],
    [{ tier: "ultra" }, "mismatch_tier"],
    [{ tier: undefined }, "mismatch_tier"],
  ]) {
    const env = makeEnv();
    const order = await placed(env);
    const res = await mockWebhook(env, paidEvent(order, over));
    assert.equal(res.status, 200, "the provider must not retry");
    assert.equal(res.data.result, "rejected");
    const row = getOrderRow(env, order.checkout_ref);
    assert.equal(row.status, "failed");
    assert.equal(row.note, note);
    assert.equal(row.key_id, null);
    assert.equal(row.provider_ref, "pay_1", "kept so the owner can find the payment and refund");
    assert.equal(count(env, "api_keys"), 0);
    assert.equal(env.REVEAL.m.size, 0);
  }
});

test("a price change after checkout does not break the order: the amount stored in the order is the contract", async () => {
  const env = makeEnv();
  const order = await placed(env);
  env.sqlite.exec("UPDATE tiers SET price_usd_minor = 999 WHERE name = 'pro'");
  assert.equal((await mockWebhook(env, paidEvent(order, { amountMinor: 700 }))).data.result, "issued");
});

test("the signature of the webhook is checked before anything else", async () => {
  const env = makeEnv();
  const order = await placed(env);
  const raw = JSON.stringify(paidEvent(order));
  for (const headers of [{}, { "x-mock-signature": "00" }, { "x-mock-signature": "f".repeat(64) }]) {
    const res = await call(env, "/v1/billing/webhook/mock", { method: "POST", raw, headers });
    assert.equal(res.status, 401);
  }
  assert.equal((await mockWebhook(env, paidEvent(order), { secret: "wrong" })).status, 401);
  const noSecret = makeEnv({ MOCK_WEBHOOK_SECRET: undefined });
  const o2 = await placed(noSecret);
  assert.equal((await mockWebhook(noSecret, paidEvent(o2), { secret: "anything" })).status, 401, "no secret configured = nothing is accepted");
  assert.equal(count(env, "api_keys") + count(noSecret, "api_keys"), 0);
  // подписанное, но не JSON — 400, а не 500
  const junk = await call(env, "/v1/billing/webhook/mock", { method: "POST", raw: "{nope", headers: { "x-mock-signature": await hmacSha256Hex("whsec-mock", "{nope") } });
  assert.equal(junk.status, 400);
});

test("webhook routing: unknown or unconfigured providers and wrong methods", async () => {
  const env = makeEnv();
  for (const name of ["polar", "lava", "usdt", "paddle", "crypto", "nobody"]) {
    assert.equal((await call(env, `/v1/billing/webhook/${name}`, { method: "POST", raw: "{}" })).status, 404, name);
  }
  assert.equal((await call(env, "/v1/billing/webhook/mock", { method: "GET" })).status, 405);
  const live = makeEnv({ BILLING_MODE: "live" });
  assert.equal((await call(live, "/v1/billing/webhook/mock", { method: "POST", raw: "{}" })).status, 404, "mock does not exist in live mode");
  const big = await call(env, "/v1/billing/webhook/mock", { method: "POST", raw: "x".repeat(300 * 1024) });
  assert.equal(big.status, 413);
});

test("an unknown order or an order of another provider is ignored", async () => {
  const env = makeEnv();
  const order = await placed(env);
  const res = await mockWebhook(env, paidEvent("ord_" + "0".repeat(32)));
  assert.equal(res.status, 200);
  assert.equal(res.data.result, "unknown_order");
  env.sqlite.exec("UPDATE web_orders SET provider = 'polar'");
  assert.equal((await mockWebhook(env, paidEvent(order))).data.result, "unknown_order");
  assert.equal(count(env, "api_keys"), 0);
});

test("no accepted terms or signature = no key", async () => {
  const env = makeEnv();
  const order = await placed(env);
  env.sqlite.exec("DELETE FROM contract_signatures");
  const res = await mockWebhook(env, paidEvent(order));
  assert.equal(res.data.result, "rejected");
  assert.equal(getOrderRow(env, order.checkout_ref).note, "no_contract");
  assert.equal(count(env, "api_keys"), 0);
});

test("a buyer banned between checkout and payment gets no key", async () => {
  const env = makeEnv();
  const order = await placed(env);
  env.sqlite.exec("UPDATE web_customers SET banned = 1");
  assert.equal((await mockWebhook(env, paidEvent(order))).data.result, "rejected");
  const row = getOrderRow(env, order.checkout_ref);
  assert.equal(row.status, "failed");
  assert.equal(row.note, "banned");
  assert.equal(count(env, "api_keys"), 0);
});

test("a refund revokes the key, marks the order and wipes the one-time copy", async () => {
  const env = makeEnv();
  const order = await placed(env);
  await mockWebhook(env, paidEvent(order));
  assert.ok(env.REVEAL.m.has(`reveal:${order.checkout_ref}`));
  const refund = { type: "refunded", orderId: order.checkout_ref, paymentId: "ref_1", amountMinor: 700, currency: "USD", tier: "pro" };
  const res = await mockWebhook(env, refund);
  assert.equal(res.data.result, "refunded");
  const row = getOrderRow(env, order.checkout_ref);
  assert.equal(row.status, "refunded");
  assert.equal(row.note, "refunded");
  const key = env.sqlite.prepare("SELECT status, revoked_at FROM api_keys").get();
  assert.equal(key.status, "revoked");
  assert.equal(key.revoked_at, T0);
  assert.equal(env.REVEAL.m.size, 0);
  assert.equal((await call(env, `/v1/billing/order/${order.checkout_ref}`)).data.status, "failed", "the buyer sees failed");
  assert.equal((await mockWebhook(env, refund)).data.result, "duplicate");
});

test("a refund of an order that was never paid changes nothing; a provider failure closes a pending order", async () => {
  const env = makeEnv();
  const order = await placed(env);
  const refund = { type: "refunded", orderId: order.checkout_ref, paymentId: "r", amountMinor: 700, currency: "USD", tier: "pro" };
  assert.equal((await mockWebhook(env, refund)).data.result, "ignored");
  assert.equal(getOrderRow(env, order.checkout_ref).status, "pending");
  await mockWebhook(env, { ...refund, type: "failed" });
  const row = getOrderRow(env, order.checkout_ref);
  assert.equal(row.status, "failed");
  assert.equal(row.note, "provider_failed");
  assert.equal((await mockWebhook(env, { ...refund, type: "weird" })).data.result, "ignored");
});

test("a payment that arrives after the order expired still pays it", async () => {
  const env = makeEnv();
  const order = await placed(env);
  env.sqlite.exec("UPDATE web_orders SET status = 'expired'");
  const res = await mockWebhook(env, paidEvent(order));
  assert.equal(res.data.result, "issued");
  assert.equal(getOrderRow(env, order.checkout_ref).status, "paid");
});

test("if a delivery died after 'paid' but before the key, the retry finishes the job once", async () => {
  const env = makeEnv();
  const order = await placed(env);
  env.sqlite.prepare("UPDATE web_orders SET status = 'paid', provider_ref = 'pay_1', paid_at = ?").run(T0);
  assert.equal((await mockWebhook(env, paidEvent(order))).data.result, "issued");
  assert.equal((await mockWebhook(env, paidEvent(order))).data.result, "duplicate");
  assert.equal(count(env, "api_keys"), 1);
  assert.equal((await mockWebhook(env, paidEvent(order, { paymentId: "different" }))).data.result, "duplicate");
});

test("live mode keys: owner web:<id>, the real label, expiry paid_at + period_days, site origins only for sites", async () => {
  const env = makeEnv();
  const site = await placed(env, { plan: "ultra", email: "site@example.com" });
  const app = await placed(env, { plan: "basic", email: "app@example.com", project: { name: "My App", type: "app", domains: ["good.com"] } });
  env.BILLING_MODE = "live"; // выдача не зависит от провайдера: событие уже разобрано
  const T = T0 + 5000;
  await processEvent(env, "mock", { type: "paid", checkoutRef: site.checkout_ref, providerRef: "p_site", amountMinor: 1200, currency: "USD", tier: "ultra" }, { now: T });
  await processEvent(env, "mock", { type: "paid", checkoutRef: app.checkout_ref, providerRef: "p_app", amountMinor: 300, currency: "USD", tier: "basic" }, { now: T });

  const keys = env.sqlite.prepare("SELECT * FROM api_keys ORDER BY tier DESC").all();
  assert.equal(keys.length, 2);
  const [ultra, basic] = keys;
  assert.equal(ultra.owner, `web:${site.customer_id}`);
  assert.equal(ultra.label, "My Site");
  assert.equal(ultra.expires_at, T + 30 * DAY);
  assert.deepEqual(JSON.parse(ultra.allowed_origins), ["example.com", "www.example.com"]);
  assert.equal(basic.owner, `web:${app.customer_id}`);
  assert.equal(basic.allowed_origins, null, "origins only for site projects");
  assert.equal(basic.expires_at, T + 30 * DAY);
});

test("expired pending orders: older than 24 hours become expired, the rest is untouched", async () => {
  const env = makeEnv();
  const old = await placed(env, { email: "old@example.com" }, { now: T0 });
  const fresh = await placed(env, { email: "fresh@example.com" }, { now: T0 + 23 * 3_600_000 });
  const paid = await placed(env, { email: "paid@example.com" }, { now: T0 });
  await mockWebhook(env, paidEvent(paid, { paymentId: "pp" }));
  assert.equal(await expirePending(env, T0 + DAY + 60_000), 1);
  assert.equal(getOrderRow(env, old.checkout_ref).status, "expired");
  assert.equal(getOrderRow(env, fresh.checkout_ref).status, "pending");
  assert.equal(getOrderRow(env, paid.checkout_ref).status, "paid");
  assert.equal(await expirePending(env, T0 + DAY + 60_000), 0, "idempotent");
  // cron: scheduled() вызывает ту же функцию
  assert.equal(typeof worker.scheduled, "function");
  await worker.scheduled({}, env);
  assert.equal(getOrderRow(env, old.checkout_ref).status, "expired");
});

test("mock test pay page: pay → key via the same issuance as the webhook → redirect to the fixed return url", async () => {
  const env = makeEnv();
  const res = await checkout(env);
  const ref = res.data.orderId;
  const page = await call(env, `/v1/billing/test/pay?order=${ref}`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type"), /text\/html/);
  assert.match(page.text, /Pay \(test\)/);
  assert.match(page.text, new RegExp(`name="order" value="${ref}"`));
  assert.match(page.headers.get("content-security-policy"), /default-src 'none'/);
  assert.equal(page.headers.get("cache-control"), "no-store");

  const pay = await call(env, "/v1/billing/test/pay", { method: "POST", raw: `order=${ref}` });
  assert.equal(pay.status, 303);
  assert.equal(pay.headers.get("location"), `https://vetrostudio.bid/api.html?order=${ref}`);
  assert.equal(count(env, "api_keys"), 1);
  const key = env.sqlite.prepare("SELECT owner, label FROM api_keys").get();
  assert.equal(key.owner, "web:test");
  assert.match(key.label, /^TEST /);

  const again = await call(env, "/v1/billing/test/pay", { method: "POST", raw: `order=${ref}` });
  assert.equal(again.status, 303, "pressing twice is harmless");
  assert.equal(count(env, "api_keys"), 1);
  assert.match((await call(env, `/v1/billing/test/pay?order=${ref}`)).text, /Renewal \(test\)/, "a paid subscription offers the renewal and cancel events");
  const pack = (await checkout(env, { email: "pack@example.com", method: "usdt", days: 90 })).data.orderId;
  await call(env, "/v1/billing/test/pay", { method: "POST", raw: `order=${pack}` });
  assert.match((await call(env, `/v1/billing/test/pay?order=${pack}`)).text, /already processed/);
});

test("the test pay page answers 404 outside test mode and for unknown or foreign orders", async () => {
  const env = makeEnv();
  const ref = (await checkout(env)).data.orderId;
  for (const mode of ["live", undefined, "TEST"]) {
    env.BILLING_MODE = mode;
    assert.equal((await call(env, `/v1/billing/test/pay?order=${ref}`)).status, 404, String(mode));
    assert.equal((await call(env, "/v1/billing/test/pay", { method: "POST", raw: `order=${ref}` })).status, 404, String(mode));
  }
  env.BILLING_MODE = "test";
  assert.equal((await call(env, "/v1/billing/test/pay?order=ord_" + "1".repeat(32))).status, 404);
  assert.equal((await call(env, "/v1/billing/test/pay")).status, 404);
  assert.equal((await call(env, "/v1/billing/test/pay?order=%3Cscript%3E")).status, 404);
  assert.equal((await call(env, "/v1/billing/test/pay", { method: "POST", raw: "order=nope" })).status, 404);
  env.sqlite.exec("UPDATE web_orders SET provider = 'polar'");
  assert.equal((await call(env, `/v1/billing/test/pay?order=${ref}`)).status, 404, "only mock orders can be paid on the test page");
  assert.equal(count(env, "api_keys"), 0);
});

test("the browser return alone never issues a key: only a provider event does", async () => {
  const env = makeEnv();
  const ref = (await checkout(env)).data.orderId;
  for (let i = 0; i < 3; i++) await call(env, `/v1/billing/order/${ref}`);
  assert.equal(count(env, "api_keys"), 0);
  assert.equal(getOrderRow(env, ref).status, "pending");
});

// --- продление повторной покупкой ----------------------------------------------------------------------------------

async function boughtAndRenewOrdered(env, { plan = "basic" } = {}) {
  const first = await placed(env, { plan });
  await mockWebhook(env, paidEvent(first, { paymentId: "first" }));
  const keyId = getOrderRow(env, first.checkout_ref).key_id;
  const second = await placed(env, { plan });
  return { first, second, keyId };
}

test("a renewal pays max(now, expires_at) + period onto the same key and creates no second key (live: 30 days)", async () => {
  const env = makeEnv();
  const { second, keyId } = await boughtAndRenewOrdered(env);
  env.BILLING_MODE = "live";
  const exp = (id) => env.sqlite.prepare("SELECT expires_at FROM api_keys WHERE id = ?").get(id).expires_at;
  env.sqlite.prepare("UPDATE api_keys SET expires_at = ? WHERE id = ?").run(T0 + 10 * DAY, keyId);
  const ev = { type: "paid", checkoutRef: second.checkout_ref, providerRef: "ren1", amountMinor: 300, currency: "USD", tier: "basic" };
  assert.equal((await processEvent(env, "mock", ev, { now: T0 + DAY })).result, "renewed");
  assert.equal(exp(keyId), T0 + 40 * DAY, "unexpired key: the period is added to the old end");
  assert.equal(count(env, "api_keys"), 1);
  const row = getOrderRow(env, second.checkout_ref);
  assert.equal(row.status, "paid");
  assert.equal(row.key_id, keyId);
  assert.equal(env.REVEAL.m.size, 1, "only the first order's one-time copy is left; the renewal added none");

  assert.equal((await processEvent(env, "mock", ev, { now: T0 + DAY })).result, "duplicate", "a replay extends nothing");
  assert.equal(exp(keyId), T0 + 40 * DAY);
});

test("renewing an expired key counts from now; the order page says renewed and shows no key", async () => {
  const env = makeEnv();
  const { second, keyId } = await boughtAndRenewOrdered(env);
  env.sqlite.prepare("UPDATE api_keys SET expires_at = ? WHERE id = ?").run(T0 - 5 * DAY, keyId);
  assert.equal((await mockWebhook(env, paidEvent(second, { paymentId: "ren2" }))).data.result, "renewed");
  assert.equal(env.sqlite.prepare("SELECT expires_at FROM api_keys WHERE id = ?").get(keyId).expires_at, T0 + DAY, "test mode period is one day");
  assert.deepEqual((await call(env, `/v1/billing/order/${second.checkout_ref}`)).data, { status: "paid", renewed: true });
});

test("a key revoked between checkout and payment is not renewed: the order fails with a note", async () => {
  const env = makeEnv();
  const { second, keyId } = await boughtAndRenewOrdered(env);
  env.sqlite.prepare("UPDATE api_keys SET status = 'revoked' WHERE id = ?").run(keyId);
  assert.equal((await mockWebhook(env, paidEvent(second, { paymentId: "ren3" }))).data.result, "rejected");
  const row = getOrderRow(env, second.checkout_ref);
  assert.equal(row.status, "failed");
  assert.equal(row.note, "renew_target_revoked");
});

test("refunding a renewal takes back only its period; the key is revoked only if nothing is left", async () => {
  const env = makeEnv();
  const { first, second, keyId } = await boughtAndRenewOrdered(env, { plan: "pro" });
  await mockWebhook(env, paidEvent(second, { paymentId: "ren4" }));
  const exp = () => env.sqlite.prepare("SELECT status, expires_at FROM api_keys WHERE id = ?").get(keyId);
  assert.equal(exp().expires_at, T0 + 2 * DAY, "first order T0 + 1 day, renewal + 1 day");
  const refund = (o, id) => ({ type: "refunded", orderId: o.checkout_ref, paymentId: id, amountMinor: 700, currency: "USD", tier: "pro" });
  assert.equal((await mockWebhook(env, refund(second, "r1"))).data.result, "refunded");
  assert.equal(getOrderRow(env, second.checkout_ref).note, "refunded_renewal");
  assert.equal(exp().status, "active");
  assert.equal(exp().expires_at, T0 + DAY);
  env.sqlite.prepare("UPDATE api_keys SET expires_at = ? WHERE id = ?").run(0, keyId);
  assert.equal((await mockWebhook(env, refund(first, "r2"))).data.result, "refunded");
  assert.equal(exp().status, "revoked", "refunding the original order revokes the key");
});

test("a renewal sends the renewal e-mail (draft text) and never the key", async () => {
  const sent = [];
  const env = makeEnv({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "k", MAIL_FROM: "x@y.z" });
  const first = await placed(env, { plan: "basic" });
  env.BILLING_MODE = "live";
  const base = { checkoutRef: first.checkout_ref, amountMinor: 300, currency: "USD", tier: "basic", type: "paid" };
  const fetchImpl = async (_u, init) => (sent.push(JSON.parse(init.body)), new Response("{}"));
  await processEvent(env, "mock", { ...base, providerRef: "a" }, { now: T0, fetchImpl });
  env.BILLING_MODE = "test";
  const second = await placed(env, { plan: "basic" });
  env.BILLING_MODE = "live";
  await processEvent(env, "mock", { ...base, checkoutRef: second.checkout_ref, providerRef: "b" }, { now: T0, fetchImpl });
  assert.equal(sent.length, 2);
  assert.match(sent[0].text, /vk_basic_[a-z2-7]{32}/);
  assert.equal(sent[1].subject, "Your Vetro API key is extended");
  assert.doesNotMatch(sent[1].text, /vk_basic_[a-z2-7]{32}/);
  assert.match(sent[1].text, /vk_basic_[a-z2-7]{0,}…|vk_basic/);
});
