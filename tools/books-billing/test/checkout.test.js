import assert from "node:assert/strict";
import { test } from "node:test";
import { body, call, checkout, getOrderRow, makeEnv, mockWebhook, paidEvent, SIGNATURE, T0 } from "./helpers.js";

const tableCount = (env, t) => env.sqlite.prepare(`SELECT COUNT(*) AS n FROM ${t}`).get().n;
const rowsWritten = (env) => ["web_customers", "web_orders", "contract_signatures"].map((t) => tableCount(env, t));

test("happy path: order id format, https url to the test page, data stored on the server", async () => {
  const env = makeEnv();
  const res = await checkout(env);
  assert.equal(res.status, 200);
  assert.match(res.data.orderId, /^ord_[0-9a-f]{32}$/);
  assert.equal(res.data.url, `https://billing.vetrostudio.bid/v1/billing/test/pay?order=${res.data.orderId}`);
  assert.deepEqual(Object.keys(res.data).sort(), ["orderId", "url"]);

  const customer = env.sqlite.prepare("SELECT * FROM web_customers").get();
  assert.equal(customer.email, "buyer@example.com", "lower case, trimmed");
  assert.equal(customer.language, "en");
  assert.equal(customer.banned, 0);
  assert.equal(customer.created_at, T0);

  const order = getOrderRow(env, res.data.orderId);
  assert.equal(order.status, "pending");
  assert.equal(order.kind, "new");
  assert.equal(order.tier, "pro");
  assert.equal(order.provider, "mock");
  assert.equal(order.method, "card");
  assert.equal(order.amount_minor, 700);
  assert.equal(order.currency, "USD");
  assert.equal(order.project_name, "My Site");
  assert.equal(order.project_type, "site");
  assert.deepEqual(JSON.parse(order.origins), ["example.com", "www.example.com"]);
  assert.equal(order.commercial, null);
  assert.equal(order.email_failed, 0);
  assert.equal(order.created_at, T0);
  assert.equal(order.provider_ref, null);
  assert.equal(order.key_id, null);

  const sig = env.sqlite.prepare("SELECT * FROM contract_signatures").get();
  assert.equal(sig.order_id, order.id);
  assert.equal(sig.signer, "Ivan Petrov");
  assert.equal(sig.terms_hash, "f3bee9bb6eb9");
  assert.equal(sig.refund_hash, "a1b2c3d4e5f6");
  assert.equal(sig.privacy_hash, "0123456789ab");
  assert.equal(order.policies_accepted_at, T0, "server clock");
  assert.equal(sig.signature_svg, SIGNATURE);
  assert.equal(sig.client_signed_at, "2026-11-23T11:59:00.000Z", "browser time is stored separately");
  assert.equal(sig.accepted_at, T0, "server time of acceptance");
  assert.equal(sig.lang, "en");
});

test("the amount comes from tiers, never from the client; every sold plan has its price", async () => {
  const env = makeEnv();
  for (const [plan, minor] of [["basic", 300], ["pro", 700], ["ultra", 1200]]) {
    const email = `${plan}@example.com`;
    const res = await checkout(env, { plan, email, amount: 1, amountMinor: 1, price: 1, currency: "EUR" });
    assert.equal(res.status, 200, plan);
    const order = getOrderRow(env, res.data.orderId);
    assert.equal(order.amount_minor, minor);
    assert.equal(order.currency, "USD");
  }
});

test("the return address is a server constant: nothing in the body can change the url", async () => {
  const env = makeEnv();
  const res = await call(env, "/v1/billing/checkout", {
    method: "POST",
    json: { ...body(), returnUrl: "https://evil.example/x", return_url: "https://evil.example/x", successUrl: "https://evil.example/x", redirect: "https://evil.example/x" },
  });
  assert.equal(res.status, 200);
  assert.doesNotMatch(res.text, /evil/);
  assert.ok(res.data.url.startsWith("https://billing.vetrostudio.bid/"));
});

test("unknown fields are ignored", async () => {
  const env = makeEnv();
  assert.equal((await call(env, "/v1/billing/checkout", { method: "POST", json: { ...body(), extra: { a: 1 }, project: { ...body().project, evil: 1 } } })).status, 200);
});

const rejects = async (over, code, status = 400, field) => {
  const env = makeEnv();
  const res = await checkout(env, over);
  assert.equal(res.status, status, JSON.stringify(over).slice(0, 120));
  assert.equal(res.data.error, code);
  if (field) assert.equal(res.data.field, field);
  assert.deepEqual(rowsWritten(env), [0, 0, 0], "a rejected order writes nothing");
};

test("plan: must exist, be sold and have a price", async () => {
  await rejects({ plan: "gold" }, "bad_field", 400, "plan");
  await rejects({ plan: "app" }, "bad_field", 400, "plan"); // sold = 0
  await rejects({ plan: "" }, "bad_field", 400, "plan");
  await rejects({ plan: 5 }, "bad_field", 400, "plan");
  await rejects({ plan: "pro'; DROP TABLE tiers;--" }, "bad_field", 400, "plan");

  const env = makeEnv();
  env.sqlite.exec("UPDATE tiers SET price_usd_minor = 0 WHERE name = 'pro'");
  assert.equal((await checkout(env)).status, 400, "price 0 = not for sale");
  env.sqlite.exec("UPDATE tiers SET price_usd_minor = 700, sold = 0 WHERE name = 'pro'");
  assert.equal((await checkout(env)).status, 400, "sold = 0");
  assert.deepEqual(rowsWritten(env), [0, 0, 0]);
});

test("method, lang and e-mail", async () => {
  await rejects({ method: "paypal" }, "bad_method");
  await rejects({ method: undefined }, "bad_method");
  await rejects({ lang: "de" }, "bad_field", 400, "lang");
  for (const email of ["", "nobody", "a@b", "a b@c.de", "@x.de", "a@@x.de", 5, `${"a".repeat(250)}@x.de`]) {
    await rejects({ email }, "bad_field", 400, "email");
  }
  const env = makeEnv();
  assert.equal((await checkout(env, { method: "usdt", email: "  USER+tag@Example.COM  " })).status, 200);
  assert.equal(env.sqlite.prepare("SELECT email FROM web_customers").get().email, "user+tag@example.com");
});

test("project name, description, type and signer lengths", async () => {
  await rejects({ project: { name: "x" } }, "bad_field", 400, "name");
  await rejects({ project: { name: "x".repeat(81) } }, "bad_field", 400, "name");
  await rejects({ project: { name: "bad\nname" } }, "bad_field", 400, "name");
  await rejects({ project: { description: "too short" } }, "bad_field", 400, "description");
  await rejects({ project: { description: "d".repeat(401) } }, "bad_field", 400, "description");
  await rejects({ project: { type: "mobile" } }, "bad_field", 400, "type");
  await rejects({ contract: { signer: "I" } }, "bad_field", 400, "signer");
  await rejects({ contract: { signer: "I".repeat(81) } }, "bad_field", 400, "signer");
  await rejects({ contract: { termsVersion: "xyz" } }, "bad_field", 400, "termsVersion");
  await rejects({ contract: { termsVersion: "F3BEE9BB6EB9" } }, "bad_field", 400, "termsVersion");
  await rejects({ contract: { termsVersion: "f3bee9bb6eb" } }, "bad_field", 400, "termsVersion");
  const env = makeEnv();
  assert.equal((await checkout(env, { project: { name: "xy", description: "d".repeat(10) }, contract: { signer: "Io" } })).status, 200);
  assert.equal((await checkout(env, { email: "b@example.com", project: { name: "x".repeat(80), description: "d".repeat(400) } })).status, 200);
});

test("domains: lower case, no scheme, path or port; at most 20; at least one for a site", async () => {
  await rejects({ project: { domains: ["https://example.com"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["example.com/path"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["example.com:8080"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["exa mple.com"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["a..b.com"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["*.com"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: ["ok.com", "nope!"] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: Array.from({ length: 21 }, (_, i) => `d${i}.example.com`) } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: [] } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: undefined } }, "bad_field", 400, "domains");
  await rejects({ project: { domains: "example.com" } }, "bad_field", 400, "domains");

  const env = makeEnv();
  const twenty = Array.from({ length: 20 }, (_, i) => `d${i}.example.com`);
  assert.equal((await checkout(env, { project: { domains: twenty } })).status, 200);
  const order = env.sqlite.prepare("SELECT origins FROM web_orders").get();
  assert.equal(JSON.parse(order.origins).length, 20);

  const env2 = makeEnv();
  assert.equal((await checkout(env2, { project: { domains: [" EXAMPLE.com ", "example.COM", "*.Sub.Example.com"] } })).status, 200);
  assert.deepEqual(JSON.parse(env2.sqlite.prepare("SELECT origins FROM web_orders").get().origins), ["example.com", "*.sub.example.com"], "lower case, deduplicated");
});

test("domains are optional for app, server and other projects; valid ones are kept", async () => {
  const env = makeEnv();
  for (const [i, type] of ["app", "server", "other"].entries()) {
    assert.equal((await checkout(env, { email: `n${i}@example.com`, project: { type, domains: [] } })).status, 200, type);
    assert.equal((await checkout(env, { email: `m${i}@example.com`, project: { type, domains: undefined } })).status, 200, type);
  }
  assert.equal((await checkout(env, { email: "k@example.com", project: { type: "app", domains: ["Good.com", "https://bad.com"] } })).status, 200);
  const last = env.sqlite.prepare("SELECT origins FROM web_orders ORDER BY id DESC LIMIT 1").get();
  assert.deepEqual(JSON.parse(last.origins), ["good.com"]);
});

test("the signature goes through the sanitizer; a bad one is bad_signature and writes nothing", async () => {
  for (const svg of [
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 9 9"><script>alert(1)</script><path d="M0 0"/></svg>`,
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 9 9" onload="alert(1)"><path d="M0 0"/></svg>`,
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 9 9"><foreignObject/><path d="M0 0"/></svg>`,
    `<!DOCTYPE svg [<!ENTITY a "b">]>${SIGNATURE}`,
    "not an svg",
    "",
    undefined,
    42,
  ]) {
    await rejects({ contract: { signatureSvg: svg } }, "bad_signature");
  }
  const env = makeEnv();
  const res = await checkout(env, { contract: { signatureSvg: `  ${SIGNATURE}\n` } });
  assert.equal(res.status, 200);
  assert.equal(env.sqlite.prepare("SELECT signature_svg FROM contract_signatures").get().signature_svg, SIGNATURE, "stored in canonical form");
});

test("an unreadable browser time is dropped, the order still goes through", async () => {
  const env = makeEnv();
  const res = await checkout(env, { contract: { signedAt: "yesterday-ish" } });
  assert.equal(res.status, 200);
  assert.equal(env.sqlite.prepare("SELECT client_signed_at FROM contract_signatures").get().client_signed_at, null);
});

test("bad JSON, non-object bodies and oversize bodies", async () => {
  const env = makeEnv();
  assert.deepEqual((await call(env, "/v1/billing/checkout", { method: "POST", raw: "{nope" })).data, { error: "bad_json" });
  assert.equal((await call(env, "/v1/billing/checkout", { method: "POST", raw: "[1,2]" })).status, 400);
  assert.equal((await call(env, "/v1/billing/checkout", { method: "POST", raw: "null" })).status, 400);
  const big = await call(env, "/v1/billing/checkout", { method: "POST", raw: JSON.stringify({ ...body(), pad: "x".repeat(300 * 1024) }) });
  assert.equal(big.status, 413);
  const declared = await call(env, "/v1/billing/checkout", { method: "POST", raw: "{}", headers: { "content-length": String(400 * 1024) } });
  assert.equal(declared.status, 413);
  assert.deepEqual(rowsWritten(env), [0, 0, 0]);
});

test("a method that is not configured gives 503 and leaves nothing in D1", async () => {
  const env = makeEnv({ BILLING_MODE: "live" }); // боевой режим, ни одного настроенного провайдера
  for (const method of ["card", "lava", "usdt"]) {
    const res = await checkout(env, { method });
    assert.equal(res.status, 503, method);
    assert.deepEqual(res.data, { error: "method_unavailable" });
  }
  assert.deepEqual(rowsWritten(env), [0, 0, 0]);
});

test("rate limit: 429 and nothing written; the per-e-mail hourly cap", async () => {
  const env = makeEnv({ LIMITER_CHECKOUT: { limit: async () => ({ success: false }) } });
  const res = await checkout(env);
  assert.equal(res.status, 429);
  assert.deepEqual(rowsWritten(env), [0, 0, 0]);

  const env2 = makeEnv();
  for (let i = 0; i < 5; i++) assert.equal((await checkout(env2, { plan: "basic" }, { now: T0 + i * 1000 })).status, 200, "order " + i);
  assert.equal((await checkout(env2, { plan: "basic" }, { now: T0 + 6000 })).status, 429);
  assert.equal((await checkout(env2, { plan: "basic", email: "other@example.com" }, { now: T0 + 6000 })).status, 200, "another e-mail is not affected");
  assert.equal((await checkout(env2, { plan: "basic" }, { now: T0 + 3_600_001 + 4000 })).status, 200, "an hour later it is allowed again");
});

test("the limiter key is derived from the IP but the IP and user-agent are stored nowhere", async () => {
  const keys = [];
  const env = makeEnv({ LIMITER_CHECKOUT: { limit: async ({ key }) => (keys.push(key), { success: true }) } });
  const res = await checkout(env, {}, { headers: { "cf-connecting-ip": "203.0.113.77", "user-agent": "SecretAgent/9.9", "x-forwarded-for": "198.51.100.5" } });
  assert.equal(res.status, 200);
  assert.deepEqual(keys, ["co:203.0.113.77"]);
  for (const t of ["web_customers", "web_orders", "contract_signatures", "api_keys"]) {
    const dump = JSON.stringify(env.sqlite.prepare(`SELECT * FROM ${t}`).all());
    assert.doesNotMatch(dump, /203\.0\.113\.77|SecretAgent|198\.51\.100/, t);
  }
});

test("a banned buyer gets 403 and nothing new is written", async () => {
  const env = makeEnv();
  assert.equal((await checkout(env)).status, 200);
  env.sqlite.exec("UPDATE web_customers SET banned = 1");
  const res = await checkout(env);
  assert.equal(res.status, 403);
  assert.deepEqual(res.data, { error: "forbidden" });
  assert.deepEqual(rowsWritten(env), [1, 1, 1]);
});

test("max_keys per buyer: 409 key_limit once the active keys reach the tier cap", async () => {
  const env = makeEnv();
  const buy = async (plan, paymentId) => {
    const res = await checkout(env, { plan });
    assert.equal(res.status, 200, plan);
    const order = getOrderRow(env, res.data.orderId);
    assert.equal((await mockWebhook(env, paidEvent(order, { paymentId }))).status, 200);
  };
  await buy("basic", "pay_b1"); // 1 активный ключ
  const before = rowsWritten(env);
  const second = await checkout(env, { plan: "basic", project: { name: "Another Site" } }); // basic.max_keys = 1, другой проект
  assert.equal(second.status, 409);
  assert.deepEqual(second.data, { error: "key_limit" });
  assert.deepEqual(rowsWritten(env), before, "409 writes nothing");

  await buy("pro", "pay_p1"); // pro.max_keys = 2: 1 < 2, можно; теперь 2 ключа
  assert.equal((await checkout(env, { plan: "pro", project: { name: "Another Site" } })).status, 409);
  assert.equal((await checkout(env, { plan: "ultra" })).status, 200, "ultra allows 5");

  // Отозванные и истёкшие ключи в лимит не входят.
  env.sqlite.exec("UPDATE api_keys SET status = 'revoked', revoked_at = 1 WHERE tier = 'basic'");
  assert.equal((await checkout(env, { plan: "pro", project: { name: "Another Site" } })).status, 200, "a revoked key frees the slot");
  env.sqlite.exec("UPDATE api_keys SET expires_at = 1 WHERE tier = 'pro'");
  assert.equal((await checkout(env, { plan: "basic", project: { name: "Another Site" } })).status, 200, "an expired key frees the slot");
});

test("policiesAccepted must be exactly true, otherwise nothing is created", async () => {
  for (const v of [false, undefined, "true", 1, null]) await rejects({ policiesAccepted: v }, "bad_field", 400, "policiesAccepted");
});

test("the three document versions are required, 12 hex each, and terms must equal termsVersion", async () => {
  const ok = { terms: "f3bee9bb6eb9", refund: "a1b2c3d4e5f6", privacy: "0123456789ab" };
  await rejects({ contract: { documents: undefined } }, "bad_field", 400, "documents");
  await rejects({ contract: { documents: { ...ok, refund: undefined } } }, "bad_field", 400, "documents");
  await rejects({ contract: { documents: { ...ok, privacy: "XYZ" } } }, "bad_field", 400, "documents");
  await rejects({ contract: { documents: { ...ok, refund: "A1B2C3D4E5F6" } } }, "bad_field", 400, "documents");
  await rejects({ contract: { documents: { ...ok, terms: "ffffffffffff" } } }, "bad_field", 400, "termsVersion");
});

test("methods are card, lava and usdt; the old ru and paddle are unknown", async () => {
  const env = makeEnv();
  for (const method of ["card", "lava", "usdt"]) assert.equal((await checkout(env, { method, email: `${method}@example.com` })).status, 200, method);
  for (const method of ["ru", "paddle", "polar", "stars"]) assert.equal((await checkout(env, { method })).data.error, "bad_method", method);
});

test("buying the same project on the same tier again is a renewal: no key_limit, kind renew pointing at the existing key", async () => {
  const env = makeEnv();
  const first = await checkout(env, { plan: "basic" });
  await mockWebhook(env, paidEvent(getOrderRow(env, first.data.orderId), { paymentId: "p1" }));
  const keyId = getOrderRow(env, first.data.orderId).key_id;
  const again = await checkout(env, { plan: "basic" });
  assert.equal(again.status, 200, "basic allows 1 key, but this is a renewal");
  const order = getOrderRow(env, again.data.orderId);
  assert.equal(order.kind, "renew");
  assert.equal(order.renew_key_id, keyId);
  assert.equal(order.key_id, null);
  // другой тариф или другой проект — не продление
  assert.equal(getOrderRow(env, (await checkout(env, { plan: "pro" })).data.orderId).kind, "new");
  assert.equal((await checkout(env, { plan: "basic", project: { name: "Elsewhere" } })).status, 409);
  // отозванный ключ не продлевается: новый заказ упирается в лимит как обычно
  env.sqlite.exec("UPDATE api_keys SET status = 'revoked' WHERE id = '" + keyId + "'");
  assert.equal(getOrderRow(env, (await checkout(env, { plan: "basic" })).data.orderId).kind, "new");
});
