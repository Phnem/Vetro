# 03 · Доменная модель и хранение

## Уровни

```text
Work (произведение)                   «Мастер и Маргарита» · Булгаков
 └─ Narration (озвучка)               читает Вячеслав Герасимов · 16 ч 52 мин
     └─ ProviderVariant (вариант)     aknigi24 / audiokniga.one / knigavuhe …  — скрыт от пользователя
         └─ MediaManifest (манифест)  треки: url, headers, mime, длительность   — живёт часы
             └─ Chapter[]             логические главы на шкале книги
```

Пользователь выбирает только **озвучку**. Варианты — внутренняя механика fallback.

## Модели (domain, чистый Kotlin)

```kotlin
@JvmInline value class WorkId(val value: String)         // UUID — вечная внутренняя личность произведения
@JvmInline value class NarrationId(val value: String)    // UUID — вечная личность озвучки (к ней привязан прогресс)
@JvmInline value class ClusterFingerprint(val value: String) // hash нормализованных полей — ТОЛЬКО для сопоставления (см. ниже)
@JvmInline value class VariantId(val value: String)      // "<sourceId>:<sourceBookId>"
@JvmInline value class SourceId(val value: String)       // "aknigi24", "local", "abs:<host>"
@JvmInline value class InfrastructureGroup(val value: String) // "ipaudio.club", "aknigi24-cdn"

data class Work(
    val id: WorkId,
    val fingerprint: ClusterFingerprint,
    val title: String,
    val titleOriginal: String?,
    val authors: List<Person>,
    val series: SeriesRef?,              // «Ведьмак», №3
    val description: String?,
    val genres: List<String>,
    val language: BookLanguage,          // RU / EN / OTHER
    val year: Int?,
    val isCollection: Boolean,           // сборник/антология
    val cover: CoverRef?,
)

data class Narration(
    val id: NarrationId,
    val workId: WorkId,
    val fingerprint: ClusterFingerprint, // нормализованные чтецы + корзина длительности
    val narrators: List<Person>,         // пусто = «чтец не указан»
    val kind: NarrationKind,             // SOLO, CAST, RADIO_PLAY, ABRIDGED, AUTO_TTS
    val durationMs: Long?,               // медиана по вариантам
    val chapterCount: Int?,
    val variants: List<ProviderVariant>,
)

data class ProviderVariant(
    val id: VariantId,
    val source: SourceId,
    val sourceUrl: String,               // страница книги у источника (для «открыть на сайте»)
    val durationMs: Long?,
    val chapterCount: Int?,
    val bitrateKbps: Int?,
    val availability: Availability,      // AVAILABLE, RESTRICTED_BY_RIGHTSHOLDER, REMOVED, NEEDS_AUTH, UNKNOWN
    val infrastructure: InfrastructureGroup?, // известна после первого резолва
    val lastVerifiedAt: Long?,
)

data class MediaManifest(
    val variant: VariantId,
    val tracks: List<AudioTrack>,
    val chapters: List<Chapter>,         // если источник глав не знает — по одной на трек
    val resolvedAt: Long,
    val expiresAt: Long?,                // из подписи URL или политики источника
)

data class AudioTrack(
    val index: Int,
    val url: String,
    val headers: Map<String, String>,    // только разрешённые к персисту — см. VetroModels.isPersistableHeader
    val mimeType: String?,
    val durationMs: Long?,               // null — узнаем от плеера и допишем в кэш
    val sizeBytes: Long?,
)

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,                   // на ШКАЛЕ КНИГИ
    val durationMs: Long?,               // null — конец главы/файла пока неизвестен
)
```

`AudioTrack.headers` проходят тот же фильтр, что `VetroVideo` (`isPersistableHeader`,
`withoutPersistedSecrets`): в кэш манифеста пишутся только `User-Agent/Accept/Referer/Origin`,
куки берутся из `MediaCookieStore` в момент запроса.

## Шкала книги (BookTimeline)

Центральная структура для позиции, прогресса, глав и переноса между вариантами.

```kotlin
class BookTimeline(tracks: List<AudioTrack>, chapters: List<Chapter>) {
    val totalMs: Long
    fun toGlobal(trackIndex: Int, offsetMs: Long): Long
    fun toTrack(globalMs: Long): Pair<Int, Long>          // для seekTo(mediaItemIndex, pos)
    fun chapterAt(globalMs: Long): Chapter
    fun progress(globalMs: Long): Float                    // 0..1 для процента и колец
    fun remainingMs(globalMs: Long, speed: Float): Long    // «осталось 3 ч 42 мин» с учётом скорости
}
```

Треки без известной длительности: до первого проигрывания шкала использует оценку (размер ÷
битрейт или длительность из метаданных источника), после — длительность от плеера записывается в
кэш манифеста, шкала пересчитывается. Позиция при этом хранится **как глава + смещение в главе**
ровно на такой случай (см. ниже).

## Перенос позиции между вариантами (PositionMapper)

Вызывается при fallback посреди книги и при ручной смене источника/озвучки.

1. **Та же озвучка, совпадает разметка глав** (число глав равно, названия после нормализации
   совпадают ≥ 80 %): позиция = (индекс главы, смещение в главе) → глобальное время нового варианта.
2. **Та же озвучка, разметка разная**, общая длительность отличается ≤ 3 %: переносим по
   глобальному времени (это одна запись, нарезанная иначе).
3. **Длительности отличаются > 3 %** (другая редакция/тишина в начале): переносим по доле книги и
   откатываем на 30 с; показываем тост «Место приблизительное».
4. **Другая озвучка** (ручная смена чтеца): только по главе при совпадении разметки, иначе по
   доле книги; всегда с подтверждением в листе выбора.

Покрывается чистыми JVM-тестами на таблице случаев.

## Хранение

### Решение (D-02, подтверждено)

- **Запись коллекции** — строка `anime` с `mediaType = 'AUDIOBOOK'`. Поля: `title` (название
  произведения на языке UI), `episodes` = число глав выбранной озвучки, `rating`, `isFavorite`,
  `tags`, `comment`, `imageFileName` (лучшая обложка, сохранённая через `ImageStorageRepository`).
  Внешние id (`anilistId`, `malId`, `shikimoriId`, `tmdbId`…) всегда `NULL`.
- **Всё остальное** — новые таблицы в `Audiobook.sq` (миграция `16.sqm`).
- **Манифесты и выдача поиска** — файловые кэши (`filesDir/audiobooks/…`), без миграций, как
  `MangaChapterCacheStore`.

### Таблицы (`Audiobook.sq`, миграция 16)

```sql
CREATE TABLE audiobook_work (
    work_id TEXT NOT NULL PRIMARY KEY,       -- UUID, неизменяемый внутренний identity
    cluster_fingerprint TEXT NOT NULL,      -- SHA-1 нормализованных полей, только для поиска совпадений
    collection_id TEXT,                    -- anime.id, NULL пока не в библиотеке
    title TEXT NOT NULL,
    title_original TEXT,
    authors_json TEXT NOT NULL,            -- [{"name":"…","role":"AUTHOR"}]
    series_title TEXT,
    series_index REAL,
    description TEXT,
    genres_json TEXT NOT NULL DEFAULT '[]',
    language TEXT NOT NULL,
    year INTEGER,
    is_collection INTEGER NOT NULL DEFAULT 0,
    cover_url TEXT,
    cover_palette_json TEXT,
    cover_blurhash TEXT,
    selected_narration_id TEXT,
    updated_at INTEGER NOT NULL
);
CREATE INDEX audiobook_work_collection ON audiobook_work(collection_id);
CREATE INDEX audiobook_work_fingerprint ON audiobook_work(cluster_fingerprint);

CREATE TABLE audiobook_narration (
    narration_id TEXT NOT NULL PRIMARY KEY, -- UUID, не зависит от нормализатора
    work_id TEXT NOT NULL REFERENCES audiobook_work(work_id) ON DELETE CASCADE,
    cluster_fingerprint TEXT NOT NULL,
    narrators_json TEXT NOT NULL,
    kind TEXT NOT NULL,
    duration_ms INTEGER,
    chapter_count INTEGER
);

CREATE TABLE audiobook_variant (
    variant_id TEXT NOT NULL PRIMARY KEY,
    narration_id TEXT NOT NULL REFERENCES audiobook_narration(narration_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL,
    source_url TEXT NOT NULL,
    duration_ms INTEGER,
    chapter_count INTEGER,
    availability TEXT NOT NULL,
    infrastructure TEXT,
    last_verified_at INTEGER,
    user_pinned INTEGER NOT NULL DEFAULT 0  -- пользователь вручную закрепил источник
);

CREATE TABLE audiobook_chapter (          -- разметка последнего удачного манифеста, для офлайна и UI
    variant_id TEXT NOT NULL REFERENCES audiobook_variant(variant_id) ON DELETE CASCADE,
    idx INTEGER NOT NULL,
    title TEXT NOT NULL,
    start_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    PRIMARY KEY (variant_id, idx)
);

CREATE TABLE audiobook_progress (
    narration_id TEXT NOT NULL PRIMARY KEY REFERENCES audiobook_narration(narration_id) ON DELETE CASCADE,
    work_id TEXT NOT NULL REFERENCES audiobook_work(work_id) ON DELETE CASCADE,
    variant_id TEXT,                       -- на каком варианте слушали последним
    global_ms INTEGER NOT NULL,
    chapter_idx INTEGER NOT NULL,
    chapter_offset_ms INTEGER NOT NULL,
    total_ms INTEGER,
    speed REAL NOT NULL DEFAULT 1.0,
    finished INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);
CREATE INDEX audiobook_progress_recent ON audiobook_progress(updated_at DESC);

CREATE TABLE audiobook_bookmark (
    id TEXT NOT NULL PRIMARY KEY,
    narration_id TEXT NOT NULL REFERENCES audiobook_narration(narration_id) ON DELETE CASCADE,
    global_ms INTEGER NOT NULL,
    chapter_idx INTEGER NOT NULL,
    chapter_offset_ms INTEGER NOT NULL,
    note TEXT,
    created_at INTEGER NOT NULL
);

CREATE TABLE audiobook_listen_session (   -- для статистики и «на этой неделе 4 ч 20 мин»
    id TEXT NOT NULL PRIMARY KEY,
    narration_id TEXT NOT NULL REFERENCES audiobook_narration(narration_id) ON DELETE CASCADE,
    started_at INTEGER NOT NULL,
    listened_ms INTEGER NOT NULL,          -- реальное время, не шкала книги
    book_ms INTEGER NOT NULL,              -- пройдено по шкале (с учётом скорости)
    device_offline INTEGER NOT NULL DEFAULT 0
);
```

Произведения, открытые из поиска, но не добавленные, живут в этих таблицах с
`collection_id = NULL` (для «Продолжить» без добавления) и чистятся раз в 30 дней, если по ним нет
прогресса.

### Идентичность произведения и отпечаток кластера

`WorkId` — UUID, созданный при первом сохранении произведения. Он не меняется при улучшении
нормализатора, смене обложки, названия или состава источников. `clusterFingerprint =
sha1(normAuthorSurnames + "|" + normTitle + "|" + language)` (16 hex-символов) — индексируемый
кандидат для поиска совпадений, не первичный ключ. Нормализация — в
[spec/05 § Нормализатор](./05-sources-and-aggregation.md#нормализатор). При перерасчёте
отпечатка сохраняется тот же `work_id`; прогресс связан с постоянными `work_id` и
`narration_id`. Если кластеризатор объединяет два уже созданных произведения, операция явно
выбирает сохраняемый UUID, перепривязывает зависимые записи транзакционно и хранит alias
старого UUID для внешних ссылок. Простое изменение hash такой операции не вызывает.

### Сохранение позиции

`ProgressTracker` в сервисе:
- каждые 5 с при игре (дедуп, если позиция не изменилась);
- на паузу, смену главы, смену трека, ошибку, `onTaskRemoved`, `onDestroy`;
- в корутине `NonCancellable` (урок `web-links-engine`: отменённая работа не должна терять запись);
- `finished = 1`, если осталось < 1 % или < 60 с.

## Коллекция, синк и старые клиенты

`MediaType.AUDIOBOOK` — это новое значение в общем поле, и в него упираются три риска:

| Риск | Защита |
|---|---|
| Supabase CHECK `anime_media_type_check` в миграции репозитория допускает `('ANIME','MANGA','TV_SERIES')`; реальную схему ещё нужно проверить | В AB-12 не отправлять `AUDIOBOOK` в Supabase. Отдельную миграцию CHECK и включение синка делать после релиза совместимой версии; также сверить `MOVIE`/`SERIES` с живой схемой. |
| Старый клиент получит `AUDIOBOOK` и `fromPersistedValue` превратит его в `ANIME` → воркеры обогащения полезут в AniList | До выхода совместимой версии audiobook-строки живут локально: явно исключить их из push и pull. Синк включать только отдельным релизным решением после проверки совместимости. |
| Воркеры, фильтрующие по `mediaType = 'ANIME'`, в SQL — безопасны; фильтрующие по `!= MANGA` — нет | AB-12 проходит все места из `graft callers MediaType` и явно исключает `AUDIOBOOK`; тест на каждый воркер: «AUDIOBOOK-строка не трогается». |

Таблицы `audiobook_*` в облако в V1 не синхронизируются (прогресс — локальный). Синк прогресса
между устройствами — кандидат на V2 (отдельная таблица Supabase с RLS как у `anime`).

## Статистика

`audiobook_listen_session` пишет `ProgressTracker`: сессия начинается с `play`, закрывается на
паузе > 2 мин или смене книги. Из неё:
- «На этой неделе: 4 ч 20 мин» (подзаголовок дома);
- сумма по автору/чтецу для полки «Чтецы, которых вы любите»;
- вклад в общий экран статистики Vetro (отдельная карточка колоды, AB-36).
