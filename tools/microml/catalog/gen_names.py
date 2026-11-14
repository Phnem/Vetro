"""Generate labelled release/folder names for the name parser.

Real titles and people names come from the CC0 corpus; the naming conventions around them
are produced by a small grammar: every part (group, season, episode, year, tech, author,
narrator, volume, chapter) has many surface forms, and separators, brackets and order vary.
A set of fixed hand-written layouts is kept out of training as a probe.
"""
from __future__ import annotations

import argparse
import collections
import json
import random
import sys
import unicodedata
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from catalog.names import DOMAINS, L, tokenize  # noqa: E402
from vml.text import normalize  # noqa: E402

GROUPS = ["SubsPlease", "Erai-raws", "HorribleSubs", "Judas", "EMBER", "ASW", "Anime Time", "DKB", "Cleo", "YTS", "RARBG",
          "NTb", "FLUX", "AniLibria", "AniDUB", "SHIZA", "AnimeVost", "JAM", "Dream Cast", "LostFilm", "NewStudio",
          "Kuraj-Bambey", "HDRezka", "MangaLib", "ReManga", "Kirei Cake", "LuCaZ", "danke-Empire", "1r0n", "Viz",
          "QxR", "Tigole", "PSA", "GalaxyRG", "ION10", "SPARKS", "NTG", "CMRG", "TeamHD", "Kyuubi", "Yameii", "Reaktor",
          "Dark Horse", "Kodansha", "Yen Press", "ROVERS", "DIMENSION", "KiNGS", "TGx", "EZTV", "MeGusta", "Trix"]
RES = ["1080p", "720p", "480p", "2160p", "4K", "1080i", "BD1080p", "360p"]
SRC = ["BDRip", "BluRay", "WEB-DL", "WEBRip", "HDTV", "DVDRip", "BDRemux", "WEB", "HDRip", "BD", "AMZN", "NF", "ATVP"]
CODEC = ["x264", "x265", "HEVC", "H.264", "h265", "AVC", "10bit", "AV1", "HEVC x265 10bit"]
AUDIO = ["AAC", "FLAC", "AC3", "DTS", "Dual Audio", "MULTI", "EAC3", "Opus", "RUS", "ENG", "JPN", "DDP5.1", "Multi-Subs",
         "Multiple Subtitle"]
FLAGS = ["REMASTERED", "EXTENDED", "PROPER", "REPACK", "UNCUT", "IMAX", "HDR", "COMPLETE", "Batch", "Uncensored"]
VIDEO_EXT = ["mkv", "mp4", "avi"]
ORD = {1: "1st", 2: "2nd", 3: "3rd", 4: "4th", 5: "5th", 6: "6th"}
Part = list  # list[(text, label)]


class Builder:
    def __init__(self) -> None:
        self.parts: list[tuple[str, int]] = []
        self.gold: dict = {}

    def add(self, text: str, label: str = "OTHER") -> "Builder":
        self.parts.append((unicodedata.normalize("NFKC", text), L[label]))
        return self

    def extend(self, parts: Part) -> "Builder":
        for text, label in parts:
            self.add(text, label)
        return self

    def render(self) -> tuple[str, list[int]]:
        raw = "".join(t for t, _ in self.parts)
        marks: list[int] = []
        for t, lab in self.parts:
            marks.extend([lab] * len(t))
        text, tokens = tokenize(raw)
        assert text == raw
        return raw, [marks[t.start] for t in tokens]


def scene(title: str) -> str:
    return ".".join(w for w in normalize(title).split(" ") if w).title() if title.isascii() else title.replace(" ", ".")


def tech_items(rng: random.Random) -> list[str]:
    items = [rng.choice(RES)]
    if rng.random() < 0.7:
        items.append(rng.choice(SRC))
    if rng.random() < 0.6:
        items.append(rng.choice(CODEC))
    if rng.random() < 0.4:
        items.append(rng.choice(AUDIO))
    if rng.random() < 0.15:
        items.append(rng.choice(FLAGS))
    rng.shuffle(items)
    return items


def tech(rng: random.Random, sep: str) -> str:
    return sep.join(tech_items(rng))


def crc(rng: random.Random) -> str:
    while True:
        value = "".join(rng.choice("0123456789ABCDEF") for _ in range(8))
        if any(c.isdigit() for c in value) and any(c.isalpha() for c in value):
            return value


def season_form(n: int, rng: random.Random, glue: str) -> Part:
    k = rng.randrange(8)
    if k == 0:
        return [(f"S{n}", "SEASON")]
    if k == 1:
        return [(f"S{n:02d}", "SEASON")]
    if k == 2:
        return [("Season" + glue, "CUE"), (str(n), "SEASON")]
    if k == 3:
        return [(ORD.get(n, f"{n}th"), "SEASON"), (glue + "Season", "CUE")]
    if k == 4:
        return [("Сезон" + glue, "CUE"), (str(n), "SEASON")]
    if k == 5:
        return [(str(n), "SEASON"), (glue + "сезон", "CUE")]
    if k == 6:
        return [("ТВ-", "CUE"), (str(n), "SEASON")]
    return [("season" + glue, "CUE"), (f"{n:02d}", "SEASON")]


def episode_form(n: int, rng: random.Random, glue: str) -> Part:
    k = rng.randrange(12)
    width = rng.choice([1, 2, 2, 2, 3])
    num = f"{n:0{width}d}"
    if k <= 2:
        return [(num, "EPISODE")]
    if k == 3:
        return [(f"E{num}", "EPISODE")]
    if k == 4:
        return [(f"EP{num}", "EPISODE")]
    if k == 5:
        return [("Episode" + glue, "CUE"), (str(n), "EPISODE")]
    if k == 6:
        return [("Ep" + glue, "CUE"), (num, "EPISODE")]
    if k == 7:
        return [(rng.choice(["Серия", "серия", "Эпизод"]) + glue, "CUE"), (str(n), "EPISODE")]
    if k == 8:
        return [(str(n), "EPISODE"), (glue + "серия", "CUE")]
    if k == 9:
        total = max(n, rng.choice([12, 13, 24, 25, 26, 50]))
        return [(num, "EPISODE"), (glue + "из" + glue, "CUE"), (str(total), "OTHER")]
    if k == 10:
        return [(f"{num}v{rng.randint(2, 3)}", "EPISODE")]
    return [("#", "OTHER"), (str(n), "EPISODE")]


def volume_form(n: int, rng: random.Random, glue: str, book: bool) -> Part:
    if book:
        return [(rng.choice(["Книга", "Часть", "Том", "Book", "Part", "Vol.", "книга", "часть"]) + glue, "CUE"), (str(n), "VOLUME")]
    k = rng.randrange(7)
    if k <= 1:
        return [(f"v{n:02d}", "VOLUME")]
    if k == 2:
        return [(f"v{n}", "VOLUME")]
    if k == 3:
        return [("Vol." + glue, "CUE"), (str(n), "VOLUME")]
    if k == 4:
        return [("Volume" + glue, "CUE"), (f"{n:02d}", "VOLUME")]
    if k == 5:
        return [("Том" + glue, "CUE"), (str(n), "VOLUME")]
    return [("Vol" + glue, "CUE"), (f"{n:02d}", "VOLUME")]


def chapter_form(n: int, rng: random.Random, glue: str) -> Part:
    k = rng.randrange(8)
    if k <= 1:
        return [(f"c{n:03d}", "CHAPTER")]
    if k == 2:
        return [(f"c{n}", "CHAPTER")]
    if k == 3:
        return [("Ch." + glue, "CUE"), (str(n), "CHAPTER")]
    if k == 4:
        return [("Chapter" + glue, "CUE"), (str(n), "CHAPTER")]
    if k == 5:
        return [(rng.choice(["Глава", "глава"]) + glue, "CUE"), (str(n), "CHAPTER")]
    if k == 6:
        return [(f"ch{n}", "CHAPTER")]
    return [("#", "OTHER"), (str(n), "CHAPTER")]


def wrap(parts: Part, rng: random.Random, force: bool = False) -> Part:
    if force or rng.random() < 0.45:
        o, c = rng.choice([("[", "]"), ("(", ")"), ("(", ")"), ("[", "]"), ("{", "}")])
        return [(o, "OTHER")] + parts + [(c, "OTHER")]
    return parts


def person_form(name: str, rng: random.Random) -> str:
    words = name.split()
    if len(words) < 2 or rng.random() < 0.45:
        return name
    k = rng.randrange(5)
    if k == 0:
        return words[-1] + " " + " ".join(words[:-1])
    if k == 1:
        return words[-1] + " " + " ".join(w[0] + "." for w in words[:-1])
    if k == 2:
        return " ".join(w[0] + "." for w in words[:-1]) + " " + words[-1]
    if k == 3:
        return words[-1] + ", " + " ".join(words[:-1])
    return words[-1]


def video(rng: random.Random, pools: dict) -> tuple[Builder, str]:
    b = Builder()
    kind = rng.choice(["ANIME", "ANIME", "SERIES", "MOVIE"])
    title = rng.choice(pools[kind])
    style = rng.choices(["space", "dot", "under"], [6, 3, 1])[0]
    glue = {"space": " ", "dot": ".", "under": "_"}[style]
    shown = title if style == "space" else scene(title) if style == "dot" else title.replace(" ", "_")
    b.gold = {"title": title}
    group = rng.choice(GROUPS) if style == "space" else rng.choice(GROUPS).replace(" ", "")
    group_at = rng.choices(["none", "front", "back"], [4, 4, 2])[0]
    if group_at == "front":
        b.add("[").add(group, "GROUP").add("]" + (" " if style == "space" else glue if rng.random() < 0.7 else ""))
    year_first = style == "space" and rng.random() < 0.03
    year = rng.randint(1950, 2026)
    if year_first:
        b.add(str(year), "YEAR").add(rng.choice([" - ", " "]))
        b.gold["year"] = year
    b.add(shown, "TITLE")
    if style == "space" and rng.random() < 0.08:  # bilingual tracker name
        other = rng.choice(pools["EN"] if any(ord(c) > 0x400 for c in title) else pools["RU"])
        if rng.random() < 0.7:
            b.add(rng.choice([" / ", " | "])).add(other, "TITLE")
        else:
            b.add(" (").add(other, "TITLE").add(")")
        b.gold["alt_title"] = other
    seq: list[Part] = []
    mode = rng.choices(["episode", "se", "season_ep", "none", "season"], [4, 3, 2, 3, 0.5])[0] if kind != "MOVIE" else "none"
    ep, season = rng.randint(1, 26 if rng.random() < 0.8 else 400), rng.randint(1, 8)
    dash = {"space": " - ", "dot": ".", "under": "_-_"}[style]
    if mode == "se":
        form = rng.choice([f"S{season:02d}E{ep % 100:02d}", f"s{season:02d}e{ep % 100:02d}", f"{season}x{ep % 100:02d}", f"S{season}E{ep % 100}"])
        seq.append([(form, "SE")])
        b.gold.update(season=season, episode=ep % 100)
    elif mode in ("episode", "season_ep", "season"):
        if mode != "episode":
            seq.append(season_form(season, rng, glue))
            b.gold["season"] = season
        if mode != "season":
            seq.append(episode_form(ep, rng, glue))
            b.gold["episode"] = ep
    for part in seq:
        separator = rng.choice([dash, glue, glue]) if style == "space" else rng.choice([dash, glue])
        if style == "space" and rng.random() < 0.2:
            b.add(" ").extend(wrap(part, rng, force=True))
        else:
            b.add(separator).extend(part)
    if mode == "se" and style == "space" and rng.random() < 0.15:  # episode title after the marker
        b.add(" - ").add(" ".join(rng.choice(pools["EN"]).split()[:3]), "OTHER")
    if not year_first and (kind == "MOVIE" or rng.random() < 0.15) and rng.random() < 0.85:
        if style == "space":
            b.add(" ").extend(wrap([(str(year), "YEAR")], rng, force=rng.random() < 0.7))
        else:
            b.add(glue).add(str(year), "YEAR")
        b.gold["year"] = year
    if rng.random() < 0.65:
        if style == "space":
            k = rng.randrange(4)
            if k == 0:
                b.add(" ").add(tech(rng, " "), "TECH")
            elif k == 1:
                b.add(" [").add(tech(rng, rng.choice([" ", ", ", "]["])), "TECH").add("]")
            elif k == 2:
                b.add(" (").add(tech(rng, rng.choice([" ", ", "])), "TECH").add(")")
            else:
                for item in tech_items(rng):
                    b.add(" [" if rng.random() < 0.5 else "[").add(item, "TECH").add("]")
            if rng.random() < 0.25:
                b.add("[").add(crc(rng), "TECH").add("]")
        else:
            b.add(glue).add(tech(rng, glue), "TECH")
    if group_at == "back":
        if style == "space":
            o, c = rng.choice([(" [", "]"), (" (", ")"), ("-", "")])
            b.add(o).add(group, "GROUP").add(c)
        else:
            b.add("-").add(group, "GROUP")
    if rng.random() < 0.55:
        b.add("." + rng.choice(VIDEO_EXT), "TECH")
    return b, "video"


def audiobook(rng: random.Random, pools: dict) -> tuple[Builder, str]:
    b = Builder()
    title = rng.choice(pools["BOOK_WORK"])
    author = person_form(rng.choice(pools["PEOPLE"]), rng)
    if rng.random() < 0.06:
        author += rng.choice([" и ", ", ", " & ", " and "]) + person_form(rng.choice(pools["PEOPLE"]), rng)
    narrator = person_form(rng.choice(pools["PEOPLE"]), rng)
    year, n = rng.randint(1985, 2026), rng.randint(1, 12)
    style = rng.choices(["space", "under"], [9, 1])[0]
    dash = rng.choice([" - ", " — ", " – ", " - ", ". "]) if style == "space" else "_-_"
    sp = " " if style == "space" else "_"
    fix = (lambda s: s) if style == "space" else (lambda s: s.replace(" ", "_"))
    b.gold = {"title": title}
    layout = rng.choices(["author_title", "title_only", "track", "title_author"], [7, 2, 1, 0.4])[0]
    if layout == "track":
        b.add(f"{n:02d}", "CHAPTER").add(rng.choice([" - ", ". ", " ", "_"])).add(fix(title), "TITLE")
        b.gold["chapter"] = n
        if rng.random() < 0.8:
            b.add("." + rng.choice(["mp3", "m4b", "m4a", "ogg"]), "TECH")
        return b, "audiobook"
    if layout == "author_title":
        b.add(fix(author), "AUTHOR").add(dash).add(fix(title), "TITLE")
        b.gold["author"] = author
    elif layout == "title_author":
        b.add(fix(title), "TITLE").add(" (").add(rng.choice(["автор ", "by ", ""]), "CUE").add(author, "AUTHOR").add(")")
        b.gold["author"] = author
    else:
        b.add(fix(title), "TITLE")
    if rng.random() < 0.12:
        b.add(rng.choice([dash, sp, ", "]) if style == "space" else "_").extend(volume_form(n, rng, sp, True))
        b.gold["volume"] = n
    if rng.random() < 0.4 and style == "space":
        cue = rng.choice(["", "", "читает ", "чтец ", "read by ", "narrated by ", "Читает ", "Чтец ", "исполнитель ", "исп. ", "озвучка "])
        k = rng.randrange(6)
        b.gold["narrator"] = narrator
        if k <= 2:
            o, c = rng.choice([(" [", "]"), (" (", ")")])
            b.add(o).add(cue, "CUE").add(narrator, "NARRATOR")
            if rng.random() < 0.25:
                b.add(", ").add(str(year), "YEAR")
                b.gold["year"] = year
            b.add(c)
        elif k == 3:
            b.add(", ").add(cue or "читает ", "CUE").add(narrator, "NARRATOR")
        elif k == 4:
            b.add(" - ").add(cue or "читает ", "CUE").add(narrator, "NARRATOR")
        else:
            b.add(" [").add(narrator, "NARRATOR").add("]")
    if "year" not in b.gold and rng.random() < 0.25:
        if style == "space":
            b.add(rng.choice([" ", ", "])).extend(wrap([(str(year), "YEAR")], rng, force=rng.random() < 0.7))
        else:
            b.add("_").add(str(year), "YEAR")
        b.gold["year"] = year
    if rng.random() < 0.2:
        extra = rng.choice(["аудиокнига", "MP3", "m4b", f"{rng.choice([64, 96, 128, 192])} kbps", "MP3 128kbps", "Audiobook", "unabridged"])
        label = "CUE" if extra.lower() in ("аудиокнига", "audiobook") else "TECH"
        if style == "space":
            b.add(" ").extend(wrap([(extra, label)], rng, force=rng.random() < 0.6))
        else:
            b.add("_").add(extra, label)
    if rng.random() < 0.12:
        b.add("." + rng.choice(["m4b", "mp3"]), "TECH")
    return b, "audiobook"


def manga(rng: random.Random, pools: dict) -> tuple[Builder, str]:
    b = Builder()
    title = rng.choice(pools["MANGA"])
    vol, ch = rng.randint(1, 60), rng.randint(1, 400)
    group = rng.choice(GROUPS)
    b.gold = {"title": title}
    group_at = rng.choices(["none", "front", "back"], [6, 2, 3])[0]
    if group_at == "front":
        b.add("[").add(group, "GROUP").add("] ")
    b.add(title, "TITLE")
    mode = rng.choices(["vc", "v", "c", "cv", "none"], [3, 2, 3, 0.5, 1.5])[0]
    parts: list[Part] = []
    if "v" in mode and mode != "none":
        parts.append(volume_form(vol, rng, " ", False))
        b.gold["volume"] = vol
    if "c" in mode and mode != "none":
        parts.append(chapter_form(ch, rng, " "))
        b.gold["chapter"] = ch
    if mode == "cv":
        parts.reverse()
    for part in parts:
        if rng.random() < 0.2:
            b.add(" ").extend(wrap(part, rng, force=True))
        else:
            b.add(rng.choice([" ", " ", " - "])).extend(part)
    if rng.random() < 0.2:
        b.add(" (").add(rng.choice(["Digital", "Scan", "Colored", "Webtoon"]), "TECH").add(")")
    if group_at == "back":
        o, c = rng.choice([(" [", "]"), (" (", ")")])
        b.add(o).add(group, "GROUP").add(c)
    if rng.random() < 0.6:
        b.add("." + rng.choice(["cbz", "zip", "cbr", "pdf"]), "TECH")
    return b, "manga"


def probe(rng: random.Random, pools: dict) -> tuple[Builder, str]:
    """Fixed layouts the grammar does not produce as such; validation/test only."""
    b = Builder()
    k = rng.randrange(8)
    year, n, m = rng.randint(1960, 2026), rng.randint(1, 24), rng.randint(1, 9)
    if k == 0:
        title = rng.choice(pools["ANIME"])
        b.add(title, "TITLE").add(" (").add(rng.choice(GROUPS), "GROUP").add(") ").add(f"{n:02d}", "EPISODE").add(" ").add(tech(rng, " "), "TECH")
        b.gold = {"title": title, "episode": n}
        return b, "video"
    if k == 1:
        title = rng.choice(pools["SERIES"])
        b.add(title, "TITLE").add(", ").add("сезон ", "CUE").add(str(m), "SEASON").add(", ").add("эпизод ", "CUE").add(str(n), "EPISODE")
        b.gold = {"title": title, "season": m, "episode": n}
        return b, "video"
    if k == 2:
        title = rng.choice(pools["MOVIE"])
        b.add(str(year), "YEAR").add(". ").add(title, "TITLE").add(" [").add(tech(rng, " "), "TECH").add("]")
        b.gold = {"title": title, "year": year}
        return b, "video"
    if k == 3:
        title, author, reader = rng.choice(pools["BOOK_WORK"]), rng.choice(pools["PEOPLE"]), rng.choice(pools["PEOPLE"])
        b.add(author, "AUTHOR").add(": ").add(title, "TITLE").add(" / ").add("читает ", "CUE").add(reader, "NARRATOR")
        b.gold = {"title": title, "author": author, "narrator": reader}
        return b, "audiobook"
    if k == 4:
        title, author = rng.choice(pools["BOOK_WORK"]), rng.choice(pools["PEOPLE"])
        b.add("[").add(str(year), "YEAR").add("] ").add(author, "AUTHOR").add(" - ").add(title, "TITLE")
        b.gold = {"title": title, "author": author, "year": year}
        return b, "audiobook"
    if k == 5:
        title = rng.choice(pools["MANGA"])
        b.add(title, "TITLE").add(", ").add("том ", "CUE").add(str(m), "VOLUME").add(", ").add("гл. ", "CUE").add(str(n), "CHAPTER")
        b.gold = {"title": title, "volume": m, "chapter": n}
        return b, "manga"
    if k == 6:
        title = rng.choice(pools["MANGA"])
        b.add(title, "TITLE").add(" - ").add(f"{n:03d}", "CHAPTER").add(" (").add(str(year), "YEAR").add(") (").add("Digital", "TECH").add(")")
        b.gold = {"title": title, "chapter": n, "year": year}
        return b, "manga"
    title = rng.choice(pools["ANIME"])
    b.add(title, "TITLE").add(" ").add("Сезон ", "CUE").add(str(m), "SEASON").add(" [").add(f"{n:02d}", "EPISODE").add("] ").add(rng.choice(RES), "TECH")
    b.gold = {"title": title, "season": m, "episode": n}
    return b, "video"


def load_pools(corpus: Path, stages: set[str], rng: random.Random) -> dict:
    ents = {e["qid"]: e["kind"] for e in pq.read_table(corpus / "entities.parquet", columns=["qid", "kind", "stage"]).to_pylist()
            if e["stage"] in stages}
    pools: dict[str, list[str]] = collections.defaultdict(list)
    table = pq.read_table(corpus / "titles.parquet", columns=["qid", "lang", "text"])
    for q, lang, text in zip(table["qid"].to_pylist(), table["lang"].to_pylist(), table["text"].to_pylist()):
        kind = ents.get(q)
        if kind is None or not (2 <= len(text) <= 70) or any(c in text for c in "[](){}/|_\n\t"):
            continue
        if lang in ("en", "ru", "ja-Latn") or (lang in ("ja", "uk", "de", "fr", "es") and rng.random() < 0.15):
            pools[kind].append(text)
            if lang == "ru":
                pools["RU"].append(text)
            elif lang == "en":
                pools["EN"].append(text)
    people: list[str] = []
    author_file = corpus / "authors.json"
    if author_file.exists():
        tag = 0 if stages & {"enc", "gbm"} else 1 if "validation" in stages else 2
        for q, names in json.loads(author_file.read_text(encoding="utf-8")).items():
            if int(q[1:]) % 10 in ({0, 1, 2, 3, 4, 5, 6, 7}, {8}, {9})[tag]:
                people.extend(n for lang, n in names if lang in ("en", "ru") and 4 <= len(n) <= 40 and not any(c in n for c in "()[],/"))
    pools["PEOPLE"] = people or ["Иван Петров", "John Smith"]
    return pools


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/catalog")
    parser.add_argument("--count", type=json.loads, default={"train": 300000, "validation": 20000, "test": 30000})
    parser.add_argument("--seed", type=int, default=20261110)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for split, stages in (("train", {"enc", "gbm"}), ("validation", {"validation"}), ("test", {"test"})):
        rng = random.Random(f"{args.seed}:{split}")
        pools = load_pools(args.corpus, stages, rng)
        rows = []
        while len(rows) < args.count[split]:
            held = split != "train" and rng.random() < 0.2
            maker = probe if held else rng.choices([video, audiobook, manga], [5, 3, 2])[0]
            builder, domain = maker(rng, pools)
            raw, labels = builder.render()
            if not labels or len(labels) > 60:
                continue
            # Callers often know where a name came from; sometimes they do not.
            rows.append({"raw": raw, "domain": DOMAINS[domain] if rng.random() > 0.2 else 0, "true_domain": domain,
                         "labels": labels, "held_out_template": int(held), "gold": json.dumps(builder.gold, ensure_ascii=False)})
        pq.write_table(pa.Table.from_pylist(rows), args.output / f"names-{split}.parquet", compression="zstd")
        print(json.dumps({"split": split, "rows": len(rows), "sample": [r["raw"] for r in rows[:14]]}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
