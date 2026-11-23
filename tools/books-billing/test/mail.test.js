import assert from "node:assert/strict";
import { test } from "node:test";
import { processEvent } from "../src/issue.js";
import { composeKeyEmail, fmtUtc } from "../src/mail.js";
import { checkout, getOrderRow, makeEnv, T0 } from "./helpers.js";

const KEY = "vk_pro_abcdefghijklmnopqrstuvwxyz234567";
const noSleep = async () => {};
const paid = (order) => ({ type: "paid", checkoutRef: order.checkout_ref, providerRef: "p1", amountMinor: order.amount_minor, currency: "USD", tier: order.tier });

test("the e-mail texts follow the brief, EN and RU, domains line only for sites", () => {
  const base = { signer: "Ivan Petrov", plan: "Pro", expiresAt: Date.UTC(2026, 11, 23, 12, 0), key: KEY };
  const en = composeKeyEmail({ ...base, lang: "en", domains: ["a.com", "b.com"] });
  assert.equal(en.subject, "Your Vetro API key");
  assert.match(en.text, /^Hello, Ivan Petrov!/);
  assert.match(en.text, /Your Vetro API key \(Pro, valid until 2026-12-23 12:00 UTC\):\n\nvk_pro_/);
  assert.match(en.text, /The key works from these domains only: a\.com, b\.com\./);
  assert.match(en.text, /https:\/\/t\.me\/Vetro_chat\n— Vetro Studio\n$/);
  assert.match(en.text, /Authorization: Bearer <key>" on https:\/\/api\.vetrostudio\.bid/);
  assert.doesNotMatch(composeKeyEmail({ ...base, lang: "en", domains: [] }).text, /domains only/);

  const ru = composeKeyEmail({ ...base, lang: "ru", domains: ["a.com"] });
  assert.equal(ru.subject, "Ваш ключ Vetro API");
  assert.match(ru.text, /^Здравствуйте, Ivan Petrov!/);
  assert.match(ru.text, /\(тариф Pro, действует до 2026-12-23 12:00 UTC\)/);
  assert.match(ru.text, /Ключ работает только с этих доменов: a\.com\./);
  assert.doesNotMatch(composeKeyEmail({ ...base, lang: "ru" }).text, /доменов/);
  assert.equal(fmtUtc(Date.UTC(2026, 0, 5, 3, 4)), "2026-01-05 03:04");
});

async function liveOrder(extra) {
  const env = makeEnv(extra);
  const order = getOrderRow(env, (await checkout(env)).data.orderId);
  env.BILLING_MODE = "live";
  return { env, order };
}

test("with the Resend transport the key goes out by e-mail and email_failed stays 0", async () => {
  const sent = [];
  const fetchImpl = async (url, init) => (sent.push({ url, init, body: JSON.parse(init.body) }), new Response("{}", { status: 200 }));
  const { env, order } = await liveOrder({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "re_secret", MAIL_FROM: "Vetro <keys@vetrostudio.bid>" });
  const res = await processEvent(env, "mock", paid(order), { now: T0, fetchImpl, sleep: noSleep });
  assert.equal(res.result, "issued");
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, "https://api.resend.com/emails");
  assert.equal(sent[0].init.headers.authorization, "Bearer re_secret");
  assert.deepEqual(sent[0].body.to, ["buyer@example.com"]);
  assert.equal(sent[0].body.from, "Vetro <keys@vetrostudio.bid>");
  assert.match(sent[0].body.text, /vk_pro_[a-z2-7]{32}/);
  assert.match(sent[0].body.text, /example\.com, www\.example\.com/);
  assert.equal(getOrderRow(env, order.checkout_ref).email_failed, 0);
});

test("three attempts with pauses, then email_failed = 1; the key itself is still issued", async () => {
  let calls = 0;
  const pauses = [];
  const fetchImpl = async () => (calls++, new Response("no", { status: 500 }));
  const { env, order } = await liveOrder({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "re_secret", MAIL_FROM: "x@y.z" });
  const res = await processEvent(env, "mock", paid(order), { now: T0, fetchImpl, sleep: async (ms) => pauses.push(ms) });
  assert.equal(res.result, "issued");
  assert.equal(calls, 3);
  assert.equal(pauses.length, 2);
  assert.equal(getOrderRow(env, order.checkout_ref).email_failed, 1);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) AS n FROM api_keys").get().n, 1);
});

test("a transport that recovers on the second try counts as sent", async () => {
  let calls = 0;
  const fetchImpl = async () => new Response("", { status: ++calls < 2 ? 503 : 200 });
  const { env, order } = await liveOrder({ MAIL_TRANSPORT: "resend", MAIL_API_KEY: "k", MAIL_FROM: "x@y.z" });
  await processEvent(env, "mock", paid(order), { now: T0, fetchImpl, sleep: noSleep });
  assert.equal(calls, 2);
  assert.equal(getOrderRow(env, order.checkout_ref).email_failed, 0);
});

test("live mode without any mail transport marks email_failed; test mode just skips the e-mail", async () => {
  const live = await liveOrder();
  await processEvent(live.env, "mock", paid(live.order), { now: T0, sleep: noSleep });
  assert.equal(getOrderRow(live.env, live.order.checkout_ref).email_failed, 1);

  const env = makeEnv();
  const order = getOrderRow(env, (await checkout(env)).data.orderId);
  await processEvent(env, "mock", paid(order), { now: T0, sleep: noSleep });
  assert.equal(getOrderRow(env, order.checkout_ref).email_failed, 0);
});

test("a transport without its secrets fails safely and never throws into the payment path", async () => {
  const { env, order } = await liveOrder({ MAIL_TRANSPORT: "resend" });
  const res = await processEvent(env, "mock", paid(order), { now: T0, sleep: noSleep, fetchImpl: async () => { throw new Error("must not be called"); } });
  assert.equal(res.result, "issued");
  assert.equal(getOrderRow(env, order.checkout_ref).email_failed, 1);
});
