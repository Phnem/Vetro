# Проверка 137 API из `vetro_media_api_master_list.md` — 2026-09-26

Каждому API отправлен один настоящий минимальный запрос **без ключа** (с машины в регионе IL).
Ответ классифицирован:

- ✅ **работает без ключа** — вернул данные;
- 🔑 **нужен ключ/вход** — корректный отказ 401/403/400 «нет ключа» (значит, API живой);
- 🛡 **Cloudflare / защита от ботов** — отдал проверку браузера вместо данных;
- ⚠️ **сбоит** — 5xx, таймаут, неверный ответ при живом домене;
- ❌ **мёртв** — домена нет, «No such app» (Heroku), закрыт владельцем;
- 🟦 **уже в приложении**.

Полезность для Vetro: **A** — стоит подключать, **B** — полезно как дополнение, **C** — декоративное
или не про наш контент, **—** — не нужно.

## Итог в цифрах

Проверено 175 записей: 137 позиций списка (с повторами между разделами) плюс варианты одного API
(два разных «IMDb API», демо и документация self-hosted) и повторные запросы для сомнительных.

| Результат | Сколько |
|---|---|
| ✅ работает без ключа | 83 |
| 🔑 живой, нужен ключ или вход (чаще всего бесплатный) | 54 |
| 🛡 закрыт Cloudflare от автоматических запросов | 4 |
| ⚠️ сбоит (5xx, таймауты, блок местного DNS) | 10 |
| ❌ мёртв (нет домена, «No such app», закрыт) | 24 |

Из «работает»/«нужен ключ» по-настоящему полезны для Vetro около 25 (оценка **A**/**B** ниже);
большая часть списка — декоративные или не про наш контент (франшизные вики, религиозные тексты,
музыка, стоки, облачный видеомонтаж).

### Что стоит подключать (A)

| Зачем | API | Доступ |
|---|---|---|
| Кино/сериалы: метаданные, провайдеры «где смотреть» | TMDb | бесплатный ключ |
| Расписание серий, «следующая серия» | TVmaze | без ключа |
| Синхронизация просмотренного (кино/сериалы/аниме) | Trakt, Simkl | бесплатный client id + OAuth пользователя |
| Трейлеры | YouTube Data API | бесплатный ключ (квота) |
| Логотипы и фоны для карточек | Fanart.tv | бесплатный ключ |
| Субтитры | OpenSubtitles | бесплатный ключ |
| Книги: метаданные, обложки, издания | Open Library, Google Books (+полки пользователя), iTunes Search | без ключа / свой ключ |
| Бесплатные книги | Gutendex (Project Gutenberg), Internet Archive | без ключа |
| Свои серверы | Jellyfin, Emby, Plex, Audiobookshelf, Komga, Kavita, OPDS/Calibre-Web, Nextcloud, WebDAV, S3 | вход пользователя |
| Уже в приложении | AniList, MAL, Shikimori, Kitsu, MangaDex, trace.moe, JustWatch, AniSkip, Internet Archive | — |

### Дополнительно (B)

OMDb (рейтинги IMDb/RT), MDBList (сводные рейтинги), TVDB (эпизоды), Watchmode («где смотреть»),
AniDB (эпизоды, нужен зарегистрированный клиент), AnimeNewsNetwork (стафф), Anime-Skip и IntroDB
(пропуск заставок), BookBrainz (идентичность изданий), NYT Books (бестселлеры), Comic Vine (если появятся
комиксы), TasteDive («похожее»), Spotify (библиотека аудиокниг), PodcastIndex/iTunes (если появятся
подкасты), Jikan (запасной для MAL — сейчас лежит вместе с MAL).

### Не подключать

- **Скрейперы пиратских сайтов** (Consumet, Enime, Shiro, Manganelo, Jandapress) — либо мертвы
  (Consumet закрыт с кодом 451 «по юридическим причинам»), либо 18+ скрейпер; для своих источников у
  Vetro уже есть собственный слой.
- **Мёртвые:** AniAPI, AnimeFacts, Waifu.pics, Catch The Show, TrailerAddict, IMDb API (imdbapi.dev),
  Utelly, Goodreads, Napster, KSoft.Si, Gaana, JioSaavn, Buffy & Angel, Dune, Vampire Diaries, Catalogopolis,
  AnimeQuotes, Thirukkural, Marvel.
- **Не наш контент:** музыка, радио, стоки, облачный видеомонтаж, религиозные тексты, франшизные вики.

## Anime / Manga

| API | Результат | Детали ответа | Для Vetro |
|---|---|---|---|
| AniList | 🟦 ✅ | GraphQL, данные без ключа | A — уже основной источник |
| MyAnimeList API | 🟦 🔑 | 403 без `X-MAL-CLIENT-ID` (бесплатный client id) | A — уже используется |
| Jikan | ⚠️ | 504 дважды: «Jikan failed to connect to MyAnimeList» — зависит от MAL, падает вместе с ним | B — запасной для MAL |
| Kitsu | 🟦 ✅ | JSON:API, домен теперь kitsu.app | B — уже используется |
| AniDB | 🔑 | `<error code="302">client version missing or invalid</error>` — нужен зарегистрированный клиент; жёсткие лимиты, бан за частые запросы | B — эпизоды/ID, только с регистрацией клиента |
| Shikimori | 🟦 ✅ | данные без ключа | A — уже основной RU-источник |
| AnimeNewsNetwork | ✅ | XML энциклопедии без ключа | B — стафф, студии |
| MangaDex | 🟦 ✅ | без ключа | A — уже движок манги |
| Mangapi | 🔑 | RapidAPI, платный ключ | C — перевод страниц, эксперимент |
| Trace.moe | 🟦 ✅ | `/me`: квота 100 запросов, без ключа | A — уже используется (поиск по кадру) |
| AniAPI | ❌ | на домене заглушка вместо API (проект закрыт в 2022) | — |
| Wibu API | ⚠️ | wibusaka: корень 404; это API «где смотреть» для Индонезии | — |
| Dattebayo API | ✅ | Naruto, без ключа | C |
| Dragon Ball API | ✅ | без ключа | C |
| Studio Ghibli API | ✅ | без ключа | C |
| Danbooru | 🛡 | Cloudflare-челлендж | C — арт; ненадёжно для приложения |
| Waifu.im | 🛡 | Cloudflare-челлендж | C |
| Waifu.pics | ❌ | домена нет (NXDOMAIN) | C |
| NekosBest | 🛡 | Cloudflare-челлендж | C |
| Nekos API | ✅ | v4, картинки + цвета, без ключа | C |
| Nekosia API | ✅ | картинки + палитра, без ключа | C — палитра для фонов |
| AnimeChan | ✅ | случайная цитата без ключа | C |
| AnimeQuotes API | ❌ | на vercel — веб-страница, путь API → 404 | C |
| AnimeFacts | ❌ | Heroku «No such app» | — |
| Consumet (публичный) | ❌ | 451 Unavailable For Legal Reasons — публичный сервер закрыт по юридическим причинам; остался только self-host | — (скрейпинг пиратских сайтов) |
| Enime API | ❌ | у домена нет адресов | — |
| Shiro API | ❌ | домен есть, но TLS-ошибка — сервер не обслуживает API | — |
| Manganelo (через Consumet) | ❌ | тот же 451 | — |
| What Anime (= trace.moe) | ✅ | поиск по кадру вернул AniList id и таймкод | уже есть через trace.moe |
| OpenSubtitles | 🔑 | 403 «You cannot consume this service» без Api-Key (бесплатный ключ) | A — субтитры |

## Movies / TV

| API | Результат | Детали ответа | Для Vetro |
|---|---|---|---|
| TMDb | 🟦 🔑 | 401 без ключа (бесплатный) | A — главный источник кино/сериалов, провайдеры «где смотреть» |
| OMDb | 🔑 | «No API key provided» (бесплатно 1000/сут) | B — рейтинги IMDb/RT |
| IMDb API (imdbapi.dev) | ❌ | домена нет (NXDOMAIN) | — |
| IMDb API (tv-api.com, платный) | ⚠️ | 404/таймаут | — |
| IMDbOT | ⚠️ | домен есть (Cloudflare Workers), но местный DNS его блокирует — проверить с другой сети | — |
| TVDB | 🔑 | 401 (ключ + подписка для приложений) | B — эпизоды |
| TVmaze | ✅ | без ключа | A — расписание серий, «следующая серия» |
| Trakt | 🔑 | без `trakt-api-key` — отказ (бесплатный client id, OAuth пользователя) | A — синхронизация просмотренного, скробблинг |
| Simkl | 🔑 | 412 «client_id wrong» (бесплатный) | A — единый трекер кино+сериалы+аниме |
| Watchmode | 🔑 | 401 (бесплатный тариф) | B — «где смотреть» |
| Utelly | ❌ | RapidAPI: «API doesn't exists» | — |
| uNoGS | 🔑 | RapidAPI, платный | C — только Netflix |
| Shoof Aflam | ✅ (сайт) | страница документации жива; рынок MENA | — |
| Catch The Show | ❌ | Heroku «No such app» | — |
| TrailerAddict | ❌ | api-домена нет (NXDOMAIN) | — |
| YouTube Data API | 🔑 | 403 без ключа (бесплатная квота) | A — трейлеры |
| Vimeo API | 🔑 | 401 без токена | C |
| Dailymotion API | ✅ | поиск без ключа | C |
| Stream.cz | ✅ | открытый GraphQL | — (чешский контент) |
| Czech Television XML | ✅ (страница) | нужен логин партнёра | — |
| TranscriptAPI | ✅ (сайт) | платный | C |
| Fanart.tv | 🔑 | «missing api_key» (бесплатный) | A — логотипы, фоны, постеры |
| MDBList | 🔑 | 401 (бесплатный ключ) | B — сводные рейтинги |
| JustWatch | 🟦 ✅ | GraphQL отвечает без ключа (API неофициальный) | A — уже в «Где смотреть» |
| IntroDB | ✅ (сайт) | живой сайт; API для пропуска заставок фильмов/сериалов | B — skip intro для кино |
| Anime-Skip | 🔑 | «X-Client-ID header must be passed» (бесплатный) | B — к AniSkip |
| AniMap | ⚠️ | китайский сайт-карта, не API сопоставления ID | — |
| AniSkip (сравнение) | 🟦 ✅ | уже используется | — |

## Niche / Franchise

| API | Результат | Для Vetro |
|---|---|---|
| An API of Ice And Fire | ✅ | C |
| Bob's Burgers API | ✅ | C |
| Breaking Bad API | 🛡 (429 challenge) | — |
| Buffy & Angel API | ❌ (404) | — |
| Catalogopolis | ❌ (NXDOMAIN) | — |
| Final Space API | ⚠️ (500, при повторе 200 — нестабилен) | C |
| STAPI | ✅ | C |
| SWAPI | ✅ | C |
| SWAPI GraphQL | ✅ | C |
| The Lord of the Rings API | ✅ (книги без ключа, остальное с ключом) | C |
| The Vampire Diaries API | ❌ (API-путь 404) | — |
| ThronesApi | ✅ | C |
| Dune API | ❌ (Heroku «No such app») | — |
| HP-API | ✅ | C |
| MCU Countdown | ✅ | C |
| Disney API | ✅ | C |
| Rick and Morty API | ✅ | C |
| PotterDB | ✅ | C |
| Marvel API | ❌ (500 дважды; портал разработчиков Marvel закрыт) | — |

Нишевые API — декоративные: пасхалки на карточках отдельных франшиз. В ядро не нужны.

## Books / E-books

| API | Результат | Детали | Для Vetro |
|---|---|---|---|
| Open Library | ✅ | поиск без ключа, обложки, ISBN, издания | A — метаданные и обложки книг |
| Google Books API | 🔑 | 429: общая квота без ключа исчерпана — нужен свой (бесплатный) ключ; OAuth для полок | A — метаданные, полки пользователя |
| Gutendex | ✅ | Project Gutenberg, без ключа | A — бесплатные книги (EPUB) |
| Internet Archive API | 🟦 ✅ | уже источник аудиокниг | A |
| LibriVox API | ✅ | без ключа (поиск по названию слабый — используем через archive.org) | A — уже через archive.org |
| ISBNdb | 🔑 | 401, платный | C |
| BookBrainz | ✅ | `/1/search` без ключа (путь `/1.0/` из списков устарел) | B — идентичность произведений и изданий |
| Crossref | ✅ | без ключа | C — научные публикации |
| British National Bibliography | ⚠️ | таймаут дважды (сервис BL, похоже, выключен) | — |
| Penguin Publishing API | ⚠️ | таймаут дважды | — |
| Big Book API | 🔑 | 401, платный | C |
| Goodreads API | ❌ | «Invalid API key» — выдача ключей закрыта с 2020 | — |
| NYT Books API | 🔑 | нет ключа (бесплатный) | B — бестселлеры |
| iTunes Search API | ✅ | без ключа (книги, аудиокниги, подкасты) | A — метаданные + ссылки в Apple Books |
| PoetryDB | ✅ | C |
| Ganjoor | ✅ | — |
| Urantia Papers API | ✅ | — |
| KDP Intelligence | ✅ (документация) | — |
| Library Management API | ✅ (учебный репозиторий) | — |

## Specialized text

| API | Результат |
|---|---|
| A Bíblia Digital | ⚠️ 503 (Heroku Application Error) |
| Bible-api | ✅ |
| api.bible | 🔑 (бесплатный ключ) |
| Holy Bible API | ✅ |
| Bhagavad Gita API | ✅ |
| Quran Cloud | ✅ (при повторе) |
| GurbaniNow | ✅ (`/v2/banis`; `/shabad/1` отдаёт 500) |
| Thirukkural API | ❌ (404; зеркало на vercel — 402 Payment required) |
| Amanah Sunnah | ✅ (страница разработчика) |

Для Vetro — не нужно (не наш тип контента).

## Comics

| API | Результат | Для Vetro |
|---|---|---|
| Comic Vine | 🔑 (бесплатный ключ) | A — метаданные комиксов, если будет раздел комиксов |
| Comichron Data | ✅ (JSON на GitHub) | C |
| xkcd API | ✅ | C |
| Jandapress | ✅ (18+ скрейпер доуджинси) | — не подключать |

## Discovery / Availability

| API | Результат | Для Vetro |
|---|---|---|
| TasteDive | 🔑/🛡 (403 без ключа) | B — «похожее» между типами |
| TMDb watch/providers | 🔑 (ключ TMDb) | A — «где смотреть» официально |

## Video utility

Hyperserve ✅ (сайт), Mux 🔑, Gcore Streaming 🔑, Shotstack 🔑, JSON2Video 🔑, Rendi 🔑, Rendobar ✅ (сайт),
ApyHub-утилиты (thumbnail/GIF/audio/compress) 🔑, iLoveVideoEditor ✅ (сайт), Hunt Video ⚠️ (таймаут дважды).
Все — платные облачные сервисы обработки/хостинга своего видео. Для Vetro не нужны: превью и
перекодирование делаются на устройстве (Media3), своего видео-хостинга у приложения нет.

## Self-hosted / Open protocols (подробно — `research-2026-09-26.md`)

Jellyfin ✅ (демо: вход, каталог, поток), Emby ✅ (документация), Plex ✅ (привязка по PIN), Audiobookshelf ✅
(документация), Komga ✅ (OpenAPI), Kavita ✅ (демо живо), OPDS ✅ (Gutenberg; разово 504, при повторе 200), Nextcloud ✅,
S3 ✅. Всё — **A**, главный путь «подключить своё».

## Music / Audio / Podcasts

| API | Результат | Для Vetro |
|---|---|---|
| Spotify Web API | 🔑 OAuth | B — библиотека аудиокниг пользователя (см. прошлый отчёт) |
| Deezer API | ✅ без ключа | C |
| MusicBrainz | ✅ | C |
| Discogs | ✅ (публичные данные без токена) | C |
| Last.fm | 🔑 (бесплатный ключ) | C |
| TheAudioDB | ✅ (с открытым тестовым ключом «123»; «2» отозван) | C |
| 7digital | 🔑 | — |
| Audiomack | 🔑 | — |
| Bandcamp | ❌ как API (публичного API нет, только партнёрский) | — |
| SoundCloud | 🔑 (регистрация приложений закрыта годами) | — |
| Mixcloud | ✅ | — |
| Napster | ❌ домена API нет (NXDOMAIN) | — |
| Jamendo | 🔑 (бесплатный client id) | — |
| KKBOX | 🔑 | — |
| Freesound | 🔑 | — |
| AudD | 🔑 (без токена блок) | — |
| Genius | 🔑 | — |
| Musixmatch | 🔑 | — |
| Lyrics.ovh | ✅ | — |
| KSoft.Si | ❌ (на домене сайт, API закрыт) | — |
| Vagalume | ⚠️ 503 | — |
| Songlink / Odesli | 🔑 («PUBLIC_API_ACCESS_DEPRECATED» — теперь только с ключом) | — |
| Openwhyd | ✅ | — |
| Phishin | ✅ | — |
| Radio Browser | ✅ | C — если появится радио |
| iTunes (подкасты) | ✅ | B — если появятся подкасты |
| PodcastIndex | 🔑 (бесплатный ключ) | B — подкасты: открытый индекс + прямые RSS/MP3 |
| Taddy | ✅ GraphQL отвечает (данные — с ключом) | B |
| Podchaser | ✅ GraphQL отвечает (данные — с ключом) | C |
| Particle | ✅ (сайт) | C |
| Bandsintown | 🔑 | — |
| Songkick | 🔑 | — |
| Gaana (unofficial) | ❌ 404 | — |
| JioSaavn (unofficial) | ❌ домена нет (NXDOMAIN) | — |
| Verome API | ✅ (только репозиторий, самостоятельный запуск) | — |

Музыка к Vetro не относится; из раздела полезны только **подкасты** (PodcastIndex/iTunes — открытые RSS
с MP3, ложатся на тот же плеер, что аудиокниги), если такой раздел появится.

## Artwork / Images

Flickr 🔑, Unsplash 🔑, Pexels 🔑, Pixabay 🔑, Getty 🔑 (платный), Shutterstock 🔑 (платный), Giphy 🔑,
Imgur 🔑, Gyazo 🔑, Wallhaven ✅, Fanart.tv 🔑 (A — см. выше), Danbooru 🛡, Waifu.im 🛡, Nekosia ✅, Pexafy ✅ (сайт).
Для Vetro реально полезен только **Fanart.tv** (логотипы и фоны для карточек). Стоковые фото — нет:
у приложения визуал строится из обложек самих тайтлов.
