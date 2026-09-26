# Execution log

## 2026-09-23 — План

- Разобраны сырые планы пользователя (`tecplan.txt`, `desplan.txt`), референс плеера и видео с
  полками (кадры через `D:\ffmpeg`), универсальные спеки движения и стекла из `Vetro Fixik`.
- Изучены точки врезки в код: `WorkspacePage.BOOKS`/`BooksScreen`, `MediaType`, поиск в
  `HomeViewModel`, `SourceEngine`/`ProviderHealth`, `DetailsScreen`, `FrostedGlass`,
  `MotionTokens`, манифест, SQLDelight-миграции (последняя — 15), Supabase-миграции.
- Написаны MASTER_PLAN, 14 спецификаций, тикеты фазы 0 (AB-01…AB-05).

## 2026-09-23 — AB-01

Outcome: DONE_WITH_DEVIATIONS.

- Зафиксированы Q1–Q8 пользователя; исправлены D-02, D-05, D-12, D-16 и порядок M4.
- Принят `docs/adr/0001-audiobooks-storage-and-identity.md`, создан корневой глоссарий
  `CONTEXT.md`. В спецификации SQL hash удалён из PK/FK; `WorkId`/`NarrationId` — UUID.
- `graft callers MediaType --depth 2` не нашёл индексированных входящих рёбер. Поиск `rg`
  выявил модель, local mapper, sync, Home, enrichment, updates, search, details.
- Репозиторная Supabase-миграция проверена, но живая схема не проверена: нет подключённой БД.
  Cloud sync для audiobook отключён по Q3, проверка схемы нужна до его включения.
- Baseline `./gradlew :app:compileDebugKotlin -q` прошёл. Ревью ADR и схемы: без блокирующих
  расхождений с ответами пользователя.
- Следующий тикет: AB-02, отдельный Media3 gate. До его video smoke audiobook-код не начинать.

## 2026-09-23 — AB-02

Outcome: DONE_WITH_DEVIATIONS; Media3 video gate пройден на контролируемых потоках.

- Media3 1.4.1 → 1.11.1, добавлены `media3-ui-compose` и `media3-exoplayer-workmanager`.
  Официальные [release notes](https://developer.android.com/jetpack/androidx/releases/media3)
  подтверждают стабильную 1.11.1; Kotlin 2.1.0 с ней скомпилировал проект.
- `:app:compileDebugKotlin`, `:app:assembleDebug`, оба набора unit tests и
  `:app:assembleRelease` прошли. Начальный transient Gradle cache-move failure ушёл после
  повторного запуска с одним worker.
- Эмулятор API 37: HLS, ABR (180p → 360p по `StreamTelemetry`), progressive MP4, offline MP4
  при выключенной сети — работают. Файлы smoke-фидов и скриншоты лежат во временной папке ОС,
  не в репозитории. Отмечена системная 16 KB compatibility warning для существующих
  `libdatastore_shared_counter.so`/`libandroidx.graphics.path.so`.
- PiP подтверждён: после открытия контролов нажата кнопка PiP, Activity перешла в
  `mLastReportedPictureInPictureMode=true`, видео видно поверх домашнего экрана.
- Временный debug-манифест удалён, финальный debug APK пересобран. Живые внешние источники,
  download/cancel и re-resolve не подтверждены: матрица содержит устаревшие Consumet/Gogoanime
  и jut.su video-сценарии; их актуальную проверку включили в AB-38.
- Следующий тикет: AB-03. К аудиокнигам переходим после compile, tests и пройденных
  HLS/ABR/MP4/offline/PiP video smoke, как требовал отдельный gate Q6.

## 2026-09-23 — AB-03

Outcome: DONE_WITH_DEVIATIONS. D-04 подтверждён; физический абсолютный frame gate и
перелёт из реального Details перенесены в AB-10 до интеграции прототипа.

- Изолированная ветка `codex/audiobooks-player-prototype` и worktree
  `C:\Users\2004i\.codex\worktrees\audiobooks-player-prototype\Vetro-collection`.
  Основной пакет Vetro на телефоне не заменён: прототип имеет suffix `.audioprototype`.
- Один `AudiobookPlayerHost` размещён над `NavHost`; прототип использует fake book,
  `originRect` обложки Books, `Animatable` для прибытия и раскрытия, жест и predictive back,
  материал `playerControl` и вдавливание страницы.
- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` прошли.
  На эмуляторе API 37 инструментальный тест 20 циклов прошёл дважды; второй также проверил
  обычный back полный → мини → закрытие. Видео/GIF сохранены в `reviews/assets`. Без записи
  эмулятор показал 273 janky frames из 374 (72,99%, median 48 мс): цель 0 кадров >32 мс
  ещё не подтверждена. Системный 16 KB warning относится к существующим native libraries.
- Xiaomi 2211133C (API 36) подключён, прототип установлен рядом с Vetro. MIUI отклоняет
  ADB `input tap` без включённой «USB debugging (Security settings)». Для инструментального
  кадрового smoke достаточно разблокированного экрана; настройка нужна для ручного управления.
- Попытка debug-only `showWhenLocked` Activity не помогла: MIUI оставил keyguard сверху, тест
  ждал фокуса. После ручной разблокировки Compose instrumentation сможет кликать без ADB input;
  security setting нужна только для ручных ADB-касаний.
- Удаление runtime blur над фейковым градиентом улучшило медиану эмулятора до 38 мс;
  layout/graphicsLayer-оптимизация дала 42 мс. Обе версии прошли 20 циклов, но целевые
  0 кадров >32 мс на эмуляторе не достигнуты.
- Xiaomi API 36: 20 циклов и back полный → мини → закрытие прошли. Без записи экрана
  `FrameMetrics` показал 18/2966 кадров >32 мс (прибытие 2, раскрытие 10, сворачивание 6);
  `gfxinfo` median 9 мс, p95 21–22 мс. Короткое перекрытие стеклянных контролов ухудшило
  результат до 28/2966 и было отменено. Видео/GIF Xiaomi и `gfxinfo` сохранены в
  `reviews/assets`. Последний уточняющий прогон по индексам кадров был прерван: телефон
  уснул; он не меняет вывод по завершённым прогонам.
- Следующий тикет: AB-04, прототип морфа веера в страницу полки.

## 2026-09-23 — AB-04

Outcome: DONE (изолированный прототип); выбран слой A для AB-30.

- Ветка `codex/audiobooks-shelf-prototype`, worktree
  `C:\Users\2004i\.codex\worktrees\audiobooks-shelf-prototype\Vetro-collection`.
  Отдельный debug package `.shelfprototype` установлен рядом с исходным Vetro.
- Пять fake полок на `WorkspacePage.BOOKS`. A — `Animatable`-слой с веером→рядом, заголовком,
  планкой и сеткой, свайпом вниз и Back; B — вложенный `NavHost` и `sharedElement` для трёх
  обложек и заголовка. `WorkspaceScreen` прячет док по прогрессу A/состоянию маршрута B.
- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` прошли.
  На Xiaomi API 36 Compose UI тест прошёл по 12 циклов A и B; отдельный тест проверил
  прерывание A через 100 мс и системное Back. На эмуляторе свайп закрывает A.
- Первое создание overlay при каждом открытии давало 32 кадра >32 мс за 8 раскрытий.
  Слой оставлен в композиции скрытым, без semantics и перехвата; повторный реальный замер
  после 2 warmups: A open 0/880 кадров >32 мс, p95 16,8 мс; A close 0/640, p95 11,2 мс.
  B open 32/822, p95 26,8 мс; B close 36/804, p95 30,4 мс. Замер без screenrecord и без
  Compose virtual clock. Свайп первоначально мог отскочить из-за асинхронных `snapTo`;
  исправлено накоплением drag-distance и отменой старого Job.
- Видео [слоя](reviews/assets/ab04-layer-prototype.mp4) и
  [маршрута](reviews/assets/ab04-route-prototype.mp4) сохранены. Пружины `shelfExpand`
  (ζ 0,92; stiffness 186,6) и `shelfCollapse` (ζ 1; stiffness 438,6) в изолированном worktree.
- Основная рабочая копия содержит посторонние незакоммиченные изменения; код прототипа
  не перенесён в main. Дизайн-примитивы AB-28, production морф и проверка на настоящих
  обложках AB-30. Следующий тикет: AB-05 — разведка источников и фикстуры.

## 2026-09-23 — AB-05

Outcome: DONE_WITH_DEVIATIONS. Подробное решение: `reviews/05-source-recon.md`.

- Проверены robots.txt, поиск и по две страницы 3 RU/3 EN; 101Audiobooks и Akniga отдельно;
  LibriVox/IA живым публичным API, Audiobookshelf/SAF по официальным справкам. Созданы 12
  карточек в `research/sources/` и безопасные структурные фикстуры в test resources.
- Aknigi24 медиа/API пути и Audiokniga.one поиск/HLS закрыты robots; полные адаптеры для них
  сейчас не подходят. Knigavuhe дал реальную restricted-страницу и остаётся RU кандидатом,
  но media resolve не доказан. Golden/101 используют общий `ipaudio.club`, AudioAZ — IA.
- LibriVox `title=letters` дал 404 при наличии книги 52; `title=^all` дал 200. Поиск должен
  корректно обрабатывать 404 и проходить отдельный quality smoke. IA item содержит 481 файл,
  поэтому главами не могут считаться все вложения подряд.
- В repo не сохранялись полные HTML/аудиобайты; для AB-15 остаются parser fixtures выбранного
  разрешённого источника, Range/Referer/cookie и проверка прав конкретного контента.
- Реестр spec/05 и назначение AB-17/18 обновлены. Следующий тикет: AB-06.

## 2026-09-23 — AB-06

Outcome: DONE_WITH_DEVIATIONS.

- Добавлен `audiobooks/` с `AudiobookFeatureGate(enabled=false)` через `BuildConfig`,
  отдельным Koin-модулем и RU/EN `AudiobookStrings`; заглушка «Книги» теперь использует их.
  Большой `UiStrings` не расширялся. Сервис/БД/sync ещё не подключались.
- `:app:compileDebugKotlin -q` и `:app:assembleRelease -q` прошли. Release APK unsigned,
  поэтому не устанавливался поверх существующего `com.phnem.vetro` на Xiaomi/эмуляторе.
  Проверку запуска с изолированным package включили в AB-07.
- Следующий тикет: AB-07.

## 2026-09-24 — AB-07

Outcome: DONE_WITH_DEVIATIONS.

- Добавлены same-process `AudiobookPlaybackService`, speech ExoPlayer, стандартные 15/30
  seek buttons, MediaButtonReceiver, локальный промежуточный store и callback resumption.
  Release session закрыта флагом до M1; debug открыт для теста.
- AndroidTest с синтетическим WAV прошёл на эмуляторе и Xiaomi: MediaController без Activity,
  уведомление, продолжение после stopService с ненулевой позиции. На Xiaomi первый быстрый
  прогон сохранил 0; smoke выдерживает 1,5 с до паузы и проходит. Kill/reboot и BT/звонок —
  в финальном AB-38.
- Временная подписанная release-копия с отдельным package запустилась на эмуляторе без crash/
  VerifyError. После smoke все временные Gradle-настройки убраны; финальные debug compile и
  unsigned release build прошли. На телефоне оставлены только основной Vetro и одна тестовая
  копия; четыре старых prototype package и test-runner удалены. На эмуляторе удалены старые
  prototype/runner/release package, неизвестный пакет другого агента не тронут.
- По просьбе пользователя в план добавлен AB-39: семь рекомендованных книг, 14 RU/EN
  обложек и проверка реальных источников перед AB-38. Правило одного тестового package добавлено
  в `MASTER_PLAN.md`. Следующий тикет: AB-08.

## 2026-09-24 — AB-08

Outcome: DONE_WITH_DEVIATIONS. Подробности: `issues/08-timeline-and-resolving-data-source.md`
и `reviews/08-timeline-and-resolving-data-source.md`.

- Шкала треков/глав, неизвестная и оценочная длительность, стабильный `vetro-audio` URI,
  очередь без M4B clipping, runtime manifest resolver и Media3 ResolvingDataSource готовы.
- Сервис на HTTP 401/403/410 инвалидирует manifest и делает одну попытку с прежней позиции.
  Реальный ответ HTTP и отсутствие цикла проверить в AB-17 с первым онлайн-источником.
- JVM tests и три Android tests на Xiaomi прошли; `:app:assembleRelease -q` прошёл.
  Скрипт `scripts/audiobook-smoke.ps1` обновляет единственный тестовый package поверх
  установленного и удаляет runner. После теста телефон содержит только основной Vetro
  и одну тестовую копию.
- Динамическое обновление названия главы в шторке — AB-10; потоковый кэш — AB-17.
  Следующий тикет: AB-09 Local Folder.

## 2026-09-24 — AB-09

Outcome: DONE_WITH_DEVIATIONS. Подробности: `issues/09-local-folder-source.md` и
`reviews/09-local-folder-source.md`.

- Добавлен Local Folder provider: SAF read grant, ограниченный скан, естественный порядок,
  постоянные UUID для книги/озвучки, Media3 metadata и главы, `content://` manifest.
- Три Android tests на Xiaomi прошли: сортировка, logical URI, MediaLibraryService и guard.
  Реальная выбранная папка показала `SampleBook · 2`; `dumpsys media_session` подтвердил
  `PLAYING` для нашего тестового package. По замечанию пользователя кнопка сообщает о запуске.
- Тестовые WAV и папка удалены; сохранённый SAF grant и данные нашего smoke package очищены.
  `com.phnem.vetro.perf` принадлежит другому агенту и оставлен по прямому указанию пользователя.
- Реальные M4B/ID3 CHAP файлы проверить в AB-38; полноценный playback UI — AB-10.
  Следующий тикет: AB-10.

## 2026-09-25 — AB-10, визуальная коррекция и замер

Outcome: IMPLEMENTING. Подробности: `issues/10-player-ui.md`.

- Квадратный мини-плеер, раскрытие из его текущего угла, полный экран по пользовательскому референсу и поиск локальной обложки уже находятся в отдельной ветке `codex/audiobooks-implementation`.
- Уплотнён блок названия/главы, шкала главы сделана непрерывной. Скриншоты с Xiaomi сохранены в `reviews/assets/`.
- Проверенный 20-цикловый замер до сохранения полного экрана в composition: 34/3816 кадров >32 мс. После: 2/3930. Нулевая приёмка не достигнута. Первый быстрый прогон без контроля foreground признан невалидным.
- AndroidTest на Xiaomi завис в MIUI instrumentation и был прерван; runner удалён. Debug APK собран и установлен поверх единственной нашей тестовой копии. `:app:compileReleaseKotlin --offline --max-workers=1` прошёл. UI AndroidTest после последней правки ещё требуется на свободном эмуляторе.
- Мини-плеер получил четыре RU/EN accessibility-действия перемещения в углы с той же магнитной анимацией, что после DnD. Новая проверка координат в AndroidTest скомпилировалась, но не запускалась: Xiaomi погас, эмулятор занят приложением второго агента.

## 2026-09-26 — решения Q9–Q11, AB-15 + AB-17 (Aknigi24)

Outcome: DONE (без device-smoke: по новому ритму проверяет пользователь).

- Пользователь: источники как в tecplan (сайты со стримингом, включены по умолчанию), пустой дом —
  витрина 7 книг + полки из источников, работа в этой ветке. `perf/v3.3.5` влита (конфликт в
  `LocalBooksPanel.kt` — наша версия + iOS fling). AB-10 закрыт с отклонением (гейт 2/3930 принят),
  AB-11 частично: остаток после БД.
- `AudiobookSource` (spec/05, в урезанном виде: search, details, variantOf; `Restricted`/`Failed`
  раздельно) — наследует `ManifestSource`, так что резолвер плеера находит онлайн-варианты по `VariantId`.
- `Aknigi24Source` + `Aknigi24Parser`: поиск `/search?q=`, страница `/book/<slug>`, главы из JSON
  `#player-data`, аудио `/book/<id>/chapter/<n>` (прямой MP3, Range, без подписи → `expiresAt=null`).
  404/410 → `Restricted(REMOVED)`, страница без плеера с текстом о правообладателе → `RIGHTS_HOLDER`.
  Лимит 2 rps (burst 3), общий root OkHttp.
- Фикстуры живых страниц `search-soliaris.html`, `book-soliaris.html`; `Aknigi24ParserTest` 4/4.
- Дальше: AB-12/13 — БД (work/narration/variant/progress), затем дом «Книги».

## 2026-09-26 — AB-13 (БД раздела), AB-12 отложен

Outcome: DONE_WITH_DEVIATIONS.

- `Audiobook.sq` + `16.sqm`: work / narration / variant / chapter / progress / bookmark /
  listen_session по spec/03. `Migration16Test`: обновлённая и чистая установки дают одинаковую
  схему; «Продолжить» отдаёт незаконченные книги, свежие первыми.
- `AudiobookRepository`: `saveOpened` (вариант → озвучка → произведение; озвучки одной книги
  собираются по отпечатку «имена авторов словами, отсортированы | название | язык» — начало AB-24),
  `ensureFromPlayback` (минимальные записи для локальной папки), `saveProgress` (NonCancellable),
  `continueListening` (Flow), `listenedSince`.
- `AudiobookProgressTracker` в сервисе: позиция по шкале книги, глава и смещение, скорость,
  «дослушано» (< 60 с или < 1 %); пишется там же, где очередь для системного возобновления.
- Отклонение: AB-12 (строка коллекции `MediaType.AUDIOBOOK`, guard'ы воркеров и синка) переносится
  к AB-33 «Добавить в библиотеку» — дому «Книги» он не нужен, а риск для коллекции высокий.
  Backup audiobook-таблиц — вместе с ним.
