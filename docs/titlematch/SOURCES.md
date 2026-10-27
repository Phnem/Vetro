# TitleMatch: источники и ограничения

Проверено 2026-10-27 по первичным источникам. Это инженерный реестр происхождения;
загрузка в исследовательский кэш не разрешает автоматически обучение или релиз.

| Ресурс | Проверенные факты | Применение |
|---|---|---|
| [Manami 2026-27](https://github.com/manami-project/anime-offline-database/releases/tag/2026-27) | Архивирован 4 июля; README сообщает 41,537 записей; ODbL/DbCL в LICENSE | Исторический snapshot; audit aliases/IDs, отдельно проверить происхождение полей |
| [cedya 2026-43](https://github.com/cedya77/anime-offline-database/releases/tag/2026-43) | Актуальный зафиксированный релиз; статистика README отстаёт от release API | Независимый diff с Manami; не заменять корпус без анализа |
| [Fribb](https://github.com/Fribb/anime-lists/tree/08dc828de12e60e736e78b24a096aa7b4d77d7f1) | Маппинги содержат TMDB movie/TV, сезоны и episode offset; корневая лицензия не найдена | Только карантин/исследование; обучение и распространение не разрешены этим планом |
| [Wikidata data access](https://www.wikidata.org/wiki/Wikidata:Data_access) | Структурированные данные CC0; entity JSON сохраняет QID/revision/aliases/labels/claims | Небольшие revision-pinned samples; затем bounded ID-based выгрузка |
| [Potion 2M](https://huggingface.co/minishlab/potion-base-2M) | Model card: MIT, статические эмбеддинги, английский teacher; 7,559,256 байт весов на выбранной ревизии | Английский offline baseline, не готовая модель идентичности |
| [Multilingual MiniLM](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2) | Model card: Apache-2.0; INT8 ONNX/tokenizer/config фиксируются вместе | Многоязычный offline baseline; не включается в APK; не источник истинных identity labels |

Все точные URL, commits/revisions, длины и SHA-256 находятся в
[`resources.lock.json`](../../tools/titlematch/resources.lock.json). JSONL первых
двух корпусов начинается метаданными; synonyms не имеют надёжной per-alias
разметки языка/происхождения. Значение language для них остаётся unknown, пока
его не установит проверенный источник. Один script не определяет язык.

`relatedAnime` и Fribb collections означают отношения между записями и не могут
превращаться в положительную разметку «одно произведение». Связи на уровне сезона
или эпизодов также не являются равенством целого сериала.

## Дополнительные поставщики

- [AniList API terms](https://docs.anilist.co/guide/terms-of-use) ограничивают
  массовый сбор и конкурирующие list/tracker приложения. Новый bulk ingestion
  отсутствует. В смешанных snapshot нужно отдельно проверить upstream rights;
  ODbL агрегатора не является автоматическим разрешением всех поставщиков.
- [Jikan](https://github.com/jikan-me/jikan-rest): MIT относится к ПО; API получает
  данные MAL и сам по себе не даёт права на обучающий/распространяемый датасет.
- [Shikimori API](https://shikimori.io/api/doc): разрешение массового обучения и
  распространения не подтверждено; загрузку корпуса отложили. Доступность API не
  означает лицензию данных.
- [TMDB API FAQ](https://developer.themoviedb.org/docs/faq): отдельно проверить
  условия использования/атрибуции и применимость к offline pack/training, прежде
  чем формировать корпус фильмов/сериалов.

Wikidata: [P4086 MAL anime](https://www.wikidata.org/wiki/Property:P4086),
[P4087 MAL manga](https://www.wikidata.org/wiki/Property:P4087),
[P4947 TMDB movie](https://www.wikidata.org/wiki/Property:P4947),
[P4983 TMDB TV](https://www.wikidata.org/wiki/Property:P4983),
[P648 Open Library](https://www.wikidata.org/wiki/Property:P648). У Open Library
различать work, edition и author; литературное произведение не определяет narration.

## Экспорт и Android

[Официальная Android-документация LiteRT](https://developers.google.com/edge/litert/android)
описывает `2.3.0` (7 октября 2026), CPU Interpreter и minSdk 23.
[PyTorch conversion](https://developers.google.com/edge/litert/conversion/pytorch/overview)
использует `litert_torch.convert` и требует export-compatible граф.
[Quantization](https://developers.google.com/edge/litert/quantization/model_optimization)
описывает `litert-quantizer`: static INT8 требует representative calibration;
dynamic weights INT8 с FP32 activations не является полностью целочисленной моделью.

Вывод проекта: сначала проверить минимальный CharCNN с Embedding/Gather/Conv/
pooling и одинаковым preprocessing на Python/Android. Нельзя заранее обещать
совместимость операторов, размер или latency по одному названию архитектуры.
Новые Gradle/Python зависимости в этом этапе не установлены и не зафиксированы:
согласованный export environment будет создан отдельным ticket после spike.

## Допуск к дальнейшему использованию

Для каждого source/field отдельно хранить downloaded, training_eligible и
redistribution_eligible. Сейчас downloaded подтверждается только integrity report;
dataset eligibility ещё не утверждена. Даже CC0 sample проверяется на соответствие
сущности/уровня, прежде чем станет частью gold labels. Не смешивать отдельные
условия моделей с лицензией кода и лицензией каталожных данных.
