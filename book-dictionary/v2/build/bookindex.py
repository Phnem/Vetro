"""BookIndex V2 on-disk formats: the device index (SQLite/FTS4), delta packs, apply logic.

Why FTS4 and not FTS5: the app uses the platform SQLite, where FTS4 is guaranteed on every API
level and FTS5 is not. The table stores the display strings once (no second copy for search);
accent folding and Latin transliterations live in the extra token column `x`.

Device schema (user_version = 2):
  books  FTS4(ru, en, ra, ea, alt, x, y, f, cv)   docid = numeric vetro_id (000000771 -> 771)
         ru/en title, ra/ea author (ru/en), alt = alternative titles joined by ' | ',
         x = folded + transliterated tokens, y = year, f = flags, cv = cover revision
         (y, f, cv are notindexed)
  books_terms  fts4aux(books): the word list with document counts; the app uses it to fix typos
  meta   (k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID
Flags: 1 = has cover, 2 = restricted licence (redistribution_allowed = 0), 4 = bilingual core.

Delta pack = zstd(text). Line 1: {"from":a,"to":b,"rows":n}. Then one JSON array per line:
  [id, ru, en, ra, ea, alt, x, y, f, cv]  -> upsert,     [id] -> delete.
"""
import json
import sqlite3

import zstandard

SCHEMA_VERSION = 2
COLS = ("ru", "en", "ra", "ea", "alt", "x", "y", "f", "cv")
FLAG_COVER, FLAG_RESTRICTED, FLAG_CORE = 1, 2, 4


def create(path: str, prefix: str = "") -> sqlite3.Connection:
    """prefix="" (default) is deliberate: a prefix index costs +16 MiB for ~110k books and `term*`
    queries on the plain term dictionary are already millisecond-fast at this size.
    matchinfo=fts3 drops the per-document size table (-1.9 MiB); ranking is done in the app."""
    conn = sqlite3.connect(path)
    prefix_opt = f', prefix="{prefix}"' if prefix else ""
    conn.executescript(
        f"""
        PRAGMA page_size = 4096;
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        PRAGMA user_version = {SCHEMA_VERSION};
        CREATE VIRTUAL TABLE books USING fts4(
            ru, en, ra, ea, alt, x, y, f, cv,
            notindexed=y, notindexed=f, notindexed=cv,
            matchinfo=fts3,
            tokenize=unicode61{prefix_opt}
        );
        CREATE VIRTUAL TABLE books_terms USING fts4aux(books);
        CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID;
        """
    )
    return conn


def insert_rows(conn: sqlite3.Connection, rows) -> None:
    conn.executemany(
        "INSERT INTO books(docid, ru, en, ra, ea, alt, x, y, f, cv) VALUES (?,?,?,?,?,?,?,?,?,?)", rows
    )


def set_meta(conn: sqlite3.Connection, **kv) -> None:
    conn.executemany("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", [(k, str(v)) for k, v in kv.items()])


def finalize(conn: sqlite3.Connection) -> None:
    conn.execute("INSERT INTO books(books) VALUES('optimize')")
    conn.commit()
    conn.execute("VACUUM")
    conn.close()


def read_all(path: str) -> dict:
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    out = {
        r[0]: tuple(r[1:])
        for r in conn.execute("SELECT docid, ru, en, ra, ea, alt, x, y, f, cv FROM books")
    }
    conn.close()
    return out


def read_meta(path: str) -> dict:
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    out = dict(conn.execute("SELECT k, v FROM meta"))
    conn.close()
    return out


def _norm_row(row: tuple) -> list:
    """Stable JSON form: None and '' are the same thing for the client."""
    return [("" if v is None else v) for v in row]


def make_delta(old_path: str, new_path: str, out_path: str, v_from: int, v_to: int) -> dict:
    old, new = read_all(old_path), read_all(new_path)
    lines = []
    upserts = deletes = 0
    for vid, row in new.items():
        if vid not in old or _norm_row(old[vid]) != _norm_row(row):
            lines.append(json.dumps([vid, *_norm_row(row)], ensure_ascii=False, separators=(",", ":")))
            upserts += 1
    for vid in old:
        if vid not in new:
            lines.append(json.dumps([vid]))
            deletes += 1
    header = json.dumps({"from": v_from, "to": v_to, "rows": len(lines)}, separators=(",", ":"))
    payload = (header + "\n" + "\n".join(lines) + "\n").encode("utf-8")
    with open(out_path, "wb") as f:
        f.write(zstandard.ZstdCompressor(level=19).compress(payload))
    return {"upserts": upserts, "deletes": deletes, "raw_bytes": len(payload)}


def apply_delta(db_path: str, delta_path: str) -> dict:
    """Reference implementation of what the Android client does (single transaction)."""
    with open(delta_path, "rb") as f:
        text = zstandard.ZstdDecompressor().stream_reader(f).read().decode("utf-8")
    it = iter(text.splitlines())
    header = json.loads(next(it))
    conn = sqlite3.connect(db_path)
    cur_version = int(dict(conn.execute("SELECT k, v FROM meta")).get("version", 0))
    if cur_version != header["from"]:
        conn.close()
        raise ValueError(f"delta is {header['from']}->{header['to']}, index is at {cur_version}")
    conn.execute("BEGIN")
    n = 0
    for line in it:
        if not line:
            continue
        arr = json.loads(line)
        conn.execute("DELETE FROM books WHERE docid = ?", (arr[0],))
        if len(arr) > 1:
            conn.execute(
                "INSERT INTO books(docid, ru, en, ra, ea, alt, x, y, f, cv) VALUES (?,?,?,?,?,?,?,?,?,?)", arr
            )
        n += 1
    conn.execute("INSERT OR REPLACE INTO meta(k, v) VALUES ('version', ?)", (str(header["to"]),))
    conn.commit()
    conn.close()
    return {"rows": n, "to": header["to"]}


def compress_file(src: str, dst: str, level: int = 19) -> None:
    cctx = zstandard.ZstdCompressor(level=level, threads=-1)
    with open(src, "rb") as fi, open(dst, "wb") as fo:
        cctx.copy_stream(fi, fo)


def decompress_file(src: str, dst: str) -> None:
    with open(src, "rb") as fi, open(dst, "wb") as fo:
        zstandard.ZstdDecompressor().copy_stream(fi, fo)
