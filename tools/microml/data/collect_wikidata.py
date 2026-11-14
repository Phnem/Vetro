"""Collect CC0 Wikidata snapshots for the MicroML corpus (all five media kinds).

Only the Wikidata Query Service is contacted. Every raw response is cached with
its query text and SHA-256, so the corpus can be rebuilt without the network.
No catalogue provider (AniList, MAL, TMDB, Shikimori...) is queried.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import random
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ENDPOINT = "https://query.wikidata.org/sparql"
USER_AGENT = "VetroMicroMLResearch/1.0 (offline model training; CC0 structured data only)"

# kind -> external-id properties that anchor an entity to a catalogue the app already uses.
DOMAINS = {
    "ANIME": ["P4086", "P8729", "P5646"],      # MAL anime, AniList anime, AniDB
    "MANGA": ["P4087", "P8731"],               # MAL manga, AniList manga
    "MOVIE": ["P4947"],                        # TMDB movie
    "SERIES": ["P4983"],                       # TMDB tv
    "BOOK_WORK": ["P648"],                     # Open Library (works only, filtered below)
}
# Second wave, selected by class instead of catalogue id. Entities already in a base list are skipped.
EXTRA = {
    "ANIME_X": ["Q63952888", "Q20650540", "Q220898", "Q1047299", "Q1107", "Q100269041"],
    "MANGA_X": ["Q21198342", "Q8274", "Q562214", "Q754669"],
    "LIGHT_NOVEL": ["Q104213567", "Q747381"],
    "SEASON": ["Q3464665"],                    # television series season: series (P179) + ordinal (P1545)
    "BOOK_SERIES": ["Q277759", "Q1667921"],
}
LANGS = ["en", "ru", "ja", "uk", "fr", "de", "es", "zh", "ko", "it", "pl", "pt"]
# Truthy statements fetched per entity. Kept as one flat (item, property, value) list: no cross product.
PROPS = ["P31", "P577", "P580", "P571", "P179", "P361", "P155", "P156", "P144", "P4969", "P527",
         "P136", "P50", "P57", "P495", "P364", "P407", "P2047", "P1113", "P2437", "P1545",
         "P1814", "P2125", "P1476", "P1680",
         "P4086", "P8729", "P5646", "P4087", "P8731", "P4947", "P4983", "P345", "P648", "P2603", "P212", "P957"]


def fetch(query: str, target: Path, meta: dict, retries: int = 6) -> dict:
    receipt_path = target.with_suffix(".receipt.json")
    query_hash = hashlib.sha256(query.encode()).hexdigest()
    if target.exists() and receipt_path.exists():
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt["query_sha256"] == query_hash:
            return receipt
    body = urlencode({"query": query, "format": "json"}).encode()
    for attempt in range(retries):
        try:
            request = Request(ENDPOINT, data=body, headers={
                "User-Agent": USER_AGENT, "Accept": "application/sparql-results+json",
                "Content-Type": "application/x-www-form-urlencoded"})
            with urlopen(request, timeout=120) as response:
                raw = response.read()
            rows = json.loads(raw)["results"]["bindings"]
            target.parent.mkdir(parents=True, exist_ok=True)
            part = target.with_suffix(".part")
            part.write_bytes(raw)
            part.replace(target)
            receipt = dict(meta, query=query, query_sha256=query_hash,
                           retrieved_at=datetime.now(timezone.utc).isoformat(),
                           sha256=hashlib.sha256(raw).hexdigest(), bytes=len(raw), rows=len(rows),
                           license="CC0-1.0", source="Wikidata Query Service")
            receipt_path.write_text(json.dumps(receipt) + "\n", encoding="utf-8", newline="\n")
            return receipt
        except Exception:  # 429/5xx/timeouts: back off and retry
            if attempt == retries - 1:
                raise
            time.sleep(min(90, 4 * 2 ** attempt) + random.random() * 2)
    raise AssertionError("unreachable")


def id_list(out: Path, kind: str, limit: int) -> list[str]:
    ids: set[str] = set()
    for prop in DOMAINS[kind]:
        cond = 'FILTER(STRSTARTS(?x,"OL") && STRENDS(?x,"W"))' if kind == "BOOK_WORK" else ""
        target = out / "ids" / f"{kind.lower()}-{prop}.json"
        # Plain membership query: joining sitelink counts here times out on the 287k film list.
        query = f"SELECT DISTINCT ?item WHERE {{ ?item wdt:{prop} ?x . {cond} }}"
        fetch(query, target, {"kind": kind, "property": prop, "stage": "ids"})
        for row in json.loads(target.read_bytes())["results"]["bindings"]:
            ids.add(row["item"]["value"].rsplit("/", 1)[-1])
    qids = sorted(ids, key=lambda q: int(q[1:]))
    if len(qids) <= limit:
        return qids
    # Uniform sample with a fixed seed: no popularity filter, so obscure titles stay represented.
    return sorted(random.Random(20261110).sample(qids, limit), key=lambda q: int(q[1:]))


def class_ids(out: Path, kind: str, taken: set[str]) -> list[str]:
    ids: set[str] = set()
    for cls in EXTRA[kind]:
        target = out / "ids" / f"{kind.lower()}-{cls}.json"
        fetch(f"SELECT DISTINCT ?item WHERE {{ ?item wdt:P31 wd:{cls} }}", target, {"kind": kind, "class": cls, "stage": "ids"})
        for row in json.loads(target.read_bytes())["results"]["bindings"]:
            ids.add(row["item"]["value"].rsplit("/", 1)[-1])
    return sorted(ids - taken, key=lambda q: int(q[1:]))


def label_query(page: list[str]) -> str:
    values = " ".join("wd:" + q for q in page)
    langs = ", ".join(f'"{lang}"' for lang in LANGS)
    return f'''SELECT ?item ?kind ?text WHERE {{ VALUES ?item {{ {values} }}
      {{ ?item rdfs:label ?text BIND("L" AS ?kind) }} UNION {{ ?item skos:altLabel ?text BIND("A" AS ?kind) }}
      FILTER(LANG(?text) IN ({langs})) }}'''


def prop_query(page: list[str]) -> str:
    values = " ".join("wd:" + q for q in page)
    props = " ".join("wdt:" + p for p in PROPS)
    # Sitelink count (how many wikis describe the entity) rides along as a pseudo-property.
    return f'''SELECT ?item ?p ?v WHERE {{ VALUES ?item {{ {values} }}
      {{ VALUES ?p {{ {props} }} ?item ?p ?v }} UNION {{ ?item wikibase:sitelinks ?v BIND(wikibase:sitelinks AS ?p) }} }}'''


def page_job(out: Path, kind: str, index: int, page: list[str]) -> tuple[str, int, int]:
    meta = {"kind": kind, "page": index, "entities": len(page)}
    a = fetch(label_query(page), out / "labels" / f"{kind.lower()}-{index:05d}.json", dict(meta, stage="labels"))
    b = fetch(prop_query(page), out / "props" / f"{kind.lower()}-{index:05d}.json", dict(meta, stage="props"))
    return kind, index, a["rows"] + b["rows"]


def collect_refs(out: Path, refs_file: Path, page_size: int, workers: int) -> None:
    """Labels of entities the corpus only points at: authors, genres, series hubs."""
    refs = json.loads(refs_file.read_text(encoding="utf-8"))
    jobs = []
    for field in ("authors", "genres", "series"):
        ids = refs.get(field, [])
        for i in range(0, len(ids), page_size):
            jobs.append((field, i // page_size, ids[i:i + page_size]))
    print(json.dumps({"refs": {k: len(v) for k, v in refs.items()}, "pages": len(jobs)}), flush=True)

    def job(item):
        field, index, page = item
        fetch(label_query(page), out / "refs" / f"{field}-{index:05d}.json", {"kind": field, "page": index, "stage": "refs"})
        return field, index

    with cf.ThreadPoolExecutor(workers) as pool:
        for n, _ in enumerate(pool.map(job, jobs), 1):
            if n % 25 == 0 or n == len(jobs):
                print(json.dumps({"done": n, "of": len(jobs)}), flush=True)
    (out / "COMPLETE-refs").write_text(datetime.now(timezone.utc).isoformat(), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/wikidata-raw")
    parser.add_argument("--page-size", type=int, default=400)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--limits", type=json.loads,
                        default={"ANIME": 60000, "MANGA": 60000, "MOVIE": 300000, "SERIES": 150000, "BOOK_WORK": 150000})
    parser.add_argument("--reverse", action="store_true")
    parser.add_argument("--extra", action="store_true", help="collect the class-selected second wave only")
    parser.add_argument("--refs", type=Path, default=None, help="corpus refs.json: fetch labels of referenced entities")
    args = parser.parse_args()
    if args.refs:
        collect_refs(args.output, args.refs, args.page_size, args.workers)
        return
    jobs = []
    plan = {}
    taken: set[str] = set()
    for kind in (list(DOMAINS) + list(EXTRA) if args.extra else DOMAINS):
        if kind in EXTRA:
            ids = class_ids(args.output, kind, taken)
        else:
            ids = id_list(args.output, kind, args.limits[kind])
        taken.update(ids)
        if args.extra and kind in DOMAINS:
            continue  # base lists belong to the first wave
        plan[kind] = len(ids)
        (args.output / "ids" / f"{kind.lower()}.selected.json").write_text(json.dumps(ids), encoding="utf-8")
        for i in range(0, len(ids), args.page_size):
            jobs.append((kind, i // args.page_size, ids[i:i + args.page_size]))
    print(json.dumps({"plan": plan, "pages": len(jobs)}), flush=True)
    random.Random(1).shuffle(jobs)  # every kind progresses, so an interrupted run is still usable
    if args.reverse:  # a second process can work the same plan from the other end
        jobs.reverse()
    done = 0
    with cf.ThreadPoolExecutor(args.workers) as pool:
        for kind, index, rows in pool.map(lambda j: page_job(args.output, *j), jobs):
            done += 1
            if done % 25 == 0 or done == len(jobs):
                print(json.dumps({"done": done, "of": len(jobs), "last": [kind, index, rows]}), flush=True)
    (args.output / ("COMPLETE-extra" if args.extra else "COMPLETE")).write_text(datetime.now(timezone.utc).isoformat(), encoding="utf-8")


if __name__ == "__main__":
    main()
