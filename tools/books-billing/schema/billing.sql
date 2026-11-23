-- Оплата на сайте (vetro-books-billing). Выполнять ПОСЛЕ schema/access.sql и billing-001-tier-price.sql, идемпотентно:
--   npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing.sql   (из tools/books-api)
-- Таблицы бота (customers, applications, orders) завязаны на telegram_id и звёзды, поэтому у сайта свои.
-- IP-адреса и user-agent не пишутся нигде: условия на сайте обещают, что сервис их не логирует.

CREATE TABLE IF NOT EXISTS web_customers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT NOT NULL UNIQUE,            -- нижний регистр, обрезанные пробелы
  language TEXT,                         -- en | ru
  banned INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS web_orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  checkout_ref TEXT NOT NULL UNIQUE,     -- «ord_» + 32 hex (128 бит): это и есть orderId для сайта
  customer_id INTEGER NOT NULL REFERENCES web_customers(id),
  kind TEXT NOT NULL DEFAULT 'new',      -- new | renew | upgrade (сайт пока шлёт только new)
  tier TEXT NOT NULL REFERENCES tiers(name),
  provider TEXT NOT NULL,                -- mock | polar | lava | usdt
  method TEXT NOT NULL,                  -- то, что прислал сайт: card (Polar.sh) | lava (lava.top) | usdt (USDT, Solana)
  amount_minor INTEGER NOT NULL,         -- берётся из tiers.price_usd_minor на сервере, не от клиента
  currency TEXT NOT NULL DEFAULT 'USD',
  provider_ref TEXT UNIQUE,              -- id платежа/события у провайдера; UNIQUE = повтор вебхука не выдаст второй ключ
  status TEXT NOT NULL DEFAULT 'pending',-- pending | paid | failed | refunded | expired
  key_id TEXT REFERENCES api_keys(id),   -- выданный (new) или продлённый (renew) ключ; ставится при выдаче
  renew_key_id TEXT REFERENCES api_keys(id), -- для kind = renew: ключ, который продлевается (определяется при checkout)
  policies_accepted_at INTEGER NOT NULL, -- мс, серверные часы: галочка «Terms, Privacy, Refund, цифровая услуга активируется сразу»
  project_name TEXT NOT NULL,            -- 2..80
  project_type TEXT NOT NULL,            -- site | app | server | other (значения сайта)
  project_description TEXT NOT NULL,     -- 10..400
  origins TEXT,                          -- JSON-массив доменов; обязателен при project_type = site, иначе может быть NULL
  commercial INTEGER,                    -- NULL: сайт этого поля не шлёт, не требовать
  email_failed INTEGER NOT NULL DEFAULT 0,
  note TEXT,                             -- пометка владельцу: mismatch_amount | mismatch_currency | mismatch_tier | no_contract | banned | refunded | ...
  created_at INTEGER NOT NULL,           -- серверное время, мс
  paid_at INTEGER
);
CREATE INDEX IF NOT EXISTS web_orders_customer ON web_orders(customer_id);
CREATE INDEX IF NOT EXISTS web_orders_status ON web_orders(status);

CREATE TABLE IF NOT EXISTS contract_signatures (
  order_id INTEGER PRIMARY KEY REFERENCES web_orders(id),
  signer TEXT NOT NULL,                  -- 2..80
  terms_hash TEXT NOT NULL,              -- 12 hex: версия Terms of Service (contract.documents.terms = termsVersion)
  refund_hash TEXT NOT NULL,             -- 12 hex: версия Refund Policy (contract.documents.refund)
  privacy_hash TEXT NOT NULL,            -- 12 hex: версия Privacy Policy (contract.documents.privacy)
  signature_svg TEXT NOT NULL,           -- ТОЛЬКО после санитайзера (src/svg.js), ≤ 200 КБ
  client_signed_at TEXT,                 -- время браузера, не доверенное, хранится отдельно
  accepted_at INTEGER NOT NULL,          -- серверное время приёма условий, мс
  lang TEXT
);
