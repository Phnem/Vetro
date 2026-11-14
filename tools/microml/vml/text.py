"""text-spec-v1: the single text contract shared by every MicroML model.

Kotlin mirrors this file function by function (MicroText.kt). Any change here is a
new spec version: models record the version they were trained with and refuse to
run against another one.
"""
from __future__ import annotations

import re
import unicodedata

SPEC = "text-spec-v1"

# ---------------------------------------------------------------- normalisation


def normalize(text: str) -> str:
    """NFKC, lowercase, letters and decimal digits only, single spaces between words."""
    value = unicodedata.normalize("NFKC", text).lower()
    out: list[str] = []
    space = False
    for ch in value:
        cat = unicodedata.category(ch)
        if cat[0] == "L" or cat == "Nd":
            if space and out:
                out.append(" ")
            out.append("е" if ch == "ё" else ch)
            space = False
        else:
            space = True
    return "".join(out)


def key(text: str) -> str:
    return normalize(text).replace(" ", "")


SCRIPTS = ("LATIN", "CYRILLIC", "CJK", "HANGUL", "OTHER")


def script_of_char(ch: str) -> int:
    o = ord(ch)
    if o < 0x250 or 0x1E00 <= o < 0x1F00:
        return 0
    if 0x400 <= o < 0x530:
        return 1
    if 0x3040 <= o < 0x3100 or 0x3400 <= o < 0x4DC0 or 0x4E00 <= o < 0xA000 or 0xF900 <= o < 0xFB00 or 0x31F0 <= o < 0x3200:
        return 2
    if 0xAC00 <= o < 0xD7B0 or 0x1100 <= o < 0x1200 or 0x3130 <= o < 0x3190:
        return 3
    return 4


def script(norm: str) -> int:
    """Dominant script of a normalised string; -1 when it has no letters."""
    counts = [0, 0, 0, 0, 0]
    for ch in norm:
        if ch != " " and not ("0" <= ch <= "9"):
            counts[script_of_char(ch)] += 1
    best = max(range(5), key=lambda i: (counts[i], -i))
    return best if counts[best] else -1


# ---------------------------------------------------------------- folding to Latin

CYR = {"а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ж": "zh", "з": "z", "и": "i",
       "й": "i", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s",
       "т": "t", "у": "u", "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch",
       "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
       "і": "i", "ї": "yi", "є": "ye", "ґ": "g", "ў": "u", "ј": "j", "љ": "lj", "њ": "nj",
       "ћ": "c", "ђ": "dj", "џ": "dz", "ѕ": "dz", "қ": "k", "ғ": "g", "ү": "u", "ұ": "u",
       "ә": "a", "ө": "o", "һ": "h", "ң": "n"}

_KANA_ROWS = {
    "あ": "a", "い": "i", "う": "u", "え": "e", "お": "o",
    "か": "ka", "き": "ki", "く": "ku", "け": "ke", "こ": "ko",
    "さ": "sa", "し": "shi", "す": "su", "せ": "se", "そ": "so",
    "た": "ta", "ち": "chi", "つ": "tsu", "て": "te", "と": "to",
    "な": "na", "に": "ni", "ぬ": "nu", "ね": "ne", "の": "no",
    "は": "ha", "ひ": "hi", "ふ": "fu", "へ": "he", "ほ": "ho",
    "ま": "ma", "み": "mi", "む": "mu", "め": "me", "も": "mo",
    "や": "ya", "ゆ": "yu", "よ": "yo",
    "ら": "ra", "り": "ri", "る": "ru", "れ": "re", "ろ": "ro",
    "わ": "wa", "ゐ": "i", "ゑ": "e", "を": "o", "ん": "n",
    "が": "ga", "ぎ": "gi", "ぐ": "gu", "げ": "ge", "ご": "go",
    "ざ": "za", "じ": "ji", "ず": "zu", "ぜ": "ze", "ぞ": "zo",
    "だ": "da", "ぢ": "ji", "づ": "zu", "で": "de", "ど": "do",
    "ば": "ba", "び": "bi", "ぶ": "bu", "べ": "be", "ぼ": "bo",
    "ぱ": "pa", "ぴ": "pi", "ぷ": "pu", "ぺ": "pe", "ぽ": "po",
    "ゔ": "vu",
    "ぁ": "a", "ぃ": "i", "ぅ": "u", "ぇ": "e", "ぉ": "o",
}
_SMALL_Y = {"ゃ": "a", "ゅ": "u", "ょ": "o"}
_SMALL_VOWEL = {"ぁ": "a", "ぃ": "i", "ぅ": "u", "ぇ": "e", "ぉ": "o"}
_HANGUL_L = ["g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"]
_HANGUL_V = ["a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we",
             "wi", "yu", "eu", "ui", "i"]
_HANGUL_T = ["", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p",
             "t", "t", "ng", "t", "t", "k", "t", "p", "t"]
_LATIN_EXTRA = {"ß": "ss", "æ": "ae", "œ": "oe", "ø": "o", "đ": "d", "ð": "d", "þ": "th",
                "ł": "l", "ı": "i", "ħ": "h"}


def _hira(ch: str) -> str:
    o = ord(ch)
    return chr(o - 0x60) if 0x30A1 <= o <= 0x30F6 else ch


def latin(norm: str) -> str:
    """Fold a normalised string to ASCII-ish Latin. Han characters pass through unchanged."""
    out: list[str] = []
    geminate = False
    chars = [_hira(c) for c in norm]
    i = 0
    while i < len(chars):
        ch = chars[i]
        o = ord(ch)
        piece: str | None = None
        if ch in CYR:
            piece = CYR[ch]
        elif ch == "っ":
            geminate = True
            i += 1
            continue
        elif ch == "ー":
            i += 1
            continue
        elif ch in _KANA_ROWS:
            piece = _KANA_ROWS[ch]
            nxt = chars[i + 1] if i + 1 < len(chars) else ""
            if nxt in _SMALL_Y and piece.endswith("i") and len(piece) >= 2:
                stem = piece[:-1]
                piece = (stem if stem in ("sh", "ch", "j") else stem + "y") + _SMALL_Y[nxt]
                i += 1
            elif nxt in _SMALL_VOWEL and piece[-1] in "ue" and len(piece) >= 2:
                piece = piece[:-1] + _SMALL_VOWEL[nxt]
                i += 1
        elif ch in _SMALL_Y:
            piece = "y" + _SMALL_Y[ch]
        elif 0xAC00 <= o <= 0xD7A3:
            s = o - 0xAC00
            piece = _HANGUL_L[s // 588] + _HANGUL_V[(s % 588) // 28] + _HANGUL_T[s % 28]
        elif ch in _LATIN_EXTRA:
            piece = _LATIN_EXTRA[ch]
        elif o >= 0x80 and script_of_char(ch) == 0:
            base = unicodedata.normalize("NFD", ch)
            piece = "".join(c for c in base if "a" <= c <= "z" or "0" <= c <= "9")
        if piece is None:
            piece = ch
        if geminate and piece and piece[0] not in "aeiou":
            piece = piece[0] + piece
        geminate = False
        out.append(piece)
        i += 1
    return "".join(out)


_SKELETON = [("shch", "s"), ("sch", "s"), ("dzh", "z"), ("tch", "t"), ("sh", "s"), ("zh", "z"), ("kh", "h"),
             ("ch", "t"), ("ts", "t"), ("tz", "t"), ("dz", "z"), ("dj", "z"), ("ph", "f"), ("th", "t"),
             ("ck", "k"), ("qu", "kv"), ("gh", "g"), ("x", "ks"), ("q", "k"), ("w", "v"), ("j", "z"),
             ("ou", "o"), ("oo", "u"), ("ee", "i"), ("ya", "ia"), ("yu", "iu"), ("yo", "io"), ("ye", "ie"),
             ("y", "i")]


def skeleton(norm: str) -> str:
    """Coarse cross-script phonetic key: Hepburn, Polivanov and plain Latin collapse together."""
    s = latin(norm)
    s = re.sub(r"c(?=[eiy])", "s", s)
    for src, dst in _SKELETON:
        s = s.replace(src, dst)
    s = s.replace("c", "k")
    out: list[str] = []
    for ch in s:
        if not out or out[-1] != ch or ch == " " or "0" <= ch <= "9":
            out.append(ch)
    return "".join(out)


# ---------------------------------------------------------------- hashed n-grams


def fnv1a(data: bytes, seed: int = 0x811C9DC5) -> int:
    h = seed
    for b in data:
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    h ^= h >> 15
    h = (h * 0x2C1B3C6D) & 0xFFFFFFFF
    h ^= h >> 12
    return h


def grams(norm: str) -> list[str]:
    """Feature strings of one normalised title. 'o' = original script, 's' = skeleton."""
    feats: set[str] = set()
    for space, form in (("o", norm), ("s", skeleton(norm))):
        for word in form.split(" "):
            if not word:
                continue
            feats.add(space + "<" + word + ">")
            padded = "<" + word + ">"
            for n in (3, 4):
                for i in range(len(padded) - n + 1):
                    feats.add(space + padded[i:i + n])
            if space == "o":
                for i, ch in enumerate(word):
                    if script_of_char(ch) >= 2:
                        feats.add("u" + ch)
                        if i + 1 < len(word):
                            feats.add("b" + word[i:i + 2])
    return sorted(feats)


def bucket_ids(norm: str, buckets: int) -> list[int]:
    """Sorted unique bucket indices; `buckets` must be a power of two."""
    return sorted({fnv1a(g.encode("utf-8")) & (buckets - 1) for g in grams(norm)})


# ---------------------------------------------------------------- sequence markers

_ROMAN = {"ii": 2, "iii": 3, "iv": 4, "v": 5, "vi": 6, "vii": 7, "viii": 8, "ix": 9, "x": 10,
          "xi": 11, "xii": 12, "xiii": 13}
_ORDINAL = {"first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7,
            "eighth": 8, "ninth": 9, "tenth": 10, "1st": 1, "2nd": 2, "3rd": 3, "4th": 4, "5th": 5,
            "6th": 6, "7th": 7, "8th": 8, "9th": 9, "10th": 10,
            "первый": 1, "второй": 2, "третий": 3, "четвертый": 4, "пятый": 5, "шестой": 6,
            "первая": 1, "вторая": 2, "третья": 3, "четвертая": 4, "пятая": 5, "шестая": 6,
            "二": 2, "三": 3, "四": 4, "五": 5, "弐": 2}
_VARIANT = {"movie": "movie", "film": "movie", "фильм": "movie", "gekijouban": "movie", "gekijoban": "movie",
            "劇場版": "movie", "ova": "ova", "oav": "ova", "oad": "ova", "ona": "ona",
            "special": "special", "specials": "special", "спешл": "special", "спецвыпуск": "special",
            "recap": "recap", "рекап": "recap", "summary": "recap", "総集編": "recap",
            "remake": "remake", "ремейк": "remake", "reboot": "remake", "shippuden": "shippuden",
            "brotherhood": "brotherhood", "kai": "kai", "final": "final", "финал": "final",
            "zero": "zero", "prequel": "prequel", "sequel": "sequel", "gaiden": "gaiden",
            "spinoff": "gaiden", "drama": "drama", "manga": "manga", "manhwa": "manga",
            "novel": "novel", "ранобэ": "novel", "манга": "manga", "audiobook": "audio",
            "аудиокнига": "audio", "uncut": "cut", "extended": "cut", "us": "region_us", "uk": "region_uk"}
_SEQ_WORD = {"season", "сезон", "part", "часть", "cour", "vol", "volume", "том", "book", "книга",
             "chapter", "глава", "episode", "серия", "эпизод", "act", "phase", "series", "期", "第", "部",
             "章", "巻", "s", "ep", "pt", "кн", "ч", "т"}
# [0-9], never \d: Android's ICU and the JVM disagree on what \d matches.
_GLUED = re.compile(r"(?:s|season|сезон|part|pt|vol|v|том|ч|кн|ep|e|第)([0-9]{1,3})(?:期|部|章|巻|話)?")
_SUFFIXED = re.compile(r"([0-9]{1,3})(?:期|部|章|巻|話|nd|rd|th|st|й|я|е|ой|ая)")


def markers(text: str) -> tuple[frozenset[int], frozenset[str], frozenset[int]]:
    """(sequence numbers, variant tags, 4-digit years) found in a title."""
    norm = normalize(text)
    words = norm.split(" ") if norm else []
    seq: set[int] = set()
    variants: set[str] = set()
    years: set[int] = set()
    for i, w in enumerate(words):
        prev = words[i - 1] if i else ""
        nxt = words[i + 1] if i + 1 < len(words) else ""
        if w in _VARIANT:
            variants.add(_VARIANT[w])
        if w and all("0" <= c <= "9" for c in w):
            n = int(w[:9])
            if len(w) == 4 and 1900 <= n <= 2099:
                years.add(n)
            elif n < 1000:
                seq.add(n)
        elif w in _ROMAN and i > 0:
            seq.add(_ROMAN[w])
        elif w in _ORDINAL and (prev in _SEQ_WORD or nxt in _SEQ_WORD):
            seq.add(_ORDINAL[w])
        else:
            m = _GLUED.fullmatch(w) or _SUFFIXED.fullmatch(w)
            if m:
                seq.add(int(m.group(1)))
    return frozenset(seq), frozenset(variants), frozenset(years)
