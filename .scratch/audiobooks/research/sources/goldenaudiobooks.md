# GoldenAudiobooks — AB-05

Проверено 2026-09-23: [robots.txt](https://goldenaudiobooks.com/robots.txt), [поиск](https://goldenaudiobooks.com/?s=mark+twain), [книга](https://goldenaudiobooks.com/mark-twain-adventures-huckleberry-finn-audiobook/), [вторая книга](https://goldenaudiobooks.com/ernest-cline-ready-player-one-audio-book/). Снимки: `app/src/test/resources/audiobooks/goldenaudiobooks/`.

- WordPress, поиск `GET /?s=…`; robots не закрывает каталог. На первой странице 11 `<audio><source>` с MP3 на `ipaudio.club`. Главы даны последовательно в HTML; обложка доступна, правило оригинального размера не проверено.
- Media Referer/cookies, Range, стабильность URL, права размещения конкретной записи и доступность по регионам не проверены. Аудиобайты не скачивались.
- Решение: технически `SEARCH`, `DETAILS`, `CHAPTERS`, потенциально `STREAM`; без production `DOWNLOAD` и без автоматического выбора до проверки прав/живого воспроизведения. `InfrastructureGroup=ipaudio.club`, **общая с 101Audiobooks**, поэтому эти сайты не независимые варианты fallback.

