"""Turn cached WDQS responses into the MicroML corpus tables.

Splits are assigned per connected group (franchise / adaptation / shared alias), before
any pair, augmentation or negative is generated. Entities that TitleMatch v2 used for
validation or test keep that role here, so both generations can be compared on v2's own test set.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import sys
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from vml.text import key, latin, normalize  # noqa: E402

KIND_PRIORITY = ["ANIME", "MANGA", "SERIES", "MOVIE", "BOOK_WORK"]
# file prefix of a collection wave -> (media kind, entity level or None for the kind's default level)
DOMAIN_KIND = {"ANIME": ("ANIME", None), "MANGA": ("MANGA", None), "MOVIE": ("MOVIE", None),
               "SERIES": ("SERIES", None), "BOOK_WORK": ("BOOK_WORK", None), "ANIME_X": ("ANIME", None),
               "MANGA_X": ("MANGA", None), "LIGHT_NOVEL": ("BOOK_WORK", None), "SEASON": ("SERIES", "SEASON"),
               "BOOK_SERIES": ("BOOK_WORK", "SERIES_HUB")}
LIST_PROPS = {"P31": "p31", "P179": "series", "P361": "part_of", "P155": "follows", "P156": "followed_by",
              "P144": "based_on", "P4969": "derivative", "P527": "has_part", "P136": "genres",
              "P50": "authors", "P57": "directors", "P495": "countries", "P364": "orig_lang", "P407": "lang_of_work"}
ID_PROPS = {"P4086": "mal_anime", "P8729": "anilist_anime", "P5646": "anidb", "P4087": "mal_manga",
            "P8731": "anilist_manga", "P4947": "tmdb_movie", "P4983": "tmdb_tv", "P345": "imdb",
            "P648": "openlibrary", "P2603": "kinopoisk"}
LINK_FIELDS = ["series", "part_of", "follows", "followed_by", "based_on", "derivative", "has_part"]


class Union:
    def __init__(self) -> None:
        self.parent: dict[str, str] = {}

    def find(self, x: str) -> str:
        parent = self.parent
        root = x
        while parent.setdefault(root, root) != root:
            root = parent[root]
        while parent[x] != root:
            parent[x], x = root, parent[x]
        return root

    def join(self, a: str, b: str) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            if ra > rb:
                ra, rb = rb, ra
            self.parent[rb] = ra


def qid(uri: str) -> str:
    return uri.rsplit("/", 1)[-1]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, default=ROOT / ".cache/wikidata-raw")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--v2-entities", type=Path, default=None, help="TitleMatch corpus-v2/entities.parquet")
    parser.add_argument("--collision-cap", type=int, default=3)
    args = parser.parse_args()

    kinds: dict[str, set[str]] = collections.defaultdict(set)
    levels: dict[str, str] = {}
    ent: dict[str, dict] = {}
    for path in sorted((args.raw / "props").glob("*.json")):
        if path.name.endswith(".receipt.json"):
            continue
        kind, level = DOMAIN_KIND[path.name.rsplit("-", 1)[0].upper()]
        for row in json.loads(path.read_bytes())["results"]["bindings"]:
            q = qid(row["item"]["value"])
            kinds[q].add(kind)
            if level:
                levels[q] = level
            e = ent.setdefault(q, {"years": [], "ids": {}, "extra_titles": []})
            p = row["p"]["value"].rsplit("/", 1)[-1].replace("ontology#", "")
            v = row["v"]
            if p in LIST_PROPS:
                if v["type"] == "uri":
                    e.setdefault(LIST_PROPS[p], set()).add(qid(v["value"]))
            elif p in ("P577", "P580", "P571"):
                text = v["value"]
                if len(text) >= 4 and text[:4].isdigit():
                    e["years"].append((0 if p != "P571" else 1, int(text[:4])))
            elif p in ID_PROPS:
                e["ids"].setdefault(ID_PROPS[p], set()).add(v["value"])
            elif p == "sitelinks":
                e["sitelinks"] = int(v["value"])
            elif p in ("P2047", "P1113", "P2437", "P1545"):
                try:
                    e[p] = float(v["value"])
                except ValueError:
                    pass
            elif p in ("P1476", "P1680"):
                e["extra_titles"].append((v.get("xml:lang", "und"), v["value"], "title" if p == "P1476" else "subtitle"))
            elif p in ("P1814", "P2125"):
                e["extra_titles"].append(("ja-Latn" if p == "P2125" else "ja-kana", v["value"], "reading"))

    titles: dict[str, dict[tuple[str, str], str]] = collections.defaultdict(dict)
    for path in sorted((args.raw / "labels").glob("*.json")):
        if path.name.endswith(".receipt.json"):
            continue
        kind, level = DOMAIN_KIND[path.name.rsplit("-", 1)[0].upper()]
        for row in json.loads(path.read_bytes())["results"]["bindings"]:
            q = qid(row["item"]["value"])
            kinds[q].add(kind)
            if level:
                levels[q] = level
            text = row["text"]["value"].strip()
            lang = row["text"].get("xml:lang", "und")
            if text and len(text) <= 300:
                source = "label" if row["kind"]["value"] == "L" else "alias"
                titles[q].setdefault((lang, text), source)
    for q, e in ent.items():
        for lang, text, source in e["extra_titles"]:
            text = text.strip()
            if text and len(text) <= 300 and source != "subtitle":
                titles[q].setdefault((lang, text), source)
            if lang == "ja-kana":  # deterministic Hepburn-style reading: a synthetic but faithful romaji alias
                roma = latin(normalize(text))
                if roma and roma.isascii():
                    titles[q].setdefault(("ja-Latn", roma), "reading-synth")

    entities = [q for q in kinds if titles.get(q) and any(normalize(t) for _, t in titles[q])]
    eset = set(entities)

    union = Union()
    for q in entities:
        union.find(q)
        for field in LINK_FIELDS:
            for target in ent.get(q, {}).get(field, ()):
                union.join(q, target)  # hubs outside the corpus still tie their members together
    by_key: dict[str, set[str]] = collections.defaultdict(set)
    for q in entities:
        for (_, text) in titles[q]:
            k = key(text)
            if len(k) >= 3:
                by_key[k].add(q)
    collisions = 0
    generic_keys = 0
    for k, members in by_key.items():
        if len(members) < 2:
            continue
        if len(members) > args.collision_cap:
            generic_keys += 1  # "hamlet", "home"... recorded below, removed from positives later
            continue
        collisions += 1
        first, *rest = sorted(members)
        for other in rest:
            union.join(first, other)

    size_of: collections.Counter = collections.Counter(union.find(q) for q in entities)
    forced: dict[str, str] = {}
    if args.v2_entities and args.v2_entities.exists():
        for row in pq.read_table(args.v2_entities, columns=["qid", "split"]).to_pylist():
            if row["split"] in ("test", "validation") and row["qid"] in eset:
                root = union.find(row["qid"])
                # Sprawling hub groups stay with the hash split; v2 pairs that touch them are
                # reported as excluded from the v2-compatible comparison instead of dragging 1000s of entities.
                if size_of[root] <= 200 and forced.get(root) != "test":
                    forced[root] = row["split"]

    def split_of(root: str) -> str:
        if root in forced:
            return forced[root]
        if size_of[root] > 2000:
            return "train"
        h = int(hashlib.sha256(("vml-split-1:" + root).encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
        return "train" if h < 0.86 else "validation" if h < 0.93 else "test"

    def stage_of(root: str, split: str) -> str:
        """Train groups are divided once more: the encoder never sees the groups the pair models learn from."""
        if split != "train":
            return split
        if size_of[root] > 2000:
            return "enc"
        h = int(hashlib.sha256(("vml-stage-1:" + root).encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
        return "enc" if h < 0.80 else "gbm"

    rows = []
    title_rows = []
    group_sizes: collections.Counter = collections.Counter()
    for q in sorted(entities, key=lambda x: int(x[1:])):
        e = ent.get(q, {"years": [], "ids": {}})
        kind = next(k for k in KIND_PRIORITY if k in kinds[q])
        years = sorted(e["years"])
        published = [y for flag, y in years if flag == 0] or [y for _, y in years]
        root = union.find(q)
        group_sizes[root] += 1
        row = {"qid": q, "kind": kind, "kinds": sorted(kinds[q]), "level": levels.get(q, "ENTRY"),
               "year": min(published) if published else None, "sitelinks": e.get("sitelinks", 0),
               "duration": e.get("P2047"), "episodes": e.get("P1113"), "seasons": e.get("P2437"),
               "ordinal": e.get("P1545"), "group": root, "split": split_of(root),
               "stage": stage_of(root, split_of(root)),
               "ids": json.dumps({k: sorted(v) for k, v in e["ids"].items()}, sort_keys=True)}
        for field in LIST_PROPS.values():
            row[field] = sorted(e.get(field, ()))
        rows.append(row)
        for (lang, text), source in sorted(titles[q].items()):
            if normalize(text):
                title_rows.append({"qid": q, "lang": lang, "text": text, "source": source})

    args.output.mkdir(parents=True, exist_ok=True)
    pq.write_table(pa.Table.from_pylist(rows), args.output / "entities.parquet", compression="zstd")
    pq.write_table(pa.Table.from_pylist(title_rows), args.output / "titles.parquet", compression="zstd")
    generic = sorted(k for k, m in by_key.items() if len(m) > args.collision_cap)
    (args.output / "generic_keys.json").write_text(json.dumps(generic, ensure_ascii=False), encoding="utf-8")
    ref_map: dict[str, set[str]] = collections.defaultdict(set)
    for row in rows:
        for field in ("authors", "genres", "series", "p31", "directors"):
            ref_map[field].update(t for t in row[field] if t not in eset)
    (args.output / "refs.json").write_text(json.dumps({k: sorted(v) for k, v in ref_map.items()}), encoding="utf-8")
    # Labels of referenced entities (second collector stage): author names, genre names, series names.
    ref_labels: dict[str, dict[str, list[list[str]]]] = {"authors": {}, "genres": {}, "series": {}}
    for path in sorted((args.raw / "refs").glob("*.json")) if (args.raw / "refs").exists() else []:
        if path.name.endswith(".receipt.json"):
            continue
        field = path.name.rsplit("-", 1)[0]
        for row in json.loads(path.read_bytes())["results"]["bindings"]:
            text = row["text"]["value"].strip()
            if text and len(text) <= 200 and normalize(text):
                names = ref_labels[field].setdefault(qid(row["item"]["value"]), [])
                pair = [row["text"].get("xml:lang", "und"), text]
                if pair not in names and len(names) < 40:
                    names.append(pair)
    for field, mapping in ref_labels.items():
        (args.output / f"{field}.json").write_text(json.dumps(mapping, ensure_ascii=False), encoding="utf-8")
    sizes = sorted(group_sizes.values(), reverse=True)
    report = {"entities": len(rows), "titles": len(title_rows),
              "by_kind": dict(collections.Counter(r["kind"] for r in rows)),
              "by_split": dict(collections.Counter(r["split"] for r in rows)),
              "by_stage": dict(collections.Counter(r["stage"] for r in rows)),
              "by_kind_split": {f"{k}/{s}": n for (k, s), n in sorted(collections.Counter((r["kind"], r["split"]) for r in rows).items())},
              "title_langs": dict(collections.Counter(t["lang"] for t in title_rows).most_common(16)),
              "by_level": dict(collections.Counter(r["level"] for r in rows)),
              "groups": len(sizes), "largest_groups": sizes[:10], "alias_collision_keys": collisions,
              "generic_keys": generic_keys, "forced_groups": dict(collections.Counter(forced.values())),
              "refs": {k: len(v) for k, v in ref_map.items()},
              "ref_labels": {k: len(v) for k, v in ref_labels.items()}}
    (args.output / "corpus.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
