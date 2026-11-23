import assert from "node:assert/strict";
import { test } from "node:test";
import { SUB_GRACE_MS } from "../src/issue.js";
import { mapEvent } from "../src/providers/polar.js";
import { providerForMethod } from "../src/providers/index.js";
import { hmacSha256Base64 } from "../src/signing.js";
import { call, checkout, getOrderRow, makeEnv, T0 } from "./helpers.js";

const DAY = 86_400_000;
const SECRET_BYTES = "polar-test-secret-bytes-0123456789";
const SECRET = "whsec_" + Buffer.from(SECRET_BYTES).toString("base64");
const te = new TextEncoder();

const polarEnv = (extra = {}) =>
  makeEnv({
    BILLING_MODE: "live",
    MOCK_WEBHOOK_SECRET: undefined,
    POLAR_ACCESS_TOKEN: "polar_oat_test",
    POLAR_WEBHOOK_SECRET: SECRET,
    POLAR_PRODUCT_BASIC: "prod_basic",
    POLAR_PRODUCT_PRO: "prod_pro",
    POLAR_PRODUCT_ULTRA: "prod_ultra",
    ...extra,
  });

/** Подменяет глобальный fetch на время теста: адаптер ходит в Polar только через него. */
async function withFetch(handler, fn) {
  const calls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init, body: init?.body ? JSON.parse(init.body) : null });
    return handler(String(url), init);
  };
  try {
    return await fn(calls);
  } finally {
    globalThis.fetch = original;
  }
}
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });

/** Подпись Standard Webhooks. scheme "new": ключ = base64-декод после whsec_; "old": UTF-8 байты всей строки секрета. */
async function signed(body, { scheme = "new", id = "msg_1", ts = Math.floor(Date.now() / 1000), secret = SECRET } = {}) {
  const raw = typeof body === "string" ? body : JSON.stringify(body);
  const key = scheme === "new" ? Uint8Array.from(Buffer.from(secret.replace(/^whsec_/, ""), "base64")) : te.encode(secret);
  const sig = await hmacSha256Base64(key, `${id}.${ts}.${raw}`);
  return { raw, headers: { "webhook-id": id, "webhook-timestamp": String(ts), "webhook-signature": `v1,${sig}` } };
}
const hook = async (env, body, opts) => {
  const { raw, headers } = await signed(body, opts);
  return call(env, "/v1/billing/webhook/polar", { method: "POST", raw, headers });
};

const iso = (ms) => new Date(ms).toISOString();
const sub = (ref, over = {}) => ({ id: "sub_pl_1", current_period_end: iso(T0 + 30 * DAY), metadata: { order_ref: ref, tier: "pro" }, ...over });
const orderPaid = (ref, over = {}, reason = "subscription_create") => ({
  type: "order.paid",
  timestamp: iso(T0),
  data: { id: "po_1", billing_reason: reason, subscription_id: "sub_pl_1", subtotal_amount: 700, discount_amount: 0, net_amount: 700, tax_amount: 140, total_amount: 840, currency: "usd", metadata: { order_ref: ref, tier: "pro" }, subscription: sub(ref), ...over },
});

async function placed(env, over) {
  let order;
  await withFetch(() => json({ id: "co_1", url: "https://polar.sh/checkout/co_1" }), async () => {
    const res = await checkout(env, over);
    assert.equal(res.status, 200, JSON.stringify(res.data));
    order = getOrderRow(env, res.data.orderId);
  });
  return order;
}

test("the provider is configured only when the token, the webhook secret and all three products are set", () => {
  assert.ok(providerForMethod(polarEnv(), "card"));
  for (const k of ["POLAR_ACCESS_TOKEN", "POLAR_WEBHOOK_SECRET", "POLAR_PRODUCT_BASIC", "POLAR_PRODUCT_PRO", "POLAR_PRODUCT_ULTRA"]) {
    assert.equal(providerForMethod(polarEnv({ [k]: undefined }), "card"), null, k);
  }
  assert.equal(providerForMethod(polarEnv(), "lava"), null, "lava and usdt have no adapters yet");
  assert.equal(providerForMethod(polarEnv(), "usdt"), null);
});

test("checkout creates a Polar session on the tier's subscription product and returns its https url", async () => {
  const env = polarEnv();
  await withFetch(() => json({ id: "co_1", url: "https://polar.sh/checkout/co_1" }), async (calls) => {
    const res = await checkout(env, { plan: "pro", email: "Buyer@Example.com" });
    assert.equal(res.status, 200);
    assert.equal(res.data.url, "https://polar.sh/checkout/co_1");
    assert.deepEqual(Object.keys(res.data).sort(), ["orderId", "url"]);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "https://api.polar.sh/v1/checkouts/");
    assert.equal(calls[0].init.method, "POST");
    assert.equal(calls[0].init.headers.authorization, "Bearer polar_oat_test");
    const b = calls[0].body;
    assert.deepEqual(b.products, ["prod_pro"]);
    assert.equal(b.success_url, `https://vetrostudio.bid/api.html?order=${res.data.orderId}`);
    assert.equal(b.customer_email, "buyer@example.com");
    assert.equal(b.allow_discount_codes, false);
    assert.deepEqual(b.metadata, { order_ref: res.data.orderId, tier: "pro" });
    const order = getOrderRow(env, res.data.orderId);
    assert.equal(order.provider, "polar");
    assert.equal(order.amount_minor, 700);
    assert.equal(order.status, "pending");
  });
});

test("the sandbox server and every tier use their own product", async () => {
  const env = polarEnv({ POLAR_SERVER: "sandbox" });
  await withFetch(() => json({ url: "https://sandbox.polar.sh/checkout/x" }), async (calls) => {
    let n = 0;
    for (const plan of ["basic", "pro", "ultra"]) await checkout(env, { plan, email: `s${n++}@example.com` });
    assert.deepEqual(calls.map((c) => c.body.products[0]), ["prod_basic", "prod_pro", "prod_ultra"]);
    assert.ok(calls.every((c) => c.url === "https://sandbox-api.polar.sh/v1/checkouts/"));
  });
});

test("a Polar failure or a reply without an https url is 502 and the order is closed", async () => {
  for (const reply of [() => json({ error: "x" }, 500), () => json({ id: "co" }), () => json({ url: "http://insecure.example/x" })]) {
    const env = polarEnv();
    await withFetch(reply, async () => {
      const res = await checkout(env);
      assert.equal(res.status, 502);
      assert.deepEqual(res.data, { error: "provider_error" });
    });
    const row = env.sqlite.prepare("SELECT status, note FROM web_orders").get();
    assert.equal(row.status, "failed");
    assert.equal(row.note, "provider_error");
  }
});

test("webhook signature: both secret schemes pass; a wrong secret, stale time, tampering or missing headers do not", async () => {
  const env = polarEnv();
  const ref = (await placed(env)).checkout_ref;
  const ping = { type: "customer.created", data: {} };
  assert.equal((await hook(env, ping, { scheme: "new" })).status, 200);
  assert.equal((await hook(env, ping, { scheme: "old" })).status, 200, "secrets created before 8 Sep 2026 sign with the whole string");
  assert.equal((await hook(env, ping, { secret: "whsec_" + Buffer.from("other-secret-bytes").toString("base64") })).status, 401);
  assert.equal((await hook(env, ping, { ts: Math.floor(Date.now() / 1000) - 3600 })).status, 401, "stale");
  assert.equal((await hook(env, ping, { ts: Math.floor(Date.now() / 1000) + 3600 })).status, 401, "from the future");
  const { raw, headers } = await signed(orderPaid(ref));
  assert.equal((await call(env, "/v1/billing/webhook/polar", { method: "POST", raw: raw.replace("700", "1"), headers })).status, 401, "tampered body");
  assert.equal((await call(env, "/v1/billing/webhook/polar", { method: "POST", raw, headers: {} })).status, 401);
  assert.equal((await call(env, "/v1/billing/webhook/polar", { method: "POST", raw, headers: { ...headers, "webhook-signature": "v2,AAAA" } })).status, 401);
  assert.equal((await hook(polarEnv({ POLAR_WEBHOOK_SECRET: undefined }), ping)).status, 404, "unconfigured provider does not exist");
  const several = { ...headers, "webhook-signature": `v1,AAAA ${headers["webhook-signature"]}` };
  assert.equal((await call(env, "/v1/billing/webhook/polar", { method: "POST", raw, headers: several })).status, 200, "a list of signatures: any valid one is enough");
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 1);
});

test("order.paid (subscription_create) issues the key until the period end plus a grace, and links the subscription", async () => {
  const env = polarEnv();
  const order = await placed(env);
  const res = await hook(env, orderPaid(order.checkout_ref));
  assert.equal(res.status, 200);
  assert.equal(res.data.result, "issued");
  const row = getOrderRow(env, order.checkout_ref);
  assert.equal(row.status, "paid");
  assert.equal(row.provider_ref, "po_1");
  const key = env.sqlite.prepare("SELECT * FROM api_keys").get();
  assert.equal(key.owner, `web:${order.customer_id}`, "live mode keys belong to the buyer, not to web:test");
  assert.equal(key.expires_at, T0 + 30 * DAY + SUB_GRACE_MS);
  const s = env.sqlite.prepare("SELECT * FROM web_subscriptions").get();
  assert.equal(s.sub_ref, "sub_pl_1");
  assert.equal(s.provider, "polar");
  assert.equal(s.status, "active");
  assert.equal((await hook(env, orderPaid(order.checkout_ref), { id: "msg_2" })).data.result, "duplicate", "a redelivery issues nothing");
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 1);
});

test("the amount is checked on the net amount in USD; a wrong amount, currency or tier is rejected without a key", async () => {
  for (const [over, note] of [
    [{ net_amount: 100, subtotal_amount: 100 }, "mismatch_amount"],
    [{ currency: "eur" }, "mismatch_currency"],
    [{ metadata: { tier: "ultra" }, subscription: undefined }, "mismatch_tier"],
  ]) {
    const env = polarEnv();
    const order = await placed(env);
    const body = orderPaid(order.checkout_ref, over);
    if (over.metadata) body.data.metadata = { order_ref: order.checkout_ref, ...over.metadata };
    const res = await hook(env, body);
    assert.equal(res.data.result, "rejected");
    assert.equal(getOrderRow(env, order.checkout_ref).note, note);
    assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 0);
  }
});

test("tax does not count: the paid total may be higher than the price, the net amount decides", async () => {
  const env = polarEnv();
  const order = await placed(env);
  const res = await hook(env, orderPaid(order.checkout_ref, { total_amount: 1000, tax_amount: 300 }));
  assert.equal(res.data.result, "issued");
});

test("the order reference is also read from the subscription metadata", async () => {
  const env = polarEnv();
  const order = await placed(env);
  const body = orderPaid(order.checkout_ref);
  body.data.metadata = {};
  assert.equal((await hook(env, body)).data.result, "issued");
});

test("order.paid (subscription_cycle) renews the same key; a redelivery does not; the period end moves it", async () => {
  const env = polarEnv();
  const order = await placed(env);
  await hook(env, orderPaid(order.checkout_ref));
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  const cycle = orderPaid(order.checkout_ref, { id: "po_2", subscription: sub(order.checkout_ref, { current_period_end: iso(T0 + 61 * DAY) }) }, "subscription_cycle");
  const res = await hook(env, cycle, { id: "msg_c" });
  assert.equal(res.data.result, "renewed");
  const exp = () => env.sqlite.prepare("SELECT expires_at FROM api_keys WHERE id = ?").get(keyId).expires_at;
  assert.equal(exp(), T0 + 61 * DAY + SUB_GRACE_MS);
  assert.equal((await hook(env, cycle, { id: "msg_c2" })).data.result, "duplicate");
  assert.equal(exp(), T0 + 61 * DAY + SUB_GRACE_MS);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 1);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM web_renewals").get().n, 1);
  const other = orderPaid(order.checkout_ref, { id: "po_3", net_amount: 5, subscription: sub(order.checkout_ref) }, "subscription_cycle");
  assert.equal((await hook(env, other)).data.result, "rejected", "another amount is not a renewal");
});

test("canceled keeps the key to the end of the period, uncanceled brings renewals back, revoked closes the subscription", async () => {
  const env = polarEnv();
  const order = await placed(env);
  await hook(env, orderPaid(order.checkout_ref));
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  const exp = env.sqlite.prepare("SELECT expires_at FROM api_keys WHERE id = ?").get(keyId).expires_at;
  const status = () => env.sqlite.prepare("SELECT status FROM web_subscriptions").get().status;
  await hook(env, { type: "subscription.canceled", data: { id: "sub_pl_1" } });
  assert.equal(status(), "canceled");
  await hook(env, { type: "subscription.uncanceled", data: { id: "sub_pl_1" } });
  assert.equal(status(), "active");
  await hook(env, { type: "subscription.canceled", data: { id: "sub_pl_1" } });
  await hook(env, { type: "subscription.revoked", data: { id: "sub_pl_1" } });
  assert.equal(status(), "revoked");
  await hook(env, { type: "subscription.uncanceled", data: { id: "sub_pl_1" } });
  assert.equal(status(), "revoked", "a closed subscription is not revived");
  const k = env.sqlite.prepare("SELECT status, expires_at FROM api_keys WHERE id = ?").get(keyId);
  assert.equal(k.status, "active");
  assert.equal(k.expires_at, exp, "access runs to the end of the paid period");
  assert.equal((await hook(env, { type: "subscription.canceled", data: { id: "unknown" } })).data.result, "unknown_order");
});

test("order.refunded revokes the key and cancels the subscription at Polar (DELETE); a refunded renewal takes back a month", async () => {
  const env = polarEnv();
  const order = await placed(env);
  await hook(env, orderPaid(order.checkout_ref));
  const keyId = getOrderRow(env, order.checkout_ref).key_id;
  await hook(env, orderPaid(order.checkout_ref, { id: "po_2", subscription: sub(order.checkout_ref, { current_period_end: iso(T0 + 61 * DAY) }) }, "subscription_cycle"));
  const exp = () => env.sqlite.prepare("SELECT status, expires_at FROM api_keys WHERE id = ?").get(keyId);
  const before = exp().expires_at;
  await withFetch(() => json({}), async (calls) => {
    const res = await hook(env, { type: "order.refunded", data: { id: "po_2", metadata: { order_ref: order.checkout_ref }, subscription_id: "sub_pl_1" } });
    assert.equal(res.data.result, "refunded", "found by the payment, not by the shared order reference");
    assert.equal(exp().status, "active", "the first month is still paid");
    assert.equal(exp().expires_at, before - 30 * DAY);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "https://api.polar.sh/v1/subscriptions/sub_pl_1");
    assert.equal(calls[0].init.method, "DELETE");
    assert.equal(calls[0].init.headers.authorization, "Bearer polar_oat_test");
    const res2 = await hook(env, { type: "order.refunded", data: { id: "po_1" } });
    assert.equal(res2.data.result, "refunded");
    assert.equal(exp().status, "revoked");
    assert.equal(getOrderRow(env, order.checkout_ref).status, "refunded");
  });
});

test("cancelling at Polar: already revoked (403) or missing (404) is fine, a locked subscription (409) is flagged for the owner", async () => {
  for (const [status, note] of [[403, "refunded"], [404, "refunded"], [409, "refunded_cancel_failed"], [422, "refunded_cancel_failed"], [500, "refunded_cancel_failed"]]) {
    const env = polarEnv();
    const order = await placed(env);
    await hook(env, orderPaid(order.checkout_ref));
    await withFetch(() => json({}, status), async () => {
      assert.equal((await hook(env, { type: "order.refunded", data: { id: "po_1" } })).data.result, "refunded");
    });
    assert.equal(getOrderRow(env, order.checkout_ref).note, note, String(status));
  }
});

test("unknown events and other billing reasons are acknowledged and ignored", async () => {
  const env = polarEnv();
  const order = await placed(env);
  for (const body of [
    { type: "customer.created", data: { id: "c" } },
    { type: "order.created", data: { id: "o" } },
    { type: "subscription.active", data: { id: "sub_pl_1" } },
    { type: "subscription.updated", data: { id: "sub_pl_1" } },
    orderPaid(order.checkout_ref, {}, "subscription_update"),
    orderPaid(order.checkout_ref, {}, "subscription_meter_cycle"),
    {},
  ]) {
    const res = await hook(env, body);
    assert.equal(res.status, 200, JSON.stringify(body).slice(0, 60));
    assert.equal(res.data.result, "ignored");
  }
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 0);
  assert.equal(getOrderRow(env, order.checkout_ref).status, "pending");
});

test("mapEvent is a pure mapping from Polar payloads to engine events", () => {
  assert.deepEqual(mapEvent({ type: "subscription.revoked", data: { id: "s1" } }), { type: "revoked", subRef: "s1" });
  assert.deepEqual(mapEvent({ type: "order.refunded", data: { id: "o1", metadata: { order_ref: "x" } } }), { type: "refunded", providerRef: "o1" });
  const paid = mapEvent(orderPaid("ord_x", { subscription: undefined, subscription_id: null }, "purchase"));
  assert.equal(paid.type, "paid");
  assert.equal(paid.subscriptionId, undefined);
  assert.equal(paid.periodEnd, undefined);
  assert.equal(paid.currency, "USD");
  assert.equal(mapEvent(null).type, "ignore");
});

test("in test mode card orders still go to the mock page, never to Polar", async () => {
  const res = await checkout(polarEnv({ BILLING_MODE: "test" }));
  assert.equal(res.status, 200);
  assert.ok(res.data.url.startsWith("https://billing.vetrostudio.bid/v1/billing/test/pay"));
});
