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
  kind TEXT NOT NULL DEFAULT 'new',      -- new | renew (повторная покупка того же проекта и тарифа) | upgrade (не используется)
  billing TEXT NOT NULL,                 -- subscription (Polar, card, ежемесячно) | one_time (lava, usdt: пакет на days дней)
  days INTEGER NOT NULL,                 -- срок оплаты: 30 для подписки, 30 | 90 | 180 | 365 для пакета
  tier TEXT NOT NULL REFERENCES tiers(name),
  provider TEXT NOT NULL,                -- mock | polar | lava | usdt
  method TEXT NOT NULL,                  -- то, что прислал сайт: card (Polar.sh) | lava (lava.top) | usdt (USDT, Solana)
  amount_minor INTEGER NOT NULL,         -- центы США; считает сервер (подписка: tiers.price_usd_minor, пакет: сетка src/pricing.js), не клиент
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

-- Подписка у провайдера (Polar: ежемесячная). Создаётся при первой оплате, дальше события продлевают ключ заказа-источника.
CREATE TABLE IF NOT EXISTS web_subscriptions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sub_ref TEXT NOT NULL UNIQUE,          -- id подписки у провайдера
  provider TEXT NOT NULL,
  order_id INTEGER NOT NULL REFERENCES web_orders(id), -- заказ-источник; ключ = web_orders.key_id
  status TEXT NOT NULL DEFAULT 'active', -- active | canceled (продлений не будет, доступ до конца периода) | revoked (доступ закончен)
  note TEXT,                             -- renewal_mismatch | key_revoked | ...
  created_at INTEGER NOT NULL,
  canceled_at INTEGER
);
CREATE INDEX IF NOT EXISTS web_subscriptions_order ON web_subscriptions(order_id);

-- Подтверждённые продления подписки (каждое = +1 месяц к тому же ключу). UNIQUE(provider_ref) = повтор вебхука ничего не продлевает.
CREATE TABLE IF NOT EXISTS web_renewals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  subscription_id INTEGER NOT NULL REFERENCES web_subscriptions(id),
  provider_ref TEXT NOT NULL UNIQUE,
  amount_minor INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'paid',   -- paid | refunded
  paid_at INTEGER NOT NULL
);

-- Напоминания «ключ-пакет заканчивается через 3 дня»: одно на значение expires_at, поэтому после продления напомнит снова.
CREATE TABLE IF NOT EXISTS web_reminders (
  key_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  sent_at INTEGER NOT NULL,
  PRIMARY KEY (key_id, expires_at)
);
