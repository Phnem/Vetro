# AB-02: Апгрейд Media3 1.4.1 → 1.11.x для всего приложения

Status: done (core Media3 video smoke на эмуляторе пройден; внешние интеграции вынесены в release QA)
Type: research → task
Blocked by: AB-01 (порядок: решение хранения → отдельный Media3 gate; Q6 подтверждён)

## Цель

Одна версия Media3 на всё приложение, в которой есть `media3-ui-compose`; видео работает как
раньше.

## Объём

1. Исследование (дополнить `.scratch/streaming-reliability/research-media3.md`): актуальная
   стабильная 1.11.x; требования к Kotlin (сейчас 2.1.0), compileSdk (36), minSdk (26);
   breaking changes 1.4 → 1.11 для используемых API: `PlayerView`, `DefaultLoadControl`,
   `AdaptiveTrackSelection` (наш `StallAwareAdaptiveTrackSelection`), `CacheDataSource`/`SimpleCache`
   (`VetroVideoCache`), `MediaSession` (standalone в `PlayerScreen.kt` и `StreamPlayerActivity`),
   `setCustomLayout` → media button preferences.
2. Поднять `media3` в `gradle/libs.versions.toml`, добавить `media3-ui-compose` и
   `media3-exoplayer-workmanager`.
3. Починить компиляцию; поведение не менять.
4. Прогнать Media3-зависимые сценарии видео на устройстве: HLS, ABR, progressive MP4,
   локальное воспроизведение без сети, PiP. Внешние источники и download wizard из
   `app/src/main/java/com/example/myapplication/media/SMOKE_MATRIX.md` проверяются на
   релизном QA-гейте AB-38: их доступность не определяется версией Media3.

## Вне рамок

Любой код аудиокниг; использование новых возможностей Media3 в видео (отдельные задачи).

## Критерии приёмки

- `./gradlew :app:testDebugUnitTest :app:assembleRelease` зелёные.
- Media3-зависимая часть smoke-матрицы видео пройдена, результаты в `EXECUTION_LOG.md`.
- Непроверенные source/download-интеграции явно перечислены в review и AB-38.
- Если 1.11.x требует апгрейда Kotlin — это вынесено в отдельный предварительный коммит.

## Ссылки

[spec/04 § Версия Media3](../spec/04-playback-engine.md#версия-media3-гейт-ab-02)

## Выполнено 2026-09-23

- Официальный release table подтверждает Media3 1.11.1; все зависимости обновлены одной
  версией. Добавлены `media3-ui-compose` и `media3-exoplayer-workmanager`.
- Kotlin 2.1.0 оставлен: проект компилируется с артефактами Media3 1.11.1, отдельный апгрейд
  Kotlin не потребовался.
- Baseline до апгрейда: `:app:compileDebugKotlin -q` успешно.
- После апгрейда: `:app:compileDebugKotlin`, `:app:assembleDebug`,
  `:app:testDebugUnitTest`, `:core:network:testDebugUnitTest`, `:app:assembleRelease` успешно.
- Первая попытка компиляции упала на перемещении временного Gradle transform cache до
  компиляции исходников. Повтор с `--no-daemon --max-workers=1` прошёл без очистки кэша.
- На эмуляторе Pixel_10_Pro, API 37 (16 KB page), проверены: HLS 24 с, адаптивный HLS 96 с
  (телеметрия `StreamTelemetry` показывает переход 180p → 360p), progressive MP4 96 с,
  локальный MP4 через `DownloadedPlayerActivity` при отключённой сети (`PLAYING` в media session).
- PiP подтверждён на том же эмуляторе: после раскрытия контролов кнопка `PiP` переводит
  `StreamPlayerActivity` в `mLastReportedPictureInPictureMode=true`; видео остаётся в окне
  поверх домашнего экрана.
- Для ADB smoke временно экспортировались два player Activity **только в debug-манифесте**.
  Временный manifest удалён; финальный debug APK собран повторно и установлен. Проверено,
  что оба Activity снова `exported=false` в merged manifest.

## Остаточные проверки релиза

- Живые пути из `media/SMOKE_MATRIX.md` (AniLibria, AnimeGo/AniBoom, jut.su, загрузка
  ffmpeg/yt-dlp, отмена, 403/410 re-resolve, legacy wizard) пока не пройдены на устройстве.
  `Consumet/Gogoanime` в матрице уже устарел: код `AnimeHeavenSource` прямо отмечает закрытие
  `api.consumet.org`; `SourceEngine` использует jut.su как reference для таймскипов, а не
  как видеопровайдер. Эти проверки остаются частью AB-38 с актуальными источниками.
- Системное предупреждение эмулятора о 16 KB совместимости двух существующих `.so` требует
  отдельной проверки релизной поставки; оно не связано с Media3 API.
