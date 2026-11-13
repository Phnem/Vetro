# vetro-books-bot

Telegram-бот для выдачи, продления и управления ключами доступа к API каталога книг Vetro (`api.vetrostudio.bid`, ADR 0003).
Работает как отдельный Cloudflare Worker поверх общей базы D1 `vetro-books`.

## Возможности

- Выбор языка при `/start` (English / Русский) с сохранением в `customers.language` и `bot_sessions`.
- Постоянное кнопочное меню и inline-кнопки на всех шагах.
- 8-шаговая анкета заявки (`applications`):
  1. `app_name` (до 80 символов)
  2. `description` (до 500 символов)
  3. `kind` (`website | mobile | server | other`)
  4. `origins` (обязательно для `website`, домены через запятую без схемы и пути; `NULL` для остальных)
  5. `commercial` (`0 | 1`)
  6. `expected_per_day` (необязательно; по нему бот рекомендует тариф)
  7. `tier` (тарифы читаются из `tiers WHERE sold = 1 ORDER BY sort`, с кнопкой «Подробнее о тарифах»)
  8. `terms_accepted_at` (условия использования с лимитами выбранного тарифа из `tiers`; без согласия заявка и счёт не создаются)

> **Важно:** перед выкладкой в продакшен владелец должен прочитать и при необходимости уточнить текст условий в `src/i18n.js` (`termsText`).

- Оплата в **Telegram Stars** (`currency = 'XTR'`, пустой `provider_token`, сумма из `tiers.price_stars`).
- Проверка `pre_checkout_query`, идемпотентная обработка `successful_payment` по `orders.charge_id` (`UNIQUE`), поддержка возвратов (`refunded_payment` и `/refund` для владельца).
- Однократный показ открытого ключа `vk_<tier>_<32>` в блоке кода; в D1 хранится только `SHA-256` и префикс (`newApiKey` / `sha256Hex` по контракту `tools/books-api/src/access.js`).
- Защита от спама: не более 3 открытых заявок (`new` / `invoiced`) на покупателя одновременно; не более 30 сообщений в минуту с одного `chat_id` (сверх лимита молча игнорируется); серверная проверка длин всех полей анкеты.
- Команды: `/start`, `/plans`, `/buy`, `/key`, `/rotate`, `/usage` (маршрут `_denied` показывается отдельной строкой «Отклонено» / «Denied» и не входит в общий расход), `/revoke`, `/lang`, `/cancel`, `/help` (+ `/refund` для владельца).

## Секреты

Все секреты передаются только через `wrangler secret put` и в репозиторий не попадают:

| Секрет | Назначение |
|---|---|
| `TELEGRAM_BOT_TOKEN` | Токен бота от `@BotFather` для вызовов Telegram Bot API |
| `TELEGRAM_WEBHOOK_SECRET` | Случайная строка для проверки заголовка `X-Telegram-Bot-Api-Secret-Token` на `/webhook` |
| `OWNER_TELEGRAM_ID` | Числовой Telegram ID владельца для уведомлений о заявках/оплатах и команды `/refund` |

## Очередь уведомлений `bot_outbox` и cron

Панель владельца (`vetro-books-admin`) не ходит в Telegram напрямую: она добавляет строки в таблицу `bot_outbox` (`chat_id`, готовый HTML `text`, `created_at`, `sent_at = NULL`, `attempts = 0`).

В `wrangler.toml` настроен cron-триггер `* * * * *` (раз в минуту), вызывающий обработчик `scheduled` (`src/outbox.js`):

1. Выбирает из `bot_outbox` до 20 записей с `sent_at IS NULL` по возрастанию `id`.
2. Отправляет каждую через `sendMessage` с `parse_mode = "HTML"`.
3. При успехе выставляет `sent_at = <timestamp>` и очищает `error`.
4. При ошибке увеличивает `attempts` на `1` и сохраняет текст ошибки в `error`.
5. После **5 неудачных попыток** выставляет `sent_at = <timestamp>`, оставляя последнюю `error` (запись больше не повторяется).
6. При ошибке `403` («bot was blocked by the user») **не повторяет** отправку: сразу выставляет `sent_at` и сохраняет `error`.

## Порядок деплоя

Предполагается, что `tools/books-api/schema/access.sql` и `tools/books-api/schema/bot.sql` (включая `bot_outbox`) уже применены к базе D1 `vetro-books`.

```powershell
cd tools/books-bot

# 1. Применить схему состояния и антиспама (bot_sessions, bot_rate_limits)
npx wrangler d1 execute vetro-books --remote --file schema/sessions.sql

# 2. Задать 3 секрета Worker
npx wrangler secret put TELEGRAM_BOT_TOKEN
npx wrangler secret put TELEGRAM_WEBHOOK_SECRET
npx wrangler secret put OWNER_TELEGRAM_ID

# 3. Задеплоить Worker (и cron-триггер)
npm run deploy

# 4. Зарегистрировать webhook и меню команд EN/RU в Telegram
$env:TELEGRAM_BOT_TOKEN = "<ваш_токен>"
$env:TELEGRAM_WEBHOOK_SECRET = "<тот_же_секрет_что_в_wrangler>"
$env:WEBHOOK_URL = "https://vetro-books-bot.<subdomain>.workers.dev/webhook"
node scripts/setup-bot.mjs
```

## Тесты

```powershell
npm test
```
