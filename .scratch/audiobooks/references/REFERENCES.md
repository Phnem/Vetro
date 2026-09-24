# Референсы раздела «Аудиокниги»

Все исходные материалы, на которых стоит план. Оригиналы — по путям на ПК; копии, которые могли
исчезнуть (временные файлы) или удобнее держать рядом, лежат в этой папке.

## Исходные планы пользователя

| Что | Оригинал на ПК | Копия |
|---|---|---|
| Технический план (сырой) | `C:\Users\2004i\Downloads\tecplan.txt` | [`raw/tecplan.txt`](./raw/tecplan.txt) |
| Визуальный план (сырой) | `C:\Users\2004i\Downloads\desplan.txt` | [`raw/desplan.txt`](./raw/desplan.txt) |

Что из них взято и что изменено — [`../MASTER_PLAN.md` § «Что изменилось относительно сырых планов»](../MASTER_PLAN.md#что-изменилось-относительно-сырых-планов).

## Визуальные референсы

| Что | Оригинал на ПК | Копия | Где используется |
|---|---|---|---|
| Референс плеера (Starboy / The Weeknd) | `C:\Users\2004i\AppData\Local\Temp\claude\D--AndroidStudioProjects-Vetro-collection\c2476961-72f1-4ef6-9a31-843d6f492896\images\1.png` (временная папка — **может быть удалена**, опирайтесь на копию) | [`img/player-reference.png`](./img/player-reference.png) | [`spec/10-screen-player.md`](../spec/10-screen-player.md) |
| Видео: книжное приложение (полки, коллекции, морф карточка→полка) | `C:\Users\2004i\Downloads\From Klickpin.com- Smart small sewing projects with charm and ideas with timeless style for makers and beginners-pin-id-852728510751447010.mp4` (13,2 с, 744×912, 60 fps) | кадры в [`img/`](./img/), разбор — [`VIDEO_BREAKDOWN.md`](./VIDEO_BREAKDOWN.md) | [`spec/08-screen-books-home.md`](../spec/08-screen-books-home.md) |

Кадры из видео нарезаны `D:\ffmpeg\bin\ffmpeg.exe`. Повторить нарезку (например, плотнее) можно так:

```bash
/d/ffmpeg/bin/ffmpeg.exe -ss 2.0 -i "<путь к видео>" -vf "fps=30,scale=300:-1,tile=6x2" -frames:v 1 out.jpg
```

## Спецификации дизайн-системы

| Что | Путь | Где используется |
|---|---|---|
| Универсальная спецификация движения (пружины, 7 законов, токены) | `D:\AndroidStudioProjects\Vetro Fixik\docs\UNIVERSAL_MOTION_SPEC.md` | [`spec/07-design-language.md`](../spec/07-design-language.md) |
| Универсальная спецификация матового стекла (8 слоёв, материалы, Z-стек) | `D:\AndroidStudioProjects\Vetro Fixik\docs\UNIVERSAL_GLASS_SPEC.md` | [`spec/07-design-language.md`](../spec/07-design-language.md) |
| Копия спеки матового стекла, по которой уже сделан `FrostedGlass.kt` | `.scratch/frosted-glass-motion/spec.md` | там же |
| iOS-гайдбук движения, из которого выросли `MotionTokens` | `vetro_echoic_ios_motion_guidebook.md` (корень репо) | там же |

## Прецеденты в этом репозитории (на что равняться)

| Прецедент | Где | Чему учит |
|---|---|---|
| Изолированный движок манги | `app/.../manga/`, `.scratch/` нет — см. память `manga-engine` | пакет вместо Gradle-модуля, граница `VetroMangaSource`, стор прогресса, офлайн-главы |
| Стриминг видео и каскад источников | `app/.../media/source/SourceEngine.kt`, `PlaybackProviderCascade.kt` | таймауты, один retry, `SourceAttempt` |
| Здоровье провайдеров | `app/.../media/source/movieseries/ProviderHealth*.kt` | backoff, временное отключение, штраф в сортировке |
| Законные адаптеры (HTTP/WebDAV/Jellyfin) | `.scratch/movie-series-playback/` | формат большого плана, capability-routing, зашифрованные креды |
| Надёжность стриминга Media3 | `.scratch/streaming-reliability/research-media3.md` | версия Media3 1.4.1, что появилось в 1.9+ |
| Плеер видео со своим скином | `app/.../localplayer/ui/PlayerScreen.kt`, `media/ui/StreamPlayerActivity.kt` | свои контролы, `MediaSession`, снап-бэк бегунка |
| Полноэкранные Details с пейджером | `app/.../ui/details/DetailsScreen.kt` | закреплённый hero + лист поверх, мини-док |
| Заглушка раздела «Книги» | `app/.../ui/workspace/BooksScreen.kt`, `WorkspacePage.BOOKS` | точка входа раздела уже есть |
