# AB-07: Фоновый аудиосервис и системная сессия

Status: done_with_deviations (2026-09-24)
Type: implementation
Blocked by: AB-02, AB-06

## Цель

Ввести `MediaLibraryService` в основном процессе, ExoPlayer для речи, системную сессию и шторку с шагами −15/+30. Дать системе восстановить последний локальный элемент после остановки процесса, пока схема audiobook progress ещё не создана.

## Приёмка

- Сервис зарегистрирован с `FOREGROUND_SERVICE_MEDIA_PLAYBACK` и Media3/library intent-filter, без `android:process`.
- Плеер владеет audio focus/noisy/wake lock; release в `onDestroy`; медиа-кнопки используют стандартные seek commands.
- `onPlaybackResumption` восстанавливает последний локальный URI и позицию из приватного промежуточного store (замена на AB-13).
- Проверить реальную сессию, уведомление, фон и resumption на отдельном тестовом package Xiaomi/эмулятора. Основной пакет Vetro не заменять.
- Compile/release build и статус интеграции отмечены в журнале.

## Границы

`ResolvingDataSource`, главы/плейлист, офлайн-кэш — AB-08; SAF — AB-09; UI connection/мини-плеер — AB-10. В этом тикете тестовый файл может подаваться MediaController напрямую.

## Результат

- В основном процессе зарегистрирован `AudiobookPlaybackService` с `MediaLibrarySession`, Media3 `MediaButtonReceiver`, разрешением и типом foreground service. `onGetSession` в release закрыт флагом до M1; debug доступен для smoke.
- `AudiobookPlayerFactory` задаёт речь, audio focus, noisy pause, network wake mode и шаги 15/30 с. Системные кнопки используют `Player.COMMAND_SEEK_BACK/FORWARD`, как рекомендует Media3, а не отдельные custom commands.
- Локальный `PlaybackResumptionStore` сохраняет URI/метаданные/позицию; callback восстанавливает один локальный элемент после остановки сервиса. Заменить на полноценный progress/playlist в AB-13/AB-08.
- Синтетический WAV smoke прошёл на эмуляторе и Xiaomi: воспроизведение через `MediaController` без Activity, уведомление, 15/30 increments, pause, stopService, новое подключение и продолжение с позиции. Первый Xiaomi прогон был слишком коротким для ненулевой позиции; после задержки 1,5 с тест прошёл. Основной Vetro не заменяли.
- Изолированная подписанная debug-ключом release-копия `.ab07release` запустилась на эмуляторе без `VerifyError`/crash; удалена после smoke. Затем временные suffix/signing откатили, обычные `:app:compileDebugKotlin` и `:app:assembleRelease` прошли, финальный APK unsigned с `applicationId=com.phnem.vetro`.
- Не проверены: восстановление после убийства процесса/перезагрузки, звонок/гарнитура, длительный фон, обложка и переход по уведомлению прямо в полный плеер. Эти сценарии — AB-10/AB-13/AB-38. Кастомный notification provider не нужен для проверенного системного UI; внешний вид с обложкой дорабатывает AB-27.
- После теста на Xiaomi остались только `com.phnem.vetro` и один тестовый `com.phnem.vetro.ab07smoke`; test-runner удалён. Следующий тикет: AB-08.
