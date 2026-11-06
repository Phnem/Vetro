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

Правила хвоста:
- новый источник только дополняет, не перезаписывает уже найденное;
- нет данных / выключен ключ → `null`, а не исключение;
- ключи — в `local.properties` и `local.properties.example`, файлы `*.env` в git не попадают;
- добавляя новый хвост, дописать строку в таблицу выше.
