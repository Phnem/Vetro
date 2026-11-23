import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../src/worker.js";
import { body, call, checkout, getOrderRow, makeEnv, mockWebhook, paidEvent } from "./helpers.js";

async function paidOrder(env, over) {
  const res = await checkout(env, over);
  const order = getOrderRow(env, res.data.orderId);
  await mockWebhook(env, paidEvent(order));
  return order.checkout_ref;
}

test("GET order: pending, then paid with the key once, then without it", async () => {
  const env = makeEnv();
  const ref = (await checkout(env)).data.orderId;
  const pending = await call(env, `/v1/billing/order/${ref}`);
  assert.equal(pending.status, 200);
  assert.deepEqual(pending.data, { status: "pending" });

  await mockWebhook(env, paidEvent(getOrderRow(env, ref)));
  const first = await call(env, `/v1/billing/order/${ref}`);
  assert.equal(first.status, 200);
  assert.equal(first.data.status, "paid");
  assert.match(first.data.key, /^vk_pro_[a-z2-7]{32}$/);
  assert.deepEqual(Object.keys(first.data).sort(), ["key", "status"]);

  const second = await call(env, `/v1/billing/order/${ref}`);
  assert.deepEqual(second.data, { status: "paid" });
  assert.equal(env.REVEAL.m.size, 0, "the one-time copy is deleted on the first read");
  assert.deepEqual((await call(env, `/v1/billing/order/${ref}`)).data, { status: "paid" });
});

test("GET order shows nothing but status and key: no e-mail, project, tier or id", async () => {
  const env = makeEnv();
  const ref = await paidOrder(env, { email: "private@example.com", project: { name: "Secret Project Name" } });
  const res = await call(env, `/v1/billing/order/${ref}`);
  const text = res.text.replace(/vk_pro_[a-z2-7]{32}/, "");
  for (const secret of ["private@example.com", "Secret Project", "example.com", "pro", "ord_", "k_", "Ivan", "700", "mock", "USD"]) {
    assert.ok(!text.includes(secret), `leaks ${secret}`);
  }
  assert.equal(res.headers.get("cache-control"), "no-store");
  assert.equal(res.headers.get("referrer-policy"), "no-referrer");
  const pending = await call(env, `/v1/billing/order/${(await checkout(env, { email: "p@example.com" })).data.orderId}`);
  assert.deepEqual(Object.keys(pending.data), ["status"]);
});

test("failed, refunded and expired orders are all reported as failed", async () => {
  for (const status of ["failed", "refunded", "expired"]) {
    const env = makeEnv();
    const ref = (await checkout(env)).data.orderId;
    env.sqlite.prepare("UPDATE web_orders SET status = ?").run(status);
    assert.deepEqual((await call(env, `/v1/billing/order/${ref}`)).data, { status: "failed" }, status);
  }
});

test("an order that is paid but has no key yet is still pending for the page", async () => {
  const env = makeEnv();
  const ref = (await checkout(env)).data.orderId;
  env.sqlite.exec("UPDATE web_orders SET status = 'paid', provider_ref = 'x'");
  assert.deepEqual((await call(env, `/v1/billing/order/${ref}`)).data, { status: "pending" });
});

test("order id format: only [\\w-]{6,64} is looked up, everything else and unknown ids are 404", async () => {
  const env = makeEnv();
  await checkout(env);
  for (const id of ["abc", "a".repeat(65), "ord_" + "0".repeat(32), "has%20space", "semi;colon", "dot.dot.dot", "%3Cscript%3E", "ord_%00abcdef"]) {
    const res = await call(env, `/v1/billing/order/${id}`);
    assert.equal(res.status, 404, id);
    assert.deepEqual(res.data, { error: "not_found" });
  }
  assert.equal((await call(env, "/v1/billing/order/")).status, 404);
  assert.equal((await call(env, "/v1/billing/order/ord_abcdef", { method: "POST", raw: "{}" })).status, 405);
});

test("GET order is rate limited by the order limiter and the IP is only a counter key", async () => {
  const keys = [];
  const env = makeEnv({
    LIMITER_CHECKOUT: { limit: async () => ({ success: true }) },
    LIMITER: { limit: async ({ key }) => (keys.push(key), { success: keys.length < 3 }) },
  });
  const ref = (await checkout(env)).data.orderId;
  const ip = { "cf-connecting-ip": "203.0.113.5" };
  assert.equal((await call(env, `/v1/billing/order/${ref}`, { headers: ip })).status, 200);
  assert.equal((await call(env, `/v1/billing/order/${ref}`, { headers: ip })).status, 200);
  assert.equal((await call(env, `/v1/billing/order/${ref}`, { headers: ip })).status, 429);
  assert.deepEqual(keys, ["ord:203.0.113.5", "ord:203.0.113.5", "ord:203.0.113.5"]);
});

test("full flow through the default export, as Cloudflare would call it", async () => {
  const env = makeEnv();
  const pending = [];
  const ctx = { waitUntil: (p) => pending.push(p) };
  const origin = "https://vetrostudio.bid";
  const res = await worker.fetch(
    new Request("https://billing.vetrostudio.bid/v1/billing/checkout", { method: "POST", headers: { origin, "content-type": "application/json" }, body: JSON.stringify(body()) }),
    env,
    ctx,
  );
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("access-control-allow-origin"), origin);
  const { orderId, url } = await res.json();
  assert.ok(url.startsWith("https://"));

  const pay = await worker.fetch(new Request(url), env, ctx);
  assert.equal(pay.status, 200);
  const done = await worker.fetch(new Request("https://billing.vetrostudio.bid/v1/billing/test/pay", { method: "POST", body: `order=${orderId}` }), env, ctx);
  assert.equal(done.status, 303);
  await Promise.all(pending);
  const got = await worker.fetch(new Request(`https://billing.vetrostudio.bid/v1/billing/order/${orderId}`, { headers: { origin } }), env, ctx);
  const data = await got.json();
  assert.equal(data.status, "paid");
  assert.match(data.key, /^vk_pro_/);
});

// ---- CORS ---------------------------------------------------------------------------------------------------------

const ALLOWED = ["https://vetrostudio.bid", "https://www.vetrostudio.bid", "https://phnem.github.io"];
const get = (env, path, origin, init = {}) =>
  worker.fetch(new Request("https://billing.vetrostudio.bid" + path, { ...init, headers: { ...(origin ? { origin } : {}), ...(init.headers ?? {}) } }), env, { waitUntil() {} });

test("CORS: an allowed Origin is echoed, never '*', with Vary: Origin", async () => {
  const env = makeEnv();
  for (const origin of ALLOWED) {
    const res = await get(env, "/v1/billing/health", origin);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("access-control-allow-origin"), origin);
    assert.match(res.headers.get("vary"), /Origin/i);
  }
});

test("CORS preflight: 204 with the right methods, headers and max age; a foreign origin gets no CORS headers", async () => {
  const env = makeEnv();
  for (const origin of ALLOWED) {
    for (const path of ["/v1/billing/checkout", "/v1/billing/order/ord_abcdef", "/v1/billing/webhook/mock", "/v1/billing/nothing"]) {
      const res = await get(env, path, origin, { method: "OPTIONS", headers: { "access-control-request-method": "POST" } });
      assert.equal(res.status, 204, path);
      assert.equal(res.headers.get("access-control-allow-origin"), origin);
      assert.equal(res.headers.get("access-control-allow-methods"), "POST, GET, OPTIONS");
      assert.equal(res.headers.get("access-control-allow-headers"), "content-type");
      assert.equal(res.headers.get("access-control-max-age"), "86400");
      assert.match(res.headers.get("vary"), /Origin/i);
      assert.equal(await res.text(), "");
    }
  }
  for (const origin of ["https://evil.example", "http://vetrostudio.bid", "https://vetrostudio.bid.evil.example", "https://sub.vetrostudio.bid", "null", undefined]) {
    const pre = await get(env, "/v1/billing/checkout", origin, { method: "OPTIONS" });
    assert.equal(pre.status, 204);
    assert.equal(pre.headers.get("access-control-allow-origin"), null, String(origin));
    assert.equal(pre.headers.get("access-control-allow-methods"), null);
    const real = await get(env, "/v1/billing/health", origin);
    assert.equal(real.headers.get("access-control-allow-origin"), null, String(origin));
  }
});

test("CORS headers are present on errors too, so the site can read them", async () => {
  const env = makeEnv();
  const origin = "https://phnem.github.io";
  for (const [path, init, status] of [
    ["/v1/billing/checkout", { method: "POST", body: "{nope" }, 400],
    ["/v1/billing/order/zzz", {}, 404],
    ["/v1/billing/order/ord_abcdef", {}, 404],
    ["/v1/billing/missing", {}, 404],
    ["/v1/billing/checkout", { method: "GET" }, 405],
  ]) {
    const res = await get(env, path, origin, init);
    assert.equal(res.status, status, path);
    assert.equal(res.headers.get("access-control-allow-origin"), origin, path);
    assert.equal(res.headers.get("cache-control"), "no-store");
  }
});

test("no authorization header or site key is needed or looked at", async () => {
  const env = makeEnv();
  const res = await worker.fetch(
    new Request("https://billing.vetrostudio.bid/v1/billing/checkout", {
      method: "POST",
      headers: { authorization: "Bearer vk_ultra_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "content-type": "application/json" },
      body: JSON.stringify(body()),
    }),
    env,
    { waitUntil() {} },
  );
  assert.equal(res.status, 200);
});

test("unexpected failures answer 500 internal with no details", async () => {
  const env = makeEnv();
  env.DB = { prepare: () => { throw new Error("secret detail vk_pro_xxx"); }, batch: async () => { throw new Error("boom"); } };
  const res = await worker.fetch(
    new Request("https://billing.vetrostudio.bid/v1/billing/checkout", { method: "POST", body: JSON.stringify(body()) }),
    env,
    { waitUntil() {} },
  );
  assert.equal(res.status, 500);
  assert.deepEqual(await res.json(), { error: "internal" });
  assert.equal((await worker.fetch(new Request("https://billing.vetrostudio.bid/v1/billing/health"), {}, {})).status, 500, "no DB binding = misconfigured");
});

test("health reports the mode", async () => {
  assert.deepEqual(await (await get(makeEnv(), "/v1/billing/health")).json(), { ok: true, mode: "test" });
  assert.deepEqual(await (await get(makeEnv({ BILLING_MODE: undefined }), "/v1/billing/health")).json(), { ok: true, mode: "live" });
});
