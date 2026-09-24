# AudioAZ — AB-05

Проверено 2026-09-23: [robots.txt](https://audioaz.com/robots.txt), [поиск](https://audioaz.com/en/search?q=the+time+machine), [озвучка 7](https://audioaz.com/en/audiobook/the-time-machine-version-7-by-h-g-wells), [озвучка 2](https://audioaz.com/en/audiobook/the-time-machine-version-2-by-h-g-wells). Снимки: `app/src/test/resources/audiobooks/audioaz/`.

- Каталог `audioaz.com`, `GET /en/search?q=…`; отдельные URL у версий одной книги. `robots.txt` разрешает поиск и книгу, запрещает `/api*` и несколько служебных путей, просит `Crawl-delay: 1`.
- Детальная страница показывает автора, чтеца, главы, обложку. Версия 7 имеет 17 глав и прямые MP3 ссылки `archive.org` в HTML. Обложка с `f.audioaz.com`; размер оригинала не установлен. Поиск хорошо демонстрирует `Work→Narration`.
- Media URL ведут к IA; лучше связывать с каноническим IA item, если его identifier можно доказать, и проверять права каждого item. Range, редиректы и срок URL здесь не тестировали, байты аудио не получали.
- Решение: `SEARCH`, `DETAILS`, `NARRATIONS`, `CHAPTERS`; потенциальный `STREAM` через проверенный IA item. `InfrastructureGroup=archive.org` для найденных треков, то есть не независимый fallback от IA. `DOWNLOAD` без проверки item не заявлять.

