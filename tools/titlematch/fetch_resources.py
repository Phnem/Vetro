"""Fetch research resources from a reviewed lock. Python standard library only.

No model code is imported or executed. No resource is added to Android assets.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import time
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent
USER_AGENT = "Vetro-TitleMatch-Research/0.1 (https://github.com/Phnem/Vetro)"


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def target_path(cache: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if (not relative or "\\" in relative or ":" in relative or parts.is_absolute()
            or ".." in parts.parts or parts.as_posix() != relative):
        raise ValueError(f"Unsafe resource path: {relative!r}")
    target = cache.joinpath(*parts.parts).resolve()
    if not target.is_relative_to(cache.resolve()) or target == cache.resolve():
        raise ValueError(f"Resource outside cache: {relative!r}")
    return target


def read_lock(path: Path, cache: Path) -> dict:
    lock = json.loads(path.read_text(encoding="utf-8"))
    if lock.get("schema_version") != 1 or not lock.get("resources"):
        raise ValueError("Unsupported or empty resource lock")
    seen = set()
    for item in lock["resources"]:
        target = target_path(cache, item["path"])
        if target in seen:
            raise ValueError(f"Duplicate resource target: {item['path']}")
        seen.add(target)
        if urlparse(item["url"]).scheme != "https":
            raise ValueError("Resource URL must use HTTPS")
        if not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise ValueError("Missing SHA-256")
        if not isinstance(item["bytes"], int) or not 0 < item["bytes"] <= 200_000_000:
            raise ValueError("Invalid size or resource exceeds 200 MB limit")
        if not item.get("revision") or not item.get("usage_status"):
            raise ValueError("Missing provenance or usage status")
    return lock


def verified(item: dict, path: Path) -> bool:
    return (path.is_file() and path.stat().st_size == item["bytes"]
            and digest(path) == item["sha256"])


def download(item: dict, cache: Path) -> dict:
    target = target_path(cache, item["path"])
    if verified(item, target):
        return {"path": item["path"], "status": "verified_cached",
                "verified_at": datetime.now(timezone.utc).isoformat(),
                "file_modified_at": datetime.fromtimestamp(target.stat().st_mtime, timezone.utc).isoformat()}
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".part")
    for attempt in range(3):
        try:
            request = Request(item["url"], headers={"User-Agent": USER_AGENT})
            with urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                received = 0
                for block in iter(lambda: response.read(1024 * 1024), b""):
                    received += len(block)
                    if received > item["bytes"]:
                        raise ValueError("Download exceeds locked byte count")
                    output.write(block)
            if not verified(item, temporary):
                raise ValueError("SHA-256 or byte count mismatch")
            temporary.replace(target)
            return {"path": item["path"], "status": "downloaded_verified",
                    "downloaded_at": datetime.now(timezone.utc).isoformat(),
                    "verified_at": datetime.now(timezone.utc).isoformat()}
        except Exception:
            temporary.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    raise AssertionError("unreachable")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("fetch", "verify"))
    parser.add_argument("--lock", type=Path, default=ROOT / "resources.lock.json")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".cache")
    parser.add_argument("--jobs", type=int, choices=range(1, 5), default=3)
    args = parser.parse_args()
    lock = read_lock(args.lock, args.cache_dir)
    results = []
    if args.command == "fetch":
        args.cache_dir.mkdir(parents=True, exist_ok=True)
        with ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = {pool.submit(download, item, args.cache_dir): item
                       for item in lock["resources"]}
            for future in as_completed(futures):
                item = futures[future]
                try:
                    result = future.result()
                except Exception as error:
                    result = {"path": item["path"], "status": "failed", "error": str(error)}
                results.append(result)
                print(json.dumps(result), flush=True)
        report = {"lock_sha256": digest(args.lock),
                  "checked_at": datetime.now(timezone.utc).isoformat(),
                  "results": sorted(results, key=lambda result: result["path"])}
        (args.cache_dir / "download-report.json").write_text(
            json.dumps(report, indent=2) + "\n", encoding="utf-8")
    else:
        for item in lock["resources"]:
            ok = verified(item, target_path(args.cache_dir, item["path"]))
            result = {"path": item["path"], "status": "verified" if ok else "failed"}
            results.append(result)
            print(json.dumps(result), flush=True)
    failures = sum(result["status"] == "failed" for result in results)
    print(f"{len(results) - failures}/{len(results)} verified; {failures} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
