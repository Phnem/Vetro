# Хвосты (точки расширения)

Принцип: сначала строим архитектуру и подключаем нужное с нуля, затем оставляем **хвост** — размеченное
место, куда новый источник добавляется как пазл, без переписывания цепочки. Каждый хвост помечен
в коде тегом `VETRO-TAIL[<имя>]`.

Найти все хвосты:

```bash
grep -rn "VETRO-TAIL\[" app core --include=*.kt
```

| Тег | Что добавляется | Как |
|---|---|---|
| `title-id-source` | Источник id/названий/описания/постера (каталоги, словари, индексы) | Класс `TitleIdContributor` (core/network/.../ids/TitleIdContributor.kt) + `single<TitleIdContributor> { … }` в `enrichmentModule`. Резолвер подхватывает через `getAll()` |
| `stream-provider` | Легальный источник кино/сериалов | Класс `MovieSeriesStreamingProvider` + строка в `movieSeriesSources` (`appModule`), порядок = приоритет |
| `connection-type` | Новый способ подключения источников (карточка + страница в панели «Дополнительные источники») | Объект `ConnectionType` и строка в `ConnectionTypes` (`ui/settings/ConnectionTypes.kt`). Только протокол или описание (JSON/HTTP), без загрузки чужого кода в приложение |
| `manga-source` | Источник манги | Класс `MangaSource` + строка в списке `MangaSourceEngine` (`appModule`) |
| `rec-source` | Источник кандидатов рекомендаций (аниме, фильмы, сериалы) | Класс `CandidateProvider` (`domain/recommendations/CandidateProvider.kt`) + строка в `providers` у `RecommendationEngine` (`appModule`). Флаг в `RecommendationFlags`, статус и условия — `docs/recommendations/sources.md` |

Правила хвоста:
- новый источник только дополняет, не перезаписывает уже найденное;
- нет данных / выключен ключ → `null`, а не исключение;
- ключи — в `local.properties` и `local.properties.example`, файлы `*.env` в git не попадают;
- добавляя новый хвост, дописать строку в таблицу выше.
