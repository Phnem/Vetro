// Общие помощники тестов: фейковая D1 поверх node:sqlite (как у tools/books-bot), фейковый KV и разумное тело checkout.
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { route } from "../src/worker.js";

export function fakeD1(sqlite) {
  const wrap = (stmt) => {
    const make = (args) => ({
      all: async () => ({ results: stmt.all(...args) }),
      first: async () => stmt.get(...args) ?? null,
      run: async () => {
        const info = stmt.run(...args);
        return { success: true, meta: { changes: Number(info.changes ?? 0), last_row_id: Number(info.lastInsertRowid ?? 0) } };
      },
    });
    return { ...make([]), bind: (...args) => make(args) };
  };
  return {
    prepare: (sql) => wrap(sqlite.prepare(sql)),
    // D1 batch = одна транзакция: так же здесь.
    async batch(stmts) {
      sqlite.exec("BEGIN");
      try {
        const out = [];
        for (const s of stmts) out.push(await s.run());
        sqlite.exec("COMMIT");
        return out;
      } catch (e) {
        sqlite.exec("ROLLBACK");
        throw e;
      }
    },
  };
}

export function fakeKV() {
  const m = new Map();
  return {
    m,
    async get(k) {
      return m.get(k) ?? null;
    },
    async put(k, v, opts) {
      m.set(k, v);
      m.ttl = opts?.expirationTtl;
    },
    async delete(k) {
      m.delete(k);
    },
  };
}

export function makeEnv(extra = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON;");
  sqlite.exec(readFileSync(new URL("../../books-api/schema/access.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../schema/billing-001-tier-price.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../schema/billing.sql", import.meta.url), "utf8"));
  return { DB: fakeD1(sqlite), sqlite, REVEAL: fakeKV(), BILLING_MODE: "test", MOCK_WEBHOOK_SECRET: "whsec-mock", ...extra };
}

export const T0 = Date.UTC(2026, 10, 23, 12, 0, 0);

export const NS = 'xmlns="http://www.w3.org/2000/svg"';
export const SIGNATURE = `<svg ${NS} viewBox="0 0 300 120" fill="#1e3a8a"><path d="M10.5 20L30 40.2L10.5 60Z"/><circle cx="50.0" cy="60.0" r="2.40"/></svg>`;

export function body(over = {}) {
  const { project, contract, ...rest } = over;
  return {
    plan: "pro",
    method: "card",
    billing: (rest.method ?? "card") === "card" ? "subscription" : "one_time",
    days: 30,
    lang: "en",
    policiesAccepted: true,
    email: "Buyer@Example.com ",
    project: { name: "My Site", type: "site", description: "A small reading list website", domains: ["Example.com", "www.example.com"], ...project },
    contract: { signer: "Ivan Petrov", signedAt: "2026-11-23T11:59:00.000Z", termsVersion: "f3bee9bb6eb9", documents: { terms: "f3bee9bb6eb9", refund: "a1b2c3d4e5f6", privacy: "0123456789ab" }, signatureSvg: SIGNATURE, ...contract },
    ...rest,
  };
}

/** Вызов маршрутизатора напрямую, с управляемым временем и сбором waitUntil. */
export async function call(env, path, { method = "GET", json, raw, headers = {}, now = T0, deps = {} } = {}) {
  const pending = [];
  const init = { method, headers: { ...headers } };
  if (json !== undefined) {
    init.body = JSON.stringify(json);
    init.headers["content-type"] = "application/json";
  } else if (raw !== undefined) {
    init.body = raw;
  }
  const request = new Request("https://billing.vetrostudio.bid" + path, init);
  let res;
  try {
    res = await route(request, env, { now, waitUntil: (p) => pending.push(p), ...deps });
  } catch (e) {
    // Как default.fetch в src/worker.js: ApiError → его код, всё остальное → 500 internal.
    const known = e?.status !== undefined;
    res = new Response(JSON.stringify(known ? { error: e.code, ...e.extra } : { error: "internal" }), { status: known ? e.status : 500, headers: { "content-type": "application/json" } });
  }
  await Promise.all(pending);
  const text = await res.text();
  let data = null;
  try {
    data = JSON.parse(text);
  } catch {
    // не JSON (HTML страницы тестовой оплаты)
  }
  return { status: res.status, data, text, headers: res.headers };
}

export const checkout = (env, over, opts) => call(env, "/v1/billing/checkout", { method: "POST", json: body(over), ...opts });

/** Подписанный вебхук mock. */
export async function mockWebhook(env, event, { secret = env.MOCK_WEBHOOK_SECRET, now = T0 } = {}) {
  const { hmacSha256Hex } = await import("../src/signing.js");
  const raw = JSON.stringify(event);
  return call(env, "/v1/billing/webhook/mock", { method: "POST", raw, headers: { "x-mock-signature": await hmacSha256Hex(secret, raw) }, now });
}

export const paidEvent = (order, over = {}) => ({
  type: "paid",
  orderId: order.checkout_ref ?? order,
  paymentId: "pay_1",
  amountMinor: order.amount_minor ?? 700,
  currency: "USD",
  tier: order.tier ?? "pro",
  ...over,
});

export const getOrderRow = (env, ref) => env.sqlite.prepare("SELECT * FROM web_orders WHERE checkout_ref = ?").get(ref);
