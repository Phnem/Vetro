# vetro-books-bot

Telegram-бот для выдачи, продления и управления ключами доступа к API каталога книг Vetro (`api.vetrostudio.bid`, ADR 0003).
Работает как отдельный Cloudflare Worker поверх общей базы D1 `vetro-books`.

## Возможности

- Выбор языка при `/start` (English / Русский) с сохранением в `customers.language` и `bot_sessions`.
- Постоянное кнопочное меню + inline-кнопки на всех шагах.
- 8-шаговая анкета заявки (`applications`):
  1. `app_name` (до 80 символов)
  2. `description` (до 500 символов)
  3. `kind` (`website | mobile | server | other`)
  4. `origins` (обязательно для `website`, список доменов через запятую без схемы и пути; `NULL` для остальных)
  5. `commercial` (`0 | 1`)
  6. `expected_per_day` (необязательно; по нему бот рекомендует тариф)
  7. `tier` (тарифы читаются из `tiers WHERE sold = 1 ORDER BY sort`, с кнопкой «Подробнее о тарифах»)
  8. `terms_accepted_at` (условия использования API; без согласия заявка и счёт не создаются)
- Оплата в **Telegram Stars** (`currency = 'XTR'`, пустой `provider_token`, сумма из `tiers.price_stars`).
- Проверка `pre_checkout_query`, идемпотентная обработка `successful_payment` по `orders.charge_id` (`UNIQUE`), поддержка возвратов (`refunded_payment` и `/refund` для владельца).
- Однократный показ открытого ключа `vk_<tier>_<32>` в блоке кода; в D1 хранится только `SHA-256` и префикс.
- Команды: `/start`, `/plans`, `/buy`, `/key`, `/rotate`, `/usage`, `/revoke`, `/lang`, `/cancel`, `/help` (+ `/refund` для владельца).

## Секреты (в репозиторий не попадают)

```powershell
cd tools/books-bot
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put TELEGRAM_WEBHOOK_SECRET   # случайная строка для X-Telegram-Bot-Api-Secret-Token
npx wrangler secret put OWNER_TELEGRAM_ID         # Telegram user ID владельца для уведомлений и /refund
```

## База D1

Предполагается, что `tools/books-api/schema/access.sql` и `tools/books-api/schema/bot.sql` уже применены к `vetro-books`:

```powershell
npx wrangler d1 execute vetro-books --remote --file schema/sessions.sql
```

(Таблица `bot_sessions` также создаётся автоматически при первом обращении.)

## Деплой и регистрация webhook

```powershell
npm run deploy

$env:TELEGRAM_BOT_TOKEN = "<ваш_токен>"
$env:TELEGRAM_WEBHOOK_SECRET = "<тот_же_секрет_что_в_wrangler>"
$env:WEBHOOK_URL = "https://vetro-books-bot.<subdomain>.workers.dev/webhook"
node scripts/setup-bot.mjs
```

## Тесты

```powershell
npm test
```
