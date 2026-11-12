# BookIndex V2

Каталог книг для аудиокниг: телефонный индекс (поиск без сети) + API на Cloudflare (детали, поиск по всему
каталогу, обложки). Работает на **копии** мастер-БД: оригинал `vetro_books.sqlite` не открывается на запись.

```
Master DB 3.9 ГБ ──copy──> work/vetro_books_v2.sqlite
        │
        ├─ build_index.py ───────> out/index/base/bookindex-vN.sqlite(.zst)   на телефон, 31.7 МиБ → 10 МиБ zstd
        │                          out/index/delta/delta-A-B.zst             дельты между версиями
        │                          out/manifest.json                          что скачать приложению
        └─ build_server_data.py ─> out/server/*.sql                           D1: 268 840 произведений + FTS5
                                   out/server/covers-{small,large}.json       списки для R2 (88 110 × 2 AVIF)
```

## Что изменилось против V1 (`vetro_index.sqlite`, 50 МБ)

| | V1 | V2 |
|---|---|---|
| Файл на телефоне | 50.1 МБ, 99 833 книги | **31.7 МиБ**, 110 259 книг; скачивание **10 МиБ** (zstd) |
| Обновление | заново целиком | дельты (килобайты); база целиком, только если дельт нет или они > 50 % базы |
| Движок | FTS5 (на Android без гарантии) | **FTS4**: есть на любой версии Android; одна копия текста, без таблицы `alt_titles` |
| Префиксный индекс | нет | не нужен: `слово*` по словарю FTS4 — миллисекунды; экономия 16 МиБ |
| Опечатки | «fuzzy» только в манифесте | словарь индекса (`books_terms`, fts4aux) + расстояние Дамерау — Левенштейна в приложении |
| Латиница ↔ кириллица | нет | в индекс добавлена транслитерация (`chayka` → «Чайка») и сложенные формы (ё→е) |
| Детали книги | всё в 673 МБ мобильной БД | по запросу с сервера (`/v1/books/{id}`): серии, переводы, издания, внешние id |
| Обложки | отдельно | `/covers/{small,large}/{id9}.avif?v={cv}`, неизменяемый кэш, версия в индексе |

## Формат индекса (`build/bookindex.py`)

`books` — FTS4 (`docid` = числовой vetro_id, `000000771` → 771): `ru, en, ra, ea, alt, x, y, f, cv`.
`x` — сложенные и транслитерированные токены, `y` — год, `f` — флаги (1 обложка, 2 ограниченная лицензия,
4 ядро), `cv` — ревизия обложки. `books_terms` — словарь для исправления опечаток. `meta` — версия.
Нормализация (`build/common.py` ⇄ `norm.js` ⇄ `BookNorm.kt`) одна на три языка, общие примеры —
`tests/golden_norm.json`.

Дельта — zstd-текст: заголовок `{"from":a,"to":b,"rows":n}`, дальше JSON-массивы: `[id,…9 полей]` — заменить,
`[id]` — удалить. Приложение применяет цепочку дельт одной транзакцией на дельту и сверяет SHA-256.

## Сборка

```powershell
cd book-dictionary\v2
python -m venv .venv; .\.venv\Scripts\python -m pip install zstandard      # один раз
.\.venv\Scripts\python tools\copy_master.py ..\vetro_books.sqlite work\vetro_books_v2.sqlite   # копия (WAL-safe)
.\.venv\Scripts\python build\build_index.py --master work\vetro_books_v2.sqlite --aihand ..\aihand.txt --out out
.\.venv\Scripts\python build\build_server_data.py --master work\vetro_books_v2.sqlite --aihand ..\aihand.txt --covers ..\cover_images --out out
.\.venv\Scripts\python -m unittest discover -s tests
```

Повторный запуск `build_index.py` в ту же `out/` делает **следующую версию** и дельту от предыдущей
(`history/` хранит прошлые базы). После правок мастер-БД: свежая копия → `build_index` → `deploy-data.ps1 -IndexOnly`.

## Выкладка

См. `tools/books-api/README.md`: `npx wrangler login`, затем `scripts\deploy-data.ps1`.

## Что внутри каталога

- Телефон: все книги с обложкой, «ядро» (проверенные пары RU↔EN) и полки `aihand.txt` — 110 259.
- Сервер: то же + весь русский каталог (`RUSSIAN_CATALOG`) — 268 840. Мировой каталог без русских названий
  (1,5 млн) не выкладывается.
- Обложки: все 88 110 (FantLab — 70 537 — раздаются по решению владельца; флаг `f & 2` в индексе и `cover.lic` в
  деталях позволяют отключить любой источник отдельно). Формат AVIF: на Android 12+ читается системой, на 8–11
  приложение обложку каталога пропускает и ищет её старым путём.
