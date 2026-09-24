# 04 · Движок воспроизведения

## Версия Media3 (гейт AB-02)

В AB-02 `gradle/libs.versions.toml` обновлён с 1.4.1 до стабильной 1.11.1.
Compile, unit tests и release-сборка прошли; gate остаётся открытым до полного video smoke.

- Все артефакты Media3 обязаны быть одной версии → апгрейд затрагивает `StreamPlayerActivity`,
  `localplayer/ui/PlayerScreen.kt`, `DownloadedPlayerActivity`, `VetroVideoCache`, HLS/DASH,
  `StallAwareAdaptiveTrackSelection`, `PipActionsController`.
- `media3-ui-compose` (state-holder'ы) в 1.4.1 отсутствует — без апгрейда плана нет.
- В AB-02 проверить: минимальная версия Kotlin/metadata для 1.11.x при `kotlin = "2.1.0"`,
  `compileSdk = 36` (ок), изменения API: `setCustomLayout` → `setMediaButtonPreferences`,
  `MediaSession.Callback.onPlaybackResumption`, `DefaultLoadControl`, `CacheDataSource`,
  `PlayerView` (видео им пользуется).
- Регрессия видео — по `app/src/main/java/com/example/myapplication/media/SMOKE_MATRIX.md`.

Новые зависимости: `media3-ui-compose`, `media3-exoplayer-workmanager` (Requirements для загрузок).
`media3-session`, `media3-datasource-okhttp`, `media3-exoplayer-hls` уже в каталоге.

## Сервис

```kotlin
class AudiobookPlaybackService : MediaLibraryService() {
    private lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        val player = AudiobookPlayerFactory.create(this)          // ExoPlayer
        session = MediaLibrarySession.Builder(this, player, SessionCallback(...))
            .setSessionActivity(openAppPendingIntent())           // тап по шторке → Vetro + раскрытый плеер
            .setBitmapLoader(CoilBitmapLoader(...))               // обложки для шторки через Coil 3
            .build()
        setMediaNotificationProvider(VetroNotificationProvider(this)) // канал, мелкая иконка, порядок кнопок
    }
    override fun onGetSession(info: ControllerInfo) = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }
}
```

Манифест: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `foregroundServiceType="mediaPlayback"`,
`exported="true"` без `android:process` (сервис в основном процессе) с intent-filter'ами `androidx.media3.session.MediaLibraryService` и
`android.media.browse.MediaBrowserService` (нужно системному возобновлению и Android Auto).
Уведомление медиа-сессии на Android 13+ не требует `POST_NOTIFICATIONS` (разрешение в манифесте и
так есть).

## Настройка ExoPlayer

| Параметр | Значение | Зачем |
|---|---|---|
| `AudioAttributes` | `USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_SPEECH` | система понимает, что это речь (приглушение навигацией → пауза, а не тише) |
| `handleAudioFocus` | `true` | звонок, видео Vetro, другое приложение → пауза; конец звонка → продолжить |
| `setHandleAudioBecomingNoisy` | `true` | выдернул наушники → пауза |
| `setWakeMode` | `C.WAKE_MODE_NETWORK` | стрим с выключенным экраном |
| `setSeekBackIncrementMs` / `Forward` | 15 000 / 30 000 по умолчанию; назад и вперёд независимо 10/15/30/60 с | системные кнопки шторки используют выбранные значения |
| `setSkipSilenceEnabled` | настройка, по умолчанию `false` | «пропуск тишины» |
| `PlaybackParameters(speed, pitch = 1f)` | 0,5…3,0 | Sonic сохраняет тон |
| `LoadControl` | буфер 60–120 с вперёд, `backBuffer` 30 с | речь — низкий битрейт, большой буфер дешёвый и спасает от провалов сети; −15 с без перезагрузки |
| `MediaSourceFactory` | `DefaultMediaSourceFactory(CacheDataSource.Factory(audioCache, VetroAudioDataSource.Factory))` | офлайн и стрим одним путём |

Скорость хранится **на книгу** (`audiobook_progress.speed`) и глобальным дефолтом в настройках.

## Плейлист и метаданные

`PlaybackQueueBuilder` превращает манифест в `List<MediaItem>`:

- `mediaId = "<narrationId>#<trackIndex>"`;
- `uri = "vetro-audio://<variantId>/<trackIndex>"` (D-08);
- `MediaMetadata`: `title` = название главы, в которую попадает начало трека (или «Часть N»),
  `albumTitle` = произведение, `artist` = автор, `albumArtist`/`writer` = чтец,
  `artworkUri` = лучшая обложка, `extras` = `{workId, narrationId, chapterIndex}`;
- главы внутри одного файла (M4B) — `ClippingConfiguration` **не используем**: глава — отметка
  шкалы, плеер играет файл целиком; UI и шторка показывают текущую главу по позиции.

Автопереход к следующей главе/файлу — нативный переход плейлиста ExoPlayer (без пауз: треки
предзагружаются, `ConcatenatingMediaSource` не нужен).

Название в шторке обновляется при смене главы внутри файла: сервис слушает позицию (раз в 1 с) и
при смене главы делает `replaceMediaItem` с новыми метаданными **без** пересоздания источника —
если на целевой версии Media3 это вызывает перебуферизацию, альтернатива — обновлять
`MediaSession` через `ForwardingPlayer.getMediaMetadata()` (проверить в AB-10 вместе с UI глав).

## VetroAudioDataSource (ResolvingDataSource)

```kotlin
ResolvingDataSource.Factory(okHttpFactory) { spec ->
    if (spec.uri.scheme != "vetro-audio") return@Factory spec
    val (variantId, track) = parse(spec.uri)
    val resolved = manifestCache.freshTrack(variantId, track)   // перерезолв, если expiresAt прошёл
        ?: runBlocking { resolver.refresh(variantId, track) }     // только на IO-потоке загрузчика
    spec.buildUpon()
        .setUri(resolved.url)
        .setHttpRequestHeaders(resolved.headers + cookies(resolved.url))
        .build()
}
```

- 403/410/истёкшая подпись посреди трека → `PlaybackRecovery` помечает URL протухшим,
  `player.prepare()` с той же позицией → DataSource перерезолвит (паттерн уже работает в видео:
  «Re-resolve on 403/410 mid-stream» в SMOKE_MATRIX).
- Ключ кэша (`CacheKeyFactory`) = `variantId/track`, **не URL**: иначе смена подписи ломает
  кэш и офлайн.

## Кэш

Отдельный `SimpleCache` в `filesDir/audiobooks/media` (у одного каталога может быть только один
экземпляр — `VetroVideoCache` не трогаем). Эвиктор: `NoOpCacheEvictor` для скачанного
(DownloadManager помечает контент) + лимит потокового кэша 300 МБ (LRU поверх отдельного
`LeastRecentlyUsedCacheEvictor` невозможен в том же кэше → потоковый кэш — второй `SimpleCache`
в `cacheDir/audiobooks/stream`; порядок чтения: download-кэш → stream-кэш → сеть). Итоговая
схема потокового кэша фиксируется с первым онлайн-источником AB-17; локальный источник AB-09
кэш сети не использует. Offline download-кэш реализуется в AB-34.

## Ошибки и fallback

`PlaybackRecovery` (в сервисе) слушает `onPlayerError`:

| Ошибка | Действие |
|---|---|
| Сетевая, временная (`ERROR_CODE_IO_NETWORK_*`) | повтор с backoff 1/2/4 с тем же вариантом; офлайн-баннер в плеере |
| 403/410/401, `BAD_HTTP_STATUS` 5xx, `ERROR_CODE_IO_FILE_NOT_FOUND` | перерезолв URL; если снова — `health.record(failure)` и следующий вариант плана fallback с `PositionMapper` |
| Декодер/формат | следующий вариант; вариант получает `availability = UNKNOWN` и понижается |
| Все варианты исчерпаны | пауза, сохранить позицию, в плеере — состояние «Не удалось загрузить» с «Повторить» и «Выбрать другую озвучку» |

Смена варианта незаметна: плейлист заменяется `setMediaItems(newItems, idx, pos)`, в плеере на
1 строку появляется тихая подпись «Источник сменился», в шторке ничего не мигает.

## Команды сессии (шторка, lockscreen, гарнитура)

Кнопки медиа-уведомления: `−15`, `play/pause`, `+30` (+ «следующая глава» в расширенном виде).
В AB-07 на Media3 1.11.1 использованы стандартные `Player.COMMAND_SEEK_BACK` и
`Player.COMMAND_SEEK_FORWARD` в `CommandButton.SLOT_BACK/FORWARD`; кастомные команды для
обычной перемотки не нужны.

Кастомные команды сессии, которые использует UI через контроллер: `SET_SLEEP_TIMER`,
`CANCEL_SLEEP_TIMER`, `ADD_BOOKMARK`, `SWITCH_VARIANT`, `SET_SKIP_SILENCE`. Состояние таймера
отдаётся в `session.sessionExtras`.

Гарнитура: одиночное нажатие — play/pause; двойное — +30; тройное — −15 (стандартная обработка
Media3 для `KEYCODE_HEADSETHOOK`, сопоставление с шагами проверяется в AB-07).

## Возобновление после убийства

`MediaSession.Callback.onPlaybackResumption` → в AB-07 временно берёт последний локальный URI
и позицию из приватного `PlaybackResumptionStore`; после AB-13 берёт `audiobook_progress`,
строит плейлист из кэша манифеста (без сети, если есть скачанное/кэш) и отдаёт
`MediaItemsWithStartPosition`. Так работают: «продолжить» из системной карточки медиа, кнопка
play на Bluetooth-гарнитуре при мёртвом приложении.

## Таймер сна

Живёт в сервисе (работает при закрытом UI):

- режимы: 5/10/15/30/45/60 мин, «до конца главы», своё значение;
- последние 10 с — затухание громкости `player.volume` 1 → 0 по кривой ease-out, затем пауза и
  возврат громкости;
- встряхивание во время затухания (опция) — +5 мин (`SensorManager`, только пока таймер активен);
- при паузе вручную таймер останавливается и возобновляется с play.

## «Умный откат»

При `play` после паузы: < 1 мин — 0 с, 1–10 мин — 5 с, 10 мин–1 ч — 15 с, > 1 ч — 30 с
(не раньше начала главы). Выключается в настройках.

## Сосуществование с видео Vetro

Видеоплееры (`StreamPlayerActivity`, `LocalPlayerActivity`) держат свои `MediaSession` и audio
focus. При старте видео аудиокнига получает `AUDIOFOCUS_LOSS` → пауза, позиция сохраняется;
после видео сама не продолжается (правило Android). Мини-плеер при открытой видео-активити не
рисуется (она поверх).

## Android Auto (опционально, AB-37)

`onGetLibraryRoot` / `onGetChildren`: «Продолжить», «Скачанные», «Библиотека» (по 50 элементов).
Требует `automotive_app_desc.xml`; на телефонный UI не влияет.
