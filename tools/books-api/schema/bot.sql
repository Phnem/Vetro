-- Бот в Telegram и панель (ADR 0003): покупатели, заявки, заказы. Выполнять ПОСЛЕ schema/access.sql, идемпотентно.
--   npx wrangler d1 execute vetro-books --remote --file schema/bot.sql
-- Это общий контракт: бот ПИШЕТ в эти таблицы (одна и та же база D1 `vetro-books`, привязка DB), панель ЧИТАЕТ те же строки.
-- Отдельного «отправить запись в панель» нет: запись появилась в `applications` — она уже видна в панели.

CREATE TABLE IF NOT EXISTS customers (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  telegram_id  INTEGER NOT NULL UNIQUE,     -- message.from.id
  username     TEXT,                        -- message.from.username (может быть пустым)
  first_name   TEXT,
  language     TEXT,                        -- message.from.language_code
  email        TEXT,                        -- необязательный контакт
  banned       INTEGER NOT NULL DEFAULT 0,  -- 1 = бот ничего не выдаёт и не принимает
  created_at   INTEGER NOT NULL,            -- мс
  last_seen_at INTEGER
);

-- Заявка = то, что покупатель рассказал о своём проекте. Создаётся ДО оплаты, чтобы владелец видел её сразу.
CREATE TABLE IF NOT EXISTS applications (
  id               INTEGER PRIMARY KEY AUTOINCREMENT,
  customer_id      INTEGER NOT NULL REFERENCES customers(id),
  app_name         TEXT NOT NULL,           -- название приложения/проекта (до 80 символов)
  description      TEXT NOT NULL,           -- краткое описание (до 500 символов)
  kind             TEXT NOT NULL,           -- website | mobile | server | other
  origins          TEXT,                    -- JSON ["example.com"]; обязательно для kind = website, иначе NULL
  commercial       INTEGER NOT NULL DEFAULT 0,  -- 1 = коммерческое использование
  expected_per_day INTEGER,                 -- ориентир нагрузки (запросов в сутки); для рекомендации тарифа
  tier             TEXT NOT NULL REFERENCES tiers(name),   -- выбранный тариф
  status           TEXT NOT NULL DEFAULT 'new',  -- new | invoiced | paid | issued | rejected | expired | refunded
  terms_accepted_at INTEGER,                -- мс; NULL = условия не приняты, ключ не выдаётся
  note             TEXT,                    -- заметка владельца из панели (бот её не пишет)
  created_at       INTEGER NOT NULL,
  updated_at       INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS applications_customer ON applications(customer_id);
CREATE INDEX IF NOT EXISTS applications_status ON applications(status);

-- Заказ = один счёт в Telegram Stars. Новая покупка, продление или переход на другой тариф.
CREATE TABLE IF NOT EXISTS orders (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  application_id  INTEGER NOT NULL REFERENCES applications(id),
  customer_id     INTEGER NOT NULL REFERENCES customers(id),
  tier            TEXT NOT NULL REFERENCES tiers(name),
  kind            TEXT NOT NULL DEFAULT 'new',     -- new | renew | upgrade
  stars           INTEGER NOT NULL,                -- цена из tiers.price_stars на момент счёта
  currency        TEXT NOT NULL DEFAULT 'XTR',
  invoice_payload TEXT NOT NULL UNIQUE,            -- случайная строка в sendInvoice: по ней находим заказ в pre_checkout/successful_payment
  charge_id       TEXT UNIQUE,                     -- telegram_payment_charge_id; NULL, пока не оплачен. UNIQUE = повтор доставки не выдаст второй ключ
  status          TEXT NOT NULL DEFAULT 'pending', -- pending | paid | refunded | failed
  key_id          TEXT REFERENCES api_keys(id),    -- выданный или продлённый ключ
  created_at      INTEGER NOT NULL,
  paid_at         INTEGER
);
CREATE INDEX IF NOT EXISTS orders_application ON orders(application_id);
CREATE INDEX IF NOT EXISTS orders_status ON orders(status);
