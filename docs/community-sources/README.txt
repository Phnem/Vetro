Vetro — Community sources
=========================

Предложить источник / Submit a source:
https://docs.google.com/forms/d/e/1FAIpQLSeov9HZLYJvjpecngntqbXifCwdSXPf_t5G0PLqaob0t6pb-A/viewform

Здесь лежат проверенные автором Vetro пакеты источников. Если файл в этой папке — он проверен вручную.
Публикуются только легальные источники: открытые библиотеки, общественное достояние, официальные
бесплатные каталоги, локальные и узкоспециализированные источники, источники на местных языках.
Принимаются только текстовые файлы (JSON / TXT): ни архивов, ни исполняемых файлов.

  Vetro Sources  — пакеты .vetro-source и манифесты Vetro
  Vetro Stremio  — списки адресов Stremio-аддонов

Импорт: Настройки → Дополнительные источники → «Импортировать .vetro» (файл) или поле
«Ссылка или конфигурация» (ссылка / вставленный JSON / адрес Stremio-аддона).


======================================================================
1. АРХИТЕКТУРА: КАК VETRO ГОВОРИТ С ВНЕШНИМ ИСТОЧНИКОМ
======================================================================

Подключаемый источник в Vetro — это ДАННЫЕ, а не код. Файл описывает, какие HTTP-запросы делать и
где в JSON-ответе лежат нужные поля. Исполняет описание сам Vetro — интерпретатором с белым списком
хостов, лимитами частоты и ключом пользователя из зашифрованного хранилища. Сторонний код (JS, DEX,
плагины) Vetro не исполняет никогда.

  [Vetro] --HTTPS GET/POST--> [API источника] --JSON--> [Vetro: JSON-указатели] --> [плеер]

Цепочка для фильма или серии:

  поиск тайтла          -> id тайтла у источника   (по названию или по TMDb/IMDb/Кинопоиску…)
  список серий (units)  -> id серии                (для сериалов; у фильма может не быть)
  потоки (streams)      -> прямые ссылки на видео  (HLS .m3u8, DASH .mpd или обычный файл .mp4)

Три формата подключения:

  A. Пакет провайдера v2 (.vetro-source) — основной формат. Поиск, серии, потоки, субтитры, озвучки,
     подпись автора. Сейчас работает для ФИЛЬМОВ и СЕРИАЛОВ. Типы MANGA и AUDIOBOOK формат описывает,
     но приложение их ещё не подключает: такой пакет установится, но использоваться не будет.
  B. Манифест Vetro v1 — старый простой формат: один-два запроса по внешнему id. Только фильмы и сериалы.
  C. Stremio-аддон — Vetro говорит с сервером аддона по протоколу Stremio. Только фильмы и сериалы.

Отдельно (не файлы, а учётки в «Дополнительных источниках»): свои серверы пользователя — WebDAV,
Jellyfin, Emby (кино), Subsonic/Navidrome и Audiobookshelf (аудиокниги).


======================================================================
2. ЧЕК-ЛИСТ: КОГДА РЕСУРС ТЕХНИЧЕСКИ СОВМЕСТИМ С VETRO
======================================================================

Ресурс совместим, только если ВСЁ верно:

  [ ] Отвечает JSON по HTTPS (обычный http — только для адреса в своей локальной сети).
  [ ] Тайтл находится одним запросом: по названию ({query}) или по внешнему id
      (TMDb, IMDb, Кинопоиск, AniList, MAL, Shikimori).
  [ ] Ответ содержит ГОТОВЫЕ прямые ссылки на медиа целиком, одним полем:
      видео — HLS (.m3u8), DASH (.mpd) или файл (.mp4 и т.п.).
  [ ] Все хосты — и API, и CDN с файлами — известны заранее и их не больше 16.
  [ ] Ключ API, если нужен, передаётся заголовком (параметр в адресе — только для локальной сети).
  [ ] Запросы GET (или POST без тела).

Ресурс НЕсовместим, если нужно хоть что-то из этого:

  [x] Разбирать HTML-страницу, регулярные выражения по телу страницы.
  [x] Исполнять JavaScript, WebView, «плеер собирается скриптом».
  [x] Плеер в iframe, embed-страница вместо ссылки на поток.
  [x] Расшифровывать или деобфусцировать ссылки (AES, hex, base64-игры, «токен плеера»).
  [x] Проходить капчу, Cloudflare Turnstile, «проверку браузера».
  [x] Торренты, magnet, infoHash, usenet.
  [x] Склеивать ссылку из частей (baseUrl + hash + имя файла) — в JSON должна быть ссылка целиком.
  [x] Отправлять тело запроса (GraphQL, POST с JSON).
  [x] Ответ не JSON: XML, RSS, OPDS, Atom.
  [x] Хост ссылок заранее неизвестен (случайные зеркала, балансировка по чужим доменам).

Для Stremio-аддона дополнительно:

  [ ] manifest.json доступен по HTTPS.
  [ ] В "resources" есть "stream" (аддон только с catalog/meta/subtitles не подходит).
  [ ] В "types" есть "movie" и/или "series".
  [ ] "idPrefixes" отсутствует или содержит "tt" (IMDb).
  [ ] Не P2P (behaviorHints.p2p не true) и не требует настройки на своём сайте
      (behaviorHints.configurationRequired не true).
  [ ] Потоки приходят полем "url" с http(s)-ссылкой. Поля infoHash, nzbUrl, ytId, externalUrl
      Vetro отбрасывает.


======================================================================
3. ПАКЕТ ПРОВАЙДЕРА v2 (.vetro-source) — ФОРМАТ
======================================================================

Файл — один JSON-объект, не больше 256 КБ, кодировка UTF-8.

Поле           Обяз.  Что
-------------  -----  ---------------------------------------------------------------
format          да    всегда 2
id              да    a-z, 0-9, . _ - ; 2..64 символа; первый — буква или цифра
version         да    1.2.3; обновление принимается только на БОЛЬШУЮ версию
sdk             да    {"min": 2, "max": 2}
name            да    1..64 символа
author          нет   строка
homepage        нет   только https://
mediaTypes      да    из: MOVIE, SERIES (ANIME, MANGA, AUDIOBOOK — объявить можно, но пока не работают)
capabilities    да    из: SEARCH, SEARCH_BY_EXTERNAL_ID, UNITS, STREAMS, SUBTITLES, VARIANTS,
                      PAGES, AUDIO, DOWNLOAD
externalIds     нет   из: tmdb, imdb, anilist, mal, kinopoisk, shikimori
allowedHosts    да    1..16 хостов строчными буквами; ВСЕ хосты запросов и итоговых ссылок
auth            нет   {"kind": "HEADER", "name": "X-Api-Key"}; сам ключ вводит пользователь
rateLimit       нет   {"perSecond": 0..10, "burst": 1..20}; по умолчанию 2 и 4
operations      да    search / units / streams / pages (см. ниже)
allowInsecureHttp нет  true — только если ВСЕ хосты в локальной сети
signature       нет   {"alg": "ed25519", "publicKey": base64, "value": base64}

Каждая операция — запрос + JSON-указатели. Указатель начинается с «/», как в RFC 6901:
"/results" — поле results корня, "/ids/tmdb" — поле tmdb внутри ids. Указатели элемента
(id, title, url…) отсчитываются от элемента массива items.

  search   — request.url с {query} (для SEARCH) и/или {tmdbId} {imdbId} {kinopoiskId}
             {anilistId} {malId} {shikimoriId} (для SEARCH_BY_EXTERNAL_ID);
             items, id, title обязательны; year, type, externalIds — по желанию.
  units    — request.url обязательно с {titleId}; items, id, number обязательны; season, title — нет.
  streams  — request.url с {unitId} (нужен UNITS) или с внешним id + {season} {episode};
             items, url обязательны; label, resolution, kind (HLS/DASH/PROGRESSIVE),
             audioLanguage, subtitleLanguage, downloadAllowed — по желанию;
             subtitles: {items, url, language, format}.
  pages    — для манги: request.url с {unitId}; items, url ("/" — элемент сам строка-адрес).

Связки, которые проверяет валидатор:
  SEARCH или SEARCH_BY_EXTERNAL_ID  <=> есть search
  UNITS                             <=> есть units
  STREAMS или AUDIO                 <=> есть streams
  PAGES                             <=> есть pages, и mediaTypes содержит MANGA
  AUDIO                             => mediaTypes содержит AUDIOBOOK
  SUBTITLES                         => streams.subtitles задан
  VARIANTS                          => streams.audioLanguage или streams.subtitleLanguage задан
  {imdbId} и т.п. в запросе         => "imdb" и т.п. есть в externalIds

Адрес запроса: только абсолютный https://, хост — буквальный (подстановки в хосте запрещены) и из
allowedHosts, без логина@пароля, без «..» и «#», не длиннее 512 символов. Редиректы Vetro выполняет
сам и только внутрь allowedHosts.

Подпись (по желанию): ed25519 над каноническим JSON пакета без поля signature (ключи по алфавиту,
без пробелов). Ключ автора запоминается при первой установке — обновление принимается только с ним.

--- Пример: пакет кино и сериалов (адреса вымышленные) -----------------------

{
  "format": 2,
  "id": "example.media",
  "version": "1.0.0",
  "sdk": { "min": 2, "max": 2 },
  "name": "Example Media",
  "author": "Example author",
  "homepage": "https://example.com/",
  "mediaTypes": ["MOVIE", "SERIES"],
  "capabilities": ["SEARCH_BY_EXTERNAL_ID", "UNITS", "STREAMS", "SUBTITLES", "VARIANTS"],
  "externalIds": ["tmdb", "imdb"],
  "allowedHosts": ["api.example.com", "cdn.example.com"],
  "auth": { "kind": "HEADER", "name": "X-Api-Key" },
  "rateLimit": { "perSecond": 5, "burst": 10 },
  "operations": {
    "search": {
      "request": { "url": "https://api.example.com/v1/search?tmdb={tmdbId}" },
      "items": "/results",
      "id": "/id",
      "title": "/title",
      "year": "/year",
      "externalIds": { "tmdb": "/ids/tmdb", "imdb": "/ids/imdb" }
    },
    "units": {
      "request": { "url": "https://api.example.com/v1/titles/{titleId}/episodes" },
      "items": "/episodes",
      "id": "/id",
      "number": "/number",
      "season": "/season"
    },
    "streams": {
      "request": { "url": "https://api.example.com/v1/episodes/{unitId}/streams" },
      "items": "/streams",
      "url": "/url",
      "label": "/label",
      "resolution": "/quality",
      "audioLanguage": "/audio",
      "subtitles": { "items": "/subs", "url": "/url", "language": "/lang", "format": "/format" }
    }
  }
}

Ответы API, под которые написан пример:

  GET https://api.example.com/v1/search?tmdb=1399
  {"results":[{"id":"t42","title":"Game of Thrones","year":2011,"ids":{"tmdb":"1399","imdb":"tt0944947"}}]}

  GET https://api.example.com/v1/titles/t42/episodes
  {"episodes":[{"id":"e1","season":1,"number":1}]}

  GET https://api.example.com/v1/episodes/e1/streams
  {"streams":[{"url":"https://cdn.example.com/e1/master.m3u8","label":"1080p","quality":1080,"audio":"en",
    "subs":[{"url":"https://cdn.example.com/e1/en.vtt","lang":"en","format":"vtt"}]}]}

--- Пример: самый короткий пакет (фильмы по IMDb, без поиска) ---------------

{
  "format": 2,
  "id": "example.films",
  "version": "1.0.0",
  "sdk": { "min": 2, "max": 2 },
  "name": "Example Films",
  "mediaTypes": ["MOVIE"],
  "capabilities": ["STREAMS"],
  "externalIds": ["imdb"],
  "allowedHosts": ["films.example.org"],
  "operations": {
    "streams": {
      "request": { "url": "https://films.example.org/api/movie/{imdbId}" },
      "items": "/files",
      "url": "/href",
      "resolution": "/height"
    }
  }
}


======================================================================
4. МАНИФЕСТ VETRO v1 — ФОРМАТ (только фильмы и сериалы)
======================================================================

Поле            Обяз.  Что
--------------  -----  --------------------------------------------------------------
manifestVersion  да    всегда 1
id               да    A-Z a-z 0-9 . _ - ; 1..64 символа
name             да    строка
baseUrl          да    https://…, без ?query, #fragment и логина; http — только локальная сеть
capabilities     да    из: MOVIE, SERIES, RU, EN, DIRECT, HLS, SUBTITLES, MULTI_AUDIO, DOWNLOAD,
                       TMDB_ID, IMDB_ID, KINOPOISK_ID; обязательно MOVIE и/или SERIES
auth             нет   {"kind": "HEADER", "name": "Authorization", "prefix": "Bearer "}
movie / series   *     {"path": "/…"} — путь от baseUrl; series обязан содержать {season} и {episode}
resolveVia       *     двухшаговый вариант: lookup {path, extract} + movie/series с {lookupId}
response         да    указатели: streams, url; label, resolution, language, downloadAllowed,
                       translation — по желанию
allowInsecureHttp нет  только для адреса в локальной сети
                * — для каждого объявленного MOVIE/SERIES нужен свой запрос (прямой или через resolveVia)

Подстановки в путях: {tmdbId} {imdbId} {kinopoiskId} {season} {episode} {title} {lookupId}.

--- Пример манифеста v1 (адреса вымышленные) -----------------------------------

{
  "manifestVersion": 1,
  "id": "example-cinema",
  "name": "Example Cinema",
  "baseUrl": "https://api.example.net",
  "capabilities": ["MOVIE", "SERIES", "EN", "HLS", "TMDB_ID"],
  "auth": { "kind": "HEADER", "name": "Authorization", "prefix": "Bearer " },
  "movie":  { "path": "/movie/{tmdbId}" },
  "series": { "path": "/tv/{tmdbId}/{season}/{episode}" },
  "response": { "streams": "/sources", "url": "/file", "label": "/name", "resolution": "/height" }
}


======================================================================
5. STREMIO-АДДОНЫ (папка Vetro Stremio)
======================================================================

Аддон в Vetro добавляется ссылкой на его manifest.json — в поле «Ссылка или конфигурация».
Файл в папке Vetro Stremio — обычный текст: одна ссылка на строку, после «#» — комментарий.
Импорта такого списка целиком в приложении нет: ссылки добавляются по одной.

--- Пример файла .vetro-stremio -------------------------------------------------

# Название подборки — для чего она
https://addon.example.com/manifest.json      # что даёт аддон, язык


======================================================================
6. ЧАСТЫЕ ПРИЧИНЫ ОТКАЗА (так их пишет приложение)
======================================================================

  Unsupported package format N                    — format не 2
  Package needs provider SDK a..b                 — sdk не включает 2
  version must look like 1.2.3
  allowedHosts must list every host…              — пустой allowedHosts
  Request host X is not in allowedHosts
  Placeholders are not allowed in the host
  Placeholder {X} is not allowed here             — подстановка не из списка операции
  {imdbId} needs "imdb" in externalIds
  JSON pointer must start with '/'
  … operation and … capability must go together   — связка из раздела 3
  Query-parameter auth is not allowed for a public host
  Plain http is only allowed for a local address
  The package is larger than 256 KB
  Addon does not provide the stream resource      — Stremio без "stream"
  P2P addons are not supported
  Addon does not accept IMDb ids


EN — short version
==================

Vetro source packages are DATA, not code: HTTP templates plus JSON pointers, run by Vetro's own
interpreter with a host allow-list and rate limits. A resource is compatible only if it answers JSON
over HTTPS, finds a title by name or by a TMDb/IMDb/Kinopoisk/AniList/MAL/Shikimori id in one request,
and returns complete direct media links (HLS, DASH or a file) on known hosts (max 16). Not compatible:
HTML scraping, JavaScript, iframes, link decryption, captchas, torrents, URLs that must be assembled
from parts, request bodies (GraphQL), XML/RSS/OPDS. Packages currently work for movies and series
only. Stremio addons need the "stream" resource, movie/series types and IMDb ids, no P2P.
Sections 3–4 above contain the full field reference and complete examples (fictional hosts).
