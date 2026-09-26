# Источники аудиокниг: глобальный ресёрч 2026-09-26

Каждый кандидат проверен вживую скриптом (главная → страница книги → как отдаётся звук), подключённые —
ещё и цепочкой «поиск → детали → манифест» (для торрентов — до первого куска звука). Порядок в таблицах —
приоритет в цепочке точек доступа (`di/audiobookModule.kt`, список `AUDIOBOOK_SOURCES`).

## Подключено: потоковые сайты (RU)

| Сайт | Как отдаёт звук | Длины глав | Ссылки |
|---|---|---|---|
| aknigi24.com | JSON плеера, прямые MP3 | есть | бессрочные |
| yakniga.org | открытый GraphQL | есть | бессрочные |
| audiokniga.one | `playerInit(… 'json', […])` | есть | бессрочные |
| izib.uk | `new XSPlayer({…})`: префикс + подпись + файлы | есть | подпись ~сутки |
| knigavuhe.org | `new BookPlayer(…)` | есть | подпись ~3,5 дня |
| baza-knig.info | Playerjs `.pl.txt`, CDN с Referer | по размерам файлов | бессрочные |
| slushat-knigi.com | тот же CDN, что baza-knig (зеркало) | по размерам файлов | бессрочные |
| audioknigi.fun | AJAX `mod=audioplaylist` | по размерам файлов | подпись ~сутки |

## Подключено: потоковые сайты (EN)

| Сайт | Как отдаёт звук | Длины |
|---|---|---|
| archive.org (LibriVox + Audio Books & Poetry) | открытое API `advancedsearch` + `/metadata` | есть; законно (public domain) |
| realaudiobooks.com | `ol.baud-tracks li[data-src]` | по общей длине |
| goldenaudiobooks.com, fulllengthaudiobooks.com, bookaudiobooks.com, appaudiobooks.com | WordPress `<audio class="wp-audio-shortcode">`, общий CDN ipaudio | по битрейту из заголовка MP3 и размерам |

## Подключено: трекеры (торренты)

Движок — libtorrent4j 2.1.0-39. Раздача качается по порядку файлов, слушать можно сразу (куски с
дедлайном), скачанное остаётся на устройстве. В цепочке — после потоковых сайтов.

| Трекер | Язык | Как берётся раздача | Проверено |
|---|---|---|---|
| audioboo.org | RU | `.torrent` без регистрации (`index.php?do=download&id=`) | первый кусок за 13 с, 6 сидов |
| rutor.info | RU | открытый magnet, раздел «Книги» с отбором MP3 | метаданные за 5 с |
| audiobookbay.lu | EN | Info Hash + трекеры со страницы → magnet | метаданные за 7 с |
| thepiratebay (apibay.org) | EN | JSON API, раздел 102 → magnet | метаданные за 5 с |

## Кандидаты на следующую итерацию

- **audioknigi-torr.org, t-audioknigimp3.org** (один владелец, DLE) и **aknigi.org** — `.torrent` без
  регистрации; парсер как у audioboo.
- **nnmclub.to** — форум, раздел аудиокниг; нужно проверить, видны ли magnet гостям.
- **booktracker.org** — форум аудиокниг; скачивание `.torrent` похоже требует входа.
- **audioaz.com** — англоязычный, в основном зеркало LibriVox (уже есть через archive.org); Cloudflare.
- **audiobooks4soul.com** — плеер собирается скриптом, ссылок в HTML нет.

## Не подключено и почему

- **akniga.org** — звук за токеном плеера с шифрованием ответа; обход защиты не делаем.
- **knigavuhe-audio.com** — ссылки на звук намеренно обфусцированы; не декодируем.
- **audio-knigi.org** — ещё одно зеркало CDN baza-knig (поиск отдаёт 500); в цепочку ничего не добавляет.
- **rutracker.org, 1337x.to, audiobooks.online-knigi.com** — Cloudflare-челлендж на всё.
- **litmarket.ru** — капча.
- **tokybook.com, zaudiobooks.com, galaxyaudiobook.com, freeaudiobooks.top, kot-baun.ru, slushkinvsem.ru** —
  не отвечают, домен не существует или сертификат недействителен.
- **flibusta.su, fantasy-worlds.ru, audioknigki.com** — у книг только фрагменты ЛитРеса.
- **digitalbook.io, loyalbooks.com** — зеркала LibriVox (есть через archive.org).

## Блокировки DNS

На машине разработчика часть доменов (rutor, rutracker, nnmclub, audioknigi-torr и др.) не резолвится
системным DNS — провайдер отдаёт 0.0.0.0. Для проверки использовался публичный DNS. В приложении
обход DNS-блокировок (DNS-over-HTTPS) не встроен: такое изменение было отклонено автоматической
проверкой прав. Если у пользователя домен заблокирован, цепочка просто перейдёт к следующему звену.
