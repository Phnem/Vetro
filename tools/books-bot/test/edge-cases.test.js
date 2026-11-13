import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { handleUpdate } from "../src/bot.js";
import { createWorker } from "../src/worker.js";

function fakeD1(sqlite) {
  return {
    prepare(sql) {
      const stmt = sqlite.prepare(sql);
      const make = (args) => ({
        all: async () => ({ results: stmt.all(...args) }),
        first: async () => stmt.get(...args) ?? null,
        run: async () => {
          const info = stmt.run(...args);
          return {
            success: true,
            meta: {
              changes: Number(info.changes ?? 0),
              last_row_id: Number(info.lastInsertRowid ?? 0),
            },
          };
        },
      });
      return { ...make([]), bind: (...args) => make(args) };
    },
  };
}

function makeEnv() {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON;");
  sqlite.exec(readFileSync(new URL("../../books-api/schema/access.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../../books-api/schema/bot.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../schema/sessions.sql", import.meta.url), "utf8"));
  return {
    DB: fakeD1(sqlite),
    sqlite,
    TELEGRAM_BOT_TOKEN: "test-token",
    TELEGRAM_WEBHOOK_SECRET: "secret-abc",
    OWNER_TELEGRAM_ID: "999000",
  };
}

function makeMockTg() {
  const calls = [];
  return {
    calls,
    sendMessage: async (chatId, text, extra) => {
      calls.push({ method: "sendMessage", chatId, text, extra });
      return { message_id: calls.length };
    },
    editMessageText: async (chatId, messageId, text, extra) => {
      calls.push({ method: "editMessageText", chatId, messageId, text, extra });
      return { message_id: messageId };
    },
    answerCallbackQuery: async (id, extra) => {
      calls.push({ method: "answerCallbackQuery", id, extra });
      return true;
    },
    sendInvoice: async (params) => {
      calls.push({ method: "sendInvoice", params });
      return { message_id: calls.length };
    },
    answerPreCheckoutQuery: async (id, ok, errorMessage) => {
      calls.push({ method: "answerPreCheckoutQuery", id, ok, errorMessage });
      return true;
    },
    refundStarPayment: async (userId, chargeId) => {
      calls.push({ method: "refundStarPayment", userId, chargeId });
      return true;
    },
    last(method) {
      return [...calls].reverse().find((c) => c.method === method);
    },
  };
}

const T0 = Date.UTC(2026, 10, 13, 12, 0, 0, 0);
const user = (id = 10101) => ({ id, is_bot: false, first_name: "Test", username: `u${id}`, language_code: "en" });

test("wrong webhook secret returns 401 and writes nothing to DB", async () => {
  const env = makeEnv();
  const worker = createWorker(async () => Response.json({ ok: true, result: {} }));

  const res = await worker.fetch(
    new Request("https://bot.example/webhook", {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": "bad-secret" },
      body: JSON.stringify({
        message: { message_id: 1, from: user(111), chat: { id: 111, type: "private" }, text: "/start" },
      }),
    }),
    env,
  );
  assert.equal(res.status, 401);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM customers").get().c, 0);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM applications").get().c, 0);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM orders").get().c, 0);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM api_keys").get().c, 0);
});

test("application without terms_accepted_at cannot pass pre_checkout or issue a key", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = user(20202);
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (20202, 'u20202', ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 20202").get();
  const appId = Number(
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'NoTerms', 'Desc', 'server', 'basic', 'invoiced', NULL, ?, ?)",
      )
      .run(cust.id, T0, T0).lastInsertRowid,
  );
  env.sqlite
    .prepare(
      "INSERT INTO orders (application_id, customer_id, tier, kind, stars, invoice_payload, status, created_at) " +
        "VALUES (?, ?, 'basic', 'new', 100, 'pay_noterms', 'pending', ?)",
    )
    .run(appId, cust.id, T0);

  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-nt",
        from,
        currency: "XTR",
        total_amount: 100,
        invoice_payload: "pay_noterms",
      },
    },
    env,
    tg,
    T0 + 100,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, false);

  await handleUpdate(
    {
      message: {
        message_id: 2,
        from,
        chat: { id: 20202, type: "private" },
        successful_payment: {
          currency: "XTR",
          total_amount: 100,
          invoice_payload: "pay_noterms",
          telegram_payment_charge_id: "ch_noterms",
        },
      },
    },
    env,
    tg,
    T0 + 200,
  );
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM api_keys").get().c, 0);
  assert.equal(env.sqlite.prepare("SELECT status FROM orders WHERE invoice_payload = 'pay_noterms'").get().status, "pending");
});

test("banned=1 customer is rejected across commands, callbacks, pre_checkout, and successful_payment", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = user(30303);
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, banned, created_at) VALUES (30303, 'banned_u', 1, ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 30303").get();
  const appId = Number(
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'App', 'Desc', 'server', 'basic', 'invoiced', ?, ?, ?)",
      )
      .run(cust.id, T0, T0, T0).lastInsertRowid,
  );
  env.sqlite
    .prepare(
      "INSERT INTO orders (application_id, customer_id, tier, kind, stars, invoice_payload, status, created_at) " +
        "VALUES (?, ?, 'basic', 'new', 100, 'pay_banned', 'pending', ?)",
    )
    .run(appId, cust.id, T0);

  await handleUpdate(
    { message: { message_id: 1, from, chat: { id: 30303, type: "private" }, text: "/start" } },
    env,
    tg,
    T0 + 10,
  );
  assert.match(tg.last("sendMessage").text, /Access restricted/);

  await handleUpdate(
    {
      callback_query: {
        id: "cb-ban",
        from,
        message: { message_id: 5, chat: { id: 30303, type: "private" } },
        data: "nav:buy",
      },
    },
    env,
    tg,
    T0 + 20,
  );
  assert.equal(tg.last("answerCallbackQuery").extra.show_alert, true);

  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-ban",
        from,
        currency: "XTR",
        total_amount: 100,
        invoice_payload: "pay_banned",
      },
    },
    env,
    tg,
    T0 + 30,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, false);

  await handleUpdate(
    {
      message: {
        message_id: 2,
        from,
        chat: { id: 30303, type: "private" },
        successful_payment: {
          currency: "XTR",
          total_amount: 100,
          invoice_payload: "pay_banned",
          telegram_payment_charge_id: "ch_banned",
        },
      },
    },
    env,
    tg,
    T0 + 40,
  );
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM api_keys").get().c, 0);
});

test("pre_checkout rejects when tiers.max_keys is already reached", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = user(40404);
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (40404, 'maxk', ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 40404").get();
  const appId = Number(
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'App1', 'Desc', 'server', 'basic', 'issued', ?, ?, ?)",
      )
      .run(cust.id, T0, T0, T0).lastInsertRowid,
  );
  // 1 активный ключ Basic (max_keys = 1)
  env.sqlite
    .prepare(
      "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, customer_id, application_id, created_at, expires_at) " +
        "VALUES ('k_existing1', 'h_ex1', 'vk_basic_11…', 'basic', 'active', ?, ?, ?, ?)",
    )
    .run(cust.id, appId, T0, T0 + 10 * 86_400_000);
  // Второй ожидающий заказ на Basic
  env.sqlite
    .prepare(
      "INSERT INTO orders (application_id, customer_id, tier, kind, stars, invoice_payload, status, created_at) " +
        "VALUES (?, ?, 'basic', 'new', 100, 'pay_overlimit', 'pending', ?)",
    )
    .run(appId, cust.id, T0);

  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-limit",
        from,
        currency: "XTR",
        total_amount: 100,
        invoice_payload: "pay_overlimit",
      },
    },
    env,
    tg,
    T0 + 100,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, false);
  assert.match(tg.last("answerPreCheckoutQuery").errorMessage, /limit/i);
});

test("renew never moves expires_at backward for expired keys, and upgrade keeps same key id/hash", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = user(50505);
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (50505, 'renew_u', ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 50505").get();
  const appId = Number(
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'OldApp', 'Desc', 'server', 'basic', 'issued', ?, ?, ?)",
      )
      .run(cust.id, T0, T0, T0).lastInsertRowid,
  );
  const expiredAt = T0 - 5 * 86_400_000;
  env.sqlite
    .prepare(
      "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, customer_id, application_id, created_at, expires_at) " +
        "VALUES ('k_exp1', 'hash_keep', 'vk_basic_kk…', 'basic', 'active', 'OldApp', 'telegram:50505', ?, ?, ?, ?)",
    )
    .run(cust.id, appId, T0 - 40 * 86_400_000, expiredAt);

  // Продление истёкшего ключа: срок считается от now (T0 + 1000), а не от прошлого expiredAt
  await handleUpdate(
    {
      callback_query: {
        id: "cb-ren",
        from,
        message: { message_id: 1, chat: { id: 50505, type: "private" } },
        data: "key:renew:k_exp1",
      },
    },
    env,
    tg,
    T0 + 1000,
  );
  const renOrder = env.sqlite.prepare("SELECT * FROM orders WHERE kind = 'renew'").get();
  await handleUpdate(
    {
      message: {
        message_id: 2,
        from,
        chat: { id: 50505, type: "private" },
        successful_payment: {
          currency: "XTR",
          total_amount: 100,
          invoice_payload: renOrder.invoice_payload,
          telegram_payment_charge_id: "ch_ren_exp",
        },
      },
    },
    env,
    tg,
    T0 + 2000,
  );
  const afterRen = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = 'k_exp1'").get();
  assert.equal(afterRen.expires_at, T0 + 2000 + 30 * 86_400_000);
  assert.ok(afterRen.expires_at > T0);

  // Upgrade -> Ultra: тот же id и key_hash, новый tier = ultra
  await handleUpdate(
    {
      callback_query: {
        id: "cb-upg",
        from,
        message: { message_id: 3, chat: { id: 50505, type: "private" } },
        data: "key:upg_to:k_exp1:ultra",
      },
    },
    env,
    tg,
    T0 + 3000,
  );
  const upgOrder = env.sqlite.prepare("SELECT * FROM orders WHERE kind = 'upgrade'").get();
  await handleUpdate(
    {
      message: {
        message_id: 4,
        from,
        chat: { id: 50505, type: "private" },
        successful_payment: {
          currency: "XTR",
          total_amount: 1500,
          invoice_payload: upgOrder.invoice_payload,
          telegram_payment_charge_id: "ch_upg_ultra",
        },
      },
    },
    env,
    tg,
    T0 + 4000,
  );
  const afterUpg = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = 'k_exp1'").get();
  assert.equal(afterUpg.tier, "ultra");
  assert.equal(afterUpg.key_hash, "hash_keep");
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM api_keys").get().c, 1);

  // Не-владелец не может вызвать /refund
  await handleUpdate(
    { message: { message_id: 5, from, chat: { id: 50505, type: "private" }, text: `/refund ${upgOrder.id}` } },
    env,
    tg,
    T0 + 5000,
  );
  assert.equal(tg.last("refundStarPayment"), undefined);
  assert.equal(env.sqlite.prepare("SELECT status FROM orders WHERE id = ?").get(upgOrder.id).status, "paid");
});
