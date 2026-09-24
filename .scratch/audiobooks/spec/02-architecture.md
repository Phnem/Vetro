# 02 · Архитектура

## Пакет и слои

Корень: `app/src/main/java/com/example/myapplication/audiobooks/` (D-01). Внутри — те же слои,
что у `manga/`:

```text
audiobooks/
├── domain/            чистый Kotlin, без Android: модели, контракты, алгоритмы
│   ├── model/         Work, Narration, ProviderVariant, MediaManifest, Chapter, BookTimeline, Bookmark…
│   ├── source/        AudiobookSource, Capability, SourceId, InfrastructureGroup, SourceFingerprint
│   ├── aggregate/     TitleNormalizer, WorkClusterer, NarrationMerger, VariantRanker, FallbackPlan
│   ├── timeline/      BookTimeline (треки ⇄ главы ⇄ глобальное время), PositionMapper
│   └── usecase/       SearchAudiobooks, OpenWork, ResolveForPlayback, AddToLibrary, SaveProgress…
├── data/              SQLDelight-репозитории, файловые кэши, DataStore-ключи
│   ├── AudiobookRepository.kt           работы/озвучки/варианты/главы
│   ├── AudiobookProgressRepository.kt   позиция, закладки, сессии прослушивания
│   ├── ManifestCacheStore.kt            файловый stale-while-revalidate кэш манифестов
│   ├── SearchCacheStore.kt              кэш выдачи на 15 мин
│   └── AudiobookSettings.kt             ключи DataStore (шаги перемотки, скорость по умолчанию…)
├── source/            адаптеры сайтов и серверов
│   ├── AudiobookSourceEngine.kt         реестр, таймауты, один retry, параллельный поиск
│   ├── http/                            общий Ktor-клиент named("audiobook"), rate-limit, robots
│   ├── local/ LocalFolderSource.kt      SAF
│   ├── abs/ AudiobookshelfSource.kt     self-hosted сервер
│   ├── librivox/ LibriVoxSource.kt
│   ├── ru/ KnigavuheSource.kt, Aknigi24MetadataSource.kt, … (после AB-05 gate)
│   └── en/ RealAudiobooksSource.kt, GoldenAudiobooksSource.kt, AudioAzSource.kt, …
├── playback/          всё, что работает в сервисе
│   ├── AudiobookPlaybackService.kt      MediaLibraryService
│   ├── AudiobookPlayerFactory.kt        ExoPlayer + LoadControl + AudioAttributes
│   ├── VetroAudioDataSource.kt          ResolvingDataSource: vetro-audio:// → URL+headers
│   ├── SessionCallback.kt               кастомные команды (−15/+30, скорость, сон, закладка)
│   ├── SleepTimer.kt, SmartRewind.kt, ProgressTracker.kt
│   └── PlaybackQueueBuilder.kt          манифест → List<MediaItem> + метаданные
├── download/          Media3 DownloadManager, DownloadService, трекинг
├── covers/            CoverResolver, CoverPaletteStore, blurhash
└── ui/
    ├── home/          BooksHomeScreen (заменяет заглушку BooksScreen), полки, морф
    ├── shelf/         ShelfPage
    ├── details/       AudiobookDetailsScreen (+ страница глав)
    ├── player/        AudiobookPlayerHost, MiniPlayer, FullPlayer, листы (главы/скорость/сон/озвучка)
    ├── search/        секция аудиокниг в поиске
    ├── components/    CoverCard, CoverFan, ShelfPlank, GlassRoundButton, GlassChip
    └── AudiobookStrings.kt
```

Правило зависимостей: `ui → domain ← data/source/playback`. `domain` не импортирует Android,
Ktor, SQLDelight — это позволяет покрыть нормализатор, кластеризацию, шкалу книги и план fallback
быстрыми JVM-тестами.

## Потоки данных

### Поиск

```text
SearchScreen (вкладка «Аудиокниги»)
  → SearchAudiobooks(query, lang)
      → AudiobookSourceEngine.search()  ── параллельно по включённым источникам с SEARCH,
      │                                    таймаут на источник, частичные результаты допустимы
      → TitleNormalizer → WorkClusterer → NarrationMerger
  → List<WorkCard(work, narrationsCount, bestCover)>  (+ Authors, Narrators)
```

### Открытие книги

```text
AudiobookDetails(workId: UUID)
  → OpenWork: берёт кластер из кэша поиска / БД
      → details() и narrations() у источников с DETAILS/NARRATIONS (лениво, по мере прокрутки)
      → CoverResolver (лучшая обложка) → палитра
  → UI: hero, чипы озвучек, главы выбранной озвучки
```

### Воспроизведение

```text
«Слушать» → ResolveForPlayback(narration)
  → FallbackPlan: варианты озвучки − недоступные, сортировка priority↓ health↓, чередование групп
  → первый вариант: chapters() + stream() → MediaManifest → ManifestCacheStore
  → PlaybackQueueBuilder: треки → MediaItem(uri = vetro-audio://<variant>/<track>) + главы в extras
  → MediaController.setMediaItems(..., startIndex, startPos из шкалы книги) → play()
Сервис:
  ExoPlayer → VetroAudioDataSource.resolve(uri) → свежий URL + заголовки (перерезолв при 403/410)
  ошибка источника → PlaybackRecovery → следующий вариант → PositionMapper → продолжить
  ProgressTracker → AudiobookProgressRepository каждые 5 с / на паузе / смене главы / onTaskRemoved
```

### Офлайн

```text
«Скачать» → AudiobookDownloadCoordinator → DownloadRequest на каждый трек (id = variant/track)
  → AudiobookDownloadService (Media3) → audiobook SimpleCache
Плеер читает через CacheDataSource(тот же кэш) → скачанное играет без сети
```

## Точки врезки в существующий код

| Где | Что меняется | Тикет |
|---|---|---|
| `gradle/libs.versions.toml` | `media3` → 1.11.x; + `media3-ui-compose`, `media3-exoplayer-workmanager` (для Requirements загрузок) | AB-02 |
| `AndroidManifest.xml` | `FOREGROUND_SERVICE_MEDIA_PLAYBACK`; `<service AudiobookPlaybackService foregroundServiceType="mediaPlayback">` с intent-filter `androidx.media3.session.MediaLibraryService` и `android.media.browse.MediaBrowserService`; `<service AudiobookDownloadService foregroundServiceType="dataSync">` | AB-07, AB-34 |
| `data/models/Anime.kt` → `MediaType` | `+ AUDIOBOOK`; `fromCategoryType("AUDIOBOOK")`; компилятор покажет все `when` (≈ 59 мест) | AB-12 |
| `core/network/.../AppContentType.kt` | `+ AUDIOBOOK` для поиска и `categoryTypeName()` | AB-32 |
| `sqldelight/.../migrations/16.sqm` + новый `Audiobook.sq` | таблицы раздела | AB-13 |
| `supabase/migrations/2026xxxx_audiobook_media_type.sql` | расширить `anime_media_type_check` | AB-12 |
| `sync/supabase/SyncRepository.kt` | пропуск/защита `AUDIOBOOK` для старых клиентов (см. spec/03) | AB-12 |
| Воркеры обогащения и апдейтов (`AnimeUpdateWorker`, `SeasonEpisodesWorker`, `FullEnrichmentWorker`, `LiveMaintenanceWorker`, `WebLinkEnrichmentWorker`, `RepairAnimeDbUseCase`, `ExternalListSyncCoordinator`, `MangaUpdateCheckUseCase`) | явный пропуск `AUDIOBOOK` | AB-12 |
| `ui/workspace/BooksScreen.kt` | заменяется на `audiobooks/ui/home/BooksHomeScreen` (сигнатура `(language, bottomInset, modifier)` сохраняется) | AB-29 |
| `ui/workspace/WorkspaceScreen.kt`, `WorkspaceDock.kt` | поиск в доке на странице «Книги» открывает поиск книг; док прячется на странице полки | AB-29, AB-30 |
| `ui/shared/WorkspaceSearch.kt` | заявка поиска знает целевую страницу | AB-32 |
| `ui/navigation/Routes.kt`, `NavGraph.kt` | `AudiobookDetailsRoute(workKey, collectionId?)`, `AudiobookShelfRoute(shelfId)` (если полка — маршрут, см. AB-04), корневой `AudiobookPlayerHost` поверх `NavHost` внутри `SharedTransitionLayout` | AB-10, AB-31 |
| `ui/home/HomeViewModel.kt` / `HomeScreen.kt` | вкладка поиска `AUDIOBOOK` → `SearchAudiobooks`; карточка книги в коллекции → детальная книги | AB-32, AB-33 |
| `ui/details/DetailsScreen.kt` | вынос общих кусков (hero-зона с листом, мини-док) в `ui/shared/components/DetailsScaffold.kt`, если прототип покажет, что копия дороже | AB-31 |
| `media/source/movieseries/ProviderHealth*.kt` | вынос в `media/source/health/` без изменения поведения | AB-25 |
| `media/cookies/MediaCookieStore.kt` | переиспользуется для кук источников | AB-15 |
| `data/ai/AiLlmFallbackRouter.kt` | опциональный ИИ-арбитр для спорных кластеров | AB-24 |
| `di/appModule.kt`, `di/viewModelModule.kt` | один блок `audiobookModule` (отдельный файл `di/audiobookModule.kt`) | AB-06 |
| Настройки источников (`PlaybackSourceSettingsService`, экран источников) | секция «Аудиокниги»: вкл/выкл, порядок, адрес Audiobookshelf, папки | AB-15, AB-16 |

## DI (Koin)

`di/audiobookModule.kt`, подключается в `MainApplication` одной строкой — фича удаляется удалением
пакета, модуля, двух `<service>` и строки подключения.

```kotlin
val audiobookModule = module {
    single(named("audiobook")) { audiobookHttpClient(get()) }
    single { AudiobookSourceEngine(sources = listOf(/* по порядку приоритета */), health = get()) }
    single { AudiobookRepository(get()) }
    single { AudiobookProgressRepository(get()) }
    single { ManifestCacheStore(androidContext()) }
    single { CoverResolver(get(named("audiobook")), get()) }
    single { AudiobookPlaybackConnection(androidContext()) } // обёртка над MediaController, одна на процесс UI
    viewModel { BooksHomeViewModel(get(), get()) }
    viewModel { (workKey: String) -> AudiobookDetailsViewModel(workKey, get(), get(), get()) }
    viewModel { AudiobookPlayerViewModel(get(), get()) }
}
```

`AudiobookPlaybackConnection` — единственное место, где UI получает `MediaController`
(`SessionToken` + `MediaController.Builder.buildAsync()`), отдаёт `StateFlow<PlayerUiState>` и
команды. Экранам `MediaController` напрямую не раздаётся.

## Процессы и жизненный цикл

- Сервис — в основном процессе (отдельный `:playback`-процесс не нужен: Koin и БД общие, а
  выгоды по памяти для аудио нет).
- Сервис стартует при первом `setMediaItems`, уходит в foreground сам (Media3 делает это при
  `playWhenReady`), останавливается через `onTaskRemoved`, если не играет.
- UI может быть уничтожен — плеер живёт; при возвращении `AudiobookPlaybackConnection`
  переподключается и восстанавливает `PlayerUiState` из сессии.
