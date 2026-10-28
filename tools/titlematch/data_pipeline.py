"""Typed CC0 corpus, leakage-safe groups and a collision-preserving SQLite index."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from contextlib import closing
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import unicodedata

from fetch_resources import ROOT, digest

NORMALIZATION_VERSION = "nfkc-lower-words-v1"
NAMESPACES = {"ANIME": "mal:anime:entry", "MANGA": "mal:manga:entry",
              "MOVIE": "tmdb:movie:entry", "SERIES": "tmdb:tv:entry", "BOOK_WORK": "openlibrary:work"}


def normalize(text: str) -> str:
    value = unicodedata.normalize("NFKC", text).lower()
    return " ".join("".join(c if c.isalnum() else " " for c in value).split())


def key(text: str) -> str:
    return normalize(text).replace(" ", "")


def scoped_id(kind: str, value: str) -> str:
    if kind not in NAMESPACES or not value:
        raise ValueError("Unsupported external ID scope")
    if kind == "BOOK_WORK":
        if not re.fullmatch(r"OL\d+W", value):
            raise ValueError("Expected Open Library work ID")
    elif not value.isdecimal() or int(value) <= 0:
        raise ValueError("Expected positive catalogue ID")
    return NAMESPACES[kind] + ":" + value


def group_split(entities: list[dict], aliases: list[dict], relations: list[dict]) -> tuple[dict, dict]:
    parent = {}

    def root(item):
        parent.setdefault(item, item)
        if parent[item] != item:
            parent[item] = root(parent[item])
        return parent[item]

    def union(a, b):
        ra, rb = root(a), root(b)
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)

    by_id = {e["entity_id"]: e for e in entities}
    for e in entities:
        union(e["entity_id"], e["qid"])
    for relation in relations:
        union(relation["entity_id"], relation["target_qid"])
    first_key = {}
    for alias in aliases:
        k = alias["search_key"]
        if k:
            union(alias["entity_id"], first_key.setdefault(k, alias["entity_id"]))
    assignments = {}
    counts = Counter()
    group_counts = Counter()
    for entity_id in sorted(by_id):
        group = root(entity_id)
        bucket = int(hashlib.sha256(("vetro-split-v1:" + group).encode()).hexdigest()[:8], 16) % 100
        split = "train" if bucket < 80 else "validation" if bucket < 90 else "test"
        assignments[entity_id] = {"split": split, "group_id": group}
        counts[split] += 1
        group_counts[group] += 1
    return assignments, {"entities_per_split": dict(counts), "groups": len(group_counts),
                         "largest_groups": group_counts.most_common(10)}


def build(raw_dir: Path, output: Path, split_reference: Path | None = None) -> dict:
    import pyarrow as pa
    import pyarrow.parquet as pq

    entities = {}
    aliases = {}
    external = {}
    relations = {}
    evidence = []
    quarantine = []
    receipts = sorted(raw_dir.glob("*.receipt.json"))
    if not receipts:
        raise ValueError("No CC0 source receipts; run collect_wikidata.py first")
    for receipt_file in receipts:
        receipt = json.loads(receipt_file.read_text(encoding="utf-8"))
        source = receipt_file.with_name(receipt_file.name.replace(".receipt.json", ".json"))
        if receipt["license"] != "CC0-1.0" or digest(source) != receipt["sha256"]:
            raise ValueError(f"Unapproved/corrupt source: {source}")
        kind = receipt["kind"]
        evidence_id = receipt["sha256"]
        evidence.append({"evidence_id": evidence_id, "source": "Wikidata", "license": "CC0-1.0",
                         "retrieved_at": receipt["retrieved_at"], "query_sha256": receipt["query_sha256"],
                         "training_eligible": True, "redistribution_eligible": True,
                         "source_path": source.name})
        for row in json.loads(source.read_text(encoding="utf-8"))["results"]["bindings"]:
            qid = row["item"]["value"].rsplit("/", 1)[-1]
            entity_id = f"wd:{qid}:{kind}"
            value = row["external"]["value"]
            try:
                typed_id = scoped_id(kind, value)
            except ValueError:
                quarantine.append({"entity_id": entity_id, "reason": "unsupported_id", "value": value})
                continue
            entity = entities.setdefault(entity_id, {"entity_id": entity_id, "qid": qid, "kind": kind,
                "entity_level": "WORK" if kind == "BOOK_WORK" else "CATALOG_ENTRY", "year": None})
            date = row.get("date", {}).get("value", "")
            if re.match(r"^[1-2]\d{3}-", date):
                year = int(date[:4])
                entity["year"] = min(entity["year"] or year, year)
            text = row["text"]["value"].strip()
            language = row["text"].get("xml:lang", "und")
            search_key = key(text)
            if text and search_key:
                aliases[(entity_id, text, language)] = {"entity_id": entity_id, "text": text,
                    "language": language, "normalized": normalize(text), "search_key": search_key,
                    "evidence_id": evidence_id, "source": "Wikidata", "license": "CC0-1.0"}
            external[(entity_id, typed_id)] = {"entity_id": entity_id, "typed_id": typed_id,
                                             "evidence_id": evidence_id}
            for predicate in ("series", "parent"):
                if predicate in row:
                    q = row[predicate]["value"].rsplit("/", 1)[-1]
                    relations[(entity_id, predicate, q)] = {"entity_id": entity_id, "predicate": predicate,
                                                          "target_qid": q, "evidence_id": evidence_id}
    # Duplicate external IDs cannot establish a negative identity label or be silently unioned.
    owners = defaultdict(set)
    for row in external.values():
        owners[row["typed_id"]].add(row["entity_id"])
    bad_entities = {e for ids in owners.values() if len(ids) > 1 for e in ids}
    for e in bad_entities:
        quarantine.append({"entity_id": e, "reason": "conflicting_external_id", "value": ""})
    per_entity = defaultdict(list)
    for row in external.values():
        per_entity[row["entity_id"]].append(row["typed_id"])
    for entity_id, typed_ids in per_entity.items():
        counts = Counter(value.rsplit(":",1)[0] for value in typed_ids)
        if any(count > 1 for count in counts.values()):
            bad_entities.add(entity_id)
            quarantine.append({"entity_id": entity_id, "reason": "multiple_ids_at_catalog_scope", "value": "|".join(sorted(typed_ids))})
    entity_rows = [e for e in entities.values() if e["entity_id"] not in bad_entities]
    keep = {e["entity_id"] for e in entity_rows}
    alias_rows = [a for a in aliases.values() if a["entity_id"] in keep]
    id_rows = [a for a in external.values() if a["entity_id"] in keep]
    relation_rows = [a for a in relations.values() if a["entity_id"] in keep]
    splits, split_report = group_split(entity_rows, alias_rows, relation_rows)
    challenge_path = ROOT / "challenge.json"
    challenge = json.loads(challenge_path.read_text(encoding="utf-8"))
    challenge_keys = {key(c[field]) for c in challenge["cases"] for field in ("a", "b") if key(c[field])}
    challenge_groups = {splits[a["entity_id"]]["group_id"] for a in alias_rows if a["search_key"] in challenge_keys}
    for assignment in splits.values():
        if assignment["group_id"] in challenge_groups:
            assignment["split"] = "challenge"
    if split_reference is not None:
        reference = {r["entity_id"]:r for r in pq.read_table(split_reference).to_pylist()}
        for entity_id in splits:
            if entity_id not in reference:
                raise ValueError("Split reference does not cover corrected corpus")
            splits[entity_id] = {k:reference[entity_id][k] for k in ("split","group_id")}
        split_report["reference_sha256"] = digest(split_reference)
        split_report["reason"] = "Preserve all original groups/splits while quarantining source granularity errors"
    split_report["entities_per_split"] = dict(Counter(a["split"] for a in splits.values()))
    split_report["challenge_sha256"] = digest(challenge_path)
    for entity in entity_rows:
        entity.update(splits[entity["entity_id"]])
    output.mkdir(parents=True, exist_ok=True)
    tables = {"entities": entity_rows, "aliases": alias_rows, "external_ids": id_rows,
              "relations": relation_rows, "evidence": evidence, "quarantine": quarantine}
    for name, rows in tables.items():
        if rows:
            pq.write_table(pa.Table.from_pylist(rows), output / f"{name}.parquet", compression="zstd")
        else:
            pq.write_table(pa.table({"entity_id": pa.array([], type=pa.string()),
                                     "reason": pa.array([], type=pa.string())}), output / f"{name}.parquet")
    database = output / "dictionary.sqlite"
    temporary = database.with_suffix(".building")
    if temporary.exists():
        temporary.unlink()
    with closing(sqlite3.connect(temporary)) as db:
        db.executescript('''CREATE TABLE entity(id TEXT PRIMARY KEY,kind TEXT,qid TEXT,year INTEGER);
          CREATE TABLE alias(entity_id TEXT,text TEXT,language TEXT,key TEXT,normalized TEXT);
          CREATE INDEX alias_key ON alias(key); CREATE INDEX alias_entity ON alias(entity_id);
          CREATE TABLE external_id(entity_id TEXT,typed_id TEXT); CREATE INDEX typed_ids ON external_id(typed_id);
          CREATE VIRTUAL TABLE search USING fts5(text,entity_id UNINDEXED,tokenize='unicode61');
          CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);''')
        db.executemany("INSERT INTO entity VALUES (?,?,?,?)", [(e["entity_id"],e["kind"],e["qid"],e["year"]) for e in entity_rows])
        db.executemany("INSERT INTO alias VALUES (?,?,?,?,?)", [(a["entity_id"],a["text"],a["language"],a["search_key"],a["normalized"]) for a in alias_rows])
        db.executemany("INSERT INTO search VALUES (?,?)", [(a["normalized"],a["entity_id"]) for a in alias_rows])
        db.executemany("INSERT INTO external_id VALUES (?,?)", [(i["entity_id"],i["typed_id"]) for i in id_rows])
        db.executemany("INSERT INTO metadata VALUES (?,?)", [("normalization",NORMALIZATION_VERSION),("license","CC0-1.0"),("schema_version","1")])
        db.execute("INSERT INTO search(search) VALUES ('optimize')")
        db.commit()
    temporary.replace(database)
    collisions = Counter(a["search_key"] for a in {(a["entity_id"],a["search_key"]):a for a in alias_rows}.values())
    report = {"source": "Wikidata CC0 only", "normalization": NORMALIZATION_VERSION,
              "counts": {k: len(v) for k,v in tables.items()}, "domains": dict(Counter(e["kind"] for e in entity_rows)),
              "languages": dict(Counter(a["language"] for a in alias_rows)), "split": split_report,
              "collision_keys": sum(n>1 for n in collisions.values()), "dictionary_bytes": database.stat().st_size,
              "files": {p.name: digest(p) for p in sorted(output.glob("*.parquet"))}}
    (output / "corpus.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8", newline="\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir", type=Path, default=ROOT / ".cache/wikidata-corpus")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/corpus-v1")
    parser.add_argument("--split-reference", type=Path)
    args = parser.parse_args()
    print(json.dumps(build(args.raw_dir, args.output, args.split_reference), indent=2))
