# Хвосты (точки расширения)

Принцип: сначала строим архитектуру и подключаем нужное с нуля, затем оставляем **хвост** — размеченное
место, куда новый источник добавляется как пазл, без переписывания цепочки. Каждый хвост помечен
в коде тегом `VETRO-TAIL[<имя>]`.

Найти все хвосты:

```bash
grep -rn "VETRO-TAIL\[" app core --include=*.kt
```

| Тег | Что добавляется | Как |
|---|---|---|
| `title-id-source` | Источник id/названий/описания/постера (каталоги, словари, индексы) | Класс `TitleIdContributor` (core/network/.../ids/TitleIdContributor.kt) + `single<TitleIdContributor> { … }` в `enrichmentModule`. Резолвер подхватывает через `getAll()` |
| `stream-provider` | Легальный источник кино/сериалов | Класс `MovieSeriesStreamingProvider` + строка в `movieSeriesSources` (`appModule`), порядок = приоритет |
| `connection-type` | Новый способ подключения источников (карточка + страница в панели «Дополнительные источники») | Объект `ConnectionType` и строка в `ConnectionTypes` (`ui/settings/ConnectionTypes.kt`). Только протокол или описание (JSON/HTTP), без загрузки чужого кода в приложение |
| `manga-source` | Источник манги | Класс `MangaSource` + строка в списке `MangaSourceEngine` (`appModule`) |
| `mihon-repo` | Ещё один репозиторий расширений Mihon (кроме Keiyoushi) | Объект `MihonRepo` (адреса индекса + SHA-256 сертификата подписи) в `manga/extensions/MihonRepo.kt`; каталог и установщик берут поля объекта. APK с чужой подписью не грузятся |
| `mihon-js` | JS-движок для расширений, которые считают страницы скриптом сайта | Реализация `evaluate` в `eu/kanade/tachiyomi/network/JavaScriptEngine.kt` (у Mihon — QuickJS, `quickjs-android`). Пока бросает понятную ошибку |
| `mihon-cloudflare` | Обход JS-проверки Cloudflare для источников расширений | Перехватчик в клиенте `eu/kanade/tachiyomi/network/NetworkHelper.kt` (у Mihon — CloudflareInterceptor на невидимом WebView). Куки WebView уже общие |
| `mihon-image-client` | Картинки страниц через клиент источника (перемешанные/подписанные страницы) | Свой фетчер Coil для страниц расширений вместо адреса+заголовков из `imageRequest` (`manga/extensions/MihonSourceAdapter.kt`) |
| `marc-catalogue-sources` | Новый библиотечный MARC21-дамп для словаря книг (например, LC Books All) | Строка в списке источников `book-dictionary/run_v3/marc_links/extract.py`; поля 041$h/240/765/880 разбираются общим парсером. Только метаданные, лицензия дампа — в README линии |
| `online-book-stores` | Онлайн-магазин или каталог книг как источник пар RU↔EN для словаря книг (Яндекс Книги, Литрес, MyBook и т. д.) | Функция `(http, title, author, lang) -> [listing]` и строка в `STORES` в `book-dictionary/run_v3/online_stores/collect_pairs.py`. Нет данных или ключа → `[]`. Выход только `CANDIDATE`, в мастер напрямую не пишется. Яндекс Книги без публичного API, не парсятся |
| `rec-source` | Источник кандидатов рекомендаций (аниме, фильмы, сериалы) | Класс `CandidateProvider` (`domain/recommendations/CandidateProvider.kt`) + строка в `providers` у `RecommendationEngine` (`appModule`). Флаг в `RecommendationFlags`, статус и условия — `docs/recommendations/sources.md` |
| `microml-label-source` | Новый источник честной метки для обучения Identity (действие пользователя, из которого следует «та же сущность» или «разные») | Метод `ContributionService.recordPair(a, b, label, source)` (`microml/contribution/ContributionService.kt`), вызывать из места, где пользователь принял решение; новое значение `source` добавить в `ContributionSchema.PAIR_SOURCES` и в `PAIR_SOURCES` у `tools/microml-collector/src/worker.js`. Без согласия метод ничего не делает; в пару идут только векторы сходства, не названия |

| `book-catalog-rerank` | Модель переранжирования каталога книг (TitleMatch/MicroML) | Точка в `BookCatalog.search` (`app/.../audiobooks/catalog/BookCatalog.kt`): после `CatalogRanker.rank`, до `take(limit)`. Вход — запрос и `CatalogBook`, выход — порядок. Нет модели → порядок `CatalogRanker` |
| `book-catalog-source` | Ещё один источник каталога книг (кроме BookIndex V2) | Источник строк в сборке индекса `book-dictionary/v2/build/build_index.py` (`load_selection`) и серверных деталей `build_server_data.py` (`load_details`). Только метаданные; флаг `redistribution_allowed` переносится в `f & 2` |
| `billing-provider` | Платёжная система для продажи ключей Vetro API на сайте (Polar.sh = card, lava.top = lava, USDT Solana = usdt) | Файл-переходник в `tools/books-billing/src/providers/` (`name`, `configured(env)`, `createCheckout`, `parseWebhook` с подписью по сырому телу; готовый образец — `polar.js`) + строка в `PROVIDERS` и, для нового метода, в `METHOD_PROVIDER` (`providers/index.js`). Не настроен → `configured() = false` → 503. Секреты только `wrangler secret put` |
| `stream-cache` | Дисковый кэш http-потоков плеера (подключён, выключен) | `media/player/StreamDiskCache.kt`: выполнить проверки из его описания (настоящие HLS/DASH, ключ для подписанных адресов, живые плейлисты, батарея и диск) и включить `ENABLED`. Плеер уже берёт кэш оттуда |
| `billing-mail` | Почтовый сервис для письма с ключом (Resend уже есть; Cloudflare Email Service, MailChannels) | Функция `({ to, subject, text }, env, fetchImpl)` и строка в `TRANSPORTS` (`tools/books-billing/src/mail.js`), выбор переменной `MAIL_TRANSPORT`. Бросает при ошибке: Worker повторит три раза и поставит `email_failed` |

Правила хвоста:
- новый источник только дополняет, не перезаписывает уже найденное;
- нет данных / выключен ключ → `null`, а не исключение;
- ключи — в `local.properties` и `local.properties.example`, файлы `*.env` в git не попадают;
- добавляя новый хвост, дописать строку в таблицу выше.
