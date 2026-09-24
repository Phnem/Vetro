# 05 · Источники и агрегация

## Контракт источника

Доработанный интерфейс из плана пользователя (что изменено — в MASTER_PLAN § «Что изменилось»):

```kotlin
interface AudiobookSource {
    val id: SourceId
    val displayName: String
    val languages: Set<BookLanguage>
    val capabilities: Set<Capability>
    val fingerprint: SourceFingerprint          // хосты → инфраструктура (статически известное)

    suspend fun search(query: String, page: Int = 0): SourceResult<List<SourceBook>>
    suspend fun details(ref: SourceBookRef): SourceResult<SourceBookDetails>
    suspend fun narrations(ref: SourceBookRef): SourceResult<List<SourceNarration>> // default: одна из details
    suspend fun chapters(ref: SourceBookRef): SourceResult<List<SourceChapter>>
    suspend fun resolve(ref: SourceBookRef): SourceResult<MediaManifest>            // бывший stream(); из него же качаем
    suspend fun byAuthor(author: String): SourceResult<List<SourceBook>> = Unsupported
    suspend fun bySeries(series: String): SourceResult<List<SourceBook>> = Unsupported
}

enum class Capability {
    SEARCH, DETAILS, NARRATIONS, CHAPTERS,
    STREAM, DOWNLOAD, OFFLINE,
    AUTHOR_PAGES, SERIES_PAGES,
    AUTH_REQUIRED,          // источник требует вход — в V1 такие не подключаем (кроме своего Audiobookshelf)
    RIGHTS_VERIFIED,        // контент лицензирован/общественное достояние/свой файл
    METADATA_ONLY,          // только поиск/описания, без аудио (Akniga)
}

data class SourceFingerprint(
    val catalogueHosts: Set<String>,
    val mediaHosts: Set<String>,             // заранее известные; дополняются из фактических URL
    val declaredGroup: InfrastructureGroup?, // например "ipaudio.club" для Golden + 101
)

sealed interface SourceResult<out T> {
    data class Ok<T>(val value: T) : SourceResult<T>
    data object Unsupported : SourceResult<Nothing>
    data class Restricted(val reason: Availability) : SourceResult<Nothing> // правообладатель / удалено
    data class Failed(val kind: FailureKind, val cause: Throwable?) : SourceResult<Nothing> // NETWORK, BLOCKED, PARSE, TIMEOUT, RATE_LIMITED
}
```

`Restricted` и `Failed` — разные вещи: первое исключает **этот вариант** и запускает поиск
доступной той же озвучки у других независимых источников (D-12); второе — технический сбой,
для которого работают повтор и fallback.

Metadata-only источник полезен без воспроизведения: обогащает карточку, находит озвучки и дубли.

## Реестр источников

| Приоритет | Источник | Язык | Роль после AB-05 | Подтверждено / условно | Инфраструктура |
|---|---|---|---|---|---|
| — | **Локальная папка** (SAF) | любой | первый offline-якорь | SAF API подтверждён; SEARCH, DETAILS, CHAPTERS, STREAM, OFFLINE, RIGHTS_VERIFIED после device-тестов AB-09 | `local` |
| — | **Audiobookshelf** (свой сервер) | любой | свой сервер | API документирован, но справка устарела; AUTH_REQUIRED, остальные возможности после contract test | `abs:<server-scope>` |
| — | **LibriVox** | EN (+RU мало) | открытый каталог | SEARCH, DETAILS, CHAPTERS в живом API; STREAM/DOWNLOAD и права конкретной записи/региона проверить | `librivox.org` / фактический media-host |
| — | **Internet Archive** | любой | открытый архив | SEARCH, DETAILS, files подтверждены; STREAM/DOWNLOAD после отбора item/лицензии/Range | `archive.org` |
| RU 1 | **Knigavuhe** | RU | первый RU кандидат | SEARCH, DETAILS, NARRATIONS, метаданные CHAPTERS; STREAM требует разрешённого resolve-probe; явный Restricted подтверждён | `knigavuhe.org` предварительно |
| RU мета | **Aknigi24** | RU | метаданные | SEARCH, DETAILS, METADATA_ONLY; robots закрывает `/api/` и book media paths | `aknigi24.com`, медиа неизвестно |
| RU мета | **Audiokniga.one** | RU | ручные детали/сборники | DETAILS, METADATA_ONLY; robots закрывает форму поиска и HLS paths | `audiokniga.one`, медиа неизвестно |
| RU рез. | **Akniga** | RU | пока только резерв метаданных | METADATA_ONLY; robots закрывает `/stream/`, `/ajax/`; поиск/детали ещё не проверены | `akniga.org` предварительно |
| RU рез. | AudioTales, Audio-Knigi-Online | RU | резерв | по AB-05 | по AB-05 |
| RU рез. | Audioboo | RU | последний рубеж (нестабилен, 403) | по AB-05 | по AB-05 |
| EN 1 | **RealAudiobooks** | EN | исследовательский кандидат | SEARCH, DETAILS, CHAPTERS; MP3 в HTML, но STREAM до проверки прав и live smoke не включать | `ipaudio7.com` |
| EN 2 | **GoldenAudiobooks** | EN | исследовательский кандидат | SEARCH, DETAILS, CHAPTERS; MP3 в HTML, права/Range не проверены | `ipaudio.club` |
| EN 2′ | **101Audiobooks** | EN | общий резерв Golden | SEARCH, DETAILS, CHAPTERS; MP3 в HTML, права/Range не проверены | `ipaudio.club` (+ родственные хосты проверить) |
| EN 3 | **AudioAZ** | EN | разные озвучки, ссылка на IA | SEARCH, DETAILS, NARRATIONS, CHAPTERS; STREAM только после проверки IA item | `archive.org` для найденных треков |

Карточки и датированные свидетельства — в `research/sources/`. `robots.txt` регулирует обход,
но сам по себе не доказывает разрешение на распространение записи. `STREAM`/`DOWNLOAD`
у исследовательских кандидатов не включать из одного лишь наличия URL в HTML.
План AB-17 переключается с Aknigi24 на первый подтверждённо пригодный RU источник; текущий
кандидат — Knigavuhe, с отдельной проверкой разрешённого media resolve. Если он не пройдёт,
AB-17 должен завершиться метаданными, а первым полным онлайн-источником станет LibriVox/IA.

Приоритеты — стартовые веса `basePriority` (100, 80, 60…); пользователь может переставить
порядок в настройках источников; динамика — через здоровье (ниже). Законные якоря всегда
первые, когда у них есть совпадение: свой файл лучше любого сайта.

## Общая HTTP-инфраструктура (AB-15)

- `HttpClient` Ktor `named("audiobook")`: браузерный UA (как `named("weblink")`), таймауты
  connect 8 с / request 15 с, редиректы, gzip.
- **Rate-limit на хост**: токен-бакет 2 запроса/с, 1 параллельный запрос на страницу книги.
  Параллельный поиск идёт по разным хостам, а не долбит один.
- **robots.txt**: `RobotsPolicy` читает и кэширует `robots.txt` каждого хоста на сутки; адаптер
  объявляет, какие пути трогает; запрос к запрещённому пути не уходит (`Failed(BLOCKED)`).
  Покрыто тестом на фикстуре robots.
- Куки — `MediaCookieStore`; ни куки, ни подписи URL в лог не пишутся (`safeSyncError`-подобный
  санитайзер для логов раздела).
- Никакого обхода антибота, капч, DRM, «мостов» через headless-браузер. Источник закрылся →
  `Failed(BLOCKED)` → здоровье падает → источник тихо уходит вниз.
- Парсинг HTML — `jsoup` (уже в каталоге), JSON — kotlinx.serialization.

### Стенд фикстур

Каждый адаптер тестируется на сохранённых ответах (`app/src/test/resources/audiobooks/<source>/`):
страница поиска, книги, плейлиста/глав, robots. Ktor `MockEngine` отдаёт фикстуры по URL.
Отдельный ручной «живой» прогон (`@LiveSource`-тесты, исключённые из CI) показывает, не
поменялась ли разметка сайта. Разведка AB-05 собирает первые фикстуры.

## Агрегатор

### Поиск

```text
query ─► для каждого включённого источника с SEARCH (параллельно, таймаут 6 с, результаты стримятся)
       ─► SourceBook[] ─► TitleNormalizer ─► WorkClusterer ─► NarrationMerger ─► WorkCard[]
```

UI получает `Flow`: первые карточки появляются от самого быстрого источника, потом карточки
«дозревают» (растёт счётчик озвучек) без перескоков списка — порядок фиксируется по первому
появлению + релевантности, новые кластеры добавляются в конец текущей страницы.

### Нормализатор

Для **сравнения** (отображается оригинальный текст):

- регистр → нижний; `ё → е`; Unicode NFKC; кавычки/тире/многоточия → ASCII или пробел;
- выкинуть шум: «аудиокнига», «слушать онлайн», «читает …», «(сборник)», «[… кбит/с]»,
  «unabridged», «audiobook», «read by …», годы в скобках, номера томов в хвосте — **сохранив**
  номер как `seriesIndex`/`volume` отдельным полем;
- авторы: «Фамилия Имя Отчество» / «Имя Фамилия» / «И. О. Фамилия» → множество фамилий + инициалы;
  латиница и кириллица не сводятся транслитом (RU и EN — разные каталоги), кроме явного
  совпадения `titleOriginal`;
- токены названия: стоп-слова убраны, стемминг не нужен.

### Кластеризация произведений

1. **Блокировка** по множеству фамилий авторов (пустой автор — отдельный блок по названию).
2. Внутри блока — сходство названий: Jaccard по токенам ≥ 0,8 **или** одно название является
   префиксом другого на границе токена (подзаголовки), с одинаковым `volume`.
3. Сборник ≠ отдельное произведение, даже при совпадении названия (флаг `isCollection`).
4. Спорные пары (0,6–0,8) — либо остаются раздельными (по умолчанию), либо, если включён ИИ,
   решаются `AiLlmFallbackRouter` одним батч-запросом на выдачу; ответ кэшируется по паре ключей.

Отпечаток кластера (`clusterFingerprint`) ускоряет поиск кандидатов и может измениться при
улучшении нормализатора. Постоянный `WorkId` — UUID из локальной БД; алгоритм кластеризации
никогда не создаёт identity из hash. После AB-17 реализуем и тестируем нормализатор (AB-24),
на AB-18 доказываем склейку на выдаче двух разных сайтов, затем пишем fallback/PositionMapper.

### Склейка озвучек

Внутри произведения варианты склеиваются в одну озвучку, если:
- множества нормализованных чтецов совпадают (или у одного из вариантов чтец не указан, а
  длительность совпадает ≤ 2 %), **и**
- длительности отличаются ≤ 3 % (если известны).

Один чтец, две записи разной длительности (> 3 %) — две озвучки с подписью длительности.
«Чтец не указан» без длительности — отдельная озвучка «Неизвестный чтец» внизу списка.

### Выбор озвучки по умолчанию

`score = доступность (есть вариант AVAILABLE со здоровым источником) × 100 + число вариантов × 5
+ совпадение языка UI × 20 + «полнота» (unabridged, не TTS) × 10 − штраф за подозрительно
короткую длительность`. Выбор пользователя запоминается в `audiobook_work.selected_narration_id`.

## План fallback (FallbackPlan)

Для выбранной озвучки:

```text
1. кандидаты = варианты озвучки
2. убрать: availability ∈ {RESTRICTED_BY_RIGHTSHOLDER, REMOVED, NEEDS_AUTH (если нет входа)},
          источники выключены пользователем, health.isDisabledAt(now), группа отключена
3. если есть скачанный вариант — он первый, без сети
4. сортировка: userPinned↓, basePriority − health.penalty − groupPenalty ↓, lastVerifiedAt↓
5. перестановка «чередование групп»: после кандидата из группы G следующий берётся из другой
   группы, если такая есть; кандидаты той же группы — только после всех остальных
6. пробуем по очереди: resolve() (таймаут 8 с) → первый трек открывается → успех
7. все упали → ошибка «Не удалось загрузить» + «Выбрать другую озвучку»
```

### Здоровье

- Переиспользуем `ProviderHealthPolicy` (`media/source/movieseries/ProviderHealth.kt`), вынесенный
  в `media/source/health/` без изменения поведения (D-11); ключи `audiobook:<sourceId>`.
- Дополнительно ведётся здоровье **группы** `audiobook-infra:<group>`: сбой уровня медиа-хоста
  (таймаут, 5xx, DNS) пишется и источнику, и группе; сбой разметки страницы (PARSE) — только
  источнику.
- Фактический медиа-хост каждого успешного резолва записывается: если два источника фактически
  отдают с одного хоста, они попадают в одну группу автоматически («выученный отпечаток»),
  даже если в `fingerprint` это не объявлено.

### Права и ограничения

- `Restricted(RESTRICTED_BY_RIGHTSHOLDER)` у варианта → вариант помечается, в UI озвучки
  подпись «Недоступно у источника по запросу правообладателя». Агрегатор автоматически ищет
  ту же озвучку у других независимых источников и предлагает доступный вариант. Ограниченный
  URL не запрашивается повторно; обход DRM, авторизации и защиты сайта не допускается.
- `RIGHTS_VERIFIED` источники (свой файл, свой сервер, общественное достояние) в выдаче имеют
  бейдж и приоритет при равной релевантности.

## Законные якоря — детали

| Источник | Как | Заметки |
|---|---|---|
| Локальная папка | SAF `OpenDocumentTree`, отдельный audiobook guard не пускает Download/корень и разрешает целевую библиотеку `Audiobooks`; подпапка = книга; `MediaMetadataRetriever` для базовых тегов; M4B Nero/QuickTime и MP3 ID3 `CHAP` — `Chapter` metadata в Media3 1.11.1 | Собственный парсер M4B не нужен: использовать `media3-inspector` для metadata без playback; сортировка файлов — естественная (`2 < 10`) |
| Audiobookshelf | REST API сервера пользователя: `/api/libraries`, `/api/items/{id}`, `/api/items/{id}/play` → треки + главы; токен в зашифрованном сторе (`PlaybackSourceCredentialsStore`) | прямой аналог Jellyfin-адаптера из movie-series-playback |
| LibriVox | `librivox.org/api/feed/audiobooks?format=json&title=…` (метаданные, секции), `listen_url` и связанный `url_iarchive` при наличии | публичные записи в США; права конкретной записи и региона проверить перед загрузкой, соблюдать лимит API |
