# Использование пакета

Политика и ограничения: [MODEL_CARD.md](MODEL_CARD.md).

## В этой ветке Vetro

Сгенерированный `tools/titlematch/.cache/releases/current/android-assets`
подключён в Gradle. При его отсутствии приложение сохраняет прежние правила.
Выпускной ZIP хранится в `.cache/releases/v2/`, SHA-256 — рядом в `SHA256SUMS`.
Изменения не опубликованы и не включают удаление/объединение записей коллекции.

`TitleIdentityService` загружает пакет на IO и вычисляет признаки на Default.
Он подключён к теневому анализу рекомендаций, ручному выбору названия и
проверке перед записью автоматического обогащения. Теневые счётчики доступны
через `shadowCounts()`; в них нет названий, ID коллекции и отправки в сеть.

## В другом Android-проекте

1. Распаковать `android-assets/` из ZIP в `app/src/main/assets/`.
2. Добавить четыре Kotlin-файла из `android-sdk/` в исходники проекта.
   Требуются Kotlin stdlib и стандартные Android API, дополнительный ML-runtime
   для основной модели не нужен. Проверено с Kotlin 2.1 / Java 21 / minSdk 26.
3. Загрузить пакет в рабочем потоке и использовать отдельные политики действий.

```kotlin
val runtime = withContext(Dispatchers.IO) { TitleMatchPack.load(context.assets) }
val id = TitleExternalId(TitleNamespace.MAL_ANIME, "1")
val left = TitleIdentity(listOf("Cowboy Bebop"), TitleKind.ANIME, ids = setOf(id))
val right = TitleIdentity(listOf("Ковбой Бибоп"), TitleKind.ANIME, ids = setOf(id))
val result = withContext(Dispatchers.Default) {
    runtime?.matcher?.compare(left, right)
}
val mayHide = result?.let(TitleIdentityPolicies::hideRecommendation) ?: false
```

Общий ID в примере — подтверждённое доказательство. Без него matcher использует
словарь и модель, но ML-предположение имеет `verified=false`. Политики не разрешают
ему автоматически скрывать рекомендацию или записывать внешние ID.
Для ранжирования используется `TitleIdentityPolicies.searchScore(result)`.

Разные `TitleKind`, `TitleLevel`, пространства ID, конфликтующие ID, авторы,
сезоны и номера обрабатываются до принятия вероятностного результата. В v2
поддерживаются CATALOG_ENTRY для видео/манги и WORK для книг. Для NARRATION,
EDITION, SEASON и EPISODE возвращается неопределённость, даже при общем ID родителя.

При отсутствии или повреждении пакета `load()` возвращает null. Диагностический
`loadVerified()` бросает ошибку и используется только проверочным APK.
Холодная загрузка словаря занимает несколько секунд: не вызывать на UI-потоке.

## Альтернативная CharCNN

В ZIP лежат PyTorch-веса, TFLite INT8, отчёт экспорта и отдельная калибровка.
Не применять к ней порог/калибровку LightGBM. Выход INT8 — logit; его нужно
деквантизовать по параметрам выходного тензора, применить sigmoid и затем
калибровку из `deployment.json`. Для уверенного хвоста нельзя заменять это
трактовкой байта как готовой вероятности.

Нейросеть прошла проверку CPU LiteRT 2.3.0 в изолированном Java benchmark.
API этого runtime собран с более новой Kotlin metadata, поэтому в Vetro 2.1
он не добавлен как production-зависимость. Пример проверочного проекта отделяет
Java Interpreter от Kotlin 2.1 core. Основной forest такого ограничения не имеет.
