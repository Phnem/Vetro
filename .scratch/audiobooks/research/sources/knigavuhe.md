# Knigavuhe — AB-05

Проверено 2026-09-23: [robots.txt](https://knigavuhe.org/robots.txt), [поиск](https://knigavuhe.org/search/?q=%D0%A7%D0%B5%D1%85%D0%BE%D0%B2), [обычная книга](https://knigavuhe.org/book/khameleon-5/), [сборник](https://knigavuhe.org/book/rasskazy-86/), [ограниченная озвучка](https://knigavuhe.org/book/kontrapunkt/). Снимки: `app/src/test/resources/audiobooks/knigavuhe/`.

- Каталог `knigavuhe.org`; форма `GET /search/?q=…`; обычные `/book/<slug>/`. `robots.txt` закрывает `/letter/`, `/authors/letter/`, `/readers/letter/`, `/setdesign/`, но не поиск или книгу.
- Поиск показывает произведения и ссылки; книга даёт автора, чтеца, длительность, обложку, другие озвучки и иногда цикл. `Рассказы` — 10 частей, их нельзя смешивать с отдельным рассказом только по названию.
- Статический HTML двух обычных страниц содержит названия/длительности треков, но без `<audio>` и прямых MP3 URL. Механизм воспроизведения, медиа-хост, Referer/cookie, срок и Range **ещё не установлены**. Обход защиты не применять.
- У `/book/kontrapunkt/` перед содержимым есть явная фраза «Доступ к аудиокниге ограничен по просьбе правообладателя.»; фиксировать `Restricted(RESTRICTED_BY_RIGHTSHOLDER)` именно у этой озвучки, не запрашивать её аудио. Строки из комментариев не использовать как сигнал.
- Решение: `SEARCH`, `DETAILS`, `NARRATIONS`, метаданные `CHAPTERS` условно; `STREAM`/`DOWNLOAD` только после отдельного разрешённого resolve-probe. `InfrastructureGroup=knigavuhe.org` предварительно. Лучший RU кандидат для AB-17 после подтверждения воспроизведения; сейчас не обещать полноценный поток.

