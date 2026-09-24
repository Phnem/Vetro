# Audiokniga.one — AB-05

Проверено 2026-09-23: [robots.txt](https://audiokniga.one/robots.txt), [главная](https://audiokniga.one/), [сборник](https://audiokniga.one/17630-sbornik-radiopostanovok-1.html), [обычная книга](https://audiokniga.one/24863-moe-prokljatie.html). Снимки: `app/src/test/resources/audiobooks/audiokniga-one/`.

- Каталог `audiokniga.one`; форма на главной использует `GET /` с `do=search`, `subaction=search`, `story`. Путь/параметры поиска закрыты `Disallow: /*do=search`, `/?*`, `/search/`. Поисковый HTTP-ответ намеренно не сохранялся.
- Детальные страницы `/<id>-<slug>.html` доступны; сборник показывает автора, нескольких исполнителей, цикл и список частей. В статическом HTML есть `<audio id="player">`, но прямого MP3/HLS URL не обнаружено.
- `robots.txt` также закрывает `/book/`, `/uploads/hls/`, `/uploads/hls_merged/` и связанные HLS-пути. Медиа, Range, cookie, Referer и срок ссылок не проверены. Обложка видна в HTML; шаблон оригинала неизвестен.
- Явное ограничение правообладателя на проверенных страницах не найдено; навигационная ссылка «Правообладателям» им не является.
- Решение: только ручной/browse `DETAILS`, `METADATA_ONLY`; `SEARCH`, `STREAM`, `DOWNLOAD` не заявлять. В V1 исключить из автоматического параллельного поиска. `InfrastructureGroup=audiokniga.one` лишь предварительная метка каталога.

