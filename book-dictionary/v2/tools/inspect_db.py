"""Read-only schema/row-count dump of a SQLite DB. Usage: python -I inspect_db.py <db> [table ...]"""
import sqlite3
import sys

sys.stdout.reconfigure(encoding="utf-8")
db = sys.argv[1]
only = sys.argv[2:]
c = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
print("sqlite", sqlite3.sqlite_version)
skip = ("_data", "_idx", "_docsize", "_config", "_content")
rows = c.execute("select name,type,sql from sqlite_master where type in ('table','view') order by name").fetchall()
for name, typ, sql in rows:
    if name.startswith("sqlite_") or name.endswith(skip):
        continue
    if only and name not in only:
        continue
    if only:
        print(sql)
        for r in c.execute(f'select * from "{name}" limit 3'):
            print("  ", r)
    try:
        n = c.execute(f'select count(*) from "{name}"').fetchone()[0]
    except Exception as e:  # virtual tables without module
        n = f"err {e}"
    print(typ, name, n)
