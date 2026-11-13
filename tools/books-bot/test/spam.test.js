import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { handleUpdate } from "../src/bot.js";

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
    TELEGRAM_WEBHOOK_SECRET: "sec",
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
    answerCallbackQuery: async () => true,
    sendInvoice: async (params) => {
      calls.push({ method: "sendInvoice", params });
      return { message_id: calls.length };
    },
    last(method) {
      return [...calls].reverse().find((c) => c.method === method);
    },
  };
}

const T0 = Date.UTC(2026, 10, 13, 18, 0, 0, 0);
const from = (id = 60001) => ({ id, is_bot: false, first_name: "SpamCheck", username: `u${id}`, language_code: "en" });

test("spam: max 3 open (new/invoiced) applications per customer", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const u = from(61001);
  env.sqlite.prepare("INSERT INTO customers (telegram_id, username, created_at) VALUES (61001, 'u61001', ?)").run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 61001").get();

  for (const status of ["new", "invoiced", "invoiced"]) {
    env.sqlite
      .prepare(
        "INSERT INTO applications (customer_id, app_name, description, kind, tier, status, terms_accepted_at, created_at, updated_at) " +
          "VALUES (?, 'App', 'Desc', 'server', 'basic', ?, ?, ?, ?)",
      )
      .run(cust.id, status, T0, T0, T0);
  }

  // На /buy не пускает при 3 открытых заявках
  await handleUpdate({ message: { message_id: 1, from: u, chat: { id: 61001, type: "private" }, text: "/buy" } }, env, tg, T0 + 10);
  assert.match(tg.last("sendMessage").text, /3 open applications/);

  // И на wiz:accept напрямую также блокируется (даже если черновик уже был в сессии)
  env.sqlite
    .prepare("INSERT OR REPLACE INTO bot_sessions (telegram_id, lang, step, draft, updated_at) VALUES (61001, 'en', 'terms', ?, ?)")
    .run(
      JSON.stringify({ app_name: "Fourth", description: "Desc", kind: "server", commercial: 0, tier: "basic" }),
      T0,
    );
  await handleUpdate(
    {
      callback_query: {
        id: "cb-acc",
        from: u,
        message: { message_id: 2, chat: { id: 61001, type: "private" } },
        data: "wiz:accept",
      },
    },
    env,
    tg,
    T0 + 20,
  );
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM applications WHERE customer_id = ?").get(cust.id).c, 3);

  // Если одна заявка закрыта (expired), новую снова можно начать
  env.sqlite.prepare("UPDATE applications SET status = 'expired' WHERE id = 1").run();
  await handleUpdate({ message: { message_id: 3, from: u, chat: { id: 61001, type: "private" }, text: "/buy" } }, env, tg, T0 + 30);
  assert.match(tg.last("sendMessage").text, /Step 1\/8/);
});

test("spam: silently ignores messages above 30/min per chat_id, resets next minute", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const u = from(62001);

  for (let i = 1; i <= 30; i++) {
    await handleUpdate(
      { message: { message_id: i, from: u, chat: { id: 62001, type: "private" }, text: "/plans" } },
      env,
      tg,
      T0 + i * 100,
    );
  }
  assert.equal(tg.calls.length, 30);

  // 31-е и 32-е сообщения в ту же минуту молча игнорируются
  await handleUpdate(
    { message: { message_id: 31, from: u, chat: { id: 62001, type: "private" }, text: "/plans" } },
    env,
    tg,
    T0 + 4000,
  );
  await handleUpdate(
    { message: { message_id: 32, from: u, chat: { id: 62001, type: "private" }, text: "/start" } },
    env,
    tg,
    T0 + 5000,
  );
  assert.equal(tg.calls.length, 30);

  // Следующая минута снова пропускает
  await handleUpdate(
    { message: { message_id: 33, from: u, chat: { id: 62001, type: "private" }, text: "/plans" } },
    env,
    tg,
    T0 + 61_000,
  );
  assert.equal(tg.calls.length, 31);
});

test("spam: server-side validation rejects oversized fields both on input and at wiz:accept", async () => {
  const env = makeEnv();
  const tg = makeMockTg();
  const u = from(63001);

  await handleUpdate({ message: { message_id: 1, from: u, chat: { id: 63001, type: "private" }, text: "/buy" } }, env, tg, T0);
  await handleUpdate(
    { message: { message_id: 2, from: u, chat: { id: 63001, type: "private" }, text: "a".repeat(81) } },
    env,
    tg,
    T0 + 10,
  );
  assert.match(tg.last("sendMessage").text, /1 and 80/);

  await handleUpdate(
    { message: { message_id: 3, from: u, chat: { id: 63001, type: "private" }, text: "Valid App" } },
    env,
    tg,
    T0 + 20,
  );
  await handleUpdate(
    { message: { message_id: 4, from: u, chat: { id: 63001, type: "private" }, text: "d".repeat(501) } },
    env,
    tg,
    T0 + 30,
  );
  assert.match(tg.last("sendMessage").text, /1 and 500/);

  // Подмена длинного description прямо в сессии также отклоняется на wiz:accept
  env.sqlite
    .prepare("UPDATE bot_sessions SET step = 'terms', draft = ? WHERE telegram_id = 63001")
    .run(
      JSON.stringify({ app_name: "Ok", description: "d".repeat(501), kind: "server", commercial: 0, tier: "basic" }),
    );
  await handleUpdate(
    {
      callback_query: {
        id: "cb-bad-draft",
        from: u,
        message: { message_id: 10, chat: { id: 63001, type: "private" } },
        data: "wiz:accept",
      },
    },
    env,
    tg,
    T0 + 40,
  );
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM applications").get().c, 0);
});
