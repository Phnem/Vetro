# Internet Archive — AB-05 якорь

Проверено 2026-09-23 по [Advanced Search](https://archive.org/advancedsearch.php#raw), [MDAPI](https://archive.org/developers/md-read.html), [item URL](https://archive.org/developers/items.html), [правилам доступа](https://archive.org/developers/bots.html) и живым JSON `letters_brides_0709_librivox`. Фикстуры: `app/src/test/resources/audiobooks/internet-archive/`; детали в `research/source-anchors.md`.

- Поиск `advancedsearch.php` с `output=json`, `q`, `fl[]`, `rows`, `page`; item `GET /metadata/<identifier>` имеет `metadata` и `files`. У проверенного item 481 файл разных типов, включая MP3/M4B/JPEG/XML. Не считать каждый файл отдельной главой; выбирать по формату и metadata.
- Постоянный внешний ключ `ia:<identifier>`, трек — `identifier+filename`; канонический адрес `/download/<identifier>/<filename>`. Редирект на физический `iaNNN...us.archive.org` не сохранять.
- Нужен описательный User-Agent, кэш, обработка 429/Retry-After и ограничение частоты. Доступность и права проверять для каждого item; `mediatype=audio` недостаточно. Range/HEAD на файле ещё не проверены.
- `InfrastructureGroup=archive.org` и у AudioAZ, если тот ссылается на тот же IA item. Подтверждены `SEARCH`, `DETAILS`, список файлов; `CHAPTERS`, `STREAM`, `DOWNLOAD` условны от отбора item/файлов и проверки прав.

