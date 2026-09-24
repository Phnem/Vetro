# AB-06: Каркас пакета, DI, строки и флаг

Status: done_with_deviations (2026-09-23)
Type: implementation
Blocked by: AB-01

## Цель

Создать независимый корень `audiobooks/`, отдельный `audiobookModule` в Koin и `AudiobookStrings` без расширения большого `UiStrings`. Новая функциональность по умолчанию закрыта build flag до готовности M1; существующая заглушка «Книги» остаётся доступной.

## Приёмка

- `audiobookModule` подключён одной строкой в `VetroApplication`, пока регистрирует только флаг и готов к AB-07.
- Отдельные RU/EN строки используются заглушкой, не добавляя параметров в `UiStrings`.
- `:app:compileDebugKotlin` и `:app:assembleRelease` проходят. Для изменения DI/строк проверяется запуск release на чистом тестовом устройстве либо документируется ограничение.
- Никакой audiobook service, БД и sync на этом шаге не запускаются.

## Ссылки

`spec/02-architecture.md` § DI, `MASTER_PLAN.md` D-15.

## Результат

- Добавлены `audiobooks/AudiobookFeatureGate.kt`, `audiobooks/ui/AudiobookStrings.kt`, `di/audiobookModule.kt`; модуль подключён в `VetroApplication` одной строкой.
- `BuildConfig.AUDIOBOOKS_ENABLED=false` по умолчанию. Страница-заглушка «Книги» берёт RU/EN подписи из отдельного каталога; `UiStrings` не менялся.
- `:app:compileDebugKotlin` и `:app:assembleRelease` прошли. Release APK получился unsigned; запуск release не выполнялся, чтобы не заменить установленное приложение пользователя или тестовую копию тем же package id. Runtime smoke остаётся для AB-07 на изолированном package.
- Следующий тикет: AB-07.

Позднее в AB-07 запущена изолированная release-копия с этим DI и строками без `VerifyError`;
девиация runtime smoke AB-06 закрыта 2026-09-24.
