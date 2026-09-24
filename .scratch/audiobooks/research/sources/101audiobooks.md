# 101Audiobooks — AB-05

Проверено 2026-09-23: [robots.txt](https://101audiobooks.com/robots.txt), [поиск](https://101audiobooks.com/?s=arthur+c+clarke), [книга](https://101audiobooks.com/arthur-c-clarke-rama-ii-audiobook/), [вторая книга](https://101audiobooks.com/arthur-c-clarke-2001-audiobook/). Снимки: `app/src/test/resources/audiobooks/101audiobooks/`.

- WordPress, `GET /?s=…`; robots открыт. На странице Rama II 15 `<audio><source>`, MP3 на `ipaudio.club`. На странице поиска также встречаются `ipaudio3.club`/`ipaudio6.com`, поэтому `SourceFingerprint.mediaHosts` должен дополняться из фактических URL, а инфраструктурная группа учитывать доменное семейство при подтверждении.
- Ни авторизация, ни стабильность медиа URL, Range, Referer/cookies, права и региональная доступность не проверены; аудиобайты не брали.
- Решение: технически `SEARCH`, `DETAILS`, `CHAPTERS`, потенциально `STREAM`; production доступность условна по правам и live smoke. `InfrastructureGroup=ipaudio.club` для подтверждённых совпадающих ссылок с Golden; не считать отдельным резервом Golden.

