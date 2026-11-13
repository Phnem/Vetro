"""Compact extras for the server detail card from `vetro_work_details.sqlite` (the details daemon's store).

The daemon fills ~1.78M rows, but most of them only carry an original language and a year. What is worth shipping:
description (15k), genres (14k, after cleaning), series (fallback for works without a master-DB series), original
language, collection flag, chapter count, typical audiobook length and the FantLab rating. Everything is validated
here; the card stays compact (short keys, absent when unknown):

    desc{t,l}  gen[key..]  ol  coll  ch  dur  rate{v,n}  series[{ru,en,pos}]

Genres are canonical KEYS, not strings: the raw tags are a mix of FantLab's classifier ("Любой", "Линейный",
"20 век"), library subject headings ("Large type books") and real genres. The app localises the keys
(GENRE_NAMES here, BookGenres.kt there; tests/golden_genres.json keeps both sides in sync).
"""
import json
import re
import sqlite3

MAX_GENRES = 4
MIN_DESC = 50
MIN_RATING_VOTES = 10

# key -> (ru, en). The app has the same table (audiobooks/catalog/BookGenres.kt).
GENRE_NAMES = {
    "scifi": ("Фантастика", "Science fiction"),
    "fantasy": ("Фэнтези", "Fantasy"),
    "detective": ("Детектив", "Detective"),
    "thriller": ("Триллер", "Thriller"),
    "horror": ("Ужасы", "Horror"),
    "historical": ("Историческая проза", "Historical fiction"),
    "romance": ("Любовный роман", "Romance"),
    "adventure": ("Приключения", "Adventure"),
    "action": ("Боевик", "Action"),
    "psychological": ("Психологическая проза", "Psychological fiction"),
    "philosophical": ("Философская проза", "Philosophical fiction"),
    "social": ("Социальная проза", "Social fiction"),
    "realism": ("Реализм", "Realism"),
    "magic_realism": ("Магический реализм", "Magical realism"),
    "war": ("Военная проза", "War fiction"),
    "humor": ("Юмор", "Humor"),
    "satire": ("Сатира", "Satire"),
    "fairy_tale": ("Сказка", "Fairy tale"),
    "children": ("Детская литература", "Children's"),
    "young_adult": ("Подростковая литература", "Young adult"),
    "dystopia": ("Антиутопия", "Dystopia"),
    "post_apocalypse": ("Постапокалипсис", "Post-apocalyptic"),
    "alt_history": ("Альтернативная история", "Alternate history"),
    "mystic": ("Мистика", "Mystic"),
    "spy": ("Шпионский роман", "Spy fiction"),
    "western": ("Вестерн", "Western"),
    "litrpg": ("ЛитРПГ", "LitRPG"),
    "classic": ("Классика", "Classics"),
    "nonfiction": ("Нон-фикшн", "Non-fiction"),
    "biography": ("Биография и мемуары", "Biography & memoir"),
    "poetry": ("Поэзия", "Poetry"),
    "drama": ("Драматургия", "Drama"),
    "essay": ("Эссе", "Essay"),
    "short_stories": ("Рассказы", "Short stories"),
    "comics": ("Комиксы", "Comics"),
}

_EXACT = {
    "scifi": ["фантастика", "научная фантастика", "science fiction", "american science fiction", "english science fiction",
              "научно-фантастические аниме и манга"],
    "fantasy": ["фэнтези", "высокое фэнтези", "героическое фэнтези", "городское фэнтези", "научное фэнтези", "fantasy",
                "fantasy fiction", "american fantasy fiction", "english fantasy fiction", "фэнтезийная манга или аниме"],
    "detective": ["детектив", "detective and mystery stories", "english detective and mystery stories", "mystery",
                  "mystery fiction", "криминальный жанр", "криминальная литература"],
    "thriller": ["триллер", "thriller", "suspense", "suspense fiction", "технотриллер", "юридический триллер"],
    "horror": ["хоррор/ужасы", "ужасы", "horror stories", "horror tales", "horror tales, american", "horror"],
    "historical": ["историческая проза", "художественное произведение об истории", "историческая художественная литература",
                   "historical fiction"],
    "romance": ["любовный роман", "романтическое художественное произведение", "романтическое литературное произведение",
                "romance", "romance literature", "love stories", "love stories, american"],
    "adventure": ["приключенческое", "приключения", "adventure stories", "приключенческая манга или аниме"],
    "action": ["боевик", "аниме или манга в жанре боевика"],
    "psychological": ["психологическое", "психологическое художественное произведение", "psychological fiction"],
    "philosophical": ["философское", "философское художественное произведение"],
    "social": ["социальное"],
    "realism": ["реализм"],
    "magic_realism": ["магический реализм"],
    "war": ["военное", "военный роман"],
    "humor": ["юмористическое", "комедия", "humorous stories", "комический роман", "комедийная манга и аниме"],
    "satire": ["сатирическое", "сатира", "сатирический вымысел", "пародийное"],
    "fairy_tale": ["сказка/притча", "сказка", "fairy tales"],
    "children": ["детская литература", "детская развлекательная литература", "juvenile fiction", "children's fiction",
                 "juvenile literature", "children's stories"],
    "young_adult": ["подростковая литература", "young adult fiction", "юношеское произведение искусства"],
    "dystopia": ["антиутопическое произведение", "антиутопия"],
    "post_apocalypse": ["постапокалиптика"],
    "alt_history": ["альтернативная история нашего мира (земли)", "альтернативная история"],
    "mystic": ["мистика", "paranormal fiction", "supernatural"],
    "spy": ["спецслужбы", "художественное произведение о шпионах", "intelligence service"],
    "western": ["вестерн"],
    "litrpg": ["литрпг"],
    "classic": ["classic literature"],
    "nonfiction": ["нон-фикшен", "nonfiction", "документальная литература", "научно-популярное, познавательное"],
    "biography": ["автобиография", "биография", "мемуары", "biography", "biography & autobiography"],
    "poetry": ["поэзия", "poetry", "poetry (poetic works by one author)"],
    "drama": ["drama", "english drama", "драма"],
    "essay": ["эссе"],
    "short_stories": ["short stories"],
    "comics": ["comics & graphic novels", "graphic novels", "comic books, strips"],
}
_TAG_TO_KEY = {tag: key for key, tags in _EXACT.items() for tag in tags}

# Open Library subject headings of the "Fiction, <genre>, <subgenre>" shape.
_PREFIX_RULES = [
    (re.compile(r"^fiction, thrillers, espionage"), "spy"),
    (re.compile(r"^fiction, science fiction"), "scifi"),
    (re.compile(r"^fiction, fantasy"), "fantasy"),
    (re.compile(r"^fiction, mystery & detective"), "detective"),
    (re.compile(r"^fiction, (thrillers|suspense)"), "thriller"),
    (re.compile(r"^fiction, historical"), "historical"),
    (re.compile(r"^fiction, romance"), "romance"),
    (re.compile(r"^fiction, humorous"), "humor"),
    (re.compile(r"^fiction, horror"), "horror"),
    (re.compile(r"^fiction, war & military"), "war"),
    (re.compile(r"^fiction, westerns"), "western"),
    (re.compile(r"^fiction, action & adventure"), "adventure"),
    (re.compile(r"^fiction, psychological"), "psychological"),
    (re.compile(r"^fiction, short stories"), "short_stories"),
]


def _tag(t):
    return t.strip().lower().replace("ё", "е")


def genre_keys(tags):
    """Raw tags -> canonical genre keys, order of first appearance, at most MAX_GENRES."""
    out = []
    for raw in tags:
        t = _tag(raw)
        key = _TAG_TO_KEY.get(t)
        if key is None:
            for rx, k in _PREFIX_RULES:
                if rx.match(t):
                    key = k
                    break
        if key and key not in out:
            out.append(key)
            if len(out) == MAX_GENRES:
                break
    return out


_JUNK_DESC = re.compile(r"^(\d+\s*p\.|>|<)|^\s*$")


def clean_description(text):
    """Description text or None when it is a stub (catalogue physical description, blockquote marker, ...)."""
    if not text:
        return None
    t = re.sub(r"\*\*|__", "", text)
    t = re.sub(r"\s*\n\s*", "\n", t.strip())
    t = re.sub(r"[ \t]{2,}", " ", t)
    if len(t) < MIN_DESC or _JUNK_DESC.match(t):
        return None
    return t


_RATING = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*\((\d+)\s*votes?\)")


def parse_rating(s):
    """'8.30 (1065 votes)' -> {'v': 8.3, 'n': 1065}; None when there are too few votes to mean anything."""
    m = _RATING.match(s or "")
    if not m:
        return None
    v, n = float(m.group(1)), int(m.group(2))
    return {"v": round(v, 2), "n": n} if n >= MIN_RATING_VOTES and 0 < v <= 10 else None


_CJK = re.compile(r"[぀-ヿ㐀-䶿一-鿿가-힯]")


def series_plausible(series_title, *work_texts):
    """A CJK series name on a work that has no CJK text anywhere is a mislink (a manga series on a Russian novel)."""
    return not (_CJK.search(series_title or "") and not any(_CJK.search(t or "") for t in work_texts))


def _pos(index_num, index_text):
    if index_num is not None:
        return int(index_num) if float(index_num).is_integer() else index_num
    return index_text or None


def extras(row):
    """row: dict with the work_details columns used below -> compact dict (possibly empty)."""
    out = {}
    desc = clean_description(row.get("description"))
    if desc:
        out["desc"] = {"t": desc}
        if row.get("description_lang"):
            out["desc"]["l"] = row["description_lang"].lower()
    try:
        genres = genre_keys(json.loads(row.get("genres_json") or "[]"))
    except ValueError:
        genres = []
    if genres:
        out["gen"] = genres
    if row.get("original_language"):
        out["ol"] = row["original_language"].lower()
    if row.get("is_collection") == 1:
        out["coll"] = 1
    if (row.get("chapter_count") or 0) >= 1:
        out["ch"] = row["chapter_count"]
    if (row.get("audiobook_duration_minutes") or 0) >= 5:
        out["dur"] = row["audiobook_duration_minutes"]
    try:
        notes = json.loads(row.get("additional_notes_json") or "{}")
    except ValueError:
        notes = {}
    rating = parse_rating(notes.get("fantlab_rating") if isinstance(notes, dict) else None)
    if rating:
        out["rate"] = rating
    st = row.get("series_title")
    if st and series_plausible(st, row.get("ru_title"), row.get("en_title"), row.get("ru_author"), row.get("en_author")):
        ru, en = row.get("series_title_ru"), row.get("series_title_en")
        if not ru and not en:
            ru, en = (st, None) if re.search("[А-Яа-яЁё]", st) else (None, st)
        item = {k: v for k, v in (("ru", ru), ("en", en), ("pos", _pos(row.get("series_index_num"), row.get("series_index")))) if v}
        out["series"] = [item]
    return out


_COLUMNS = ("vetro_id, series_title, series_title_ru, series_title_en, series_index, series_index_num, genres_json, "
            "description, description_lang, original_language, is_collection, chapter_count, audiobook_duration_minutes, "
            "additional_notes_json, ru_title, en_title, ru_author, en_author")


def load_extras(details_path, vids):
    """{int vetro_id: extras} for the given 9-digit vetro ids (a full scan of the details store, read-only)."""
    conn = sqlite3.connect(f"file:{details_path}?mode=ro", uri=True, timeout=60)
    names = [c.strip() for c in _COLUMNS.split(",")]
    out = {}
    for r in conn.execute(f"SELECT {_COLUMNS} FROM work_details"):
        if r[0] not in vids:
            continue
        e = extras(dict(zip(names, r)))
        if e:
            out[int(r[0])] = e
    conn.close()
    return out
