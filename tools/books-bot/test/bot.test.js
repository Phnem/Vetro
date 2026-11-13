import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { handleUpdate } from "../src/bot.js";
import { sha256Hex } from "../src/keys.js";
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

function makeEnv(extra = {}) {
  const sqlite = new DatabaseSync(":memory:");
  sqlite.exec("PRAGMA foreign_keys = ON;");
  sqlite.exec(readFileSync(new URL("../../books-api/schema/access.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../../books-api/schema/bot.sql", import.meta.url), "utf8"));
  sqlite.exec(readFileSync(new URL("../schema/sessions.sql", import.meta.url), "utf8"));

  return {
    DB: fakeD1(sqlite),
    sqlite,
    TELEGRAM_BOT_TOKEN: "test-token",
    TELEGRAM_WEBHOOK_SECRET: "whsec-test-123",
    OWNER_TELEGRAM_ID: "999000",
    ...extra,
  };
}

function makeMockTg() {
  const calls = [];
  const rec = (method) => async (...args) => {
    calls.push({ method, args });
    return { message_id: calls.length };
  };
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
    answerCallbackQuery: rec("answerCallbackQuery"),
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

const buyerFrom = (overrides = {}) => ({
  id: 42001,
  is_bot: false,
  first_name: "Alice",
  username: "alice_dev",
  language_code: "en",
  ...overrides,
});

const msgUpdate = (text, from = buyerFrom()) => ({
  message: {
    message_id: 1,
    from,
    chat: { id: from.id, type: "private" },
    date: 1700000000,
    text,
  },
});

const cbUpdate = (data, from = buyerFrom(), messageId = 10) => ({
  callback_query: {
    id: "cb-" + data,
    from,
    message: { message_id: messageId, chat: { id: from.id, type: "private" } },
    data,
  },
});

const T0 = Date.UTC(2026, 10, 13, 12, 0, 0, 0);

test("worker enforces X-Telegram-Bot-Api-Secret-Token and exposes /health", async () => {
  const env = makeEnv();
  const tgCalls = [];
  const worker = createWorker(async (url, init) => {
    tgCalls.push({ url, body: JSON.parse(init.body) });
    return Response.json({ ok: true, result: { message_id: 1 } });
  });

  const health = await worker.fetch(new Request("https://bot.example/health"), env);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { ok: true });

  const noSecret = await worker.fetch(
    new Request("https://bot.example/webhook", { method: "POST", body: JSON.stringify(msgUpdate("/plans")) }),
    env,
  );
  assert.equal(noSecret.status, 401);

  const wrongSecret = await worker.fetch(
    new Request("https://bot.example/webhook", {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": "wrong" },
      body: JSON.stringify(msgUpdate("/plans")),
    }),
    env,
  );
  assert.equal(wrongSecret.status, 401);

  const okReq = await worker.fetch(
    new Request("https://bot.example/webhook", {
      method: "POST",
      headers: { "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET },
      body: JSON.stringify(msgUpdate("/plans")),
    }),
    env,
  );
  assert.equal(okReq.status, 200);
  assert.equal(tgCalls.length, 1);
  assert.match(tgCalls[0].url, /\/bottest-token\/sendMessage$/);
});

test("full /start -> language -> 8-step website wizard -> Stars invoice -> payment -> idempotent key issuance", async () => {
  const env = makeEnv();
  // Динамически меняем цену Pro в таблице tiers, чтобы убедиться, что бот читает цену из базы, а не из кода
  env.sqlite.prepare("UPDATE tiers SET price_stars = 450 WHERE name = 'pro'").run();
  const tg = makeMockTg();

  // /start спрашивает язык на английском
  await handleUpdate(msgUpdate("/start"), env, tg, T0);
  const langMsg = tg.last("sendMessage");
  assert.match(langMsg.text, /choose your preferred language/i);
  assert.equal(langMsg.extra.reply_markup.inline_keyboard[0][0].callback_data, "startlang:en");
  assert.equal(langMsg.extra.reply_markup.inline_keyboard[0][1].callback_data, "startlang:ru");

  // Выбор русского -> сразу шаг 1 анкеты на русском
  await handleUpdate(cbUpdate("startlang:ru"), env, tg, T0 + 1000);
  const step1 = tg.last("sendMessage");
  assert.match(step1.text, /Шаг 1\/8/);

  const customer = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 42001").get();
  assert.equal(customer.language, "ru");
  assert.equal(customer.username, "alice_dev");

  // Слишком длинное название (> 80) отклоняется
  await handleUpdate(msgUpdate("x".repeat(81)), env, tg, T0 + 2000);
  assert.match(tg.last("sendMessage").text, /от 1 до 80/);

  // Шаг 1: app_name
  await handleUpdate(msgUpdate("Vetro Web Reader"), env, tg, T0 + 3000);
  assert.match(tg.last("sendMessage").text, /Шаг 2\/8/);

  // Шаг 2: description
  await handleUpdate(msgUpdate("Online book catalogue browser for readers."), env, tg, T0 + 4000);
  assert.match(tg.last("sendMessage").text, /Шаг 3\/8/);

  // Шаг 3: kind = website -> просит домены (Шаг 4)
  await handleUpdate(cbUpdate("wiz:kind:website"), env, tg, T0 + 5000);
  assert.match(tg.last("editMessageText").text, /Шаг 4\/8/);

  // Некорректный домен со схемой отклоняется
  await handleUpdate(msgUpdate("https://reader.example/app"), env, tg, T0 + 6000);
  assert.match(tg.last("sendMessage").text, /Некорректный список доменов/);

  // Шаг 4: origins
  await handleUpdate(msgUpdate("Reader.Example, *.reader.example"), env, tg, T0 + 7000);
  assert.match(tg.last("sendMessage").text, /Шаг 5\/8/);

  // Шаг 5: commercial = 1
  await handleUpdate(cbUpdate("wiz:comm:1"), env, tg, T0 + 8000);
  assert.match(tg.last("editMessageText").text, /Шаг 6\/8/);

  // Шаг 6: expected_per_day = 15000 -> рекомендует Pro
  await handleUpdate(msgUpdate("15 000"), env, tg, T0 + 9000);
  const tierPrompt = tg.last("sendMessage");
  assert.match(tierPrompt.text, /Шаг 7\/8/);
  assert.match(tierPrompt.text, /рекомендуем: <b>Pro<\/b>/);

  // Кнопка "Подробнее о тарифах" и возврат не теряют черновик
  await handleUpdate(cbUpdate("wiz:tiers_info"), env, tg, T0 + 9100);
  assert.match(tg.last("editMessageText").text, /450 ⭐/);
  await handleUpdate(cbUpdate("wiz:back_to_tiers"), env, tg, T0 + 9200);
  assert.match(tg.last("editMessageText").text, /Шаг 7\/8/);

  // Шаг 7: выбор Pro -> Шаг 8 (условия); до согласия в applications пусто!
  await handleUpdate(cbUpdate("wiz:tier:pro"), env, tg, T0 + 10000);
  const termsMsg = tg.last("editMessageText");
  assert.match(termsMsg.text, /Шаг 8\/8/);
  assert.match(termsMsg.text, /FantLab/);
  assert.match(termsMsg.text, /cover\.page/);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM applications").get().c, 0);

  // Шаг 8: согласие -> создаётся application + order + sendInvoice(XTR, 450) + уведомление владельцу
  await handleUpdate(cbUpdate("wiz:accept"), env, tg, T0 + 11000);

  const app = env.sqlite.prepare("SELECT * FROM applications WHERE customer_id = ?").get(customer.id);
  assert.equal(app.app_name, "Vetro Web Reader");
  assert.equal(app.kind, "website");
  assert.equal(app.origins, '["reader.example","*.reader.example"]');
  assert.equal(app.commercial, 1);
  assert.equal(app.expected_per_day, 15000);
  assert.equal(app.tier, "pro");
  assert.equal(app.status, "invoiced");
  assert.equal(app.terms_accepted_at, T0 + 11000);

  const order = env.sqlite.prepare("SELECT * FROM orders WHERE application_id = ?").get(app.id);
  assert.equal(order.status, "pending");
  assert.equal(order.currency, "XTR");
  assert.equal(order.stars, 450);

  const inv = tg.last("sendInvoice");
  assert.equal(inv.params.currency, "XTR");
  assert.equal(inv.params.provider_token, "");
  assert.equal(inv.params.prices[0].amount, 450);
  assert.equal(inv.params.payload, order.invoice_payload);

  const ownerNotifs = tg.calls.filter((c) => c.method === "sendMessage" && String(c.chatId) === "999000");
  assert.equal(ownerNotifs.length, 1);
  assert.match(ownerNotifs[0].text, /Новая заявка/);

  // pre_checkout_query: сначала плохой payload -> отказ, потом настоящий -> ok
  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-bad",
        from: buyerFrom(),
        currency: "XTR",
        total_amount: 450,
        invoice_payload: "unknown",
      },
    },
    env,
    tg,
    T0 + 12000,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, false);

  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-ok",
        from: buyerFrom(),
        currency: "XTR",
        total_amount: 450,
        invoice_payload: order.invoice_payload,
      },
    },
    env,
    tg,
    T0 + 12500,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, true);

  // successful_payment -> ключ показан один раз, в БД только хэш
  const payUpdate = {
    message: {
      message_id: 99,
      from: buyerFrom(),
      chat: { id: 42001, type: "private" },
      date: 1700000100,
      successful_payment: {
        currency: "XTR",
        total_amount: 450,
        invoice_payload: order.invoice_payload,
        telegram_payment_charge_id: "tg_charge_1",
      },
    },
  };
  await handleUpdate(payUpdate, env, tg, T0 + 13000);

  const keyMsg = tg.calls.find((c) => c.method === "sendMessage" && c.chatId === 42001 && /<pre><code>vk_pro_/.test(c.text));
  assert.ok(keyMsg);
  const plainMatch = keyMsg.text.match(/<code>(vk_pro_[a-z2-7]{32})<\/code>/);
  assert.ok(plainMatch);
  const plainKey = plainMatch[1];

  const dbKey = env.sqlite.prepare("SELECT * FROM api_keys WHERE customer_id = ?").get(customer.id);
  assert.equal(dbKey.tier, "pro");
  assert.equal(dbKey.status, "active");
  assert.equal(dbKey.key_hash, await sha256Hex(plainKey));
  assert.equal(dbKey.allowed_origins, '["reader.example","*.reader.example"]');
  assert.equal(dbKey.owner, "telegram:42001");
  assert.equal(dbKey.expires_at, T0 + 13000 + 30 * 86_400_000);

  const paidOrder = env.sqlite.prepare("SELECT * FROM orders WHERE id = ?").get(order.id);
  assert.equal(paidOrder.status, "paid");
  assert.equal(paidOrder.charge_id, "tg_charge_1");
  assert.equal(paidOrder.key_id, dbKey.id);

  const issuedApp = env.sqlite.prepare("SELECT * FROM applications WHERE id = ?").get(app.id);
  assert.equal(issuedApp.status, "issued");

  // Идемпотентность: повторная доставка successful_payment не создаёт второй ключ и не шлёт сообщение с ключом
  const callsBeforeRetry = tg.calls.length;
  await handleUpdate(payUpdate, env, tg, T0 + 14000);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM api_keys").get().c, 1);
  assert.equal(tg.calls.length, callsBeforeRetry);
});

test("mobile flow skips origins (NULL), /key /usage /rotate /revoke work end-to-end", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = buyerFrom({ id: 55001, username: "bob", language_code: "en" });

  await handleUpdate(msgUpdate("/buy", from), env, tg, T0);
  await handleUpdate(msgUpdate("Bob Books App", from), env, tg, T0 + 100);
  await handleUpdate(msgUpdate("Android e-reader client", from), env, tg, T0 + 200);
  await handleUpdate(cbUpdate("wiz:kind:mobile", from), env, tg, T0 + 300);
  // Для mobile шаг origins пропускается, сразу шаг 5 (commercial)
  assert.match(tg.last("editMessageText").text, /Step 5\/8/);

  await handleUpdate(cbUpdate("wiz:comm:0", from), env, tg, T0 + 400);
  await handleUpdate(cbUpdate("wiz:load:skip", from), env, tg, T0 + 500);
  await handleUpdate(cbUpdate("wiz:tier:basic", from), env, tg, T0 + 600);
  await handleUpdate(cbUpdate("wiz:accept", from), env, tg, T0 + 700);

  const app = env.sqlite.prepare("SELECT * FROM applications").get();
  assert.equal(app.origins, null);
  assert.equal(app.expected_per_day, null);

  const order = env.sqlite.prepare("SELECT * FROM orders").get();
  await handleUpdate(
    {
      message: {
        message_id: 20,
        from,
        chat: { id: 55001, type: "private" },
        date: 1700000200,
        successful_payment: {
          currency: "XTR",
          total_amount: 100,
          invoice_payload: order.invoice_payload,
          telegram_payment_charge_id: "ch_bob_1",
        },
      },
    },
    env,
    tg,
    T0 + 800,
  );

  const key1 = env.sqlite.prepare("SELECT * FROM api_keys").get();
  assert.equal(key1.allowed_origins, null);

  // Добавляем расход в usage_daily и проверяем /usage
  const today = new Date(T0 + 800).toISOString().slice(0, 10);
  env.sqlite
    .prepare("INSERT INTO usage_daily (key_id, day, route, requests) VALUES (?, ?, 'search', 42), (?, ?, 'books', 8)")
    .run(key1.id, today, key1.id, today);

  await handleUpdate(msgUpdate("/usage", from), env, tg, T0 + 900);
  const usageMsg = tg.last("sendMessage").text;
  assert.match(usageMsg, /50/);
  assert.match(usageMsg, /search: 42/);

  // Лимит Basic max_keys = 1: вторая покупка Basic блокируется
  await handleUpdate(msgUpdate("/buy", from), env, tg, T0 + 1000);
  await handleUpdate(msgUpdate("Second App", from), env, tg, T0 + 1100);
  await handleUpdate(msgUpdate("Another app", from), env, tg, T0 + 1200);
  await handleUpdate(cbUpdate("wiz:kind:server", from), env, tg, T0 + 1300);
  await handleUpdate(cbUpdate("wiz:comm:0", from), env, tg, T0 + 1400);
  await handleUpdate(cbUpdate("wiz:load:skip", from), env, tg, T0 + 1500);
  await handleUpdate(cbUpdate("wiz:tier:basic", from), env, tg, T0 + 1600);
  assert.match(tg.last("sendMessage").text, /up to <b>1<\/b> active key/);

  // /rotate: отзывает key1 и выдаёт новый с тем же тарифом и expires_at
  await handleUpdate(msgUpdate("/rotate", from), env, tg, T0 + 1700);
  await handleUpdate(cbUpdate(`key:rotate:${key1.id}`, from), env, tg, T0 + 1800);
  await handleUpdate(cbUpdate(`key:rotate_yes:${key1.id}`, from), env, tg, T0 + 1900);

  const rotatedMsg = tg.last("sendMessage");
  assert.match(rotatedMsg.text, /<pre><code>vk_basic_/);
  const oldRow = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = ?").get(key1.id);
  assert.equal(oldRow.status, "revoked");
  assert.equal(oldRow.revoked_at, T0 + 1900);

  const newRow = env.sqlite.prepare("SELECT * FROM api_keys WHERE status = 'active'").get();
  assert.notEqual(newRow.id, key1.id);
  assert.equal(newRow.expires_at, key1.expires_at);

  // /revoke: отзывает активный ключ
  await handleUpdate(cbUpdate(`key:revoke:${newRow.id}`, from), env, tg, T0 + 2000);
  await handleUpdate(cbUpdate(`key:revoke_yes:${newRow.id}`, from), env, tg, T0 + 2100);
  const revokedRow = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = ?").get(newRow.id);
  assert.equal(revokedRow.status, "revoked");
});

test("renew, upgrade, refund and banned customer enforcement", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const from = buyerFrom({ id: 77001, username: "carol", language_code: "en" });

  // Создадим клиента и активный ключ Basic со сроком T0 + 5 дней
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (77001, 'carol', ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 77001").get();
  const appId = Number(
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'Carol CLI', 'Script', 'server', 'basic', 'issued', ?, ?, ?)",
      )
      .run(cust.id, T0, T0, T0).lastInsertRowid,
  );
  const exp0 = T0 + 5 * 86_400_000;
  env.sqlite
    .prepare(
      "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, customer_id, application_id, created_at, expires_at) " +
        "VALUES ('k_carol1', 'hash1', 'vk_basic_aa…', 'basic', 'active', 'Carol CLI', 'telegram:77001', ?, ?, ?, ?)",
    )
    .run(cust.id, appId, T0, exp0);

  // Renew: добавляет 30 дней к exp0
  await handleUpdate(cbUpdate("key:renew:k_carol1", from), env, tg, T0 + 100);
  const renewOrder = env.sqlite.prepare("SELECT * FROM orders WHERE kind = 'renew'").get();
  assert.equal(renewOrder.stars, 100);
  await handleUpdate(
    {
      message: {
        message_id: 30,
        from,
        chat: { id: 77001, type: "private" },
        date: 1700000300,
        successful_payment: {
          currency: "XTR",
          total_amount: 100,
          invoice_payload: renewOrder.invoice_payload,
          telegram_payment_charge_id: "ch_renew_1",
        },
      },
    },
    env,
    tg,
    T0 + 200,
  );
  const afterRenew = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = 'k_carol1'").get();
  assert.equal(afterRenew.expires_at, exp0 + 30 * 86_400_000);

  // Upgrade -> Pro (400 stars): обновляет tier без перевыпуска ключа
  await handleUpdate(cbUpdate("key:upg_to:k_carol1:pro", from), env, tg, T0 + 300);
  const upgOrder = env.sqlite.prepare("SELECT * FROM orders WHERE kind = 'upgrade'").get();
  assert.equal(upgOrder.stars, 400);
  await handleUpdate(
    {
      message: {
        message_id: 31,
        from,
        chat: { id: 77001, type: "private" },
        date: 1700000400,
        successful_payment: {
          currency: "XTR",
          total_amount: 400,
          invoice_payload: upgOrder.invoice_payload,
          telegram_payment_charge_id: "ch_upg_1",
        },
      },
    },
    env,
    tg,
    T0 + 400,
  );
  const afterUpg = env.sqlite.prepare("SELECT * FROM api_keys WHERE id = 'k_carol1'").get();
  assert.equal(afterUpg.tier, "pro");
  assert.equal(afterUpg.expires_at, exp0 + 60 * 86_400_000);

  // Владелец делает /refund по order.id -> refundStarPayment + order refunded + key revoked
  const ownerFrom = buyerFrom({ id: 999000, username: "owner" });
  await handleUpdate(msgUpdate(`/refund ${upgOrder.id}`, ownerFrom), env, tg, T0 + 500);
  assert.equal(tg.last("refundStarPayment").chargeId, "ch_upg_1");
  assert.equal(env.sqlite.prepare("SELECT status FROM orders WHERE id = ?").get(upgOrder.id).status, "refunded");
  assert.equal(env.sqlite.prepare("SELECT status FROM api_keys WHERE id = 'k_carol1'").get().status, "revoked");
  assert.equal(env.sqlite.prepare("SELECT status FROM applications WHERE id = ?").get(appId).status, "refunded");

  // Забаненный покупатель не может ничего получить или оплатить
  env.sqlite.prepare("UPDATE customers SET banned = 1 WHERE telegram_id = 77001").run();
  await handleUpdate(msgUpdate("/buy", from), env, tg, T0 + 600);
  assert.match(tg.last("sendMessage").text, /Access restricted/);

  await handleUpdate(
    {
      pre_checkout_query: {
        id: "pcq-banned",
        from,
        currency: "XTR",
        total_amount: 100,
        invoice_payload: "anything",
      },
    },
    env,
    tg,
    T0 + 700,
  );
  assert.equal(tg.last("answerPreCheckoutQuery").ok, false);
});
