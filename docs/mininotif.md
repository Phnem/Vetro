# mininotif: нижняя плашка «как Dynamic Island»

Документ описывает все сценарии, где показывается новая плашка, и все её параметры: геометрию, цвета, тексты, триггеры, очередь и анимацию с формулами. Цель: по нему можно воссоздать плашку практически один в один, не открывая код.

Состояние после v3.5.8-Alpha (2026-11-22): добавлен второй вид плашки, раскрытая карточка (раздел 3.4), и двухстадийный показ «пилюля → карточка» (раздел 4.9). Сценарии с карточкой собраны в 2.9. Чек-лист ручной проверки лежит в [.scratch/ui-motion-pack/mininotif-scenarios.md](../.scratch/ui-motion-pack/mininotif-scenarios.md).

Исходники:

| Что | Где |
|---|---|
| Плашка, очередь, хост, анимация | [MiniNotif.kt](../app/src/main/java/com/example/myapplication/ui/shared/mininotif/MiniNotif.kt) |
| Пружины и радиус | [MotionTokens.kt:190](../app/src/main/java/com/example/myapplication/ui/shared/theme/MotionTokens.kt) (`islandWidth`, `islandHeight`, `islandHeightShrink`, `islandPress`, `IslandRadius`) |
| Тексты синхронизации | [SyncMiniNotifStrings.kt](../app/src/main/java/com/example/myapplication/ui/shared/mininotif/SyncMiniNotifStrings.kt) |
| Хост в корне приложения | [MainActivity.kt:314](../app/src/main/java/com/example/myapplication/MainActivity.kt) |
| Dev-флаг | `DevPreferencesKeys.MINI_NOTIF` = `dev_mini_notif`, строка «Мини-уведомления» в разделе разработчика ([SettingsScreen.kt:1212](../app/src/main/java/com/example/myapplication/ui/settings/SettingsScreen.kt)) |

---

## 1. Как это работает в целом

1. Любой экран вызывает `MiniNotif.show(context, text, kind, long, holdMillis)` вместо `Toast.makeText`.
2. Если dev-флаг «Мини-уведомления» выключен, хоста нет или приложение не на экране (lifecycle ниже `STARTED`), `show` показывает обычный системный `Toast` (`LENGTH_LONG` при `long = true`, иначе `LENGTH_SHORT`). Флаг по умолчанию **выключен**.
3. Иначе сообщение кладётся в очередь (`Channel`, ёмкость 24, при переполнении выкидывается самое старое).
4. Единственный `MiniNotifHost()` лежит последним в корневом `Box` `MainActivity`, поверх всего UI, включая шторки и плеер. Он забирает сообщения по одному и показывает их.
5. `MiniNotif.status(text, kind, holdMillis)` работает так же, но при выключенном флаге или в фоне **ничего не делает** (без Toast). Сейчас так показывается только ход синхронизации на главной.
6. `MiniNotif.dismiss()` убирает текущую плашку. Тап по плашке вызывает его сам.

### Типы (`MiniNotifKind`)

| Kind | Круг значка | Глиф | Цвет глифа |
|---|---|---|---|
| `Info` (по умолчанию) | нейтральный: тёмная тема `#FFFFFF` α 0.16, светлая `#000000` α 0.08 | `ph_info_fill` (Phosphor, info fill) | `onSurface` (`#F9F9F9` в тёмной, `#1A1A1A` в светлой) |
| `Success` | `BrandGreen #34C759` | `ph_check` | белый |
| `Warning` | `BrandOrange #E85002` | `ph_warning_fill` | белый |
| `Error` | `BrandDeepRed #C10801` | `ph_x` | белый |
| `Progress` | нейтральный, как у `Info` | неопределённый `CircularProgressIndicator` 16 dp, обводка 2.2 dp, цвет `#E85002`, трек прозрачный | — |

### Два вида: пилюля и карточка

| | Пилюля (по умолчанию) | Карточка (`MiniNotifCard`) |
|---|---|---|
| Содержимое | значок 28 dp и одна строка текста (до 3 строк) | значок 38 dp, заголовок, тег справа, подзаголовок до 2 строк |
| Ширина | по тексту, от 96 dp до `экран − 32 dp` | фиксированная: `экран − 48 dp` |
| Высота | от 48 dp | 66–84 dp по тексту |
| Радиус | `min(h/2, 32 dp)`, при 48 dp это капсула | та же формула, но при высоте от 64 dp всегда 32 dp: скруглённый прямоугольник, не капсула |
| Как показать | `show(...)`, `status(...)` | `show(..., card = ...)`, `status(..., card = ...)` |

Форма одна и та же (`IslandShape`), поэтому при переходе пилюля → карточка радиус сам плавно растёт с 24 до 32 dp вместе с высотой.

API (все вызовы в `MiniNotif`):

```kotlin
// пилюля
MiniNotif.show(context, text, kind, long, holdMillis)
// карточка сразу, растёт из «зародыша» 52×24; text уходит в Toast, когда плашка недоступна
MiniNotif.show(context, text, kind, long, holdMillis, card = MiniNotifCard(title, subtitle, tag))
// пилюля, через 1450 мс раскрывается в карточку (как сценарии прототипа)
MiniNotif.showMorph(context, text, compactText, compactKind, card, kind, long)
// статус процесса: только плашка, без Toast
MiniNotif.status(text, kind, holdMillis, card)
```

Тексты карточек лежат в `MiniNotifCardStrings` (отдельный класс: в `UiStrings` не осталось места под предел в 254 поля).

### Время показа

| Константа | Значение | Когда |
|---|---|---|
| `HoldShortMillis` | 2400 мс | `show(...)` без `long` (аналог `Toast.LENGTH_SHORT`) |
| `HoldLongMillis` | 3800 мс | `show(..., long = true)` |
| `HoldCardMillis` | 2500 мс | карточка без `long` (в прототипе 2400–2530 мс) |
| `MorphAfterMillis` | 1450 мс | сколько пилюля живёт до раскрытия в карточку (в прототипе 1400–1450 мс) |
| свой `holdMillis` | любое | `status("Синхронизация…", Progress, 8000)` |
| `MinHoldMillis` | 1300 мс | раньше этого срока плашка не уступает место следующей |

Отсчёт удержания начинается сразу после старта анимации появления, так что анимация входит в срок показа. При замене одной плашки на другую отсчёт начинается на 140 мс позже, после ухода старого содержимого.

---

## 2. Все сценарии показа

Обозначения в таблицах:
- **Kind:** тип значка (раздел 1).
- **Срок:** S = 2400 мс, L = 3800 мс.
- RU и EN тексты приведены дословно. `{x}` означает подстановку.
- Если флаг выключен или приложение в фоне, вместо плашки выйдет системный Toast с тем же текстом. Исключение: `status`, он в этом случае молчит.

### 2.1. Главная (коллекция), [HomeScreen.kt](../app/src/main/java/com/example/myapplication/ui/home/HomeScreen.kt)

| # | Триггер | Kind | Срок | RU | EN |
|---|---|---|---|---|---|
| H1 | Началась облачная синхронизация (Supabase) или восстановление картинок из облака. Только при включённом флаге и загруженном списке. Вызов `MiniNotif.status`, строка 292 | `Progress` | 8000 мс | Синхронизация… | Syncing… |
| H2 | Синхронизация закончилась (переход `syncActive` из true в false), строка 298 | `Success` | S | Синхронизировано | Synced |
| H3 | Тап по чипу рейтинга на карточке, когда рейтинг тайтла считается по эпизодам, строка 382 | `Info` | L | Рейтинг считается по эпизодам. Измените их оценки или выберите Auto в деталях | Rating follows your episode scores. Edit them or choose Auto in Details |

Особенности H1 и H2:
- При включённом флаге верхняя пилюля `CloudSyncPill` («Sync») скрыта (`visible = showCloudSyncPill && !miniNotifOn`, строка 1090). Плашка снизу её заменяет.
- H1 отправляется один раз, при смене `syncActive`. Если синхронизация длится дольше 8 с, плашка «Синхронизация…» схлопнется по таймеру и до конца синхронизации не появится. По окончании выйдет H2.
- Если H2 приходит, пока H1 ещё видна, плашка не схлопывается, а перетекает из «Синхронизация…» в «Синхронизировано» (раздел 4.6).

### 2.2. Details (аниме, фильмы, сериалы)

#### Страница «Детали» и меню «⋯», [DetailsScreen.kt:155](../app/src/main/java/com/example/myapplication/ui/details/DetailsScreen.kt) → `DetailsViewModel.say(ru, en)`

Все сообщения `Info`, срок S. Исключение: D1.

| # | Триггер (функция `DetailsViewModel`) | RU | EN |
|---|---|---|---|
| D1 | Удержание названия или логотипа копирует название. Только Android ниже 13: там система не показывает свой «скопировано» ([DetailsEnrichment.kt:89](../app/src/main/java/com/example/myapplication/ui/details/DetailsEnrichment.kt)). **Kind `Success`** | Название скопировано | Title copied |
| D2 | Выключатель уведомлений тайтла (колокольчик) не сохранился, `setNotificationsOn` | Не удалось сохранить | Couldn't save |
| D3 | Тайтл убран в секретную папку, `toggleSecret` | Тайтл перемещён в секретную папку | Moved to the secret folder |
| D4 | Тайтл возвращён из секретной папки, `toggleSecret` | Тайтл возвращён в ленту | Moved back to your collection |
| D5 | Ошибка сохранения секретности, `toggleSecret` | Не удалось сохранить | Couldn't save |
| D6 | Ошибка смены статуса просмотра, `setWatchStatus` | Не удалось сохранить статус | Couldn't save the status |
| D7 | Оценки эпизодов были удалены и рейтинг переключился на Auto (флаг `ratingResetNotice`, приходит при открытии Details), `init` | Все оценки эпизодов удалены. Рейтинг переключён на Auto | All episode ratings were removed. Rating switched to Auto |
| D8 | Выбран режим Manual rating, а оценённых эпизодов нет, `pickRatingMode` | Нет оценок эпизодов: среднее пока посчитать нельзя. Manual rating недоступен | No episodes have a rating yet. Manual rating needs at least one rated episode |
| D9 | Ошибка сохранения режима рейтинга, `pickRatingMode` | Не удалось сохранить режим рейтинга | Couldn't save the rating mode |
| D10 | Ошибка сохранения режима дат, `pickDateMode` | Не удалось сохранить режим дат | Couldn't save the date mode |
| D11 | Дата окончания раньше даты начала, `saveWatchDates` | Дата окончания не может быть раньше начала | Finish date cannot precede the start date |
| D12 | Ошибка сохранения дат, `saveWatchDates` | Не удалось сохранить даты просмотра | Couldn't save watch dates |
| D13 | «⋯ → Просмотрено» отметило серии, `markWatched` | Отмечено просмотренным: {n} сер. | Marked as watched: {n} ep. |
| D14 | Ошибка отметки, `markWatched` | Не удалось отметить просмотренным | Couldn't mark as watched |
| D15 | Выбрана обложка, но скачать её не вышло, `pickCover` | Не удалось загрузить обложку | Couldn't download the cover |
| D16 | Обложка сменена, `pickCover` | Обложка обновлена ({источник}) | Cover updated ({source}) |
| D17 | Ошибка сохранения обложки, `pickCover` | Не удалось сохранить обложку | Couldn't save the cover |
| D18 | «Источник неверен» → выбран другой кандидат, `pickSource` | Источник: {titleEn} | Source: {titleEn} |
| D19 | Ошибка сохранения источника, `pickSource` | Не удалось сохранить источник | Couldn't save the source |
| D20 | «Источник неверен» → «Сбросить», `rejectSource` | Источник сброшен, тайтл помечен как ошибка | Source cleared, title flagged as wrong match |
| D21 | Ошибка сброса, `rejectSource` | Не удалось сбросить источник | Couldn't clear the source |

#### Страница «Серии», [ModernDetailsEpisodesPage.kt:213](../app/src/main/java/com/example/myapplication/ui/details/ModernDetailsEpisodesPage.kt) → `EpisodeMenuEvent.Message`

Все сообщения: `Info`, срок S. Тексты, у которых нет перевода, показываются одинаково на обоих языках.

| # | Триггер (`EpisodeMenuViewModel`) | RU | EN |
|---|---|---|---|
| E1 | Кнопка поиска новых сезонов (`discoverMoreSeasons`) нашла сезоны | Найдено сезонов: +{s}, серий: +{e} | Found +{s} season(s), +{e} episode(s) |
| E2 | Повторный поиск сезонов полностью обновил список | Список сезонов полностью обновлён | Season list fully refreshed |
| E3 | Новых сезонов нет | Новых сезонов нет | No new seasons |
| E4 | Источник потоковых сезонов не настроен | Источник потоковых сезонов не настроен | Streaming season source is not configured |
| E5 | Поиск сезонов упал или тайтл не найден | Источники не нашли этот тайтл | Sources don't have this title |
| E6 | «Смотреть», а серий нет (`startWatching`) | No episodes available | No episodes available |
| E7 | Смена качества, а источник не сообщает варианты (`requestMediaAction`) | Источник не сообщает варианты качества | Источник не сообщает варианты качества |
| E8 | Не удалось получить поток (`requestMediaAction`) | {текст ошибки} или «No stream found» | то же |
| E9 | Ошибка скачивания серии (`enqueueDownload`) | {текст ошибки} или «Download failed» | то же |
| E10 | Не удалось удалить локальную копию (`deleteEpisode`) | Couldn't delete local copy | Couldn't delete local copy |

### 2.3. Добавление и редактирование тайтла (AddEdit)

| # | Триггер | Kind | Срок | RU | EN |
|---|---|---|---|---|---|
| A1 | Нажата галочка сохранения, а название пустое ([AddEditScreen.kt:548](../app/src/main/java/com/example/myapplication/ui/addedit/AddEditScreen.kt)) | `Warning` | S | Введите название | Enter title |
| A2 | Сохранение упало (`AddEditEffect.ShowError`, [AddEditScreen.kt:117](../app/src/main/java/com/example/myapplication/ui/addedit/AddEditScreen.kt)) | `Error` | S | {текст исключения} или «Unknown error» | то же |
| A3 | Иконка копирования в поле «Название» или «Вариация» (англ. название), `PillTextFieldWithCopy` ([AddEditComponents.kt:385](../app/src/main/java/com/example/myapplication/ui/addedit/AddEditComponents.kt)) | `Success` | S | Copied! | Copied! |

Плашка поднята на 84 dp над жестовой панелью (раздел 3.1), поэтому не наезжает на круглую галочку сохранения.

### 2.4. Аудиоплеер (мини и полный), [AudiobookPlayerHost.kt](../app/src/main/java/com/example/myapplication/audiobooks/ui/AudiobookPlayerHost.kt)

| # | Триггер | Kind | Срок | RU | EN |
|---|---|---|---|---|---|
| P1 | Текущий сайт перестал отдавать книгу, плеер перешёл на следующий в цепочке (`state.sourceNotice`, строка 263) | `Warning` | L | Сайт не отвечает — продолжаем с «{name}» | The site stopped responding — continuing from {name} |
| P2 | Сайтов в цепочке не осталось (тот же триггер, пустое имя) | `Warning` | L | Ни один сайт сейчас не отдаёт эту книгу | No site is serving this book right now |
| P3 | «Ещё → Обновить обложку» нажата (строка 601) | `Info` | S | Ищем другую обложку… | Looking for another cover… |
| P4 | Результат поиска или импорта обложки: заменена (`announceCover`, строка 303) | `Info` | L | Обложка заменена | Cover changed |
| P5 | Импорт из галереи, загружено в облако | `Info` | L | Обложка сохранена и ушла в облако | Cover saved and synced to the cloud |
| P6 | Импорт, облако недоступно | `Info` | L | Облако недоступно: обложка сохранена только на этом устройстве | Cloud unavailable: the cover is saved on this device only |
| P7 | Других обложек нет | `Info` | L | Других обложек не нашли — нажмите ещё раз, чтобы начать заново | No other covers found — tap again to start over |
| P8 | Ошибка | `Info` | L | Не удалось поставить обложку | Couldn't set the cover |

P3 и P4–P8 обычно идут подряд. Если результат приходит раньше чем через 2.4 с, плашка перетекает из «Ищем…» в результат без схлопывания, но не раньше чем через 1.3 с после показа P3.

### 2.5. Страница книги (Details аудиокниги), [BookDetailsScreen.kt:165](../app/src/main/java/com/example/myapplication/audiobooks/ui/details/BookDetailsScreen.kt)

Все сообщения приходят через `BookNotice`: `Info`, срок L.

| # | Триггер (меню «⋯» книги) | RU | EN |
|---|---|---|---|
| B1 | «Автор и чтец» → Сохранить | Сохранено | Saved |
| B2 | «Прослушано» | Отмечено как прослушанное | Marked as listened |
| B3 | «Не прослушано» | Отметка снята | Mark removed |
| B4 | «Сменить обложку» → выбрана найденная | Обложка заменена | Cover replaced |
| B5 | «Сменить обложку» → «Из галереи», ушла в облако | Обложка загружена | Cover uploaded |
| B6 | «Из галереи», облако недоступно | Обложка сохранена только на этом устройстве | Cover saved on this device only |
| B7 | Ошибка смены обложки | Не удалось сменить обложку | Could not change the cover |
| B8 | «Источник неверен», другого нет | Другого источника для этой книги не нашлось | No other source for this book |
| B9 | «Источник неверен», открыт другой сайт | Открыта эта книга на другом сайте: {name} | Opened this book on another site: {name} |

### 2.6. Главная книг (полки), [BooksHomeScreen.kt:145](../app/src/main/java/com/example/myapplication/audiobooks/ui/home/BooksHomeScreen.kt)

Кнопка «Продолжить» запускает книгу без открытого листа, и запуск не удался. Kind `Info`, срок S.

| # | Причина | RU | EN |
|---|---|---|---|
| K1 | `LaunchState.Restricted` | Недоступно по просьбе правообладателя | Unavailable at the rights holder's request |
| K2 | `LaunchState.Unavailable` | Источник сейчас не отвечает | The source is not responding |

### 2.7. Настройки

| # | Где и триггер | Kind | Срок | RU | EN |
|---|---|---|---|---|---|
| S1 | «Вход по биометрии» включают, а биометрия на устройстве не настроена ([BiometricLockSettingRow.kt:26](../app/src/main/java/com/example/myapplication/security/BiometricLockSettingRow.kt)) | `Error` | L | Биометрия недоступна. Настройте отпечаток пальца или лицо в настройках устройства. | Biometrics are unavailable. Set up a fingerprint or face in your device settings. |
| S2 | Раздел разработчика → импорт БД из файла, добавлены записи ([SettingsScreen.kt:327](../app/src/main/java/com/example/myapplication/ui/settings/SettingsScreen.kt)) | `Info` | S | Импорт завершен: добавлено {n} записей | Import finished: added {n} entries |
| S3 | Импорт БД, новых нет | `Info` | S | Импорт завершен: новых записей не найдено | Import finished: no new entries found |
| S4 | Импорт БД, файл не подошёл | `Info` | S | Не удалось импортировать файл базы данных | Failed to import database file |
| S5 | Закончилась фаза «названия» (дубляж) в «Обогащении коллекции», пока открыты настройки (строка 337) | `Info` | L | Готово: заполнено {a} из {b} названий | Done: filled {a} of {b} titles |
| S6 | то же, всё уже заполнено | `Info` | L | Все названия уже заполнены | All titles are already filled |
| S7 | то же, ничего не заполнилось | `Info` | L | Не удалось заполнить названия (API/AI не дали english). Запустите дубляж ещё раз | Could not fill any titles (API/AI returned no english). Run dubbing again |
| S8 | то же, воркер упал | `Info` | L | Не удалось выполнить дубляж названий | (EN-вариант `resultFailed` в `Localization.kt`) |

Мёртвая ветка: `repairDbMessage` (строка 330) подписан на плашку, но `SettingsViewModel` никогда не записывает в него текст. Сейчас эта плашка не показывается.

#### Лист «Облако», [CloudSettingsSheet.kt:78](../app/src/main/java/com/example/myapplication/ui/settings/CloudSettingsSheet.kt)

Все сообщения: `Info`, срок L.

| # | Триггер | RU | EN |
|---|---|---|---|
| C1 | Привязка Google или GitHub не удалась | {текст ошибки} или «Link failed» | то же |
| C2 | Пароль для входа по email сохранён | Пароль сохранён — теперь можно входить по email | Password saved — you can now sign in with email |
| C3 | Ошибка сохранения пароля | {текст ошибки} или «Unknown error» | то же |
| C4 | «Войти через Google» (без аккаунта) | Logged in! / Login failed | Logged in! / Login failed |

#### Синхронизация со списками Shikimori и AniList, [ExternalListSyncCoordinator.kt:1137](../app/src/main/java/com/example/myapplication/sync/ExternalListSyncCoordinator.kt) `toastMain`

Все сообщения: `Info`, срок L. После возврата из браузера (OAuth) приложение может быть ещё в фоне. Тогда выйдет системный Toast.

| # | Триггер | Текст |
|---|---|---|
| X1 | Пользователь закрыл оверлей идущей синхронизации | Синхронизация отменена / Sync cancelled |
| X2 | Pull, Push или Sync нажаты, пока идёт другая синхронизация | Дождитесь завершения текущей синхронизации (только RU) |
| X3 | Нет client id, открывается регистрация OAuth-приложения | Открываем сайт для регистрации OAuth-приложения. Укажите redirect URI точно как в local.properties.example, добавьте ключи, пересоберите проект и повторите вход. / Opening the site to register an OAuth app. Set redirect URI exactly as in local.properties.example, add keys, rebuild, then try again. |
| X4 | OAuth вернул ошибку | {error_description или error} |
| X5 | Вход выполнен | Вход выполнен. Запуск выбранного действия… / Signed in. Running the selected action… |
| X6 | Обмен токена не удался | {текст ошибки} или «Не удалось завершить вход» / «Could not complete sign-in» |
| X7 | Сервис без логики | Для {service} логика пока не подключена (только RU) |
| X8 | Нет токена | Сначала выполните вход в Shikimori / в AniList (только RU) |
| X9 | Синхронизация упала | Ошибка синхронизации: {сообщение} (только RU) |
| X10 | Итог (приходит всегда, даже после X9) | Успешно. Добавлено: {a}, Обновлено: {u}, Отправлено: {p}, Пропущено: {s}, Ошибки: {e} (только RU) |

X9 и X10 приходят подряд. X10 сменяет X9 перетеканием не раньше чем через 1.3 с.

### 2.8. Вход (Welcome), [WelcomeScreen.kt:315](../app/src/main/java/com/example/myapplication/WelcomeScreen.kt) и [NavGraph.kt:176](../app/src/main/java/com/example/myapplication/ui/navigation/NavGraph.kt)

| # | Триггер | Kind | Срок | RU | EN |
|---|---|---|---|---|---|
| W1 | «Забыли пароль?» при пустом email | `Info` | S | Сначала введите email | Enter your email first |
| W2 | Вход через Google, GitHub или email не удался | `Error` | L | Ошибка входа: {msg} | Login failed: {msg} |
| W3 | Письмо для сброса пароля отправлено | `Success` | L | Если аккаунт существует, на {email} отправлено письмо для сброса пароля | If the account exists, a password reset email was sent to {email} |
| W4 | Сброс пароля не удался | `Error` | L | Ошибка: {msg} | Error: {msg} |

### 2.9. Сценарии с раскрытой карточкой

Раскрытие уместно там, где у сообщения есть структура: исход события, причина или цифры. Короткие реплики вроде «Сохранено» или «Copied!» остаются пилюлей. В режиме Toast (флаг выключен или приложение в фоне) везде уходит прежний полный текст.

| # | Где | Стадия 1: пилюля | Стадия 2: карточка (заголовок / тег / подзаголовок) | Kind | Срок карточки |
|---|---|---|---|---|---|
| H2 | Главная, конец облачной синхронизации | «Синхронизация…» (`Progress`, H1), пока идёт процесс | Синхронизировано / Облако / Тайтлов в коллекции: {n}. EN: Synced / Cloud / Titles in your collection: {n} | `Success` | 2400 мс |
| P1 | Плеер: сайт перестал отвечать | «Ищем другой источник…» (`Info`, 1450 мс). EN: Looking for another source… | Сайт не отвечает / Резерв / Продолжаем с «{name}». EN: The site stopped responding / Fallback / Continuing from {name} | `Warning` | 3800 мс |
| P2 | Плеер: сайтов не осталось | «Ищем другой источник…» (`Info`, 1450 мс) | Источников не осталось / без тега / Ни один сайт сейчас не отдаёт эту книгу. EN: No sources left / — / No site is serving this book right now | `Warning` | 3800 мс |
| X10 | Итог синхронизации Shikimori или AniList | нет: карточка растёт сразу из зародыша, а если X5 ещё на экране, перетекает из неё | Синхронизация завершена (или Завершено с ошибками, если `errors > 0`) / Shikimori или AniList / Добавлено: {a}, Обновлено: {u}, затем Отправлено, Пропущено, Ошибки, если они не нулевые. Только RU | `Success`, при ошибках `Warning` | 3800 мс |
| A2 | AddEdit: сохранение упало | нет | Не удалось сохранить / без тега / {текст исключения}. EN: Couldn't save | `Error` | 3800 мс |
| S1 | Настройки: биометрия не настроена | нет | Биометрия недоступна / без тега / Настройте отпечаток пальца или лицо в настройках устройства. EN: Biometrics unavailable / Set up a fingerprint or face in your device settings | `Error` | 3800 мс |
| W2 | Вход: Google, GitHub или email не удался | нет | Ошибка входа / без тега / {текст ошибки}. EN: Sign-in failed | `Error` | 3800 мс |
| W3 | Вход: письмо сброса пароля отправлено | нет | Письмо отправлено / без тега / Если аккаунт существует, на {email} придёт ссылка для сброса пароля. EN: Email sent / If the account exists, a reset link is on its way to {email} | `Success` | 3800 мс |
| W4 | Вход: сброс пароля не удался | нет | Не удалось сбросить пароль / без тега / {текст ошибки}. EN: Couldn't reset the password | `Error` | 3800 мс |

Подзаголовок обрезается многоточием после двух строк (около 70 символов при 393 dp), поэтому длинные тексты исключений видны не целиком.

### 2.10. Подключено, но не вызывается

`AnimatedCopyButton` ([SharedComponents.kt:108](../app/src/main/java/com/example/myapplication/ui/shared/components/SharedComponents.kt)) показал бы `Success` «Copied!», но у компонента нет вызовов.

---

## 3. Внешний вид

### 3.1. Положение на экране

```
┌──────────────────────────────── экран ───────────────────────────────┐
│                                                                      │
│                              (контент)                               │
│                                                                      │
│  16dp ┌──────────────────── плашка ─────────────────────┐ 16dp       │
│ ◄────►│ (●)  Текст сообщения                             │◄────►      │
│       └──────────────────────────────────────────────────┘            │
│                              ▲ 84 dp                                  │
│ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ верх жестовой панели / клавиатуры ─ ─ ─ ─ ─ ─ ─ │
└──────────────────────────────────────────────────────────────────────┘
```

- Хост занимает весь экран (`fillMaxSize`) и учитывает `imePadding()` и `navigationBarsPadding()`. При открытой клавиатуре плашка встаёт над ней.
- Отступы: снизу `bottomClearance = 84.dp`, это выше плавающего дока, галочки сохранения AddEdit и мини-дока Details. По бокам по 16 dp (`SideMargin`).
- Выравнивание `Alignment.BottomCenter`: плашка по центру по горизонтали и растёт вверх от нижнего края.
- Касания мимо плашки проходят насквозь. `pointerInput` стоит только на самой плашке.

### 3.2. Геометрия

| Параметр | Значение |
|---|---|
| Внутренний отступ слева (`PadStart`) | 10 dp |
| Внутренний отступ справа (`PadEnd`) | 18 dp |
| Вертикальный отступ (`PadVertical`) | 10 dp сверху и снизу. Участвует только в расчёте высоты, содержимое центрируется |
| Круг значка (`IconSize`) | 28 dp |
| Глиф или спиннер внутри круга | 16 dp |
| Зазор значок–текст (`IconGap`) | 10 dp |
| Минимальная высота (`MinHeight`) | 48 dp |
| Минимальная ширина (`MinWidth`) | 96 dp |
| «Зародыш» (`SeedWidth × SeedHeight`) | 52 × 24 dp: из него плашка растёт и в него сжимается |
| Радиус углов | `r = min(h / 2, 32 dp)`. При h ≤ 64 dp это пилюля, выше радиус 32 dp |
| Тень | `shadow(elevation = 14.dp)`, ambient и spot `#000000` α 0.25, по форме плашки |
| Кант | 1 dp. Тёмная тема `#FFFFFF` α 0.10, светлая `#000000` α 0.08 |
| Фон | тёмная тема `#2C2C2C`, светлая `#EDEDED`. Сплошной, без размытия подложки |

Расчёт целевого размера (`sizeOf`, строка 328):

```
textMax = max(screenWidth − 2·16 − 10 − 28 − 10 − 18, 96)   // = screenWidth − 98 dp
textW, textH = measure(text, style, maxLines = 3, maxWidth = textMax, Ellipsis)

W = max(10 + 28 + 10 + 18 + textW, 96)  = max(66 dp + textW, 96 dp)
H = max(48, textH + 2·10)               = max(48 dp, textH + 20 dp)
```

Примеры при fontScale 1 (строка 20 sp ≈ 20 dp):

| Строк | H | Радиус |
|---|---|---|
| 1 | 48 dp | 24 dp |
| 2 | 60 dp | 30 dp |
| 3 | 80 dp | 32 dp |

Максимальная ширина плашки: `screenWidth − 32 dp`. На экране 393 dp это 361 dp, текст до 295 dp.

### 3.3. Содержимое

```
Row(padding start = 10dp, end = 18dp, verticalAlignment = Center)
 ├─ KindBadge: Box 28dp, CircleShape, фон по типу, по центру Icon 16dp или спиннер 16dp
 ├─ Spacer 10dp
 └─ Text: maxLines = 3, Ellipsis, widthIn(max = textMax)
```

Содержимое стоит по центру плашки по обеим осям (`contentAlignment = Center` и `wrapContentSize(Center, unbounded = true)`). Пока форма меньше содержимого, оно выходит за край и обрезается `clip(IslandShape)`.

Стиль текста:

| Свойство | Значение |
|---|---|
| Шрифт | `SnProFamily` = `FontFamily.SansSerif` (системный) |
| Размер | 15 sp |
| Межстрочный интервал | 20 sp |
| Начертание | `FontWeight.Medium` (500) |
| Трекинг | −0.15 sp (−0.01 em) |
| Цвет | `onSurface`: `#F9F9F9` в тёмной теме, `#1A1A1A` в светлой |

Доступность: `semantics(mergeDescendants = true) { liveRegion = Polite }`. TalkBack зачитывает текст при каждой смене.

### 3.4. Раскрытая карточка («размер 2» прототипа)

```
┌────────────────────────── 345 dp (экран − 2·24) ───────────────────────────┐
│ 14   ┌──────┐  12   Заголовок 15/20 SemiBold ................ [ Тег ]  18  │
│      │ 38dp │       Подзаголовок 13/17, до 2 строк, onSurface α0.65         │
│      └──────┘                                                               │
└─────────────────────────────────────────────────────────────────────────────┘
   вертикальные отступы 14 dp сверху и снизу, радиус min(h/2, 32) = 32 dp
```

| Параметр | Значение |
|---|---|
| Ширина | `screenWidth − 2·24 dp` (393 dp → 345 dp), не зависит от текста |
| Отступы | слева 14 dp, справа 18 dp, сверху и снизу 14 dp |
| Значок | круг 38 dp, глиф или спиннер 20 dp; цвета по `MiniNotifKind`, как у пилюли |
| Зазор значок–текст | 12 dp |
| Заголовок | 15 sp / 20 sp, `SemiBold` (600), трекинг −0.15 sp, одна строка с многоточием, `onSurface` |
| Тег | 11 sp / 14 sp, `Medium` (500), `onSurface` α 0.45; капсула с фоном `#FFFFFF` α 0.07 (тёмная) или `#000000` α 0.06 (светлая); отступы 7 × 2 dp; прижат к правому краю, от заголовка 8 dp |
| Подзаголовок | 13 sp / 17 sp, `Normal` (400), трекинг −0.065 sp, `onSurface` α 0.65, до 2 строк, отступ от заголовка 2 dp |
| Ширина текстовой колонки | `345 − 14 − 18 − 38 − 12 = 263 dp` |
| Фон, кант, тень, положение | те же, что у пилюли (3.1, 3.2) |

Высота считается по тексту (`sizeOf`, ветка `card`):

```
textW = cardWidth − 14 − 18 − 38 − 12
titleH = measure(title, 15/20, maxLines = 1, textW)
subH   = measure(subtitle, 13/17, maxLines = 2, textW)       // 0, если подзаголовка нет
gap    = subH > 0 ? 2 dp : 0
H = 2·14 + max(38, titleH + gap + subH)

без подзаголовка            66 dp
подзаголовок в 1 строку     67 dp
подзаголовок в 2 строки     84 dp
```

Углы. В прототипе радиус считается как `min(h/2, 32)`. У карточки (h ≥ 66) он всегда 32 dp, то есть меньше половины высоты. Поэтому карточка выглядит как скруглённый прямоугольник, а не как капсула. Код менять не пришлось: `IslandShape` уже считает именно так, а при росте пилюли (48 dp, радиус 24) в карточку радиус плавно доходит до 32 dp вместе с пружиной высоты.

---

## 4. Анимация

### 4.1. Пружины

Все пружины с массой 1. Параметры перенесены из референса designbookmark «Morph island» (`shared/tokens.css`), где они заданы как жёсткость k и вязкость c. В Compose это `spring(dampingRatio = c / (2·√k), stiffness = k)`:

```kotlin
// MotionTokens.kt:222
private fun <T> referenceSpring(stiffness: Float, damping: Float, reduced: Boolean): SpringSpec<T> =
    spring(dampingRatio = if (reduced) 1f else damping / (2f * sqrt(stiffness)), stiffness = stiffness)

fun <T> islandWidth()        = referenceSpring(380f, 24f)  // ширина
fun <T> islandHeight()       = referenceSpring(300f, 22f)  // высота при росте, подъём хоста
fun <T> islandHeightShrink() = referenceSpring(390f, 22f)  // высота при сжатии (300 × 1.3)
fun <T> islandPress()        = referenceSpring(520f, 18f)  // «нажатие», масштаб
val IslandRadius = 32.dp
```

Уравнение движения для отклонения от цели `x = value − target`:

```
x'' + c·x' + k·x = 0
ω₀ = √k,   ζ = c / (2√k),   ω_d = ω₀·√(1 − ζ²)
```

Все четыре пружины недодемпфированы (ζ < 1). Решение в закрытой форме при старте из `x₀` со скоростью `v₀`:

```
x(t) = e^(−ζω₀t) · [ x₀·cos(ω_d t) + ((v₀ + ζω₀x₀) / ω_d)·sin(ω_d t) ]
value(t) = target + x(t)
```

Производные характеристики:

```
перелёт (доля шага) = e^(−ζπ / √(1−ζ²))
время пика t_p      = π / ω_d
оседание до 2 %     ≈ 4 / (ζω₀) = 8 / c
```

| Токен | k | c | ζ | ω₀, рад/с | ω_d, рад/с | Перелёт | t_p | Оседание ~2 % |
|---|---|---|---|---|---|---|---|---|
| `islandWidth` | 380 | 24 | 0.616 | 19.49 | 15.36 | 8.6 % | 205 мс | ~333 мс |
| `islandHeight` | 300 | 22 | 0.635 | 17.32 | 13.38 | 7.6 % | 235 мс | ~364 мс |
| `islandHeightShrink` | 390 | 22 | 0.557 | 19.75 | 16.40 | 12.2 % | 192 мс | ~364 мс |
| `islandPress` | 520 | 18 | 0.395 | 22.80 | 20.95 | 25.9 % | 150 мс | ~444 мс |

Ширина жёстче высоты, поэтому любое изменение формы сначала уходит вбок, а потом оседает по вертикали.

### 4.2. Твины

`tween(ms)` в Compose по умолчанию идёт с `FastOutSlowInEasing = cubic-bezier(0.4, 0, 0.2, 1)` (ниже FOSI). Масштаб содержимого использует отдельную кривую `ContentEase = cubic-bezier(0.16, 1, 0.3, 1)` (expo-out из референса).

| Величина | Анимация |
|---|---|
| `hostAlpha` 0 → 1 | `tween(140)` FOSI |
| `hostAlpha` 1 → 0 | `tween(160)` FOSI, в reduced `tween(150)` |
| `hostLift` 10 dp → 0 (появление) | пружина `islandHeight` |
| `hostLift` 0 → 6 dp (уход) | `tween(160)` FOSI |
| `contentAlpha` 0 → 1 | `tween(350)` FOSI, в reduced `tween(150)` |
| `contentAlpha` → 0 при замене | `tween(140)` FOSI |
| `contentAlpha` → 0 при схлопывании | `tween(120)` FOSI |
| `contentScale` 0.9 → 1 | `tween(600, ContentEase)` |

### 4.3. Производные эффекты (на каждый кадр)

Всё считается в `graphicsLayer`, без перекомпозиции:

```kotlin
// плашка (MiniNotif.kt:437)
alpha        = hostAlpha
translationY = hostLift                                    // px, вниз положительно
give         = clamp(−height.velocity / 7000, −0.05, 0.05) // velocity в px/с
scaleX       = press · (1 + give)                          // «объём»: пока высота бежит, ширина отдаёт
scaleY       = press

// содержимое (MiniNotif.kt:480)
alpha  = contentAlpha
scale  = contentScale                                      // X и Y
blur   = (1 − contentAlpha) · 6 dp                         // BlurEffect(blur, blur, Decal), только API 31+ и blur > 0.5 px
```

Формулы:

```
give(t)    = clamp(−ḣ(t) / 7000, −0.05, +0.05)
scaleX(t)  = p(t) · (1 + give(t))
scaleY(t)  = p(t)
blur(t)    = 6dp · (1 − α_c(t))
```

`give` зависит от плотности экрана, потому что скорость измеряется в px/с. Пример: рост 24 → 48 dp при плотности 2.75 (66 px). Пиковая скорость пружины `islandHeight` из покоя: `Δ·ω₀·e^(−ζφ/√(1−ζ²))`, где `φ = atan(√(1−ζ²)/ζ)`. Получается `66·17.32·0.484 ≈ 553 px/с`, `give ≈ −0.079`, после ограничения −0.05. На росте плашка на мгновение сужается на 5 %, на сжатии расширяется на 5 %.

Размер плашки на каждом кадре выставляется жёстко: `layout { measure(Constraints.fixed(width.roundToInt(), height.roundToInt())) }`. Тень, клип, фон и кант идут после этого и повторяют форму. Радиус пересчитывается от текущей высоты.

### 4.4. «Толчок» (press) со стартовой скоростью

При каждом показе `press` анимируется к 1 из 1 с отрицательной скоростью. Плашка сначала проседает, потом слегка перепрыгивает:

```
p(t) = 1 + (v₀ / ω_d) · e^(−ζω₀t) · sin(ω_d t)      // x₀ = 0, k = 520, c = 18
минимум при  t* = atan(ω_d / (ζω₀)) / ω_d ≈ 55.6 мс
```

| Когда | v₀ (1/с) | Минимум p | Следующий пик (≈206 мс) |
|---|---|---|---|
| Новое сообщение крупнее прежнего (площадь растёт) | −1.6 | ≈ 0.957 | ≈ 1.011 |
| Новое сообщение меньше или равно | −1.0 | ≈ 0.973 | ≈ 1.007 |
| Пришёл тот же текст и тип (повтор) | −2.0 | ≈ 0.947 | ≈ 1.014 |

Нажатие пальцем: `press → 0.96` пружиной `islandPress`, при отпускании `→ 1` той же пружиной. Тап вызывает `MiniNotif.dismiss()`.

### 4.5. Хронология появления (первая плашка), t = 0 в момент `present(first = true)`

```
t=0      snap: W=52dp H=24dp, hostAlpha=0, hostLift=+10dp, contentAlpha=0, contentScale=0.9
         запуск: hostAlpha→1 tween(140)
                 hostLift→0 spring(300,22)
                 width→W spring(380,24)
                 height→H spring(300,22)     // рост
                 press 1→1 spring(520,18), v₀=−1.6
         отсчёт удержания стартует сейчас (2400 / 3800 / свой)
t=56мс   press минимальный ≈0.957
t=90мс   contentScale 0.9 → 1 за 600 мс (ContentEase)
         contentAlpha 0 → 1 за 350 мс; blur 6dp → 0 вместе с ней
t=140мс  плашка полностью непрозрачна
t=205мс  ширина на пике перелёта (+8.6 % от W−52)
t=235мс  высота на пике перелёта (+7.6 % от H−24)
t≈364мс  форма осела
t=440мс  текст полностью проявлен
t=690мс  масштаб текста = 1
```

### 4.6. Замена (новое сообщение, пока видно старое)

Плашка не схлопывается, а перетекает в новый размер, как Dynamic Island между активностями.

```
(ждём, пока со старого показа пройдёт ≥ 1300 мс)
t=0      contentAlpha→0 tween(140)      // старый текст уходит с размытием до 6dp
t=140мс  содержимое подменяется на новое
         width→W' spring(380,24)
         height→H' spring(300,22) при росте, spring(390,22) при сжатии
         press v₀ = −1.6 (рост площади) или −1.0
         отсчёт удержания нового стартует здесь
t=230мс  новое содержимое: scale 0.9→1 (600, ContentEase), alpha 0→1 (350)
```

Рост определяется по площади: `grow = W'·H' > W·H`, где `W·H` берутся из `targetValue`, то есть из текущих целей.

Повтор того же текста и типа (`sameMessageAs`): содержимое не перерисовывается и размер не меняется. Только толчок `press` с v₀ = −2 и новый отсчёт удержания.

### 4.7. Схлопывание

Когда срок вышел, был тап или `dismiss()` и в очереди пусто:

```
t=0      contentAlpha→0 tween(120)
         width→52dp spring(380,24)
         height→24dp spring(390,22)
t=170мс  hostLift 0→+6dp tween(160)  (плашка уходит вниз)
         hostAlpha 1→0 tween(160)
t=330мс  плашка удалена из дерева (shown = null)
```

### 4.8. Системное «Отключить анимации»

Условие: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (`rememberReducedMotion`).

- Размер выставляется сразу (`snapTo`), пружины формы, `press` и подъём не запускаются, нажатие не проседает.
- Масштаб содержимого сразу 1, остаётся только `contentAlpha` 0 → 1 за 150 мс. Размытие при этом считается по той же формуле.
- Появление: `hostAlpha` 0 → 1 за 140 мс без подъёма.
- Схлопывание: только `hostAlpha` 1 → 0 за 150 мс.

### 4.9. Пилюля → карточка (`showMorph`)

Раскрытие использует тот же механизм, что и замена сообщения (4.6): карточка приходит как вторая стадия, и плашка перетекает в неё, а не схлопывается. Хронология при ширине экрана 393 dp:

```
t=0       пилюля показана как в 4.5: ширина по тексту, высота 48 dp, пружины form
t=1450мс  holdFor(пилюля, 1450) истёк (минимум 1300 мс на чтение пилюли соблюдён)
t=1450    contentAlpha→0 tween(140)                      // текст пилюли уходит с размытием до 6dp
t=1590    содержимое подменяется на карточку
          width→345 spring(380,24)                       // быстрее по ширине
          height→67…84 spring(300,22), так как площадь растёт
          press v₀=−1.6
          отсчёт удержания карточки: 2500 мс (long: 3800 мс)
t=1680    contentScale 0.9→1 за 600 мс (ContentEase), contentAlpha 0→1 за 350 мс
t≈1795    ширина на пике перелёта (+8.6 % от 345−W₁), пик через 205 мс после старта пружины
t≈1825    высота на пике перелёта (+7.6 % от H−48), пик через 235 мс
t≈1955    форма осела (~364 мс после старта), радиус дошёл до 32 dp
```

В прототипе текст карточки проявляется через 70 мс после подмены, в приложении остались прежние 90 мс (4.6). Если во время пилюли приходит другое сообщение, раскрытие всё равно показывается: карточка держится минимальные 1300 мс, потом плашка перетекает в следующее сообщение.

Без анимаций (4.8) пилюля сразу меняется на карточку, остаётся кроссфейд 150 мс.

---

## 5. Очередь и жизненный цикл

```kotlin
// MiniNotif.kt:399, упрощённо
while (true) {
    var next = receive()                 // ждём первое сообщение
    var active: Item? = null
    while (next != null) {
        if (active?.sameMessageAs(next) == true) kick(v0 = -2f)
        else present(next, first = active == null)
        active = next
        next = when (val r = hold(next)) {           // hold = holdFor, а у showMorph сначала пилюля, потом карточка
            is Next -> r.item                         // если раньше 1300 мс, дождаться их
            Expired, Dismissed -> null
        }
    }
    collapse()
}
```

- Хост регистрируется в `DisposableEffect`: `attachHost()` и `setHostVisible(lifecycle ≥ STARTED)`. Слушает `ON_START` и `ON_STOP`.
- Ёмкость очереди 24, `DROP_OLDEST`. Сообщения показываются строго по порядку, каждое минимум 1.3 с.
- `dismiss()` идёт через отдельный `CONFLATED` канал. Тап во время удержания сразу ведёт к схлопыванию, даже если в очереди есть ещё сообщения: они покажутся следующим циклом, с ростом из зародыша.

---

## 6. Минимальная реализация вне Android (JS)

Для прототипа в браузере, с теми же цифрами. Пружина интегрируется полунеявным Эйлером с шагом 1 мс. Для таких параметров результат совпадает с аналитическим решением из 4.1.

```js
// Пружина с массой 1: k — жёсткость, c — вязкость (как в tokens.css)
class Spring {
  constructor(value, k, c) { Object.assign(this, { value, v: 0, target: value, k, c }); }
  to(target, k = this.k, c = this.c, v0) {
    Object.assign(this, { target, k, c });
    if (v0 !== undefined) this.v = v0;
  }
  step(dt) {                       // dt в секундах
    for (let t = 0; t < dt; t += 0.001) {
      const a = -this.k * (this.value - this.target) - this.c * this.v;
      this.v += a * 0.001;
      this.value += this.v * 0.001;
    }
  }
}

const W = new Spring(52, 380, 24);    // dp
const H = new Spring(24, 300, 22);
const P = new Spring(1, 520, 18);     // press
const LIFT = new Spring(10, 300, 22); // dp

function present(w, h, first) {
  const grow = w * h > W.target * H.target;
  W.to(w, 380, 24);
  H.to(h, grow ? 300 : 390, 22);
  P.to(1, 520, 18, grow ? -1.6 : -1.0);
  if (first) LIFT.to(0);
  // через 90 мс: contentScale 0.9→1 за 600 мс по cubic-bezier(.16,1,.3,1),
  //              contentAlpha 0→1 за 350 мс по cubic-bezier(.4,0,.2,1)
}

function collapse() {
  W.to(52, 380, 24);
  H.to(24, 390, 22);
  // contentAlpha → 0 за 120 мс; через 170 мс: lift 0→6 и hostAlpha 1→0 за 160 мс, cubic-bezier(.4,0,.2,1)
}

function frame(el, content, dt, hostAlpha, contentAlpha, contentScale) {
  const prevH = H.value;
  [W, H, P, LIFT].forEach(s => s.step(dt));
  const hVelPx = (H.value - prevH) / dt * devicePixelRatio;  // px/с, как в Compose
  const give = Math.max(-0.05, Math.min(0.05, -hVelPx / 7000));
  el.style.width  = W.value + 'px';
  el.style.height = H.value + 'px';
  el.style.borderRadius = Math.min(H.value / 2, 32) + 'px';
  el.style.opacity = hostAlpha;
  el.style.transform =
    `translateY(${LIFT.value}px) scale(${P.value * (1 + give)}, ${P.value})`;
  content.style.opacity = contentAlpha;
  content.style.transform = `scale(${contentScale})`;
  content.style.filter = `blur(${(1 - contentAlpha) * 6}px)`;
}
```

CSS плашки:

```css
.island {
  position: fixed; left: 50%; bottom: calc(env(safe-area-inset-bottom) + 84px);
  translate: -50% 0; transform-origin: 50% 50%;
  background: #2C2C2C;                       /* светлая: #EDEDED */
  border: 1px solid rgb(255 255 255 / .10);  /* светлая: rgb(0 0 0 / .08) */
  box-shadow: 0 8px 28px rgb(0 0 0 / .25);   /* приближение elevation 14dp */
  overflow: hidden; display: grid; place-items: center;
}
.island .row   { display: flex; align-items: center; gap: 10px; padding: 0 18px 0 10px; }
.island .badge { width: 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; flex: none; }
.island .badge svg { width: 16px; height: 16px; }
.island .text  { font: 500 15px/20px system-ui, sans-serif; letter-spacing: -0.01em;
                 color: #F9F9F9;             /* светлая: #1A1A1A */
                 max-width: calc(100vw - 98px);
                 display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
```

В CSS `px` соответствует dp. Тень `elevation 14dp` в Android рисуется по-другому (ambient + spot от источника света сверху), так что `box-shadow` здесь приближение.

---

## 7. Известные шероховатости

- **Срок H1 «Синхронизация…».** Плашка держится 8 с и не продлевается, если синхронизация идёт дольше.
- **Типы значков.** Ошибки в плеере (P8), на странице книги (B7), в Details (D2, D5, D6 и т.д.) и в синхронизации списков (X6, X9) показываются как `Info`, а не `Error`. Такой тип выбран в местах вызова.
- **Тексты без перевода.** Часть текстов захардкожена на одном языке: «Copied!» (A3), E6, E7, E10, C1, C4, X2, X7–X10.
- **Мёртвые пути.** `repairDbMessage` в настройках и `AnimatedCopyButton` подключены, но не срабатывают.
- **Тексты карточек X10 только на русском.** Координатор синхронизации списков не знает язык интерфейса, как и его прежние сообщения.
- **Счётчик H2.** «Тайтлов в коллекции» берётся из списка главной на момент конца синхронизации и не говорит, сколько записей синхронизация изменила.
- **Задержка в P1 и P2.** Информация о смене источника показывается после пилюли «Ищем другой источник…», то есть через 1.45 с, хотя событие уже случилось. Если это мешает, `showMorph` можно заменить на `show(..., card = ...)`: карточка появится сразу.
- **Ширина экрана в хосте** запоминается при первой композиции (как и ширина текста пилюли): после поворота экрана плашка считает размеры по старой ширине до перезапуска хоста.
- **Комментарий о замене.** В коде сказано, что форма начинает меняться, пока уходит старое содержимое. На деле `present()` ждёт 140 мс до запуска пружин формы (`delay(140)` в ветке замены), так что форма стартует после ухода текста.
