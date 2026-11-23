-- Цена тарифа в долларах для продажи на сайте. ВЫПОЛНЯТЬ ОДИН РАЗ: ALTER ... ADD COLUMN не идемпотентен.
-- Применять к боевой D1 только с ведома владельца:
--   npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing-001-tier-price.sql   (из tools/books-api)
-- access.js читает колонки tiers по именам, добавление безопасно.
ALTER TABLE tiers ADD COLUMN price_usd_minor INTEGER NOT NULL DEFAULT 0;   -- центы США за period_days; 0 = нет цены, не продавать
UPDATE tiers SET price_usd_minor = 300  WHERE name = 'basic';
UPDATE tiers SET price_usd_minor = 700  WHERE name = 'pro';
UPDATE tiers SET price_usd_minor = 1200 WHERE name = 'ultra';
