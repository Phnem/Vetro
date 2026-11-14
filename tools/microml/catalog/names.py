"""name-features-v1: tokeniser, token features and field assembly for the release-name parser.

Input is any messy name the app meets: a video release file, an audiobook folder, a manga
archive. Each token gets one label; fields are then read off the labelled tokens.
The Kotlin mirror is microml/NameParser.kt.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

VERSION = "name-features-v1"
LABELS = ["TITLE", "YEAR", "SEASON", "EPISODE", "VOLUME", "CHAPTER", "GROUP", "AUTHOR", "NARRATOR", "TECH",
          "CUE", "SE", "OTHER"]
L = {name: i for i, name in enumerate(LABELS)}
DOMAINS = {"unknown": 0, "video": 1, "audiobook": 2, "manga": 3}

_OPEN = {"[": 1, "【": 1, "(": 2, "（": 2, "{": 3, "「": 3, "『": 3, "《": 3, "«": 3}
_CLOSE = set("]】)）}」』》»")
_DASH = "-–—"

TECH = set("""mkv mp4 avi m4b mp3 flac cbz cbr zip rar 7z epub fb2 pdf ogg m4a wav opus webm ts
bdrip bluray blu ray bd bdremux remux webrip web dl webdl hdtv hdrip dvdrip dvd dvd5 dvd9 tvrip satrip camrip hdr hdr10 dv sdr
x264 x265 h264 h265 hevc avc av1 xvid divx 10bit 8bit hi10p
aac ac3 eac3 dts ddp dd5 ddp5 truehd atmos opus kbps kbit kb khz vbr cbr stereo mono
rus eng jpn jap ukr ru en jp ua sub subs dub dubbed multi dual audio raw uncensored proper repack complete batch
fhd uhd hd sd hq lq imax unrated nc17 itunes amzn nf dsnp hmax atvp crunchyroll cr
remastered extended uncut subtitle subtitles multiple
digital scan scans webtoon colored unabridged abridged""".split())
SEASON_W = {"season", "seasons", "сезон", "сезона", "sezon", "cour", "期"}
EPISODE_W = {"episode", "episodes", "ep", "eps", "серия", "серии", "серий", "эпизод", "эп", "話"}
VOLUME_W = {"vol", "volume", "том", "тома", "tom", "巻"}
CHAPTER_W = {"chapter", "ch", "глава", "главы", "гл", "chap", "章"}
PART_W = {"part", "часть", "pt", "книга", "кн", "book", "disc", "cd", "disk", "диск"}
NARRATOR_W = {"читает", "чтец", "исполнитель", "narrated", "narrator", "read", "reads", "озвучка", "озвучивает", "исп"}
AUTHOR_W = {"автор", "author", "by", "written"}
AUDIO_W = {"аудиокнига", "audiobook", "аудиоспектакль", "audiobooks", "аудио"}
OF_W = {"of", "из"}

_RE = [(13, re.compile(r"s[0-9]{1,2}e[0-9]{1,4}(?:v[0-9])?")), (14, re.compile(r"s[0-9]{1,2}")),
       (15, re.compile(r"(?:e|ep|эп)[0-9]{1,4}(?:v[0-9])?")), (16, re.compile(r"[0-9]{1,2}x[0-9]{1,3}")),
       (17, re.compile(r"v[0-9]{1,3}")), (18, re.compile(r"(?:c|ch|гл)[0-9]{1,4}")),
       (2, re.compile(r"(?:bd|dvd|web)?[0-9]{3,4}[pi]|[48]k")), (20, re.compile(r"[0-9]{1,4}v[0-9]"))]
_HEX8 = re.compile(r"[0-9a-f]{8}")
_SPACED_DASH = re.compile(r"\s[-–—]+\s")


@dataclass
class Token:
    text: str
    start: int
    end: int
    depth: int
    btype: int
    first_in_bracket: int
    bracket_no: int
    sep_before: int
    dashes_before: int
    bracket_id: int = 0
    bracket_len: int = 0
    bracket_pos: int = 0


def lex(lower: str) -> int:
    if lower in TECH:
        return 1
    if lower in SEASON_W:
        return 5
    if lower in EPISODE_W:
        return 6
    if lower in VOLUME_W:
        return 7
    if lower in CHAPTER_W:
        return 8
    if lower in PART_W:
        return 9
    if lower in NARRATOR_W:
        return 10
    if lower in AUTHOR_W:
        return 11
    if lower in AUDIO_W:
        return 12
    if lower in OF_W:
        return 19
    for cls, pattern in _RE:
        if pattern.fullmatch(lower):
            return cls
    if _HEX8.fullmatch(lower) and any(c.isdigit() for c in lower) and any("a" <= c <= "f" for c in lower):
        return 4
    return 0


def _sep_class(sep: str, at_start: bool) -> int:
    if not sep:
        return 0 if at_start else 10
    if any(c in _OPEN or c in _CLOSE for c in sep):
        return 8
    if "/" in sep or "|" in sep or "\\" in sep:
        return 7
    if _SPACED_DASH.search(sep):
        return 4
    if any(c in _DASH for c in sep):
        return 5
    if "," in sep or ";" in sep:
        return 6
    if ":" in sep:
        return 9
    if "." in sep:
        return 2
    if "_" in sep:
        return 3
    if sep.isspace():
        return 1
    return 10


def tokenize(raw: str) -> tuple[str, list[Token]]:
    text = unicodedata.normalize("NFKC", raw)
    tokens: list[Token] = []
    stack: list[int] = []
    ids: list[int] = []
    fresh = False       # the next token is the first inside the bracket just opened
    bracket_no = 0
    dashes = 0
    i, last_end = 0, 0
    n = len(text)
    while i < n:
        ch = text[i]
        cat = unicodedata.category(ch)
        if cat[0] == "L" or cat == "Nd":
            j = i
            while j < n and (unicodedata.category(text[j])[0] == "L" or unicodedata.category(text[j]) == "Nd"):
                j += 1
            sep = text[last_end:i]
            cls = _sep_class(sep, not tokens)
            if cls == 4:
                dashes += 1
            tokens.append(Token(text[i:j], i, j, min(len(stack), 2), stack[-1] if stack else 0, int(fresh),
                                min(bracket_no, 5), cls, min(dashes, 3), ids[-1] if ids else 0))
            fresh = False
            last_end = j
            i = j
            continue
        if ch in _OPEN:
            stack.append(_OPEN[ch])
            bracket_no += 1
            ids.append(bracket_no)
            fresh = True
        elif ch in _CLOSE and stack:
            stack.pop()
            ids.pop()
        i += 1
    seen: dict[int, int] = {}
    for t in tokens:
        if t.bracket_id:
            t.bracket_pos = seen.get(t.bracket_id, 0)
            seen[t.bracket_id] = t.bracket_pos + 1
    for t in tokens:
        if t.bracket_id:
            t.bracket_len = seen[t.bracket_id]
    return text, tokens


def _case(text: str) -> int:
    if not any(c.isalpha() for c in text) or text.lower() == text.upper():
        return 4
    if text.islower():
        return 0
    if text.isupper():
        return 2
    if text[0].isupper() and text[1:].islower():
        return 1
    return 3


def _script(text: str) -> int:
    for ch in text:
        o = ord(ch)
        if ch.isdigit():
            continue
        if o < 0x250:
            return 0
        if 0x400 <= o < 0x530:
            return 1
        if 0x3040 <= o < 0xA000:
            return 2
        return 3
    return 4


OWN = ["len", "is_digit", "is_alpha", "n_digits", "case", "script", "value", "year_like", "leading_zero", "index",
       "rel_pos", "n_tokens", "depth", "btype", "first_in_bracket", "sep_before", "sep_after", "lex", "remaining",
       "dashes_before", "bracket_no", "domain", "bracket_len", "bracket_pos", "years_before", "years_after",
       "digits_before", "digits_after", "person"]
CTX = ["is_digit", "is_alpha", "lex", "len", "sep_before", "depth", "year_like", "person"]
NAMES = OWN + [f"{name}@{off:+d}" for off in (-2, -1, 1, 2) for name in CTX]


def features(tokens: list[Token], domain: int, person=None) -> list[list[float]]:
    """`person(lowercased token) -> float`: the Catalog person-name lexicon score, 0 when unavailable."""
    n = len(tokens)
    base = []
    for i, t in enumerate(tokens):
        lower = t.text.lower()
        digits = sum(1 for c in t.text if "0" <= c <= "9")
        is_digit = int(digits == len(t.text))
        value = min(int(t.text[:9]), 3000) if is_digit else -1
        base.append({
            "len": min(len(t.text), 20), "is_digit": is_digit, "is_alpha": int(digits == 0), "n_digits": min(digits, 8),
            "case": _case(t.text), "script": _script(t.text), "value": value,
            "year_like": int(is_digit and len(t.text) == 4 and 1900 <= value <= 2099),
            "leading_zero": int(is_digit and len(t.text) > 1 and t.text[0] == "0"), "index": min(i, 6),
            "rel_pos": i / (n - 1) if n > 1 else 0.0, "n_tokens": min(n, 20), "depth": t.depth, "btype": t.btype,
            "first_in_bracket": t.first_in_bracket, "sep_before": t.sep_before,
            "sep_after": tokens[i + 1].sep_before if i + 1 < n else 0, "lex": lex(lower), "remaining": min(n - 1 - i, 6),
            "dashes_before": t.dashes_before, "bracket_no": t.bracket_no, "domain": domain,
            "bracket_len": min(t.bracket_len, 8), "bracket_pos": min(t.bracket_pos, 8),
            "person": float(person(lower)) if (person and digits == 0) else 0.0})
    years = [b["year_like"] for b in base]
    digits = [b["is_digit"] for b in base]
    for i, b in enumerate(base):
        b["years_before"] = min(sum(years[:i]), 2)
        b["years_after"] = min(sum(years[i + 1:]), 2)
        b["digits_before"] = min(sum(digits[:i]), 3)
        b["digits_after"] = min(sum(digits[i + 1:]), 3)
    rows = []
    for i in range(n):
        row = [float(base[i][name]) for name in OWN]
        for off in (-2, -1, 1, 2):
            j = i + off
            row.extend(float(base[j][name]) if 0 <= j < n else -1.0 for name in CTX)
        rows.append(row)
    return rows


_SE = re.compile(r"s([0-9]{1,2})e([0-9]{1,4})")
_NXN = re.compile(r"([0-9]{1,2})x([0-9]{1,3})")
_NUM = re.compile(r"[0-9]{1,4}")


def assemble(text: str, tokens: list[Token], labels: list[int]) -> dict:
    """Read fields off labelled tokens. Title = first run of TITLE tokens, alt_title = the next run."""
    out: dict = {"title": None, "alt_title": None, "year": None, "season": None, "episode": None, "volume": None,
                 "chapter": None, "author": None, "narrator": None, "group": None}

    def runs(label: int) -> list[tuple[int, int]]:
        found, start = [], None
        for i, lab in enumerate(labels + [-1]):
            if lab == label and start is None:
                start = i
            elif lab != label and start is not None:
                found.append((start, i - 1))
                start = None
        return found

    def span(run: tuple[int, int]) -> str:
        return text[tokens[run[0]].start:tokens[run[1]].end].replace("_", " ").replace(".", " ").strip() \
            if all(tokens[k].sep_before in (2, 3) for k in range(run[0] + 1, run[1] + 1)) and run[1] > run[0] \
            else text[tokens[run[0]].start:tokens[run[1]].end].strip()

    title_runs = runs(L["TITLE"])
    if title_runs:
        out["title"] = span(title_runs[0])
        if len(title_runs) > 1:
            out["alt_title"] = span(title_runs[1])
    for field in ("AUTHOR", "NARRATOR", "GROUP"):
        r = runs(L[field])
        if r:
            out[field.lower()] = span(r[0])
    for i, lab in enumerate(labels):
        lower = tokens[i].text.lower()
        if lab == L["SE"]:
            m = _SE.search(lower) or _NXN.fullmatch(lower)
            if m:
                out["season"] = out["season"] or int(m.group(1))
                out["episode"] = out["episode"] or int(m.group(2))
            continue
        field = {L["YEAR"]: "year", L["SEASON"]: "season", L["EPISODE"]: "episode", L["VOLUME"]: "volume",
                 L["CHAPTER"]: "chapter"}.get(lab)
        if field and out[field] is None:
            m = _NUM.search(lower)
            if m:
                out[field] = int(m.group(0))
    return out
