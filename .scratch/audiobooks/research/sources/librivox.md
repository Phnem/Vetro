# LibriVox — AB-05 якорь

Проверено 2026-09-23 по [официальной API-справке](https://librivox.org/api/info), [объявлению о лимитах](https://librivox.org/2026/09/16/librivox-api-update/) и живым ответам `id=52`, `audiotracks?project_id=52`, `title=^all&limit=2`. Фикстуры: `app/src/test/resources/audiobooks/librivox/`; развёрнутые свидетельства в `research/source-anchors.md`.

- Публичный каталог, `GET /api/feed/audiobooks` с `title`, `author`, `genre`, `id`, `limit/offset`, `format=json`; детали с `extended=1&coverart=1`, секции и чтецы также через `/api/feed/audiotracks`. Для книги 52 есть 57 секций, `listen_url`, ссылка `url_iarchive` на IA item. В фикстуре оставлены две секции без описания.
- Поиск `title=letters` в этот день вернул HTTP 404 `Audiobooks could not be found`, хотя книга 52 называется `Letters of Two Brides`; `title=^all` вернул HTTP 200. Поэтому адаптер должен считать 404 пустым результатом, а качество title search проверить отдельно.
- Объявлен максимум `limit=500` и паузы в несколько секунд; код должен ограничивать частоту, кэшировать и обрабатывать 429. Range/долговечность `listen_url` не проверены, медиа не загружалось.
- `InfrastructureGroup=librivox.org` для каталога; часть медиа/архива может идти с `archive.org`, группу варианта определить по факту. `SEARCH`, `DETAILS`, `NARRATIONS`, `CHAPTERS`, потенциально `STREAM`/`DOWNLOAD`. `RIGHTS_VERIFIED` только после проверки конкретной записи и региона: [правило LibriVox о public domain](https://librivox.org/pages/public-domain/) относится к США и предупреждает о других юрисдикциях.

