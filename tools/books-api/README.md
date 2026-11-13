# vetro-books-api

Cloudflare Worker для `https://api.vetrostudio.bid`: каталог книг Vetro. Только чтение, без аккаунтов, ничего о
вызывающем не сохраняется (`observability` выключена; лимитер запросов не хранит IP).

| Маршрут | Что отдаёт |
|---|---|
| `GET /v1/health` | `{"ok":true}` |
| `GET /v1/manifest` | манифест телефонного индекса (R2 `index/manifest.json`), ETag + `max-age=300` |
| `GET /v1/index/{base\|delta}/<файл>` | версионные файлы индекса (zstd), `immutable`, поддерживается `Range` |
| `GET /v1/search?q=&limit=` | FTS5 по всему серверному каталогу (268 840 произведений, D1): `{items:[{id,ru,en,ra,ea,y,f,cv}]}` |
| `GET /v1/books/{id9}` | книга и карточка деталей (оригинал, переводы, серии, издания, внешние id, источник обложки) |
| `GET /v1/books?ids=1,2,3` | до 40 строк без деталей |
| `GET /covers/{small\|large}/{id9}.avif?v={cv}` | обложки из R2, `immutable` (версия `cv` лежит в индексе) |

`/v1/search` и `/v1/books*` ходят в D1: ответы кэшируются на краю (10 минут / сутки) и ограничены лимитером
(120 запросов в минуту на клиента; привязка `LIMITER`, без неё лимита нет).

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
