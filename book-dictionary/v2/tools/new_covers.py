"""Cover upload lists limited to covers the cloud does not have yet.

    python new_covers.py --old work\\vetro_books_v2.sqlite --lists out\\server\\covers-small.json out\\server\\covers-large.json ^
        --out out\\server\\covers-new

`--old` is the master copy the PREVIOUS release was built from: every cover it already had is in R2. Lists are JSON arrays of
{"key": "covers/small/000000771.avif", "file": "..."}; the result is the same shape, one file per input list
(covers-new-small.json, covers-new-large.json). Covers are immutable per vetro id, so "new" means "id not seen before".
"""
import argparse
import json
import os
import sqlite3

ap = argparse.ArgumentParser()
ap.add_argument("--old", required=True)
ap.add_argument("--lists", nargs="+", required=True)
ap.add_argument("--out", required=True, help="output folder")
args = ap.parse_args()

conn = sqlite3.connect(f"file:{args.old}?mode=ro", uri=True)
have = {r[0] for r in conn.execute("SELECT vetro_id FROM work_covers WHERE vetro_id IS NOT NULL")}
conn.close()
os.makedirs(args.out, exist_ok=True)

for path in args.lists:
    items = json.load(open(path, encoding="utf-8"))
    new = [i for i in items if os.path.basename(i["key"])[:9] not in have]
    size = sum(os.path.getsize(i["file"]) for i in new)
    name = os.path.basename(path).replace("covers-", "covers-new-", 1)
    with open(os.path.join(args.out, name), "w", encoding="utf-8") as f:
        json.dump(new, f)
    print(f"{name}: {len(new)} of {len(items)} are new, {size / 1e6:.0f} MB")
