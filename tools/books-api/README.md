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

## Тесты

```powershell
npm test      # node --test: подставные D1 (настоящий SQLite + FTS5) и R2; нормализация сверяется с golden_norm.json
```
