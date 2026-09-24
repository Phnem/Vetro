# AB-08 review — 2026-09-24

Outcome: DONE_WITH_DEVIATIONS. No blocking issue found in the implemented scope.

## Проверка требований

| Критерий | Итог |
|---|---|
| Границы файлов, главы, неизвестная/оценочная длительность, прогресс и скорость | JVM tests pass; неизвестное значение остаётся `null`. |
| Стабильный URI без URL и секретов | `VariantId` кодируется URL-safe Base64; round-trip с `:`, `/` и Unicode проверен. |
| Очередь без разрезания M4B | Android smoke: 2 файла, 3 главы, ровно 2 MediaItem, без clipping. |
| URL обновляется при expiry и сохраняет headers | Android smoke с fake source: v1→v2 при открытии DataSource. |
| 401/403/410 retry один раз с той же позиции | Код сервиса инвалидирует manifest, seek/prepare выполняется один раз на mediaId; интеграционный HTTP smoke перенесён в AB-17. |
| Device/release | Xiaomi: 3/3 Android tests pass; unsigned release build pass. Test runner удалён, один тестовый package остался. |

## Изменения плана

- Динамическая глава внутри M4B в шторке — AB-10.
- Потоковый кэш с ключом по логическому URI — AB-17; offline download-кэш — AB-34.
- При первом реальном онлайн-источнике проверить ответ 403/410, одно обновление URL, прежнюю позицию и отсутствие цикла.
