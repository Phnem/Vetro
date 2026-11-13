"""Server-side catalog for the Cloudflare API: D1 import files + R2 upload lists.

    v2\\.venv\\Scripts\\python build\\build_server_data.py --master work\\vetro_books_v2.sqlite ^
        --aihand ..\\aihand.txt --covers ..\\cover_images --out out

Server catalog = everything on the phone + the whole Russian catalogue tier (details on demand).
D1 tables (see out/server/schema.sql):
  works(id, ru, en, ra, ea, alt, x, y, f, cv, d)   d = JSON detail card, fts = FTS5 over text columns
Detail card (compact keys): orig{t,l} type alts[{t,l}] authors[{n,l,r}] series[{ru,en,pos}]
  tr[{l,t,by,y}] ed[{t,l,pub,y,isbn,fmt}] ext{source:id} cover{src,page,lic,w,h}
  + from the details store (--details, build/details.py): desc{t,l} gen[canonical genre keys] ol coll ch dur rate{v,n}
    and series[{ru,en,pos}] when the master DB has none
R2 lists (wrangler r2 bulk put --filename): covers-small.json, covers-large.json.
"""
import argparse
import json
import os
import sqlite3
import time
from collections import defaultdict

import build_index as bi
import details as dm
from common import norm

CHUNK = 15000
FTS_CHUNK = 10000
MAX_DETAIL_BYTES = 60000
CAPS = {"alts": 12, "authors": 8, "series": 4, "tr": 6, "ed": 6, "ext": 10}


def sql(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def add_capped(d, key, item, dedupe_on=None):
    lst = d.setdefault(key, [])
    if len(lst) >= CAPS.get(key, 99):
        return
    if dedupe_on is not None and any(dedupe_on(x) == dedupe_on(item) for x in lst):
        return
    lst.append(item)


def load_details(conn, wids):
    """work_id -> detail dict, for the given work ids (one streaming pass per side table)."""
    conn.execute("ATTACH ':memory:' AS m")
    conn.execute("CREATE TABLE m.sel(work_id INTEGER PRIMARY KEY)")
    conn.executemany("INSERT INTO m.sel VALUES (?)", [(w,) for w in wids])
    det = defaultdict(dict)

    for wid, ot, ol, wt in conn.execute(
        "SELECT w.id, w.original_title, w.original_language, w.work_type FROM works w JOIN m.sel s ON s.work_id = w.id"
    ):
        d = det[wid]
        if ot:
            d["orig"] = {"t": ot, "l": ol}
        if wt and wt != "work":
            d["type"] = wt

    for wid, title, lang, ttype in conn.execute(
        "SELECT t.work_id, t.title, t.language, t.title_type FROM work_titles t JOIN m.sel s ON s.work_id = t.work_id "
        "WHERE t.is_official = 1 OR t.title_type IN ('canonical','original','translation') ORDER BY t.work_id, t.is_primary DESC"
    ):
        add_capped(det[wid], "alts", {"t": title, "l": lang}, lambda x: norm(x["t"]))

    for wid, role, lang, name in conn.execute(
        "SELECT wa.work_id, wa.role, an.language, an.name FROM work_authors wa JOIN m.sel s ON s.work_id = wa.work_id "
        "JOIN author_names an ON an.author_id = wa.author_id "
        "WHERE an.language IN ('ru','en') AND an.name_type IN ('canonical','official') ORDER BY wa.work_id"
    ):
        add_capped(det[wid], "authors", {"n": name, "l": lang, "r": role}, lambda x: (norm(x["n"]), x["r"]))

    for wid, ru, en, canon, pos in conn.execute(
        "SELECT e.work_id, s.ru_title, s.en_title, s.canonical_title, e.position FROM series_entries e "
        "JOIN m.sel x ON x.work_id = e.work_id JOIN series s ON s.id = e.series_id"
    ):
        item = {"ru": ru, "en": en or canon}
        if pos:
            item["pos"] = pos
        add_capped(det[wid], "series", item, lambda x: (x.get("ru"), x.get("en")))

    for wid, lang, title, by, yr in conn.execute(
        "SELECT t.work_id, t.target_language, t.translated_title, t.translator_name, t.publication_year "
        "FROM translations t JOIN m.sel s ON s.work_id = t.work_id ORDER BY t.work_id, t.is_official DESC"
    ):
        item = {"l": lang, "t": title}
        if by:
            item["by"] = by
        if yr:
            item["y"] = yr
        add_capped(det[wid], "tr", item, lambda x: (x["l"], norm(x["t"])))

    for wid, title, lang, pub, yr, i13, i10, fmt in conn.execute(
        "SELECT e.work_id, e.title, e.language, e.publisher, e.publication_year, e.isbn13, e.isbn10, e.edition_format "
        "FROM editions e JOIN m.sel s ON s.work_id = e.work_id ORDER BY e.work_id, (e.isbn13 IS NULL), e.publication_year"
    ):
        item = {"t": title}
        for k, v in (("l", lang), ("pub", pub), ("y", yr), ("isbn", i13 or i10), ("fmt", fmt if fmt != "print" else None)):
            if v:
                item[k] = v
        add_capped(det[wid], "ed", item, lambda x: (norm(x["t"]), x.get("isbn")))

    for wid, src, ext in conn.execute(
        "SELECT x.entity_id, x.source, x.external_id FROM external_ids x WHERE x.entity_type = 'work' "
        "AND x.entity_id IN (SELECT work_id FROM m.sel)"
    ):
        ids = det[wid].setdefault("ext", {})
        if len(ids) < CAPS["ext"] and src not in ids:
            ids[src] = ext

    for wid, src, page, lic, w, h in conn.execute(
        "SELECT c.work_id, c.source, c.source_page, c.license, c.large_width, c.large_height "
        "FROM work_covers c JOIN m.sel s ON s.work_id = c.work_id"
    ):
        cov = {"src": src}
        for k, v in (("page", page), ("lic", lic), ("w", w), ("h", h)):
            if v:
                cov[k] = v
        det[wid]["cover"] = cov
    return det


def merge_extras(card, extra):
    """Master-DB card + details-store extras. The master's structured series wins; the store only fills a gap."""
    if not extra:
        return card
    card = dict(card)
    for k, v in extra.items():
        if k == "series" and card.get("series"):
            continue
        card.setdefault(k, v)
    return card


def detail_json(d):
    for drop in (None, "ed", "alts", "tr", "series"):
        if drop:
            d.pop(drop, None)
        s = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
        if len(s.encode("utf-8")) <= MAX_DETAIL_BYTES:
            return s
    return "{}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--master", required=True)
    ap.add_argument("--aihand", required=True, nargs="+", help="aihand.txt и/или блоки демонов (harvested_blocks*.json)")
    ap.add_argument("--details", help="снимок vetro_work_details.sqlite: описание, жанры, серии и др. в карточку")
    ap.add_argument("--suffix", default="", help="'_next': загрузить рядом с боевыми таблицами и обменять swap.sql")
    ap.add_argument("--covers", required=True, help="folder with small/ and large/ AVIF files named {work_id}.avif")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    t0 = time.time()
    out = os.path.join(os.path.abspath(args.out), "server")
    os.makedirs(out, exist_ok=True)

    selected, covers = bi.load_selection(args.master, args.aihand)
    conn = sqlite3.connect(f"file:{args.master}?mode=ro", uri=True)
    vid_to_wid = dict(conn.execute("SELECT vetro_id, work_id FROM vetro_id_registry WHERE work_id IS NOT NULL"))
    wid_to_vid = {w: v for v, w in vid_to_wid.items()}

    extra = 0
    for r in conn.execute(f"SELECT {bi.SUMMARY_COLS} FROM work_bilingual_summary WHERE catalog_tier = 'RUSSIAN_CATALOG' "
                          "AND (ru_title IS NOT NULL OR en_title IS NOT NULL)"):
        vid = wid_to_vid.get(r[0])
        if vid and vid not in selected:
            selected[vid] = bi.row_from_summary(r)
            extra += 1
    print(f"server catalog: {len(selected)} works ({extra} beyond the phone index)")

    det = load_details(conn, {vid_to_wid[v] for v in selected if v in vid_to_wid})
    print(f"details loaded in {time.time() - t0:.0f}s")
    extra_cards = dm.load_extras(args.details, set(selected)) if args.details else {}
    if args.details:
        keys = defaultdict(int)
        for e in extra_cards.values():
            for k in e:
                keys[k] += 1
        print(f"details store: {len(extra_cards)} cards with extras; fields: {dict(sorted(keys.items()))}")

    rows = bi.to_rows(selected, covers)  # same rows as the phone index, so ids/flags/covers agree
    # Без суффикса таблицы пересоздаются «на месте» (первая выкладка). С суффиксом (`--suffix _next`) всё грузится в
    # works_next/fts_next рядом с боевыми, а swap.sql меняет их местами за доли секунды: API не пустеет на время заливки.
    W, F = "works" + args.suffix, "fts" + args.suffix
    with open(os.path.join(out, "schema.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"""DROP TABLE IF EXISTS {W};
CREATE TABLE {W} (
  id INTEGER PRIMARY KEY, ru TEXT, en TEXT, ra TEXT, ea TEXT, alt TEXT, x TEXT,
  y INTEGER, f INTEGER NOT NULL DEFAULT 0, cv INTEGER NOT NULL DEFAULT 0, d TEXT
);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
""")
    files = []
    for n, start in enumerate(range(0, len(rows), CHUNK), 1):
        part = rows[start:start + CHUNK]
        name = f"data-{n:03d}.sql"
        files.append(name)
        with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
            for (bid, ru, en, ra, ea, alt, x, y, fl, cv) in part:
                d = detail_json(merge_extras(det.get(vid_to_wid.get(f"{bid:09d}"), {}), extra_cards.get(bid)))
                f.write(f"INSERT OR REPLACE INTO {W} VALUES ({bid},{sql(ru)},{sql(en)},{sql(ra)},{sql(ea)},{sql(alt)},{sql(x)},"
                        f"{sql(y)},{fl},{cv},{sql(d)});\n")
    with open(os.path.join(out, "post-import.sql"), "w", encoding="utf-8", newline="\n") as f:
        f.write(f"DROP TABLE IF EXISTS {F};\n")
        f.write(f"CREATE VIRTUAL TABLE {F} USING fts5(ru, en, ra, ea, alt, x, tokenize='unicode61 remove_diacritics 2', prefix='2 3');\n")
        for start in range(0, len(rows), FTS_CHUNK):
            part = rows[start:start + FTS_CHUNK]
            # DELETE first: a retried chunk (wrangler failed half-way) must not leave doubled FTS rows.
            f.write(f"DELETE FROM {F} WHERE rowid BETWEEN {part[0][0]} AND {part[-1][0]};\n")
            f.write(f"INSERT INTO {F}(rowid, ru, en, ra, ea, alt, x) SELECT id, ru, en, ra, ea, alt, x FROM {W} "
                    f"WHERE id BETWEEN {part[0][0]} AND {part[-1][0]};\n")
        f.write(f"INSERT OR REPLACE INTO meta VALUES ('catalog_works', '{len(rows)}');\n")
        f.write("INSERT OR REPLACE INTO meta VALUES ('catalog_built', datetime('now'));\n")
    if args.suffix:
        with open(os.path.join(out, "swap.sql"), "w", encoding="utf-8", newline="\n") as f:
            f.write("DROP TABLE IF EXISTS fts;\nDROP TABLE IF EXISTS works;\n")
            f.write(f"ALTER TABLE {W} RENAME TO works;\nALTER TABLE {F} RENAME TO fts;\n")

    # R2 upload lists. Keys use the 9-digit vetro id so the client never needs the internal work id.
    missing = 0
    for size in ("small", "large"):
        items = []
        for wid, vid in conn.execute("SELECT work_id, vetro_id FROM work_covers WHERE vetro_id IS NOT NULL"):
            path = os.path.abspath(os.path.join(args.covers, size, f"{wid}.avif"))
            if os.path.exists(path):
                items.append({"key": f"covers/{size}/{vid}.avif", "file": path})
            else:
                missing += 1
        with open(os.path.join(out, f"covers-{size}.json"), "w", encoding="utf-8") as f:
            json.dump(items, f)
        print(f"covers-{size}.json: {len(items)} files")
    print(f"cover files missing on disk: {missing}")
    print(f"data files: {len(files)}; done in {time.time() - t0:.0f}s -> {out}")
    conn.close()


if __name__ == "__main__":
    main()
