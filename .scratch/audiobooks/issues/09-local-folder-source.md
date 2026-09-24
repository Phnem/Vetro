# AB-09: Local Folder через SAF

Status: done_with_deviations
Type: implementation
Blocked by: AB-08

## Цель

Пользователь выбирает библиотечную папку через Android SAF. Один уровень подпапок становится книгами, файлы внутри книги сортируются естественно и воспроизводятся через общий Media3 player. Доступ к выбранному дереву переживает перезапуск приложения.

## Приёмка

- Выбор папки через `OpenDocumentTree`, сохранение read-grant и понятное отклонение слишком общей папки. Папка `Audiobooks` допустима как целевая библиотека, в отличие от общего `Download`/корня.
- Ограниченный по глубине и количеству файлов скан без чтения всей памяти/медиа, сортировка `2 < 10`, отдельная книга на каждую подпапку; аудио в выбранной папке тоже отображается как книга.
- Теги и длительность читаются без падения на битых/недоступных файлах. M4B и MP3 ID3 `CHAP` берутся из метаданных Media3 1.11.1; fallback — границы файлов. Главы внутри M4B не режут файл.
- `ManifestSource` выдаёт `content://` URI, которые открывает существующий `VetroAudioDataSource`/service. После потери SAF grant книга честно недоступна, а не теряет идентичность.
- Device smoke на единственном тестовом package: тестовая папка с двумя аудиофайлами, сортировка, manifest, воспроизведение и перезапуск сервиса; debug/release build.

## Границы

Запись в общую коллекцию и SQL — AB-12/13. Полный красивый дом и полки — AB-28/30; AB-09 даёт компактный вход в локальную папку на текущем BooksScreen. Синк выключен. Не изменять `localplayer/` и чужие UX-файлы без необходимости.

## Исследование

Media3 1.11.0 добавила извлечение глав Nero и QuickTime для MP4/M4A/M4B как `Metadata.Entry` типа `Chapter`; `MetadataRetriever` в `media3-inspector` даёт `TrackGroupArray` без playback. Поэтому свой парсер MP4 atoms из исходного плана не нужен. Android SAF сохраняет read permission через `takePersistableUriPermission()`, но доступ может исчезнуть после удаления/перемещения документа. См. `spec/05` и официальные Android docs.

Источники: [Media3 1.11 release notes](https://developer.android.com/jetpack/androidx/releases/media3), [Media3 Inspector](https://developer.android.com/media/media3/inspector/retrieve-metadata), [Android SAF](https://developer.android.com/training/data-storage/shared/documents-files).

## Результат 2026-09-24

Реализованы SAF picker с сохранением read grant, ограниченный скан, естественная сортировка, стабильные UUID книги и озвучки, метаданные и встроенные главы через Media3 Inspector, а также воспроизведение локальных файлов через общий MediaLibraryService. Xiaomi: 3 Android tests прошли; реальная папка Documents/VetroAB09Smoke_2409 дала карточку `SampleBook · 2`, а `dumpsys media_session` подтвердил `PLAYING` у `com.phnem.vetro.ab07smoke`. После замечания пользователя временная кнопка стала показывать сообщение о запуске. Тестовые файлы и SAF grant удалены, данные только нашего тестового пакета очищены.

Ограничения: реальные M4B/MP3 с embedded chapters ещё не проверены на устройстве; API и fallback реализованы, отдельный corpus smoke добавлен к AB-38. Временный экран не показывает длительное состояние playback и управление; это основная приёмка AB-10. Релизный feature flag остаётся закрыт до интеграции полного дома.
