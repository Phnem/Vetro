"""Shared helpers for the BookIndex V2 build: text normalisation, transliteration, hashing.

The normalisation here is mirrored 1:1 by the Android client
(app/.../audiobooks/catalog/BookNorm.kt). Keep them in sync: golden cases live in
v2/tests/golden_norm.json and are checked on both sides.
"""
import hashlib
import re
import unicodedata

_EXTRA = {"ß": "ss", "æ": "ae", "œ": "oe", "ø": "o", "ł": "l", "đ": "d", "ı": "i", "þ": "th", "ð": "d"}
_NON_WORD = re.compile(r"[^0-9a-zа-я]+")
_CYR = re.compile(r"[а-яёА-ЯЁ]")
_RAW_SPLIT = re.compile(r"\W+")

_TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "zh", "з": "z", "и": "i",
    "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s",
    "т": "t", "у": "u", "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}


def norm(text: str | None) -> str:
    """lowercase, strip diacritics (ё->е, é->e; й stays), non-alphanumerics -> single spaces."""
    if not text:
        return ""
    # й is a letter of its own in Russian (война != воина): hide it from NFKD, which would split off the breve.
    s = unicodedata.normalize("NFKD", text.lower().replace("й", ""))
    s = "".join(_EXTRA.get(ch, ch) for ch in s if not unicodedata.category(ch).startswith("M"))
    return _NON_WORD.sub(" ", s.replace("", "й")).strip()


def has_cyrillic(text: str | None) -> bool:
    return bool(text and _CYR.search(text))


def translit(norm_text: str) -> str:
    """Latin form of an already normalised Cyrillic string (lets Latin queries find Russian titles)."""
    return "".join(_TRANSLIT.get(ch, ch) for ch in norm_text)


def extra_tokens(*fields: str | None) -> str:
    """Tokens that plain FTS tokenisation of the raw text would not produce.

    Folded forms (ё->е, é->e ...) and Latin transliterations of Cyrillic words. Only tokens that
    differ from the lowercased raw token are emitted, so the column stays small.
    """
    out: list[str] = []
    seen: set[str] = set()

    def add(tok: str) -> None:
        if tok and tok not in seen:
            seen.add(tok)
            out.append(tok)

    for field in fields:
        if not field:
            continue
        raw = {t for t in _RAW_SPLIT.split(field.lower()) if t}
        for tok in norm(field).split():
            if tok not in raw:
                add(tok)
            if "а" <= tok[0] <= "я":
                add(translit(tok))
    return " ".join(out)


def strip_parens(s: str) -> str:
    s = re.sub(r"\(.*?\)", "", s).strip()
    return re.sub(r"\[.*?\]", "", s).strip()


def sha256_file(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def int_to_vid(n: int) -> str:
    return f"{n:09d}"
