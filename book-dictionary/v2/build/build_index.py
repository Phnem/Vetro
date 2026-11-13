"""BookIndex V2 builder: master copy -> device index (+ zstd), delta from the previous build, manifest.

    v2\\.venv\\Scripts\\python build\\build_index.py --master work\\vetro_books_v2.sqlite --aihand ..\\aihand.txt --out out

Selection (what ships to every phone): works with a cover, the verified bilingual core, and every
book on the curated aihand shelves. Everything else lives behind the API.
Reads the master copy read-only. Output layout under --out:
  index/base/bookindex-v{N}.sqlite[.zst]   index/delta/delta-{a}-{b}.zst   manifest.json
  history/bookindex-v{N}.sqlite            (raw bases, kept to diff the next build against)
"""
import argparse
import datetime
import json
import os
import re
import shutil
import sqlite3
import time

import bookindex
from common import extra_tokens, norm, sha256_file, strip_parens

MAX_ALTS = 5
MAX_ALT_LEN = 80
MAX_DELTA_CHAIN = 8
API_BASE = "https://api.vetrostudio.bid"


def split_alts(*raw_fields):
    out = []
    for raw in raw_fields:
        if raw:
            out.extend(t.strip() for t in re.split(r"[;|]", raw) if t.strip())
    return out


def clean_alts(alts, *taken):
    seen = {norm(t) for t in taken if t}
    out = []
    for a in alts:
        k = norm(a)
        if not k or k in seen or len(a) > MAX_ALT_LEN:
            continue
        seen.add(k)
        out.append(a)
        if len(out) >= MAX_ALTS:
            break
    return " | ".join(out)


def cover_rev(sha):
    return int(sha[:6], 16) if sha else 0


SUMMARY_COLS = ("work_id, ru_title, en_title, ru_author, en_author, first_publication_year, "
                "alt_titles_ru, alt_titles_en, catalog_tier, redistribution_allowed")


def row_from_summary(r):
    """work_bilingual_summary row (SUMMARY_COLS order) -> display row dict."""
    _, ru_t, en_t, ru_a, en_a, yr, alt_ru, alt_en, tier, redist = r
    return {
        "ru": ru_t or en_t or "", "en": en_t if ru_t else None,
        "ra": ru_a or en_a or "", "ea": en_a if ru_a else None,
        "year": yr, "alts": split_alts(alt_ru, alt_en),
        "core": tier == "BILINGUAL_CORE", "restricted": not redist,
    }


def load_selection(master_path, aihand_path):
    """Returns ({vetro_id: row dict}, {vetro_id: (cover source, rev)}) for the device index."""
    conn = sqlite3.connect(f"file:{master_path}?mode=ro", uri=True)
    q = conn.cursor()

    # Один файл или несколько в одном формате (aihand.txt + блоки, добытые демонами): блоки просто складываются.
    aihand = []
    for path in [aihand_path] if isinstance(aihand_path, str) else aihand_path:
        with open(path, "r", encoding="utf-8") as f:
            aihand += json.load(f)
    ah_ru_n = {norm(a.get("author_ru", "")) for a in aihand if a.get("author_ru")}
    ah_en_n = {norm(a.get("author_en", "")) for a in aihand if a.get("author_en")}

    wid_to_vid = dict(q.execute("SELECT work_id, vetro_id FROM vetro_id_registry WHERE work_id IS NOT NULL"))
    print(f"registry: {len(wid_to_vid)} work->vetro ids")

    covers = {}
    for wid, vid, src, sha in q.execute("SELECT work_id, vetro_id, source, source_sha256 FROM work_covers"):
        vid = vid or wid_to_vid.get(wid)
        if vid:
            covers[vid] = (src, cover_rev(sha))
    print(f"covers: {len(covers)}")

    reg_ru, reg_en = {}, {}
    for wid, vid, rt, et, ra, ea in q.execute(
        "SELECT work_id, vetro_id, canonical_ru_title, canonical_en_title, canonical_ru_author, canonical_en_author "
        "FROM vetro_id_registry WHERE work_id < 0 OR canonical_ru_author IS NOT NULL OR canonical_en_author IS NOT NULL"
    ):
        if wid is not None and (wid < 0 or norm(ra) in ah_ru_n or norm(ea) in ah_en_n):
            hit = (wid, vid)
            if ra and rt:
                reg_ru[(norm(ra), norm(rt))] = hit
                reg_ru[(norm(ra), norm(strip_parens(rt)))] = hit
            if ea and et:
                reg_en[(norm(ea), norm(et))] = hit

    selected = {}
    by_ru, by_en, by_wid = {}, {}, {}
    n = 0
    for r in q.execute(f"SELECT {SUMMARY_COLS} FROM work_bilingual_summary WHERE ru_title IS NOT NULL OR en_title IS NOT NULL"):
        n += 1
        wid, ru_t, en_t, ru_a, en_a = r[0], r[1], r[2], r[3], r[4]
        vid = wid_to_vid.get(wid)
        if vid and (r[8] == "BILINGUAL_CORE" or vid in covers):
            selected[vid] = row_from_summary(r)
        na_ru, na_en = norm(ru_a), norm(en_a)
        if na_ru in ah_ru_n or na_en in ah_en_n:
            by_wid[wid] = r
            if ru_a and ru_t:
                by_ru[(na_ru, norm(ru_t))] = r
                by_ru[(na_ru, norm(strip_parens(ru_t)))] = r
            if en_a and en_t:
                by_en[(na_en, norm(en_t))] = r
    print(f"summary rows scanned: {n}; core+covered selected: {len(selected)}; aihand candidates: {len(by_wid)}")

    added = 0
    for a in aihand:
        ru_a, en_a = a.get("author_ru", "").strip(), a.get("author_en", "").strip()
        for rt, et in zip(a.get("books_ru", []), a.get("books_en", [])):
            rt, et = rt.strip(), et.strip()
            m = by_ru.get((norm(ru_a), norm(rt))) or by_en.get((norm(en_a), norm(et)))
            vid = wid_to_vid.get(m[0]) if m else None
            if not vid:
                reg = reg_ru.get((norm(ru_a), norm(rt))) or reg_en.get((norm(en_a), norm(et)))
                if reg:
                    vid = reg[1]
                    m = m or by_wid.get(reg[0])
            if not vid or vid in selected:
                continue
            base = row_from_summary(m) if m else {"year": None, "alts": [], "core": False, "restricted": False}
            base.update({"ru": rt, "en": et or None, "ra": ru_a or en_a, "ea": en_a or None})
            if "(" in rt:
                base["alts"] = [strip_parens(rt)] + base["alts"]
            selected[vid] = base
            added += 1
    print(f"aihand added: {added}; total: {len(selected)}")
    conn.close()
    return selected, covers


def to_rows(selected, covers):
    rows = []
    for vid, s in selected.items():
        cov = covers.get(vid)
        flags = (bookindex.FLAG_COVER if cov else 0) | (bookindex.FLAG_RESTRICTED if s["restricted"] else 0) \
            | (bookindex.FLAG_CORE if s["core"] else 0)
        alt = clean_alts(s["alts"], s["ru"], s["en"])
        x = extra_tokens(s["ru"], s["en"], s["ra"], s["ea"], alt)
        rows.append((int(vid), s["ru"], s["en"], s["ra"], s["ea"], alt, x, s["year"], flags, cov[1] if cov else 0))
    rows.sort(key=lambda r: r[0])
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--master", required=True)
    ap.add_argument("--aihand", required=True, nargs="+", help="aihand.txt и/или блоки демонов (harvested_blocks*.json)")
    ap.add_argument("--out", required=True)
    ap.add_argument("--prefix", default="", help="FTS4 prefix index lengths, e.g. '2,3' (+16 MiB); off by default")
    ap.add_argument("--no-zst", action="store_true", help="skip compression (experiments)")
    args = ap.parse_args()
    t0 = time.time()

    out = os.path.abspath(args.out)
    for d in ("index/base", "index/delta", "history"):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    manifest_path = os.path.join(out, "manifest.json")
    prev = json.load(open(manifest_path, encoding="utf-8")) if os.path.exists(manifest_path) else None
    version = (prev["version"] + 1) if prev else 1

    selected, covers = load_selection(args.master, args.aihand)
    rows = to_rows(selected, covers)

    raw_path = os.path.join(out, "index", "base", f"bookindex-v{version}.sqlite")
    if os.path.exists(raw_path):
        os.remove(raw_path)
    conn = bookindex.create(raw_path, args.prefix)
    bookindex.insert_rows(conn, rows)
    created = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    n_cov = sum(1 for r in rows if r[8] & bookindex.FLAG_COVER)
    bookindex.set_meta(conn, schema=bookindex.SCHEMA_VERSION, version=version, created_at=created,
                       total=len(rows), covers=n_cov)
    bookindex.finalize(conn)
    raw_bytes = os.path.getsize(raw_path)
    print(f"base v{version}: {len(rows)} books, {n_cov} covers, {raw_bytes / 1048576:.1f} MiB raw")

    base = {"version": version, "raw_bytes": raw_bytes, "raw_sha256": sha256_file(raw_path)}
    if not args.no_zst:
        zst_path = raw_path + ".zst"
        bookindex.compress_file(raw_path, zst_path)
        base.update({"url": f"index/base/bookindex-v{version}.sqlite.zst", "bytes": os.path.getsize(zst_path),
                     "sha256": sha256_file(zst_path)})
        print(f"  zstd: {base['bytes'] / 1048576:.1f} MiB")

    deltas = list(prev["deltas"]) if prev else []
    shutil.copyfile(raw_path, os.path.join(out, "history", f"bookindex-v{version}.sqlite"))
    if prev:
        old_hist = os.path.join(out, "history", f"bookindex-v{prev['version']}.sqlite")
        if os.path.exists(old_hist) and not args.no_zst:
            dpath = os.path.join(out, "index", "delta", f"delta-{prev['version']}-{version}.zst")
            st = bookindex.make_delta(old_hist, raw_path, dpath, prev["version"], version)
            deltas.append({"from": prev["version"], "to": version,
                           "url": f"index/delta/delta-{prev['version']}-{version}.zst",
                           "bytes": os.path.getsize(dpath), "sha256": sha256_file(dpath), **st})
            print(f"  delta {prev['version']}->{version}: {st['upserts']} upserts, {st['deletes']} deletes, "
                  f"{os.path.getsize(dpath)} bytes")
        deltas = deltas[-MAX_DELTA_CHAIN:]

    manifest = {
        "schema": bookindex.SCHEMA_VERSION, "format": "vetro_bookindex_v2", "version": version, "created_at": created,
        "base": base, "deltas": deltas,
        "stats": {"books": len(rows), "with_covers": n_cov},
        "api": f"{API_BASE}/v1", "covers": f"{API_BASE}/covers",
        "search": ["fts4_prefix", "fold_accents", "translit_ru_lat", "typo_vocab", "bilingual"],
    }
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    print(f"done in {time.time() - t0:.0f}s -> {manifest_path}")


if __name__ == "__main__":
    main()
