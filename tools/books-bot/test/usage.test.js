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

test("/usage separates _denied into its own line and excludes it from today/month totals", async () => {
  const env = makeEnv();
  const T0 = Date.UTC(2026, 10, 13, 12, 0, 0, 0); // 2026-11-13
  env.sqlite
    .prepare("INSERT INTO customers (telegram_id, username, language, created_at) VALUES (88001, 'u88001', 'ru', ?)")
    .run(T0);
  const cust = env.sqlite.prepare("SELECT * FROM customers WHERE telegram_id = 88001").get();
  env.sqlite
    .prepare(
      "INSERT INTO api_keys (id, key_hash, key_prefix, tier, status, label, owner, customer_id, created_at, expires_at) " +
        "VALUES ('k_usage1', 'h_u1', 'vk_pro_u1…', 'pro', 'active', 'MySite', 'telegram:88001', ?, ?, ?)",
    )
    .run(cust.id, T0, T0 + 30 * 86_400_000);

  // Записи за прошлый день текущего месяца (2026-11-05) и за сегодня (2026-11-13)
  // Также запись за прошлый месяц (2026-10-20), которая НЕ должна попасть в отчёт за ноябрь.
  env.sqlite
    .prepare(
      "INSERT INTO usage_daily (key_id, day, route, requests) VALUES " +
        "('k_usage1', '2026-10-20', 'search', 999), " +
        "('k_usage1', '2026-11-05', 'search', 100), " +
        "('k_usage1', '2026-11-05', '_denied', 12), " +
        "('k_usage1', '2026-11-13', 'search', 25), " +
        "('k_usage1', '2026-11-13', 'covers', 15), " +
        "('k_usage1', '2026-11-13', '_denied', 7)",
    )
    .run();

  let lastMsg = null;
  const tg = {
    sendMessage: async (_chatId, text) => {
      lastMsg = text;
      return { message_id: 1 };
    },
  };

  await handleUpdate(
    {
      message: {
        message_id: 1,
        from: { id: 88001, is_bot: false, first_name: "U", username: "u88001", language_code: "ru" },
        chat: { id: 88001, type: "private" },
        text: "/usage",
      },
    },
    env,
    tg,
    T0,
  );

  assert.ok(lastMsg);
  // Сегодня: 25 + 15 = 40 (без _denied = 7)
  assert.match(lastMsg, /Сегодня:\s*<b>40<\/b>/);
  // За месяц: 100 + 25 + 15 = 140 (без _denied = 19 и без октября 999)
  assert.match(lastMsg, /За месяц:\s*<b>140<\/b>/);
  // _denied не в скобках маршрутов и выведен отдельной строкой «Отклонено»
  assert.doesNotMatch(lastMsg, /_denied/);
  assert.match(lastMsg, /\n\s*Отклонено:\s*<b>7<\/b> сегодня · <b>19<\/b> за месяц/);
});
