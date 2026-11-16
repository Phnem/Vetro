"""Cloud catalogue of anime, films and series: D1 import files + R2 cover lists for tools/books-api.

    python -X utf8 cloud\\build_media_server.py --master <snapshot vetro_media.sqlite> --details <snapshot vetro_media_details.sqlite> ^
        --covers anime=anime\\cover_images --covers cinema=cinema\\cover_images --out cloud\\out

Everything is built from SNAPSHOTS (book-dictionary/v2/tools/copy_master.py): the daemons keep writing to the live databases.
D1 tables (out/server/schema.sql), loaded next to the live ones with --suffix _next and swapped in by swap.sql:
  media(n, id, t, ru, en, ro, orig, alt, x, y, st, pop, f, cv, d)  n = numeric key for FTS, id = 'A00000001', t = anime|movie|tv_series,
  d = JSON detail card; media_fts = FTS5 over ru, en, ro, orig, alt, x.
R2 lists: covers-{small,large}.json with keys covers/media/{small|large}/{id}.avif (the same shape the book lists use).
"""
import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "book-dictionary", "v2", "build"))
from common import extra_tokens  # noqa: E402  (same folding / transliteration as the book catalogue)

CHUNK = 15000
FTS_CHUNK = 10000
MAX_ALTS = 12
MAX_ALT_CHARS = 1500
MAX_DETAIL_BYTES = 30000
BASE = {"anime": 0, "movie": 10_000_000, "tv_series": 20_000_000}
ID_RE = re.compile(r"^[A-Z]\d{8}$")
EXT_COLUMNS = {"mal": "mal_id", "shikimori": "shikimori_id", "anilist": "anilist_id", "anidb": "anidb_id", "kitsu": "kitsu_id",
               "tmdb": "tmdb_id", "imdb": "imdb_id", "tvmaze": "tvmaze_id", "tvdb": "tvdb_id", "wikidata": "wikidata_qid"}


def sql(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def numeric_key(media_type, vid):
    return BASE[media_type] + int(vid[1:])


def cover_files(dirs):
    """vid -> {size: path}. A title whose HD file is missing but whose low-resolution one exists serves that one for both sizes."""
    found = {}
    for root in dirs:
        low = {}
        d = os.path.join(root, "low_resolution")
        if os.path.isdir(d):
            for name in os.listdir(d):
                if name.endswith(".avif"):
                    low[name[:-5]] = os.path.join(d, name)
        for size in ("small", "large"):
            d = os.path.join(root, size)
            if not os.path.isdir(d):
                continue
            for name in os.listdir(d):
                if name.endswith(".avif"):  # files ending in .avif.tmp are still being written by a daemon
                    found.setdefault(name[:-5], {})[size] = os.path.join(d, name)
        for vid, path in low.items():
            have = found.setdefault(vid, {})
            have.setdefault("small", path)
            have.setdefault("large", path)
    return found


def load_json(text, default):
    try:
        return json.loads(text) if text else default
    except (TypeError, ValueError):
        return default


def detail_card(det, cover, ext):
    d = {}
    if det:
        desc = (det["description"] or "").strip()
        if desc:
            d["desc"] = {"t": desc[:4000], "l": det["description_lang"] or ""}
        genres = [g for g in load_json(det["genres_json"], []) if isinstance(g, str) and g.strip()][:30]
        if genres:
            d["gen"] = genres
        if det["franchise_title"]:
            fr = {"t": det["franchise_title"]}
            if det["franchise_index"] not in (None, ""):
                fr["i"] = det["franchise_index"]
            if det["franchise_link_count"] not in (None, "", 0, "0"):
                fr["n"] = det["franchise_link_count"]
            d["fr"] = fr
        for key, col in (("ol", "original_language"), ("seas", "seasons_count"), ("eps", "episodes_count"),
                         ("dur", "episode_duration_minutes"), ("st", "release_status")):
            v = det[col]
            if v not in (None, "", "None"):
                d[key] = v
    if ext:
        d["ext"] = ext
    if cover:
        d["cover"] = cover
    blob = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
    if len(blob.encode("utf-8")) > MAX_DETAIL_BYTES:
        d.pop("desc", None)
        blob = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
    return blob if d else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--master", required=True)
    ap.add_argument("--details", required=True)
    ap.add_argument("--covers", action="append", required=True, help="name=folder with small/ large/ low_resolution/ (repeat)")
    ap.add_argument("--suffix", default="_next")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    t0 = time.time()
    out = os.path.join(os.path.abspath(args.out), "server")
    os.makedirs(out, exist_ok=True)
    files = cover_files([c.split("=", 1)[-1] for c in args.covers])
    print(f"cover files on disk: {len(files)} titles")

    conn = sqlite3.connect(f"file:{args.master}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    det_conn = sqlite3.connect(f"file:{args.details}?mode=ro", uri=True)
    det_conn.row_factory = sqlite3.Row
    details = {r["vetro_id"]: r for r in det_conn.execute("SELECT * FROM details")}
    covers = {r["vetro_id"]: r for r in conn.execute(
        "SELECT vetro_id, status, width, height, sha256, source, license, rights_review FROM cover_records WHERE status LIKE 'saved%'")}
    alts = {}
    for vid, title, lang in conn.execute("SELECT vetro_id, title, lang FROM alt_titles ORDER BY id"):
        lst = alts.setdefault(vid, [])
        if title and len(lst) < MAX_ALTS and title not in lst:
            lst.append(title)
    print(f"snapshots read in {time.time() - t0:.0f}s: {len(details)} detail cards, {len(covers)} cover records, {len(alts)} titles with alternatives")

    rows = []
    cover_small, cover_large = [], []
    for r in conn.execute("SELECT * FROM media_master ORDER BY media_type, vetro_id"):
        vid, mt = r["vetro_id"], r["media_type"]
        if mt not in BASE or not ID_RE.match(vid):
            continue
        titles = [r["ru_title"], r["en_title"], r["romaji_title"], r["original_title"], r["native_title"]]
        if not any(t and t.strip() for t in titles):
            continue
        alt_list = [a for a in alts.get(vid, []) if a not in titles]
        alt = " | ".join(alt_list)[:MAX_ALT_CHARS]
        x = extra_tokens(r["ru_title"], r["en_title"], r["romaji_title"], r["original_title"], alt)
        fl = 0
        cv = 0
        cover = None
        have = files.get(vid)
        c = covers.get(vid)
        if have and have.get("small") and have.get("large"):
            fl |= 1
            cv = int(hashlib.sha256((c["sha256"] if c and c["sha256"] else vid).encode()).hexdigest()[:7], 16)
            if c:
                if c["rights_review"]:
                    fl |= 2
                cover = {"src": c["source"], "lic": c["license"], "w": c["width"], "h": c["height"]}
            cover_small.append({"key": f"covers/media/small/{vid}.avif", "file": os.path.abspath(have["small"])})
            cover_large.append({"key": f"covers/media/large/{vid}.avif", "file": os.path.abspath(have["large"])})
        ext = {k: r[col] for k, col in EXT_COLUMNS.items() if r[col] not in (None, "")}
        card = detail_card(details.get(vid), cover, ext)
        rows.append((numeric_key(mt, vid), vid, mt, r["ru_title"], r["en_title"], r["romaji_title"], r["original_title"], alt, x,
                     r["year"], r["media_subtype"], round(r["popularity_score"] or 0, 2), fl, cv, card))
    print(f"catalogue rows: {len(rows)}; covers: {len(cover_small)}; built in {time.time() - t0:.0f}s")

    M, F = "media" + args.suffix, "media_fts" + args.suffix
    with open(os.path.join(out, "schema.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""DROP TABLE IF EXISTS {M};
CREATE TABLE {M} (
  n INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, t TEXT NOT NULL, ru TEXT, en TEXT, ro TEXT, orig TEXT, alt TEXT, x TEXT,
  y INTEGER, st TEXT, pop REAL, f INTEGER NOT NULL DEFAULT 0, cv INTEGER NOT NULL DEFAULT 0, d TEXT
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
""")
    names = []
    for n, start in enumerate(range(0, len(rows), CHUNK), 1):
        name = f"data-{n:03d}.sql"
        names.append(name)
        with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
            for row in rows[start:start + CHUNK]:
                f.write(f"INSERT OR REPLACE INTO {M} VALUES ({','.join(sql(v) for v in row)});\n")
    with open(os.path.join(out, "post-import.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"DROP TABLE IF EXISTS {F};\n")
        f.write(f"CREATE VIRTUAL TABLE {F} USING fts5(ru, en, ro, orig, alt, x, tokenize='unicode61 remove_diacritics 2', prefix='2 3');\n")
        keys = [r[0] for r in rows]
        for start in range(0, len(keys), FTS_CHUNK):
            lo, hi = keys[start], keys[min(start + FTS_CHUNK, len(keys)) - 1]
            f.write(f"DELETE FROM {F} WHERE rowid BETWEEN {lo} AND {hi};\n")
            f.write(f"INSERT INTO {F}(rowid, ru, en, ro, orig, alt, x) SELECT n, ru, en, ro, orig, alt, x FROM {M} WHERE n BETWEEN {lo} AND {hi};\n")
        f.write(f"INSERT OR REPLACE INTO meta VALUES ('media_titles', '{len(rows)}');\n")
        f.write("INSERT OR REPLACE INTO meta VALUES ('media_built', datetime('now'));\n")
    with open(os.path.join(out, "swap.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write("DROP TABLE IF EXISTS media_fts;\nDROP TABLE IF EXISTS media;\n")
        f.write(f"ALTER TABLE {M} RENAME TO media;\nALTER TABLE {F} RENAME TO media_fts;\n")
    for size, items in (("small", cover_small), ("large", cover_large)):
        with open(os.path.join(out, f"covers-{size}.json"), "w", encoding="utf-8") as f:
            json.dump(items, f)
    print(f"data files: {len(names)}; done in {time.time() - t0:.0f}s -> {out}")


if __name__ == "__main__":
    main()
