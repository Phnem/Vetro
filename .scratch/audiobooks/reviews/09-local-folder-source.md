# AB-09 review — 2026-09-24

Outcome: DONE_WITH_DEVIATIONS. Локальная папка работает через системный выбор и единый Media3 service.

| Критерий | Доказательство |
|---|---|
| SAF grant и каталог | Xiaomi: выбор `Documents/VetroAB09Smoke_2409`, сохранённый `content://` URI, карточка `SampleBook · 2` на [снимке](assets/ab09-ui-selected.png). |
| Порядок и идентичность | AndroidTest: `2.wav` перед `10.wav`, WorkId/NarrationId остаются теми же после повторного скана/добавления. |
| Manifest и воспроизведение | AndroidTest: два трека, главы по файлам, чтение `RIFF` через logical URI, запуск MediaLibraryService. Реальный SAF URI на Xiaomi дал `PlaybackState PLAYING` с speech audio attributes. |
| Очистка | Тестовая папка удалена без рекурсивного удаления; `pm clear` применён только к `com.phnem.vetro.ab07smoke`; основной Vetro и пакет другого агента сохранены. |
| Сборка | Debug/release Kotlin compile и release APK; 3 Android tests на Xiaomi. |

Открыто: corpus с реальными M4B/Nero/QuickTime и MP3 ID3 CHAP проверить в AB-38; полный playback UI и актуальное состояние книги — AB-10. Нажатие на временную кнопку теперь даёт текстовое подтверждение запуска.
