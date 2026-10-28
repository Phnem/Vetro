"""Collect bounded CC0 label/alias snapshots. Never queries catalogue providers."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from fetch_resources import ROOT, USER_AGENT, digest

DOMAINS = {"ANIME": "P4086", "MANGA": "P4087", "MOVIE": "P4947",
           "SERIES": "P4983", "BOOK_WORK": "P648"}


def query(kind: str, offset: int, count: int, ids: list[str] | None = None) -> str:
    # Entity selection occurs before labels; LIMIT never silently truncates aliases.
    work_filter = 'FILTER(STRSTARTS(?external, "OL") && STRENDS(?external, "W"))' if kind == "BOOK_WORK" else ""
    selection = "VALUES ?item { " + " ".join("wd:" + q for q in ids) + " }" if ids is not None else f"{{ SELECT DISTINCT ?item WHERE {{ ?item wdt:{DOMAINS[kind]} ?external . {work_filter} }} ORDER BY ?item LIMIT {count} OFFSET {offset} }}"
    return f'''SELECT ?item ?external ?text ?date ?series ?parent WHERE {{
      {selection}
      ?item wdt:{DOMAINS[kind]} ?external . {work_filter}
      ?item (rdfs:label|skos:altLabel) ?text .
      FILTER(LANG(?text) IN ("en", "ru", "ja", "uk", "fr", "de"))
      OPTIONAL {{ ?item wdt:P577 ?date }}
      OPTIONAL {{ ?item wdt:P179 ?series }}
      OPTIONAL {{ ?item wdt:P361 ?parent }}
    }}'''


def collect(folder: Path, kind: str, offset: int, count: int, ids: list[str] | None = None) -> dict:
    request_query = query(kind, offset, count, ids)
    query_hash = hashlib.sha256(request_query.encode()).hexdigest()
    target = folder / f"{kind.lower()}-{offset:06d}.json"
    receipt_path = target.with_suffix(".receipt.json")
    if target.exists() and receipt_path.exists():
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        if receipt["query_sha256"] == query_hash and receipt["sha256"] == digest(target):
            return receipt
        raise ValueError(f"Changed cached query/snapshot: {target}")
    url = "https://query.wikidata.org/sparql?" + urlencode({"query": request_query, "format": "json"})
    for attempt in range(4):
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/sparql-results+json"})
            with urlopen(request, timeout=90) as response:
                raw = response.read(40_000_001)
            if len(raw) > 40_000_000:
                raise ValueError("Query response exceeds 40 MB")
            data = json.loads(raw)
            bindings = data["results"]["bindings"]
            # Check query shape before installing bytes; no HTML/error-page caching.
            if any(not {"item", "external", "text"}.issubset(row) for row in bindings):
                raise ValueError("Unexpected Wikidata query result")
            folder.mkdir(parents=True, exist_ok=True)
            temporary = target.with_suffix(".part")
            temporary.write_bytes(raw)
            temporary.replace(target)
            receipt = {"kind": kind, "property": DOMAINS[kind], "offset": offset,
                       "entity_page_size": count, "query": request_query, "query_sha256": query_hash,
                       "url": url, "retrieved_at": datetime.now(timezone.utc).isoformat(),
                       "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw),
                       "rows": len(bindings), "entities": len({r["item"]["value"] for r in bindings}),
                       "license": "CC0-1.0", "source": "Wikidata structured data",
                       "provenance_limit": "WDQS response snapshot, not per-entity revision history"}
            receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8", newline="\n")
            return receipt
        except Exception:
            if attempt == 3:
                raise
            time.sleep(3 * (attempt + 1))
    raise AssertionError("unreachable")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--limit-per-domain", type=int, default=10_000)
    parser.add_argument("--page-size", type=int, default=500)
    parser.add_argument("--kinds", nargs="+", choices=list(DOMAINS), default=list(DOMAINS))
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/wikidata-corpus")
    args = parser.parse_args()
    if not 1 <= args.page_size <= 1000 or not 1 <= args.limit_per_domain <= 20_000:
        parser.error("Require page size1..1000 and domain bound1..20000")
    manifest = []
    for kind in args.kinds:
        args.output.mkdir(parents=True, exist_ok=True)
        id_file = args.output / (kind.lower() + ".ids.json")
        id_receipt = id_file.with_suffix(".sha256")
        if id_file.exists() and id_receipt.exists() and digest(id_file) == id_receipt.read_text().strip():
            ids = json.loads(id_file.read_text())["ids"][:args.limit_per_domain]
        else:
            condition = 'FILTER(STRSTARTS(?external,"OL") && STRENDS(?external,"W"))' if kind == "BOOK_WORK" else ""
            id_query = f"SELECT DISTINCT ?item WHERE {{ ?item wdt:{DOMAINS[kind]} ?external . {condition} }} ORDER BY ?item LIMIT {args.limit_per_domain}"
            url = "https://query.wikidata.org/sparql?" + urlencode({"query": id_query, "format": "json"})
            with urlopen(Request(url, headers={"User-Agent": USER_AGENT}), timeout=90) as response:
                rows = json.loads(response.read())["results"]["bindings"]
            ids = [row["item"]["value"].rsplit("/",1)[-1] for row in rows]
            id_file.write_text(json.dumps({"query": id_query, "ids": ids}) + "\n",encoding="utf-8",newline="\n")
            id_receipt.write_text(digest(id_file) + "\n",encoding="utf-8",newline="\n")
        for offset in range(0, len(ids), args.page_size):
            page = ids[offset:offset + args.page_size]
            receipt = collect(args.output, kind, offset, len(page), page)
            manifest.append(receipt)
            print(json.dumps({k: receipt[k] for k in ("kind", "offset", "entities", "rows", "bytes")}), flush=True)
            # A full external-ID page can contain few labelled entities. Stop only at zero rows.
            time.sleep(0.3)
    (args.output / "manifest.json").write_text(json.dumps({"pages": manifest}, indent=2) + "\n",
                                            encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
