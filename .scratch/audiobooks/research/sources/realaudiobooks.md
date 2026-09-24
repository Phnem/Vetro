# RealAudiobooks — AB-05

Проверено 2026-09-23: [robots.txt](https://realaudiobooks.com/robots.txt), [поиск](https://realaudiobooks.com/?s=the+time+machine), [книга](https://realaudiobooks.com/the-time-machine-h-g-wells-baud/), [другая книга](https://realaudiobooks.com/m-mitchell-waldrop-the-dream-machine-m-mitchell-waldrop-unabridged/). Снимки: `app/src/test/resources/audiobooks/realaudiobooks/`.

- WordPress каталог, форма `GET /?s=…`, открытый `robots.txt`. Страница `The Time Machine` имеет автора, чтеца Derek Jacobi, длительность 03:55:42, 15 частей; HTML содержит `<audio id="mainPlayer">` и прямые MP3 ссылки с `ipaudio7.com`. Обложка с `fullaudiobooks.com`; оригинальный размер не установлен.
- Поиск и детали доступны без login в данном проходе. Не проверяли HEAD/Range, Referer, cookies, срок медиа URL, геодоступность и право размещения записи; аудиобайты не загружались. `robots.txt` разрешение не подтверждает лицензию.
- Решение: технически `SEARCH`, `DETAILS`, `CHAPTERS`, потенциально `STREAM`; в production включать поток только после проверки прав конкретной записи и безопасного live smoke. `DOWNLOAD` не подтверждён. `InfrastructureGroup=ipaudio7.com`; связь с `ipaudio.club` не предполагать по похожему имени.

