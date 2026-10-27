"""Research-only overlap analysis. Does not import AOD labels into training."""
from collections import Counter, defaultdict
import json
from pathlib import Path

from fetch_resources import ROOT


def read(path):
    rows, owners = [], {}
    with path.open(encoding="utf-8") as stream:
        next(stream)
        for index, line in enumerate(stream):
            row = json.loads(line)
            rows.append(row)
            for source in row["sources"]:
                if source in owners:
                    raise ValueError("Duplicate source identity within snapshot")
                owners[source] = index
    return rows, owners


def main():
    a, a_ids = read(ROOT / ".cache/data/manami/anime-offline-database.jsonl")
    b, b_ids = read(ROOT / ".cache/data/cedya/anime-offline-database.jsonl")
    a_to_b, b_to_a = defaultdict(set), defaultdict(set)
    for url in a_ids.keys() & b_ids.keys():
        ai, bi = a_ids[url], b_ids[url]
        a_to_b[ai].add(bi)
        b_to_a[bi].add(ai)
    merged = [(bi, ais) for bi, ais in b_to_a.items() if len(ais) > 1]
    split = [(ai, bis) for ai, bis in a_to_b.items() if len(bis) > 1]
    one_to_one = [(ai, next(iter(bis))) for ai, bis in a_to_b.items()
                  if len(bis) == 1 and len(b_to_a[next(iter(bis))]) == 1]
    report = {"scope": "research only; overlaps are not approved equivalences",
        "manami_records": len(a), "cedya_records": len(b), "one_to_one_overlap": len(one_to_one),
        "merge_groups": len(merged), "split_groups": len(split),
        "records_without_shared_ids": {"manami": len(a)-len(a_to_b), "cedya":len(b)-len(b_to_a)},
        "one_to_one_format_changes": sum(a[ai].get("type") != b[bi].get("type") for ai,bi in one_to_one),
        "one_to_one_year_changes": sum(a[ai].get("animeSeason",{}).get("year") != b[bi].get("animeSeason",{}).get("year") for ai,bi in one_to_one),
        "merge_sizes": dict(Counter(len(ais) for _,ais in merged)),
        "warning": "A merge can cross season/OVA/remake boundaries; requires evidence review, never automatic union."}
    target = ROOT / ".cache/snapshot-diff.json"
    target.write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
    print(json.dumps(report,indent=2))


if __name__ == "__main__":
    main()
