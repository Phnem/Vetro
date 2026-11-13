# vetro-books-api

Cloudflare Worker для `https://api.vetrostudio.bid`: каталог книг Vetro. Только чтение, без аккаунтов, ничего о
вызывающем не сохраняется (`observability` выключена; лимитер запросов не хранит IP).

| Маршрут | Что отдаёт |
|---|---|
| `GET /v1/health` | `{"ok":true}` |
| `GET /v1/manifest` | манифест телефонного индекса (R2 `index/manifest.json`), ETag + `max-age=300` |
| `GET /v1/index/{base\|delta}/<файл>` | версионные файлы индекса (zstd), `immutable`, поддерживается `Range` |
| `GET /v1/search?q=&limit=` | FTS5 по всему серверному каталогу (268 840 произведений, D1): `{items:[{id,ru,en,ra,ea,y,f,cv}]}` |
| `GET /v1/books/{id9}` | книга и карточка деталей `detail`: `orig`, `type`, `alts`, `authors`, `series`, `tr`, `ed`, `ext`, `cover` и (с 2026-11-13) `desc{t,l}` описание, `gen[]` ключи жанров, `ol` язык оригинала, `coll` сборник, `ch` главы, `dur` типичная длина (мин), `rate{v,n}` оценка FantLab. Все ключи необязательны; жанры — канонические ключи (`scifi`, `fantasy`…), названия на стороне клиента. Сборка: `book-dictionary/v2/build/details.py` |
| `GET /v1/books?ids=1,2,3` | до 40 строк без деталей |
| `GET /covers/{small\|large}/{id9}.avif?v={cv}` | обложки из R2, `immutable` (версия `cv` лежит в индексе) |

`/v1/search` и `/v1/books*` ходят в D1: ответы кэшируются на краю (10 минут / сутки) и ограничены лимитером
(120 запросов в минуту на клиента; привязка `LIMITER`, без неё лимита нет).

## Доступ по ключам (docs/adr/0003-api-access-keys-and-tiers.md)

Всё, кроме `/v1/health`, закрывается ключом при `ACCESS_MODE = "enforce"` (в `wrangler.toml` сейчас `"open"`: ключ необязателен, но если пришёл,
проверяется). Ключ передаётся заголовком `Authorization: Bearer vk_…` или `X-Api-Key`; для `<img>` на обложках допустим `?key=`.

| Тариф | В секунду | В минуту | В сутки | `limit` / `ids` | Обложки | Индекс | Цена (⭐ за 30 дней, заготовка) |
|---|---|---|---|---|---|---|---|
| basic | 2 | 30 | 2 000 | 20 / 10 | нет | нет | 100 |
| pro | 5 | 120 | 20 000 | 50 / 40 | да | нет | 400 |
| ultra | 20 | 600 | 200 000 | 50 / 40 | да | да | 1 500 |
| app (не продаётся) | – | 120 | – | 50 / 40 | да | да | – |

Лимиты и цены лежат в таблице `tiers` и правятся без деплоя (кэш на краю 60 с). Отказы — JSON `{"error": …}`: `401 missing_key | invalid_key |
expired_key`, `403 key_revoked | key_expired | origin_not_allowed | tier_forbids_route`, `429 rate_limited` (поля `scope`: `second|minute|day`,
`retry_after`, заголовок `Retry-After`). Успешные ответы несут `X-RateLimit-Limit/Remaining-Minute|Day`. `limit=` и `ids=` сверх потолка тарифа
обрезаются, а не отклоняются.

```powershell
npx wrangler d1 execute vetro-books --remote --file schema/access.sql              # один раз: тарифы, ключи, расход
node scripts/new-key.mjs pro --label site --owner site --origins vetrostudio.bid,phnem.github.io --days 365   # ключ + INSERT
npx wrangler d1 execute vetro-books --remote --command "<напечатанный INSERT>"      # ключ показывается один раз, в базе только хэш
```

Ключ для страниц сайта виден в браузере, поэтому для него обязателен `--origins`: он принимается только с этих сайтов. Учёт по ключам ведёт Durable
Object `KeyMeter` (расход по маршрутам пишется в `usage_daily` раз в минуту). Бот в Telegram и панель — фазы 3–4 ADR 0003.

### Токен установки приложения (аттестация ключа Android)

Приложение получает токен `va_…` (30 дней), доказав серверу, что это настоящий APK, а не скрипт:

```
GET  /v1/auth/challenge                 → {"challenge": "...", "expires_in": 120}
POST /v1/auth/register {"challenge", "chain": [base64 DER, лист первым]}
                                        → {"token": "va_...", "expires_at": ms, "install_id": "..."}
```

Телефон создаёт ключ в аппаратном хранилище с этим `challenge`, цепочка идёт до корня Google (`src/attest-roots.js`, обновление:
`node scripts/fetch-attestation-roots.mjs`). В листе записаны пакет, SHA-256 подписи APK, уровень защиты и состояние загрузчика. Токен выдаётся,
если: цепочка и подписи верны, `challenge` свежий и тот же, защита TEE/StrongBox (не программная), пакет и **все** подписи есть в `app_signatures`,
загрузчик не в состоянии `failed` (`ATTEST_REJECT_BOOT_STATES` ужесточает: `failed,unverified` не пустит и телефоны с разблокированным загрузчиком).
Отказ: `403 {"error":"attestation_failed","reason":…}` (`bad_signature`, `untrusted_root`, `certificate_expired`, `challenge_mismatch`,
`software_attestation`, `package_not_allowed`, `signature_not_allowed`, `boot_state_rejected`…), у приложения в этом случае остаются старые источники.
Установку можно отозвать: `UPDATE installs SET status = 'revoked' WHERE id = '…'` (действует в пределах минуты, повторная регистрация закрыта).

Секрет подписи токенов и заданий — `APP_TOKEN_SECRET`; задаёт владелец и в репозиторий он не попадает:

```powershell
npx wrangler secret put APP_TOKEN_SECRET      # длинная случайная строка, например: node -e "console.log(require('crypto').randomBytes(48).toString('base64url'))"
node scripts/add-signature.mjs com.phnem.vetro <sha256 подписи релиза> release     # → INSERT для app_signatures (wrangler d1 execute ... --command)
```

Проверено на Xiaomi 13 (Android 16): цепочка из 5 сертификатов, корень «Key Attestation CA1», защита TEE, KeyMint 200; её копия лежит в
`test/fixtures/attestation-xiaomi13.json`, на ней идут тесты (`test/attest.test.js`, `test/auth.test.js`). Промежуточные сертификаты Google
короткоживущие, поэтому проверка срока идёт по времени снятия фикстуры.

## Выкладка

1. Собрать данные: `book-dictionary/v2/README.md` (получатся `out/manifest.json`, `out/index/*`, `out/server/*`).
2. Один раз войти: `npx wrangler login` (откроется браузер; токены никуда не вводятся).
3. Из этой папки:

```powershell
.\scripts\deploy-data.ps1                # бакет vetro-books, база D1 vetro-books, каталог, индекс, обложки (~7.5 ГБ), Worker
.\scripts\deploy-data.ps1 -SkipCovers    # без обложек (их можно догрузить позже тем же скриптом)
.\scripts\deploy-data.ps1 -IndexOnly     # только новая версия индекса и манифест (после build_index.py)
```

Скрипт сам вписывает `database_id` в `wrangler.toml`. Домен `api.vetrostudio.bid` привязывается маршрутом
`custom_domain = true` из `wrangler.toml` (нужна зона `vetrostudio.bid` в этом же аккаунте Cloudflare). Если
`wrangler deploy` не смог привязать домен — удалить блок `[[routes]]` и сделать руками: Workers & Pages → Worker
`vetro-books-api` → Settings → Domains & Routes → Add → Custom Domain → `api.vetrostudio.bid`.

Проверка: `https://api.vetrostudio.bid/v1/health`, `/v1/manifest`, `/v1/search?q=оруэлл`.

### Обновление каталога (новая сборка, без простоя)

`deploy-data.ps1` — первичная выкладка (создаёт бакет и базу). Для новой версии данных — `refresh-data.ps1`: живые таблицы не
трогаются, пока всё не загружено, поэтому API отвечает на всём протяжении. Сборка входных файлов — `book-dictionary/v2/README.md`
(«Обновление каталога»).

```powershell
.\scripts\refresh-data.ps1 -Covers      # только новые обложки (covers-new-*.json), по кускам с отметками; `wrangler r2 bulk put`, ~3 файла/с
.\scripts\refresh-data.ps1 -Load        # D1: works_next + fts_next рядом с боевыми (~10 минут), возобновляется по файлам
.\scripts\refresh-data.ps1 -Publish     # файлы индекса телефона → обмен works_next→works, fts_next→fts → манифест (всегда последним)
```

`-Covers` и `-Load` можно запускать одновременно, `-Publish` — только когда оба закончились (иначе в карточках будут ссылки на
обложки, которых ещё нет в бакете). Каждый вызов wrangler повторяется при 429/5xx. Проверено 2026-11-13: переименование таблицы FTS5
в D1 работает (`ALTER TABLE fts_next RENAME TO fts`), поэтому обмен занимает доли секунды.

### Быстрая заливка обложек (временный Worker)

`wrangler r2 bulk put` ходит через REST API Cloudflare и упирается в `429` (≈4 файла/с, ~10 часов на 176 тысяч).
Через привязку R2 внутри Worker лимита нет (≈60 файлов/с, около 45 минут). Порядок, как это было сделано 2026-11-12:

1. `uploader/` — временный Worker с записью в бакет по секрету `TOKEN` (`wrangler deploy`, затем `wrangler secret put TOKEN`).
2. `book-dictionary/v2/tools/upload_covers.py --base <url uploader> --token-file … --lists covers-small.json covers-large.json`
   (возобновляется по `covers-uploaded.txt`; нужен свой User-Agent — Cloudflare режет `Python-urllib` ошибкой 1010).
3. `wrangler delete vetro-books-uploader --force` и стереть файл токена. Постоянно держать такой Worker нельзя: он пишет в бакет.

### Домен

`api.vetrostudio.bid` привязан с 2026-11-13: регистрация `vetrostudio.bid` (Cloudflare Registrar) переехала на аккаунт с
Worker, `[[routes]]` с `custom_domain = true` включён в `wrangler.toml`, `npx wrangler deploy` ответил `api.vetrostudio.bid
(custom domain)`. (Раньше деплой отвечал `Can't infer zone`: зона жила на другом аккаунте.) Запасной адрес
`https://vetro-books-api.<поддомен аккаунта>.workers.dev` оставлен (`workers_dev = true`, корневой ключ: после заголовка
`[[routes]]` он стал бы полем маршрута и деплой упал бы).

## Тесты

```powershell
npm test      # node --test: подставные D1 (настоящий SQLite + FTS5) и R2; нормализация сверяется с golden_norm.json
```
