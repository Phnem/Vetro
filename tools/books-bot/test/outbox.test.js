import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { processOutbox } from "../src/outbox.js";
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
    TELEGRAM_WEBHOOK_SECRET: "sec",
  };
}

const T0 = Date.UTC(2026, 10, 13, 18, 0, 0, 0);

test("outbox: success sets sent_at and sends HTML", async () => {
  const env = makeEnv();
  env.sqlite
    .prepare("INSERT INTO bot_outbox (chat_id, text, created_at) VALUES (1001, '<b>Approved</b>', ?)")
    .run(T0);

  const sent = [];
  const tg = {
    sendMessage: async (chatId, text, extra) => {
      sent.push({ chatId, text, extra });
      return { message_id: 1 };
    },
  };

  const res = await processOutbox(env, tg, T0 + 1000);
  assert.deepEqual(res, { total: 1, sent: 1, failed: 0 });
  assert.equal(sent.length, 1);
  assert.equal(sent[0].chatId, 1001);
  assert.equal(sent[0].text, "<b>Approved</b>");
  assert.equal(sent[0].extra.parse_mode, "HTML");

  const row = env.sqlite.prepare("SELECT * FROM bot_outbox WHERE id = 1").get();
  assert.equal(row.sent_at, T0 + 1000);
  assert.equal(row.error, null);
  assert.equal(row.attempts, 0);
});

test("outbox: retries transient errors and stops after 5 failures keeping error and setting sent_at", async () => {
  const env = makeEnv();
  env.sqlite
    .prepare("INSERT INTO bot_outbox (chat_id, text, created_at) VALUES (1002, 'Hello', ?)")
    .run(T0);

  const tg = {
    sendMessage: async () => {
      const err = new Error("Too Many Requests: retry after 10");
      err.code = 429;
      throw err;
    },
  };

  for (let i = 1; i <= 4; i++) {
    const r = await processOutbox(env, tg, T0 + i * 1000);
    assert.deepEqual(r, { total: 1, sent: 0, failed: 1 });
    const row = env.sqlite.prepare("SELECT * FROM bot_outbox WHERE id = 1").get();
    assert.equal(row.attempts, i);
    assert.equal(row.sent_at, null);
    assert.match(row.error, /Too Many Requests/);
  }

  // 5-я неудача: ставит sent_at, оставляет error, больше не выбирается
  const fifth = await processOutbox(env, tg, T0 + 5000);
  assert.deepEqual(fifth, { total: 1, sent: 0, failed: 1 });
  const row5 = env.sqlite.prepare("SELECT * FROM bot_outbox WHERE id = 1").get();
  assert.equal(row5.attempts, 5);
  assert.equal(row5.sent_at, T0 + 5000);
  assert.match(row5.error, /Too Many Requests/);

  const sixth = await processOutbox(env, tg, T0 + 6000);
  assert.deepEqual(sixth, { total: 0, sent: 0, failed: 0 });
});

test("outbox: 403 bot was blocked is not retried", async () => {
  const env = makeEnv();
  env.sqlite
    .prepare("INSERT INTO bot_outbox (chat_id, text, created_at) VALUES (1003, 'Hi', ?)")
    .run(T0);

  let calls = 0;
  const tg = {
    sendMessage: async () => {
      calls++;
      const err = new Error("Forbidden: bot was blocked by the user");
      err.code = 403;
      throw err;
    },
  };

  const r1 = await processOutbox(env, tg, T0 + 1000);
  assert.deepEqual(r1, { total: 1, sent: 0, failed: 1 });
  const row = env.sqlite.prepare("SELECT * FROM bot_outbox WHERE id = 1").get();
  assert.equal(row.attempts, 1);
  assert.equal(row.sent_at, T0 + 1000);
  assert.match(row.error, /bot was blocked/);

  const r2 = await processOutbox(env, tg, T0 + 2000);
  assert.deepEqual(r2, { total: 0, sent: 0, failed: 0 });
  assert.equal(calls, 1);
});

test("worker.scheduled processes up to 20 rows from bot_outbox via Telegram API", async () => {
  const env = makeEnv();
  for (let i = 1; i <= 25; i++) {
    env.sqlite
      .prepare("INSERT INTO bot_outbox (chat_id, text, created_at) VALUES (?, ?, ?)")
      .run(2000 + i, `Msg #${i}`, T0 + i);
  }

  const fetchCalls = [];
  const worker = createWorker(async (url, init) => {
    fetchCalls.push({ url, body: JSON.parse(init.body) });
    return Response.json({ ok: true, result: { message_id: fetchCalls.length } });
  });

  await worker.scheduled({}, env);
  assert.equal(fetchCalls.length, 20);
  assert.equal(fetchCalls[0].body.parse_mode, "HTML");
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM bot_outbox WHERE sent_at IS NOT NULL").get().c, 20);
  assert.equal(env.sqlite.prepare("SELECT COUNT(*) c FROM bot_outbox WHERE sent_at IS NULL").get().c, 5);
});
