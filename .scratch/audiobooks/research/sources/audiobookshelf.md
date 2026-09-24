# Audiobookshelf — AB-05 якорь

Проверено 2026-09-23 по [официальной API-справке](https://api.audiobookshelf.org/). Пользовательский сервер и токен не предоставлены; живого контракта и фикстур ответов пока нет. Справка сама помечена устаревшей. Детали в `research/source-anchors.md`.

- Собственный сервер пользователя, `Authorization: Bearer <token>`; предполагаемые `/api/libraries`, `/api/libraries/<id>/search?q=…`, `/api/items/<id>?expanded=1&include=progress`, `/api/items/<id>/play`, обложка `/api/items/<id>/cover`.
- Внешний ключ `abs:<server-scope>:<libraryItem.id>`. `InfrastructureGroup=abs:<server-scope>`; серверы разных пользователей независимы. Ожидаемые `SEARCH`, `DETAILS`, `NARRATIONS`, `CHAPTERS`, `STREAM`, `AUTH_REQUIRED`, `RIGHTS_VERIFIED` для собственного контента. `DOWNLOAD`, Range и refresh токена не подтверждены.
- До реализации выполнить contract test на конкретной версии сервера и сохранить обезличенные ответы; не записывать токен, домашний IP, пути файлов или username. Медиа `contentUrl` интерпретировать относительно адреса сервера только после проверки.

