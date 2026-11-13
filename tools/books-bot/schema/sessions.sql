-- Состояние диалога бота между вебхуками (выполнять после schema/access.sql и schema/bot.sql).
--   npx wrangler d1 execute vetro-books --remote --file schema/sessions.sql
-- Бот также создаёт эту таблицу автоматически при первом обращении (CREATE TABLE IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS bot_sessions (
  telegram_id INTEGER PRIMARY KEY,
  lang        TEXT NOT NULL DEFAULT 'en',
  step        TEXT,
  draft       TEXT,
  updated_at  INTEGER NOT NULL
);
