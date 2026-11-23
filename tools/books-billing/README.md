# vetro-books-billing

Платёжный Worker Vetro API: `https://billing.vetrostudio.bid/v1/billing/...`. Принимает заказ с сайта Vetro Studio (тариф, проект, подпись договора),
ведёт к оплате и после **подтверждённой вебхуком** оплаты выдаёт ключ API (`vk_<tier>_...`): страница успеха показывает его один раз, ещё он уходит письмом.
Задание и контракт с сайтом: `.scratch/billing/worker-brief.md`. Ключи и тарифы: `docs/adr/0003-api-access-keys-and-tiers.md`.

**Платёжные системы здесь не выбраны и не подключены.** Есть движок, интерфейс провайдера (`src/providers/index.js`) и переходник `mock`
для тестового режима без денег. Боевой приём денег включается только по слову владельца и после того, как он прочитал условия на сайте.

## Маршруты

| Маршрут | Что делает |
|---|---|
| `POST /v1/billing/checkout` | валидация → rate limit → метод настроен? → бан / продление / `max_keys` → запись заказа и подписи → переходник → `{ orderId, url }` |
| `GET /v1/billing/order/{id}` | `{ status: pending\|paid\|failed }`; ключ в поле `key` только при первом чтении после `paid` (KV `REVEAL`, TTL 600 с); у продления `{ status: "paid", renewed: true }` без ключа |
| `POST /v1/billing/webhook/{provider}` | подпись провайдера по сырому телу → сверка суммы, валюты и тарифа → идемпотентная выдача ключа или возврат |
| `GET/POST /v1/billing/test/pay` | страница «Оплатить (тест)»; только при `BILLING_MODE=test`, иначе 404 |
| `GET /v1/billing/health` | `{ ok, mode }` |

CORS строгий: эхо одного из `https://vetrostudio.bid`, `https://www.vetrostudio.bid`, `https://phnem.github.io`, иначе без заголовка. Ключа сайта и авторизации нет.
Адрес возврата `https://vetrostudio.bid/api.html?order=<orderId>` задан в коде (`src/http.js`), из тела запроса он не берётся.

## Модели оплаты и поля запроса

| method | Провайдер | Модель | Срок |
|---|---|---|---|
| `card` | Polar.sh | ежемесячная **подписка** (Recurring Monthly), каждое подтверждённое продление = +1 месяц тому же ключу | 30 дней, `days = 30` |
| `lava` | lava.top | **предоплаченный пакет**, без автопродления | `days` = 30 / 90 / 180 / 365 |
| `usdt` | USDT, сеть Solana (только дешёвые сети) | пакет, как `lava` | 30 / 90 / 180 / 365 |

Провайдеры `polar`, `lava`, `usdt` **пока не подключены** (хвост `billing-provider`): в боевом режиме любой метод даёт `503 method_unavailable`.
В тестовом режиме все три обслуживает `mock`.

Тело `checkout`: `plan`, `method`, `billing` (`subscription` для `card`, `one_time` для `lava`/`usdt`, иначе `400 bad_field`, `field: "billing"`), `days`
(`30` для подписки, `30|90|180|365` для пакета, иначе `bad_field`, `field: "days"`), `expectedAmountUsd` (необязателен, только сверка: расхождение с серверной ценой = `400 price_mismatch`),
`lang`, `policiesAccepted: true`, `email`, `project`, `contract` (`signer`, `signedAt`, `termsVersion`, `documents { terms, refund, privacy }`, `signatureSvg`).

**Цена считается на сервере** (`src/pricing.js`, USD): подписка = `tiers.price_usd_minor` (Basic 3, Pro 7, Ultra 12 в месяц); пакеты:

| | 30 | 90 | 180 | 365 |
|---|---|---|---|---|
| basic | 3 | 9 | 15 | 29 |
| pro | 7 | 20 | 40 | 75 |
| ultra | 12 | 35 | 69 | 129 |

`policiesAccepted` должен быть ровно `true`, время принятия хранится как `web_orders.policies_accepted_at` по серверным часам. Версии трёх документов
(12 hex, `terms` равна `termsVersion`) хранятся в `contract_signatures.terms_hash / refund_hash / privacy_hash`.

## Доступ, срок и квоты

Доступ один для всех: ключ работает, пока `status = active` и `expires_at > now`. Лимиты тарифа (в секунду, в минуту, в сутки UTC) посуточные и от срока **не зависят**:
месячных счётчиков и пула квоты нет ни в `tiers`, ни в `api_keys`, ни в `usage_daily` (проверяет тест).

- **Пакет:** новый ключ живёт `paid_at + days`. Повторная покупка **того же проекта (название) на том же тарифе** при не отозванном ключе = заказ `kind = renew`:
  новый ключ не создаётся, `max_keys` не проверяется, `expires_at = max(now, expires_at) + days` (считается в SQL). Другой тариф или проект = новый ключ.
- **Подписка:** первый платёж создаёт ключ и запись `web_subscriptions`; событие `renewed` продлевает **тот же** ключ (запись в `web_renewals`, `UNIQUE(provider_ref)`: повтор вебхука
  ничего не продлевает). Если провайдер прислал конец оплаченного периода (`periodEnd`), срок = `max(текущий, periodEnd + 1 день запаса)`; без него +30 дней. `canceled`: продлений нет, ключ
  работает до конца оплаченного периода (срок не трогаем). `revoked`: подписка закрыта в учёте, срок тоже не трогаем (его могли продлить пакетом), доступ закончится сам.
  Пока подписка активна, новый заказ на тот же ключ даёт `409 subscription_active`. Сумма продления сверяется с суммой первого заказа (`renewal_mismatch` = не продлеваем).
- Страница заказа у продления: `{ status: "paid", renewed: true }` без ключа.

## Письма и cron

Письма через Resend (`MAIL_TRANSPORT=resend`, домен отправки подтверждает владелец): при выдаче ключ, срок и лимиты; при продлении короткое уведомление; **для пакетов** напоминание за 3 дня
до конца (раз на значение `expires_at`, после продления напомнит снова; подписке напоминание не нужно). Тексты письма о продлении и напоминания — **черновики**, согласовать с сайтом.
Cron раз в час: `pending` старше суток → `expired`, напоминания, очистка счётчиков.

## Возвраты, хранение, логи

Полный возврат: в течение 7 дней с платежа и только при 0 запросов по ключу (подписка и пакеты любой длины; продление подписки — допущение сайта), а также простой больше 72 часов и двойное списание.
Частичных возвратов за неиспользованное время нет. USDT не возвращается (вручную, минус комиссия сети). Сам возврат делает провайдер (Polar / lava / шлюз); его событие `refunded`
отзывает ключ, ставит заказ в `refunded` и **отменяет подписку у провайдера** (`cancelSubscription`; не вышло: `note = refunded_cancel_failed`). Возврат платежа-продления отнимает его период
(ключ отзывается, если срока не осталось). Правила применяет владелец: в панели у заказа сайта видны число запросов по ключу, подсказка «7 дней и 0 запросов» и кнопка «Возврат вручную» (USDT, простой, двойное списание).

Платёжные записи (`web_*`) не удаляются никогда (хранить 7 лет). IP и user-agent не сохраняются нигде и не пишутся в логи (логи Worker выключены; IP живёт только как ключ счётчика rate limit).
Счётчики по ключам (`usage_daily`) очищает cron, **только если задана переменная `USAGE_RETENTION_DAYS`** (по политике 60; по умолчанию не задана, ничего не удаляется).

## Тестовый режим (`BILLING_MODE=test`, сейчас так в `wrangler.toml`)

Любой `method` обслуживает `mock`: `checkout` возвращает `https://billing.vetrostudio.bid/v1/billing/test/pay?order=<id>`, кнопка «Pay (test)» проходит ту же
выдачу, что и вебхук, и возвращает на сайт. Ключи тестового режима: `owner = "web:test"`, `label` начинается с `TEST `, срок сутки; **отозвать после проверки**
(панель → «Ключи»). Они считаются в `max_keys` покупателя, пока активны и не истекли: для повторного прогона с тем же e-mail отзовите ключ или возьмите другой e-mail.
Письма в тестовом режиме не отправляются (нет транспорта = пропуск, не ошибка).

У заказа-подписки страница «Pay (test)» после оплаты показывает кнопки «Renewal (test)» и «Cancel subscription (test)»: те же события, что пришлёт Polar.

Вебхук mock (`/webhook/mock`) подписан HMAC-SHA256 от тела секретом `MOCK_WEBHOOK_SECRET` (заголовок `x-mock-signature`); нужен только тестам и ручной проверке.

## Схема

`schema/billing-001-tier-price.sql` (**один раз**, добавляет `tiers.price_usd_minor`: 300 / 700 / 1200) и `schema/billing.sql` (идемпотентно):
`web_customers`, `web_orders`, `contract_signatures`. Применять к боевой D1 только с ведома владельца, из `tools/books-api`:

```powershell
cd tools/books-api
npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing-001-tier-price.sql
npx wrangler d1 execute vetro-books --remote --file ../books-billing/schema/billing.sql
```

Отличие от брифа: в `web_orders` добавлены колонки `billing`, `days`, `note`, `renew_key_id`, `policies_accepted_at`, в `contract_signatures` — `refund_hash` и `privacy_hash`, новые таблицы `web_subscriptions`, `web_renewals`, `web_reminders`. `note` (пометка владельцу: `mismatch_amount`, `mismatch_currency`, `mismatch_tier`, `no_contract`, `banned`,
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
Смена тарифа на существующем ключе (`upgrade`) не реализована: другой тариф = новый ключ.

## Тесты

```powershell
npm test
```

Фейковая D1 поверх `node:sqlite` (`test/helpers.js`), как у `tools/books-bot`.
