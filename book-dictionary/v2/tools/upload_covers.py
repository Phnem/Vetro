"""Parallel, resumable cover upload through the temporary uploader Worker (tools/books-api/uploader).

    python -I upload_covers.py --base https://vetro-books-uploader.<account>.workers.dev ^
        --token-file ..\\out\\server\\.uploader-token --lists ..\\out\\server\\covers-small.json ..\\out\\server\\covers-large.json

Lists are JSON arrays of {"key": "covers/small/000000005.avif", "file": "D:\\\\...\\\\5.avif"}. Finished keys are appended to
covers-uploaded.txt next to the first list, so a re-run only sends what is missing. The token never goes to the console.
"""
import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ap = argparse.ArgumentParser()
ap.add_argument("--base", required=True)
ap.add_argument("--token-file", required=True)
ap.add_argument("--lists", nargs="+", required=True)
ap.add_argument("--threads", type=int, default=48)
ap.add_argument("--skip-first", type=int, default=0, help="skip this many items of the first list (known to be uploaded)")
args = ap.parse_args()

token = open(args.token_file, encoding="utf-8").read().strip()
base = args.base.rstrip("/")
done_path = os.path.join(os.path.dirname(os.path.abspath(args.lists[0])), "covers-uploaded.txt")
done = set(open(done_path, encoding="utf-8").read().split()) if os.path.exists(done_path) else set()

items = []
for n, path in enumerate(args.lists):
    with open(path, encoding="utf-8") as f:
        lst = json.load(f)
    if n == 0:
        lst = lst[args.skip_first:]
    items += [i for i in lst if i["key"] not in done]
total = len(items)
print(f"{total} files to upload ({len(done)} already done)", flush=True)

lock = threading.Lock()
state = {"ok": 0, "failed": 0}
done_file = open(done_path, "a", encoding="utf-8")
t0 = time.time()


def put(item):
    with open(item["file"], "rb") as f:
        data = f.read()
    req = urllib.request.Request(
        f"{base}/{item['key']}", data=data, method="PUT",
        # Cloudflare rejects the default "Python-urllib" User-Agent with error 1010.
        headers={"Authorization": f"Bearer {token}", "Content-Type": "image/avif", "User-Agent": "vetro-cover-uploader/1"},
    )
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                if r.status == 200:
                    return True
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 403, 405):
                raise SystemExit(f"fatal: HTTP {e.code} for {item['key']}")
        except Exception:
            pass
        time.sleep(min(2 ** attempt, 20))
    return False


def work(item):
    ok = put(item)
    with lock:
        if ok:
            state["ok"] += 1
            done_file.write(item["key"] + "\n")
            done_file.flush()
        else:
            state["failed"] += 1
        n = state["ok"] + state["failed"]
        if n % 500 == 0 or n == total:
            rate = n / max(time.time() - t0, 1)
            print(f"{n}/{total}  ok={state['ok']} failed={state['failed']}  {rate:.0f}/s", flush=True)


with ThreadPoolExecutor(args.threads) as pool:
    list(pool.map(work, items))
print(f"done: ok={state['ok']} failed={state['failed']}", flush=True)
sys.exit(1 if state["failed"] else 0)
