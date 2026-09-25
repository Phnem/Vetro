# Vetro Audiobooks — Master Plan

## Workflow

Current workflow state: IMPLEMENTING_TICKET
Current ticket: AB-10
Last completed ticket: AB-09 (DONE_WITH_DEVIATIONS: SAF catalog and Xiaomi playback pass)
Next eligible ticket: AB-10
Last updated: 2026-09-25 (AB-10 visual revision and Xiaomi frame profiling; frame gate still open)

## Цель

Добавить в Vetro Collection **пятый тип контента — аудиокниги** — рядом с аниме, фильмами,
сериалами и мангой. Раздел ощущается родной частью Vetro: та же навигация, стекло, токены движения
и local-first библиотека; свой дом «Книги» с полками; детальная страница по образцу Details;
кастомный плеер с матовым стеклом, мини-плеер поверх всего приложения, фоновое воспроизведение,
системный media-control, офлайн и агрегация нескольких источников с незаметным fallback.

> **Манифест ветки.** Audiobooks in Vetro Collection should feel like a calm premium library
> space: cover-first, shelf-based, softly animated, with subtle atmospheric depth, matte-glass
> controls, and seamless transitions from discovery to listening.

## Карта документов

| Документ | О чём |
|---|---|
| [`README.md`](./README.md) | как пользоваться папкой |
| [`references/REFERENCES.md`](./references/REFERENCES.md) | все референсы с путями на ПК |
| [`references/VIDEO_BREAKDOWN.md`](./references/VIDEO_BREAKDOWN.md) | покадровый разбор видео |
| [`spec/01-product.md`](./spec/01-product.md) | что получает пользователь, сценарии, границы V1 |
| [`spec/02-architecture.md`](./spec/02-architecture.md) | пакеты, слои, потоки данных, точки врезки в код |
| [`spec/03-domain-and-storage.md`](./spec/03-domain-and-storage.md) | Work → Narration → Variant → Manifest → Chapter, схема БД, синк |
| [`spec/04-playback-engine.md`](./spec/04-playback-engine.md) | Media3, сервис, сессия, шторка, DataSource, таймер сна |
| [`spec/05-sources-and-aggregation.md`](./spec/05-sources-and-aggregation.md) | контракт источника, адаптеры, агрегатор, fallback, здоровье |
| [`spec/06-offline.md`](./spec/06-offline.md) | скачивание книг и глав |
| [`spec/07-design-language.md`](./spec/07-design-language.md) | токены, материалы стекла, движение для раздела |
| [`spec/08-screen-books-home.md`](./spec/08-screen-books-home.md) | дом «Книги», полки, морф веер→полка |
| [`spec/09-screen-details.md`](./spec/09-screen-details.md) | детальная страница книги |
| [`spec/10-screen-player.md`](./spec/10-screen-player.md) | полноэкранный плеер и мини-плеер |
| [`spec/11-search.md`](./spec/11-search.md) | вкладка «Аудиокниги» в поиске |
| [`spec/12-covers-and-atmosphere.md`](./spec/12-covers-and-atmosphere.md) | качество обложек, палитра, фон |
| [`spec/13-quality.md`](./spec/13-quality.md) | тесты, smoke-матрица, бюджеты производительности |
| [`spec/14-risks-and-open-questions.md`](./spec/14-risks-and-open-questions.md) | риски, правовые рамки, вопросы к пользователю |
| [`issues/`](./issues/) | тикеты фазы 0 (остальные нарезаются в начале своей фазы) |

## Ключевые решения

Решения Q1–Q8 подтверждены пользователем 2026-09-23 и записаны в [spec/14](./spec/14-risks-and-open-questions.md#решения-пользователя).

| # | Решение | Почему |
|---|---|---|
| D-01 | Весь код раздела — изолированный пакет `com.example.myapplication.audiobooks` (domain / data / source / playback / download / ui), не Gradle-модуль. | Прецедент `manga/` и `localplayer/`; удаление фичи «в один клик»; быстрее сборка без межмодульных API. |
| D-02 | Запись библиотеки = строка коллекции (`anime`) с `MediaType.AUDIOBOOK` плюс отдельные таблицы аудиокниг. Синк audiobook-строк в Supabase выключен до релиза совместимой версии. | Общие рейтинг, избранное, теги, заметки, бэкап и UI; трёхуровневая модель отдельно. |
| D-03 | Дом раздела — существующая страница рабочей области `WorkspacePage.BOOKS` (заглушка `BooksScreen.kt`). | Гнездо дока и страница пейджера уже заведены «под контракт раскладки». |
| D-04 | Плеер — **один непрерывный объект** «мини ⇄ полный» в корне UI (`AudiobookPlayerHost` над `NavHost`), а не отдельный маршрут. | Закон 1 (никакой телепортации): мини-плеер и полный экран — одна поверхность; обходит известный краш `layerBackdrop` внутри `sharedBounds`. |
| D-05 | Воспроизведение — отдельный Android component `MediaLibraryService` (Media3) **в основном процессе**; `android:process` не задаём. UI говорит с ним через `MediaController`. | Фон, lockscreen, Bluetooth, шторка, восстановление после убийства, задел под Android Auto без лишней межпроцессной сложности. |
| D-06 | Media3 обновляется **для всего приложения** с 1.4.1 до актуальной 1.11.x отдельным тикетом-гейтом с регрессией видео. | Все артефакты Media3 обязаны быть одной версии; `media3-ui-compose` в 1.4.1 нет. |
| D-07 | `media3-ui-compose` — только state-holder'ы; ни `PlayerView`, ни Material-контролов. | Требование пользователя; весь визуал — Vetro. |
| D-08 | Адрес потока в плеере — виртуальный URI `vetro-audio://…`, реальный URL и заголовки подставляет `ResolvingDataSource` в момент открытия. | Подписанные ссылки протухают; перерезолв без пересборки плейлиста и без потери позиции. |
| D-09 | Позиция хранится в **времени книги** (глобальная шкала озвучки), а не «файл + смещение». Переход между вариантами — через разметку глав. | Разные источники режут одну запись на разное число файлов; fallback посреди книги не должен терять место. |
| D-10 | Fallback группируется по **инфраструктуре** (`infrastructureGroup` по медиа-хосту), здоровье ведётся и по источнику, и по группе. | Два «разных» сайта на одном CDN — одна точка отказа (GoldenAudiobooks + 101Audiobooks → `ipaudio.club`). |
| D-11 | Переиспользуем `ProviderHealthPolicy` из `media/source/movieseries/` (вынос в общий пакет `media/source/health/`). | Та же логика backoff/штрафа уже проверена на фильмах. |
| D-12 | Если вариант помечен «ограничено правообладателем», исключаем его из воспроизведения и автоматически ищем ту же озвучку у других независимых источников. Не обходим ограничения самого сайта, DRM и авторизацию. | Пользователь сохраняет доступ к доступным вариантам; ограничение конкретного источника соблюдается. |
| D-13 | Помимо сайтов из плана — локальная папка (SAF, MP3/M4B), Audiobookshelf-сервер, LibriVox/Internet Archive. | Локальная папка — эталон для player/timeline/offline; раздел работает без сайтов. |
| D-14 | Скачивание — Media3 `DownloadManager` + свой `DownloadService`, отдельный от видео `SimpleCache`. | Требование плана; офлайн через тот же `CacheDataSource`, докачка, Requirements (Wi-Fi). |
| D-15 | Строки раздела — отдельный `AudiobookStrings` + `getAudiobookStrings(lang)`, **не** `UiStrings`. | Лимит 255 параметров конструктора роняет release. |
| D-16 | `WorkId` — UUID, постоянная внутренняя идентичность произведения; прежний SHA-1 нормализованных полей — изменяемый `clusterFingerprint`, не PK и не FK. | Перенормализация и улучшение кластеризации не меняют идентичность книги и не требуют переноса прогресса по hash. |

## Что изменилось относительно сырых планов

| Было в плане | Стало | Причина |
|---|---|---|
| Media3 1.11.1 «в отдельном слое» | Одна версия Media3 на всё приложение, апгрейд — гейт AB-02 | Смешивать версии Media3 нельзя; сейчас в каталоге 1.4.1. |
| `MediaSessionService` | `MediaLibraryService` (надмножество) | Возобновление из системного UI и Android Auto почти бесплатно. |
| Mini → fullscreen «плавный переход» без уточнений | Один объект в корне UI с прогрессом раскрытия | см. D-04. |
| Скачанная книга «по главам» | По главам — только если глава = набор целых файлов; иначе книга/файл целиком | Внутри одного M4B главу не вырезать без перекодирования. |
| Knigavuhe: «ограничено правообладателем» → fallback на следующий источник | Ограниченный вариант не открываем; автоматически ищем ту же озвучку у других независимых источников | D-12, решение Q4. |
| Интерфейс `AudiobookSource` с `download()` | `download` убран: скачивание работает поверх того же `stream`-манифеста | Одна точка резолва, меньше расхождений. Добавлены `narrations()`, `health` и `fingerprint`. |
| Только сайты | + локальная папка, Audiobookshelf, LibriVox | D-13. |
| Details «большая обложка на весь hero» | Hero = центрированная обложка на размытом фоне из неё же | Портретная обложка, растянутая на ширину, режется и мылится. |

## Глобальные ограничения

- Весь код — в `com.example.myapplication.audiobooks`; R-класс — `com.phnem.vetro.R`.
- Пружины и длительности — только из `MotionTokens` (новые токены добавляются туда же).
- Новое стекло — новый материал в `FrostedMaterials`, не разовые альфы по месту.
- Никогда не добавлять/убирать узел-модификатор над `layerBackdrop`; не класть `layerBackdrop`
  внутрь `sharedBounds`/`sharedElement`.
- Новые строки — только `AudiobookStrings`; `UiStrings` не трогаем.
- Переводы строк: патчить с сохранением стиля файла; `git diff --stat` = `git diff --ignore-cr-at-eol --stat`.
- Не обходим антибот-защиту, DRM, авторизацию и `robots.txt`; уважаем снятия правообладателей.
- Секреты и куки не пишутся в лог и в персистентные манифесты.
- Один активный тикет за раз; каждый тикет проверяется release-сборкой, если трогал UI-строки или DI.
- На телефоне сохранять только основной `com.phnem.vetro` и **один** тестовый audiobook package.
  Новые тестовые APK ставить поверх него (`adb install -r`); после прогона удалять test-runner
  package и временные варианты. Не переустанавливать основной пакет ради smoke.

## Фазы и вехи

Каждая веха — вертикальный срез, который можно показать на устройстве.

| Веха | Что можно сделать руками | Тикеты |
|---|---|---|
| **M0 Гейты** | — (решения, прототипы, разведка) | AB-01 … AB-05 |
| **M1 «Слушаю свой файл»** | Выбрать папку с MP3/M4B → слушать в своём плеере, в фоне, со шторкой, с мини-плеером | AB-06 … AB-11 |
| **M2 «Книга в коллекции»** | Книга живёт в коллекции, прогресс/закладки переживают перезапуск, синк не ломается | AB-12 … AB-14 |
| **M3 «Первый онлайн-источник»** | Найти книгу у одного источника и слушать онлайн | AB-15 … AB-17, AB-27 |
| **M4 «Агрегация»** | После первого источника строим кластеризацию, проверяем её на втором сайте и затем делаем fallback/перенос позиции; остальные адаптеры идут позже | AB-24 → AB-18 → AB-25/26 → AB-16, AB-19…23 |
| **M5 «Раздел целиком»** | Дом с полками, морф веер→полка, детальная страница, вкладка поиска | AB-28 … AB-33 |
| **M6 «Офлайн»** | Скачать книгу/главы и слушать без сети | AB-34 |
| **M7 «Релиз»** | Полировка движения, доступность, рекомендации, витрина из 7 книг, QA, release | AB-35 … AB-39 (AB-38 — финальный QA) |

## Тикеты

| ID | Фаза | Название | Блокирует | Документ |
|---|---|---|---|---|
| AB-01 | 0 | ADR: хранение и доменная модель | — | [issues/01](./issues/01-adr-storage-and-domain.md) |
| AB-02 | 0 | Апгрейд Media3 до 1.11.x + регрессия видео | — | [issues/02](./issues/02-media3-upgrade.md) |
| AB-03 | 0 | Прототип: хост плеера мини⇄полный + стекло над обложкой | AB-02 | [issues/03](./issues/03-prototype-player-host.md) |
| AB-04 | 0 | Прототип: морф веер→полка, бюджет кадров | — | [issues/04](./issues/04-prototype-shelf-morph.md) |
| AB-05 | 0 | Разведка источников + фикстуры | — | [issues/05](./issues/05-source-recon.md) |
| AB-06 | 1 | Каркас пакета, DI, `AudiobookStrings`, флаг фичи | AB-01 | spec/02 |
| AB-07 | 1 | `AudiobookPlaybackService`, ExoPlayer, сессия, шторка, возобновление | AB-02, AB-06 | spec/04 |
| AB-08 | 1 | Шкала книги: треки/главы, `ResolvingDataSource`, перерезолв URL | AB-07 | spec/04 |
| AB-09 | 1 | Источник «Локальная папка» (SAF) + главы M4B/ID3 CHAP | AB-08 | spec/05 |
| AB-10 | 1 | Мини-плеер и полный плеер (контролы, прогресс, скорость, главы); интеграция Details origin и физический frame gate из AB-03 | AB-03, AB-08 | spec/10 |
| AB-11 | 1 | Таймер сна, закладки, «умный откат», пропуск тишины | AB-10 | spec/04, spec/10 |
| AB-12 | 2 | `MediaType.AUDIOBOOK`: Home «Все», guard'ы воркеров и синка; audiobook-строки локальны до совместимого релиза | AB-01 | spec/03 |
| AB-13 | 2 | Схема SQL (миграция 16), репозитории, сохранение позиции и полный локальный backup audiobook-данных | AB-12 | spec/03 |
| AB-14 | 2 | Сессии прослушивания и статистика | AB-13 | spec/03 |
| AB-15 | 3 | Контракт `AudiobookSource`, HTTP-клиент, rate-limit, стенд фикстур | AB-05 | spec/05 |
| AB-16 | 4 | Законные якоря: LibriVox/Archive.org, Audiobookshelf | AB-26 | spec/05 |
| AB-17 | 3 | Первый пригодный RU источник (Knigavuhe кандидат); media resolve gate | AB-15 | spec/05 |
| AB-18 | 4 | Второй разрешённый источник; проверка агрегации двух сайтов | AB-24 | spec/05 |
| AB-19 | 4 | RU №3 Knigavuhe | AB-26 | spec/05 |
| AB-20 | 4 | EN №1 RealAudiobooks | AB-26 | spec/05 |
| AB-21 | 4 | EN №2 GoldenAudiobooks (+101Audiobooks как одна инфраструктура) | AB-26 | spec/05 |
| AB-22 | 4 | EN №3 AudioAZ | AB-26 | spec/05 |
| AB-23 | 4 | Резерв (Akniga как metadata-only, AudioTales, Audio-Knigi-Online, Audioboo) — опционально | AB-26 | spec/05 |
| AB-24 | 4 | Нормализатор и кластеризация Work/Narration | AB-17 | spec/05 |
| AB-25 | 4 | Резолвер fallback: приоритет, здоровье, инфраструктурные группы, права | AB-18 | spec/05 |
| AB-26 | 4 | Перенос позиции между вариантами | AB-25, AB-13 | spec/03 |
| AB-27 | 5 | Резолвер обложек, палитра, blurhash, кэш | AB-15 | spec/12 |
| AB-28 | 5 | Дизайн-примитивы раздела (материалы, токены, обложка, планка) | AB-03, AB-04 | spec/07 |
| AB-29 | 5 | Дом «Книги»: hero, «Продолжить», полки, каскад | AB-28, AB-13 | spec/08 |
| AB-30 | 5 | Страница полки + морф веер→полка | AB-29 | spec/08 |
| AB-31 | 5 | Детальная страница книги | AB-28, AB-25 | spec/09 |
| AB-32 | 5 | Вкладка поиска «Аудиокниги» | AB-24 | spec/11 |
| AB-33 | 5 | Добавление в библиотеку + минимальный AddEdit | AB-31, AB-32 | spec/09 |
| AB-34 | 6 | Офлайн: DownloadManager/Service, UI загрузок, офлайн-плей | AB-13, AB-25 | spec/06 |
| AB-35 | 7 | Проход полировки движения, reduced motion, доступность | AB-30, AB-31 | spec/07 |
| AB-36 | 7 | «Подобрано для вас» и статистика на доме | AB-14, AB-29 | spec/08 |
| AB-37 | 7 | Android Auto: дерево обзора (опционально) | AB-07 | spec/04 |
| AB-39 | 7 | Релизная витрина: 7 указанных книг, автоматическое получение и кэш обложек RU/EN, проверенные источники и состояния доступности | AB-29, AB-31, AB-25, AB-27 | spec/08, issues/39 |
| AB-38 | 7 | Финальный QA: smoke-матрица, производительность, release, память проекта; реальные M4B/ID3 CHAP и 16 KB page-size native libs | всё, включая AB-39 | spec/13 |

Рекомендуемый порядок: AB-01 → **AB-02 как отдельный Media3 gate с compile и video smoke до любого audiobook-кода** → остальные M0 → M1 → M2 → AB-15 → AB-17 → AB-24 → AB-18 → AB-25 → AB-26 → прочие источники → M5 → M6 → M7.
Тикеты фазы нарезаются в `issues/NN-slug.md` в начале фазы (по конвенции `docs/agents/issue-tracker.md`).

## Команды проверки

Быстро:

```bash
./gradlew :app:compileDebugKotlin -q
```

Тесты раздела:

```bash
./gradlew :app:testDebugUnitTest --tests "*Audiobook*"
```

Полная (обязательна для тикетов, трогающих строки, DI, манифест, Media3):

```bash
./gradlew :app:testDebugUnitTest :core:network:testDebugUnitTest :app:assembleRelease
```

## Не-цели V1

См. [spec/01-product.md § Вне рамок V1](./spec/01-product.md#вне-рамок-v1).
