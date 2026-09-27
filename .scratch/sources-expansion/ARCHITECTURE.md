# Расширение источников Vetro — предложение архитектуры

Основано на `RESEARCH.md` и текущем коде. Параллельной системы не строим: обогащение встраивается
в существующие Details, плеер, пропуск заставок, Книги и кэш-сторы; провайдеры контента — развитие
уже существующих `VetroSourceManifest` + `CustomSourceRegistry` + `ProviderHealth`.

## Что уже есть (и остаётся опорой)

| Слой | Код | Как используем |
|---|---|---|
| Сущность коллекции | `data/models/Anime.kt`: `anilistId, malId, shikimoriId, tmdbId, kinopoiskId, imdbId` | ключи идентичности; новых колонок БД не вводим — недостающие ID (TVDB, TVmaze, Open Library) живут в кэше обогащения |
| Метаданные | `VetroApiService`, `AniListRemoteDataSource`, `ShikimoriRemoteDataSource`, `TmdbRemoteDataSource`, `KinopoiskRemoteDataSource` | базовая карточка Details (`fetchDetails`) не меняется |
| Сеть | общий OkHttp → Ktor, лимитер на хост (`TokenBucketRateLimiter`), `LookupResult`, `executeHttpLookup` | все новые клиенты — так же |
| Пропуск заставок | `SkipSegmentResolver` (AniSkip, сверка длительности), `MediaSkipCoordinator` | добавляем источники за тем же `SkipSegmentResolution` |
| Субтитры | `VetroVideo.subtitles` → `MediaItem.SubtitleConfiguration` | внешние дорожки добавляются к встроенным, не заменяя их |
| Кэш | `JsonMapFileStore` (файл JSON, `ensureLoaded`, `update`) | стор обогащения — тот же механизм |
| Здоровье провайдеров | `ProviderHealth`/`ProviderHealthPolicy` (бэкофф, штраф) | тот же принцип для API обогащения |
| Источники контента | `VetroSourceManifest` (декларативный JSON), `CustomSourceRegistry`, Stremio-аддоны, `PlaybackProviderCascade` | база Provider SDK |

## Часть A. Слой обогащения

### Принципы

1. **Одна сущность в UI.** Экран видит `Enrichment` для тайтла; откуда поле — знает только слой.
2. **Для каждого поля — один главный источник и упорядоченные запасные.** Параллельно — только то,
   что нужно экрану сразу; остальное — лениво, по месту использования.
3. **Кэш с разным сроком для разных полей, отдача устаревшего сразу + фоновое обновление.**
   Отрицательные ответы тоже кэшируются, чтобы не долбить API.
4. **Ключ не задан — модуль выключен молча.** Никаких ошибок в UI из-за отсутствия ключа.
5. **Бюджеты и выключатель.** Для API с суточной квотой (YouTube search, NYT, OMDb, Google Books)
   — локальный счётчик; после серии сбоев провайдер выключается на время (как `ProviderHealth`).
6. **Происхождение (provenance).** Каждое поле несёт `source`, `fetchedAt`, `confidence` —
   для отладки в журнале, в UI не протекает.

### Идентичность

`ExternalIds` тайтла собирается один раз и кэшируется на 30 дней:

```
Anime (anilist, mal, shiki, tmdb, kinopoisk, imdb)
  └─ TMDb external_ids ──────────► imdb, tvdb
  └─ TVmaze lookup?imdb=/thetvdb= ► tvmaze
  └─ AniList idMal / Shikimori myanimelist_id ► mal ↔ anilist
```

Книги — своя цепочка, не смешивать произведение и издание:

```
Work (Open Library /works/…, BookBrainz bbid — тай-брейкер)
  └─ Edition (ISBN, язык, издатель) — Open Library editions, Google Books по ISBN
       └─ Narration (уже есть в audiobook_narration)
```

Совпадение книги: нормализованное название (кириллица/латиница, без «(читает …)») + пересечение
фамилий автора; при нескольких Work — BookBrainz и число изданий как тай-брейкеры.

### Кто главный для какого поля

| Поле | Главный | Запасные | Когда запрашиваем | Срок кэша |
|---|---|---|---|---|
| Логотип (прозрачный) | TMDb images (язык UI → en → без языка, по голосам) | Fanart.tv `hd*logo` по лайкам (ключ) | открытие Details, в одном запросе TMDb | 14 дн |
| Бэкдроп | TMDb images | Fanart.tv background | то же | 14 дн |
| Трейлер | аниме: AniList `trailer` → Shikimori `videos(PV)`; кино/сериалы: TMDb videos (язык UI → en; official, Trailer > Teaser) | YouTube `videos.list`-проверка (ключ); YouTube `search.list` только под бюджетом 80/сут и строгой проверкой канала | по нажатию «Трейлер» | 7 дн |
| Рейтинги IMDb/RT/Metacritic | OMDb (ключ) | — | Details кино/сериала | 7 дн |
| Следующая серия (время) | см. «Треки выхода» ниже | | Details онгоинга + фоновая проверка серий | до выхода серии +1 ч, не больше 6 ч |
| RU-метаданные аниме | Shikimori (как сейчас) | Jikan → AniList (перевод названий уже есть) | как сейчас | как сейчас |
| MAL-данные | MAL API (как сейчас) | Jikan с выключателем | как сейчас | — |
| Пропуск OP/ED аниме | AniSkip (по MAL id + длительности) | Anime-Skip (по AniList id, версия по `baseDuration`) → IntroDB (по IMDb + S/E) | старт серии | 30 дн, «нет данных» — 3 дн |
| Recap/Preview аниме | Anime-Skip | AniSkip `recap` | старт серии | 30 дн |
| Пропуск кино/сериалов | IntroDB | — | старт серии/фильма | 30 дн, «нет» — 3 дн |
| Внешние субтитры | OpenSubtitles (ключ + вход пользователя для скачивания) | — | открытие меню субтитров / автозагрузка выбранного языка | поиск 1 дн; файл — навсегда по `file_id` |
| Книга: произведение/издания | Open Library | BookBrainz (тай-брейкер) | Details книги | 30 дн |
| Книга: описание/категории | источник аудиокниги (как сейчас) | Google Books по ISBN → Open Library | Details книги, если своего описания нет | 30 дн |
| Книга: обложка | источник аудиокниги | iTunes (600×600) → Open Library covers | если своей нет или она мелкая | 30 дн |
| Бестселлеры | NYT (ключ) | — | дом Книг, раз в сутки | 1 дн |
| Похожее (кросс-медиа) | TasteDive (ключ), после проверки качества | существующий RecommendationEngine для аниме | при прокрутке до секции | 30 дн |

### Треки выхода серии (редакция 27.09 — по правилам пользователя)

Трек — для кого выходит серия: **RU** (русская версия) и **EN** (английская). Язык интерфейса
выбирает трек: RU → RU, EN → EN. Эфир оригинала на другом языке (японский у аниме) не подменяет
отсутствующий трек — тогда секции нет.

```
TrackSchedule(track: RU|EN, previous: ReleasePoint?, next: ReleasePoint?, nextEpisode, finished, source)
ReleasePoint(at: Instant?, date: LocalDate?)   // только дата → 12:00 по поясу устройства
NextRelease(track, episode, at: Instant, exactTime, source)
```

- **RU**: аниме — расписание озвучки AniLibria `/anime/schedule/week` (день недели выпуска →
  ближайшая дата, время неизвестно → 12:00 локально; прошлая серия — `fresh_at` релиза). Сериалы
  с русским оригиналом — TVmaze/TMDb.
- **EN**: сериалы с английским оригиналом — TVmaze `airstamp` (точно) → TMDb `air_date` (дата).
  У аниме EN-трека нет (AniList/Shikimori дают японский эфир).
- Секция есть, только если: трек не закончен, следующая серия известна и впереди, прошлая вышла
  не больше 14 дней назад. Решает первое по точности расписание трека, в котором есть данные;
  менее точное его не перекрывает. Оценки «по ритму» нет.
- UI: обычная секция между плашками источников и «Информацией»: «Следующий эпизод через:» /
  «4 дн 16 ч 33 мин 00 сек». Тикает раз в секунду по часам устройства; на нуле исчезает и один раз
  перечитывает расписание мимо свежего кэша.
- Код: `domain/enrichment/title/ReleaseCountdown.kt`; тесты — по одному на правило
  (`ReleaseCountdownTest`), устройство — `NextReleaseSectionDeviceTest`.

### Порядок запросов при открытии Details

```
базовая карточка (как сейчас) ─┐
TMDb bundle: images+videos+external_ids (1 запрос, если есть tmdbId) ─┼─ параллельно
OMDb (если есть ключ и imdbId, кино/сериал) ─┘
                 ↓ после external_ids
TVmaze next/prev (только сериал/аниме «выходит»)
Fanart.tv (только если у TMDb нет логотипа и есть ключ)
```

Трейлер, похожее, субтитры, пропуск — не при открытии, а там, где понадобились.

### Где живёт код

- `core/network/.../enrichment/` — клиенты API (Ktor, `LookupResult`, лимитер на хост), парсеры,
  `EnrichmentKeys` (ключи из `local.properties` → `BuildConfig`), JVM-тесты на сохранённых ответах.
- `app/.../domain/enrichment/` — оркестраторы (`TitleEnrichment`, `NextReleaseResolver`,
  `SkipSegmentSources`, `ExternalSubtitles`, `BookIdentity`), стор кэша на `JsonMapFileStore`,
  бюджеты/выключатели.
- UI: Details (логотип, рейтинги, следующая серия, трейлер), плеер (внешние субтитры, пропуск),
  Книги (метаданные книги, полка NYT).

## Часть B. Provider SDK для внешних источников

### Решение

Развиваем уже существующий декларативный манифест в **пакет провайдера v2** — данные, а не код.
Исполнение стороннего кода (JS/DEX/JAR) не вводим: прошлый план уже отказался от плагин-рантайма,
Google Play запрещает загрузку исполняемого кода вне Play, а песочница для недоверенного кода на
Android (isolatedProcess + интерпретатор) даёт мало гарантий при большой цене. Всё, что нельзя
описать декларативно (расшифровка, скрейпинг обфусцированных страниц, iframe), по нашим же
правилам делать нельзя — так что декларативного формата хватает для всего допустимого.

### Пакет

`*.vetro-source` = JSON (или zip с `manifest.json` + иконкой):

```json
{
  "format": 2,
  "id": "example.media",
  "version": "1.2.0",
  "sdk": { "min": 2, "max": 2 },
  "name": "Example Media",
  "author": "…",
  "homepage": "https://…",
  "mediaTypes": ["MOVIE", "SERIES", "ANIME", "MANGA", "AUDIOBOOK"],
  "capabilities": ["SEARCH", "SEARCH_BY_EXTERNAL_ID", "DETAILS", "UNITS", "STREAMS", "SUBTITLES", "PAGES"],
  "externalIds": ["tmdb", "imdb", "anilist", "mal"],
  "allowedHosts": ["api.example.com", "cdn.example.com"],
  "auth": { "kind": "HEADER", "name": "X-Api-Key" },
  "rateLimit": { "perSecond": 2, "burst": 4 },
  "operations": { "search": {…}, "details": {…}, "units": {…}, "streams": {…}, "pages": {…} },
  "signature": { "alg": "ed25519", "publicKey": "…", "value": "…" }
}
```

Каждая операция — HTTP-шаблон (плейсхолдеры `{query}`, `{tmdbId}`, `{season}`, `{episode}`,
`{unitId}`…) + отображение ответа JSON-указателями в нормализованную модель (как сейчас
`ManifestResponseMapping`), цепочки `resolveVia` — как сейчас.

### Нормализованная модель

```
ProviderTitle(providerId, rawId, title, year, type, externalIds)        — поиск
   → CanonicalMatch (ExternalIds сущности Vetro, confidence)           — идентичность
   → ContentUnit(kind: MOVIE|EPISODE|CHAPTER|AUDIO_CHAPTER, season?, number, variants)
   → Variant(audioLang, subtitleLang, label)                           — sub/dub-группы
   → MediaManifest: video → существующие VetroHoster/VetroVideo;
                    manga → PageList(urls, headers-allowlist);
                    audio → существующий audiobooks MediaManifest
```

### Возможности (окончательная модель)

`SEARCH`, `SEARCH_BY_EXTERNAL_ID(ids)`, `DETAILS`, `UNITS` (серии/главы/треки), `SEASONS`,
`STREAMS(kinds: HLS, DASH, PROGRESSIVE)`, `PAGES` (страницы манги), `AUDIO` (аудиокниги),
`SUBTITLES`, `VARIANTS` (озвучки/субтитры как группы), `DOWNLOAD` (по-прежнему запрещено по
умолчанию, нужен явный флаг потока). Тип контента — отдельной осью `mediaTypes`.

### Безопасность

- **Белый список хостов**: каждый URL запроса и каждый итоговый URL потока/страницы проверяется по
  `allowedHosts`; только https; приватные адреса — только с явным `allowInsecureHttp` для LAN (как
  сейчас). Редиректы с учётными данными не следуются (как сейчас).
- **Учётные данные**: только в зашифрованном сторе, в пространстве этого провайдера
  (`PlaybackCredentialRef`); манифест хранит имя заголовка, не секрет.
- **Нет доступа к БД, файлам, другим провайдерам** — пакет не исполняется, а интерпретируется
  нашим кодом через узкий интерфейс.
- **Ресурсы**: таймаут на операцию, лимит размера ответа, лимит числа шагов цепочки, лимит запросов
  на провайдера; сбои изолированы `ProviderResolution` и `ProviderHealth`.
- **Целостность**: SHA-256 пакета фиксируется при импорте; подпись ed25519 автора (если есть) —
  обновление принимается только с тем же ключом; версия SDK проверяется диапазоном `sdk`.
- **Имена сайтов в ядре отсутствуют** — ядро знает только возможности и модель.

### Импорт

Настройки → Источники → Импортировать → выбрать файл → валидатор → экран возможностей
(типы, операции, хосты, требуется ли ключ) → подтверждение → источник попадает в резолвер со
своим здоровьем. Обновление — повторный импорт, с проверкой подписи.

### Резолвер нескольких провайдеров

Один тайтл у нескольких провайдеров сводится к канонической сущности по `ExternalIds`
(`SEARCH_BY_EXTERNAL_ID` предпочтительнее поиска по названию). Варианты группируются по
`audioLang × subtitleLang`. Выбор — по здоровью (`ProviderHealthPolicy.penalty`) и приоритету;
параллельный запуск первых N с ранним выходом по первому валидному результату (гонка), остальные
отменяются; при отказе — следующий (как в `PlaybackProviderCascade`).

### Subsonic — отдельно, как первоклассный личный сервер

Не пакет, а встроенный адаптер рядом с Jellyfin/WebDAV: вход по токену (`md5(пароль+соль)`) или
API-ключу (OpenSubsonic), `ping` + `getOpenSubsonicExtensions` для возможностей, `search3`,
`getAlbumList2`/`getMusicDirectory`, `stream` с Range (перемотка при перекодировании — `timeOffset`),
`getCoverArt`, закладки как прогресс аудиокниг. Покрывает Navidrome, Airsonic-Advanced, Gonic,
Ampache, Nextcloud Music, LMS, Supysonic.

## План работ

Статус выполнения и что осталось — в [STATUS.md](STATUS.md).
