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

## Обновление каталога (когда демоны накопили новое)

Демоны (`cover_images/run_collector.py`, `run_v3/online_stores/*_daemon.py`, `run_details_daemon.py`) пишут в живые базы,
поэтому всё берётся **снимками** (`tools/copy_master.py` работает на онлайн-копии SQLite и подходит для любой базы):

```powershell
cd book-dictionary\v2
$py = ".venv\Scripts\python.exe"
$d = "20261113"                                                           # метка снимка
& $py -I tools\copy_master.py ..\vetro_books.sqlite work\vetro_books_$d.sqlite
& $py -I tools\copy_master.py ..\vetro_work_details.sqlite work\vetro_work_details_$d.sqlite
mkdir work\blocks; copy ..\aihand.txt, ..\harvested_blocks.json, ..\run_v3\online_stores\harvested_blocks_expansion.json work\blocks
$blocks = "work\blocks\aihand.txt", "work\blocks\harvested_blocks.json", "work\blocks\harvested_blocks_expansion.json"

& $py build\build_index.py --master work\vetro_books_$d.sqlite --aihand $blocks --out out                 # версия N+1 + дельта
& $py build\build_server_data.py --master work\vetro_books_$d.sqlite --aihand $blocks `
      --details work\vetro_work_details_$d.sqlite --covers ..\cover_images --suffix _next --out out         # D1 в works_next/fts_next
& $py tools\new_covers.py --old work\<снимок прошлого релиза>.sqlite --lists out\server\covers-small.json out\server\covers-large.json --out out\server\covers-new
& $py -I -m unittest discover -s tests
```

(скрипты из `build/` запускаются без `-I`: им нужны соседние модули; самодостаточные из `tools/` и тесты — с `-I`.)
Дальше — `tools\books-api\scripts\refresh-data.ps1 -Covers`, `-Load`, `-Publish` (`tools/books-api/README.md`): живой API
отвечает на всём протяжении, таблицы меняются местами в конце, манифест публикуется последним.

`--aihand` принимает несколько файлов одного формата (блоки автор + списки RU/EN названий): к `aihand.txt` добавляются блоки,
которые добывают демоны. Блок сопоставляется с произведением, которое **уже есть** в мастер-БД; пара, у которой нет произведения,
в каталог не попадает (создавать произведения сборка не умеет).

### Подробности (`--details`)

`vetro_work_details.sqlite` (демон `run_details_daemon.py`): ~1,78 млн строк, но у большинства заполнены лишь язык оригинала и
год. В карточку (`works.d`) `build/details.py` берёт только проверенное, короткими ключами, отсутствующие опускаются:

| Ключ | Что | Откуда и как чистится |
|---|---|---|
| `desc{t,l}` | описание и язык | от 50 символов; без каталожных огрызков («216 p. ; 18 cm», «> Blockquote») и разметки `**` |
| `gen[…]` | **канонические ключи жанров** (до 4) | 12 201 сырых тегов → 35 ключей (`scifi`, `fantasy`…); таксономия FantLab и библиотечные рубрики отбрасываются. Названия по языкам — в приложении (`BookGenres.kt`), сверка по `tests/golden_genres.json` |
| `ol` | язык оригинала | как есть, в нижнем регистре |
| `coll` | сборник | `is_collection = 1` |
| `ch`, `dur` | число глав, типичная длина озвучки (мин) | `ch ≥ 1`, `dur ≥ 5` |
| `rate{v,n}` | оценка FantLab (10-балльная) и число голосов | только от 10 голосов |
| `series[{ru,en,pos}]` | серия | запасная: если у произведения нет серии в мастер-БД; серия на CJK у произведения без CJK-текста считается неверной привязкой и отбрасывается |

**Качество и права.** Описания частью из Википедии (лицензия CC BY-SA, требует указания источника), частью из FantLab и
Open Library; в базе подробностей источник описания не записан, поэтому в карточке его нет. Серии из мастер-БД местами
спорные (например, «The Idiot» у «The Genial Idiot»): сборка их не переписывает.

## Выкладка

См. `tools/books-api/README.md`: `npx wrangler login`, затем `scripts\deploy-data.ps1`.

## Что внутри каталога

- Телефон: все книги с обложкой, «ядро» (проверенные пары RU↔EN) и полки `aihand.txt` — 110 259.
- Сервер: то же + весь русский каталог (`RUSSIAN_CATALOG`) — 268 840. Мировой каталог без русских названий
  (1,5 млн) не выкладывается.
- Обложки: все 88 110 (FantLab — 70 537 — раздаются по решению владельца; флаг `f & 2` в индексе и `cover.lic` в
  деталях позволяют отключить любой источник отдельно). Формат AVIF: на Android 12+ читается системой, на 8–11
  приложение обложку каталога пропускает и ищет её старым путём.
