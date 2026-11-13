-- Доступ к API каталога (ADR 0003): тарифы, ключи, расход. Идемпотентно: можно выполнять повторно.
--   npx wrangler d1 execute vetro-books --remote --file schema/access.sql
-- Лимиты тарифа правятся в панели (или UPDATE tiers ...) без деплоя; Worker кэширует ключ и тариф на 60 с.

CREATE TABLE IF NOT EXISTS tiers (
  name          TEXT PRIMARY KEY,           -- basic | pro | ultra | app
  title         TEXT NOT NULL,
  per_second    INTEGER NOT NULL,           -- всплеск; 0 = без ограничения
  per_minute    INTEGER NOT NULL,           -- частота; 0 = без ограничения
  per_day       INTEGER NOT NULL,           -- суточная квота (UTC); 0 = без ограничения
  max_limit     INTEGER NOT NULL,           -- потолок ?limit= у /v1/search
  max_ids       INTEGER NOT NULL,           -- потолок ?ids= у /v1/books
  allow_covers  INTEGER NOT NULL DEFAULT 0,
  allow_index   INTEGER NOT NULL DEFAULT 0, -- файлы индекса для офлайна (весь каталог)
  max_keys      INTEGER NOT NULL DEFAULT 1, -- ключей на покупателя
  price_stars   INTEGER NOT NULL DEFAULT 0, -- Telegram Stars за period_days
  period_days   INTEGER NOT NULL DEFAULT 30,
  sold          INTEGER NOT NULL DEFAULT 1, -- 0 = не продаётся (app)
  sort          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS api_keys (
  id              TEXT PRIMARY KEY,         -- k_<12 hex>, виден везде; сам ключ нигде не хранится
  key_hash        TEXT NOT NULL UNIQUE,     -- SHA-256 от ключа
  key_prefix      TEXT NOT NULL,            -- первые символы для показа: vk_pro_ab12…
  tier            TEXT NOT NULL REFERENCES tiers(name),
  status          TEXT NOT NULL DEFAULT 'active',   -- active | revoked
  label           TEXT,
  owner           TEXT,                     -- telegram:<id> | site | partner:<имя>
  customer_id     INTEGER,                  -- customers.id (schema/bot.sql)
  application_id  INTEGER,                  -- applications.id: заявка, по которой ключ выдан (schema/bot.sql)
  allowed_origins TEXT,                     -- JSON ["vetrostudio.bid","*.example.com"]; NULL = без проверки Origin
  created_at      INTEGER NOT NULL,         -- мс
  expires_at      INTEGER,                  -- мс; NULL = бессрочно
  revoked_at      INTEGER
);
CREATE INDEX IF NOT EXISTS api_keys_owner ON api_keys(owner);
CREATE INDEX IF NOT EXISTS api_keys_customer ON api_keys(customer_id);

CREATE TABLE IF NOT EXISTS usage_daily (
  key_id   TEXT NOT NULL,
  day      TEXT NOT NULL,                   -- YYYY-MM-DD, UTC
  route    TEXT NOT NULL,                   -- search | books | covers | index | _denied
  requests INTEGER NOT NULL,
  PRIMARY KEY (key_id, day, route)
);

INSERT OR IGNORE INTO tiers (name, title, per_second, per_minute, per_day, max_limit, max_ids, allow_covers, allow_index, max_keys, price_stars, period_days, sold, sort) VALUES
  ('basic', 'Basic',  2,  30,   2000, 20, 10, 0, 0, 1,  100, 30, 1, 1),
  ('pro',   'Pro',    5, 120,  20000, 50, 40, 1, 0, 2,  400, 30, 1, 2),
  ('ultra', 'Ultra', 20, 600, 200000, 50, 40, 1, 1, 5, 1500, 30, 1, 3),
  ('app',   'App',    0, 120,      0, 50, 40, 1, 1, 0,    0, 30, 0, 9);
