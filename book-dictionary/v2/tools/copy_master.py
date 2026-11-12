"""Consistent copy of the master DB (WAL-safe) via the SQLite online backup API.

The original is opened read-only and never modified. Usage:
    python -I copy_master.py <src.sqlite> <dst.sqlite>
"""
import os
import sqlite3
import sys
import time

src_path, dst_path = sys.argv[1], sys.argv[2]
if os.path.exists(dst_path):
    sys.exit(f"refusing to overwrite {dst_path}")
os.makedirs(os.path.dirname(os.path.abspath(dst_path)), exist_ok=True)

t0 = time.time()
src = sqlite3.connect(f"file:{src_path}?mode=ro", uri=True)
dst = sqlite3.connect(dst_path)


def progress(status, remaining, total):
    done = (total - remaining) * 100 // max(total, 1)
    print(f"\r{done}% ", end="", flush=True)


src.backup(dst, pages=8192, progress=progress)
dst.execute("PRAGMA journal_mode = DELETE")
dst.close()
src.close()
print(f"\ncopied {os.path.getsize(dst_path):,} bytes in {time.time() - t0:.0f}s -> {dst_path}")
