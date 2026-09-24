# AB-01: ADR — хранение и доменная модель аудиокниг

Status: done_with_deviations (2026-09-23)
Type: grilling → task
Blocked by: —

## Цель

Зафиксировать решение D-02: где живёт книга библиотеки и как это уживается с коллекцией,
синком и воркерами. Результат — `docs/adr/0001-audiobooks-storage.md` и термины раздела в
`CONTEXT.md` (через `/domain-modeling`).

## Объём

- Зафиксировать подтверждённые Q1 (общая коллекция + отдельные таблицы), Q2 (видимость на Home),
  Q3 (локальные audiobook-строки до совместимого релиза).
- До миграции утвердить постоянный `WorkId` UUID и изменяемый `clusterFingerprint` вместо
  hash как первичного ключа.
- Прогнать `graft callers MediaType --depth 2` и приложить к ADR список мест, которые затронет
  `MediaType.AUDIOBOOK` (ожидается ≈ 59 упоминаний констант).
- Проверить живую схему Supabase: какие значения реально допускает `anime_media_type_check`
  (миграция в репо допускает `ANIME/MANGA/TV_SERIES`, а приложение пишет ещё `MOVIE/SERIES`?).
- Записать глоссарий (spec/01) в `CONTEXT.md`.

## Вне рамок

Любой код.

## Критерии приёмки

- ADR с контекстом, решением, отвергнутыми вариантами (полностью отдельная библиотека; всё в
  таблице `anime`) и последствиями.
- Ответы Q1–Q3 внесены в `spec/14` и `MASTER_PLAN.md` (снята пометка «подтвердить» у D-02).
- В `spec/03` PK/FK используют UUID, а нормализованный hash — только индекс сопоставления.

## Ссылки

[spec/03](../spec/03-domain-and-storage.md), [spec/14](../spec/14-risks-and-open-questions.md),
память проекта `media-type-vs-category-type`.

## Результат

- Принят `docs/adr/0001-audiobooks-storage-and-identity.md`; устойчивые термины записаны в
  `CONTEXT.md`.
- Q1–Q3 и UUID-идентичность отражены в MASTER_PLAN и spec/03; SQL-черновик миграции 16
  использует `work_id`/`narration_id` как PK/FK и индексируемый `cluster_fingerprint`.
- `graft callers MediaType --depth 2` не нашёл индексированных входящих рёбер; проведён
  текстовый поиск call sites. Полный аудит каждого места — критерий AB-12.
- Репозиторная Supabase-миграция проверена: CHECK содержит `ANIME/MANGA/TV_SERIES`, в то время
  как enum приложения содержит также `MOVIE/SERIES`. Деплойную схему проверить не удалось:
  подключённой базы или безопасного read-only доступа нет. Это отклонение не влияет на V1,
  поскольку audiobook sync явно выключен; проверка переносится в тикет включения cloud sync.

## Проверка

- `rg` по `WorkKey|work_key|narration_key` в audiobook-спецификациях: старых PK/FK-ссылок нет.
- Baseline `./gradlew :app:compileDebugKotlin -q`: прошёл до AB-02.
- Review: ADR соответствует Q1–Q3, черновой схеме и разделению работы/отпечатка;
  отсутствуют изменения исполняемого кода.
