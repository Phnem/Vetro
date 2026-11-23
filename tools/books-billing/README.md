# vetro-books-billing

Платёжный Worker Vetro API: `https://billing.vetrostudio.bid/v1/billing/...`. Принимает заказ с сайта Vetro Studio (тариф, проект, подпись договора),
ведёт к оплате и после **подтверждённой вебхуком** оплаты выдаёт ключ API (`vk_<tier>_...`): страница успеха показывает его один раз, ещё он уходит письмом.
Задание и контракт с сайтом: `.scratch/billing/worker-brief.md`. Ключи и тарифы: `docs/adr/0003-api-access-keys-and-tiers.md`.

**Платёжные системы здесь не выбраны и не подключены.** Есть движок, интерфейс провайдера (`src/providers/index.js`) и переходник `mock`
для тестового режима без денег. Боевой приём денег включается только по слову владельца и после того, как он прочитал условия на сайте.

## Маршруты

| Маршрут | Что делает |
|---|---|
| `POST /v1/billing/checkout` | валидация → rate limit → метод настроен? → бан / `max_keys` → запись заказа и подписи → переходник → `{ orderId, url }` |
| `GET /v1/billing/order/{id}` | `{ status: pending\|paid\|failed }`; ключ в поле `key` только при первом чтении после `paid` (KV `REVEAL`, TTL 600 с) |
| `POST /v1/billing/webhook/{provider}` | подпись провайдера по сырому телу → сверка суммы, валюты и тарифа → идемпотентная выдача ключа или возврат |
| `GET/POST /v1/billing/test/pay` | страница «Оплатить (тест)»; только при `BILLING_MODE=test`, иначе 404 |
| `GET /v1/billing/health` | `{ ok, mode }` |

CORS строгий: эхо одного из `https://vetrostudio.bid`, `https://www.vetrostudio.bid`, `https://phnem.github.io`, иначе без заголовка. Ключа сайта и авторизации нет.
Адрес возврата `https://vetrostudio.bid/api.html?order=<orderId>` задан в коде (`src/http.js`), из тела запроса он не берётся.

## Тестовый режим (`BILLING_MODE=test`, сейчас так в `wrangler.toml`)

Любой `method` обслуживает `mock`: `checkout` возвращает `https://billing.vetrostudio.bid/v1/billing/test/pay?order=<id>`, кнопка «Pay (test)» проходит ту же
выдачу, что и вебхук, и возвращает на сайт. Ключи тестового режима: `owner = "web:test"`, `label` начинается с `TEST `, срок сутки; **отозвать после проверки**
(панель → «Ключи»). Они считаются в `max_keys` покупателя, пока активны и не истекли: для повторного прогона с тем же e-mail отзовите ключ или возьмите другой e-mail.
Письма в тестовом режиме не отправляются (нет транспорта = пропуск, не ошибка).

Вебхук mock (`/webhook/mock`) подписан HMAC-SHA256 от тела секретом `MOCK_WEBHOOK_SECRET` (заголовок `x-mock-signature`); нужен только тестам и ручной проверке.

## Схема

`schema/billing-001-tier-price.sql` (**один раз**, добавляет `tiers.price_usd_minor`: 300 / 700 / 1200) и `schema/billing.sql` (идемпотентно):
`web_customers`, `web_orders`, `contract_signatures`. Применять к боевой D1 только с ведома владельца, из `tools/books-api`:

```powershell
cd tools/books-api
npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing-001-tier-price.sql
npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing.sql
```

Отличие от брифа: в `web_orders` добавлена колонка `note` (пометка владельцу: `mismatch_amount`, `mismatch_currency`, `mismatch_tier`, `no_contract`, `banned`,
`duplicate_payment_ref`, `refunded`, `provider_failed`, `provider_error`). IP-адреса и user-agent не пишутся нигде.

## Выдача ключа (`src/issue.js`)

1. Событие провайдера → заказ по `checkout_ref`; сумма, валюта и тариф сверяются с заказом (несовпадение → `failed` + `note`, ключа нет).
2. Нужны принятые условия и подпись, покупатель не забанен.
3. Атомарно `pending → paid` (`WHERE status IN ('pending','expired') AND provider_ref IS NULL`, ровно одно `changes = 1`). Поздний платёж по `expired` тоже оплата.
4. Ключ → `api_keys` (условная вставка + `web_orders.key_id` одной транзакцией) → `REVEAL` + письмо. В D1 только хэш и префикс.
5. Возврат → заказ `refunded`, ключ `revoked`, копия в `REVEAL` удаляется. Cron раз в час: `pending` старше суток → `expired`.

Если вебхук упал между «оплачен» и «ключ записан», повтор той же доставки (тот же `providerRef`) доводит выдачу и не создаёт второй ключ.

## Секреты и привязки

Только через `npx wrangler secret put <ИМЯ>`; в репозиторий, сайт и APK они не попадают.

| Имя | Что |
|---|---|
| `MOCK_WEBHOOK_SECRET` | необязательный, только для проверки вебхука mock |
| `MAIL_API_KEY`, переменная `MAIL_FROM`, `MAIL_TRANSPORT=resend` | письма; транспорт ещё не выбран владельцем, Resend — первый переходник |
| ключи провайдеров и секреты их вебхуков | появятся вместе с переходниками |

Привязки: `DB` (D1 `vetro-books`), `REVEAL` (KV, `npx wrangler kv namespace create REVEAL`, id вписать в `wrangler.toml`), `LIMITER` и `LIMITER_CHECKOUT` (rate limit).

## Деплой

Нужен вход `npx wrangler login` (делает владелец). Порядок: схема → KV → `npx wrangler deploy` из этой папки. Чтобы приём денег случайно не включился,
`BILLING_MODE=test` стоит в `wrangler.toml`; боевой режим = любое другое значение.

## Хвосты

`VETRO-TAIL[billing-provider]` (новый платёжный переходник, `src/providers/index.js`) и `VETRO-TAIL[billing-mail]` (новый почтовый транспорт, `src/mail.js`).
Продление и смена тарифа (`kind = renew | upgrade`) в схеме есть, сайт их пока не шлёт, поэтому выдача обрабатывает только `new`.

## Тесты

```powershell
npm test
```

Фейковая D1 поверх `node:sqlite` (`test/helpers.js`), как у `tools/books-bot`.
