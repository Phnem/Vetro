# Akniga — AB-05 резерв

Проверено 2026-09-23: [robots.txt](https://akniga.org/robots.txt). `User-agent: *` разрешает публичный каталог, но закрывает `/stream/`, `/ajax/` и часть личных/служебных путей. Поиск и страницы книг в этом проходе не снимались; `SEARCH` и `DETAILS` остаются гипотезами плана, а не подтверждённым контрактом. Решение: резерв для будущей проверки метаданных; `METADATA_ONLY`, без `STREAM`/`DOWNLOAD`. `InfrastructureGroup=akniga.org` предварительно. Фикстура robots: `app/src/test/resources/audiobooks/akniga/robots.txt`.

