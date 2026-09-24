# Aknigi24 — AB-05

Проверено 2026-09-23: [robots.txt](https://aknigi24.com/robots.txt), [поиск](https://aknigi24.com/search?q=%D0%A7%D0%B5%D1%85%D0%BE%D0%B2), [обычная книга](https://aknigi24.com/book/liminalnye-prostranstva-travm-pucok-percepcii-eremenko-filipp), [сборник](https://aknigi24.com/book/ves-cexov-cast-1-cexov-anton-abdullaev-dzaxangir). Локальные структурные снимки: `app/src/test/resources/audiobooks/aknigi24/`.

- Каталог: `aknigi24.com`; поисковая форма `GET /search?q=…`, страницы `/book/<slug>`. Поиск отдаёт заголовок, длительность и ссылки карточек; детальная страница — автора, исполнителя, обложку и `<audio id="book-player">`.
- `robots.txt` не закрывает `/search` и `/book/<slug>`, но закрывает `/api/`, `/book/*/download`, `/book/*/part`, `/book/*/full` и `/books/`. Медиа URL в сохранённом статическом HTML нет. Поэтому `resolve`, главы и загрузка не подтверждены и по текущей политике не могут обращаться к закрытым путям.
- Media/CDN host, Referer, cookie, срок URL и Range: **не проверены**; аудиобайты не запрашивались. Обложка видна через `og:image`, правило оригинала не подтверждено.
- Явную страницу с ограничением правообладателя не нашли; ссылка «Правообладателям» в футере не считается признаком ограничения. Проверка должна опираться на карточку конкретного варианта.
- Решение: `SEARCH`, `DETAILS`, `METADATA_ONLY`; без `STREAM`/`DOWNLOAD` до разрешённого и документированного медиа-пути. `InfrastructureGroup=aknigi24.com` предварительно, медиа-хост неизвестен. Намеченный AB-17 нельзя выполнять как полный потоковый адаптер в нынешнем виде.

