"""Audit downloaded snapshot structure; never generate identity labels or training data."""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import struct
import unicodedata

from fetch_resources import ROOT, digest, read_lock, target_path, verified


def search_key(value: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKC", value).casefold() if c.isalnum())


def anime_snapshot(path: Path) -> tuple[dict, set[str]]:
    stats = Counter()
    formats = Counter()
    ids = Counter()
    aliases = Counter()
    metadata = None
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            row = json.loads(line)
            if number == 1 and "title" not in row:
                metadata = row
                continue
            if not isinstance(row.get("title"), str) or not row["title"].strip():
                raise ValueError(f"{path.name}:{number}: missing title")
            sources = row.get("sources", [])
            synonyms = row.get("synonyms", [])
            if (not isinstance(sources, list) or not all(isinstance(s, str) for s in sources)
                    or not isinstance(synonyms, list) or not all(isinstance(s, str) for s in synonyms)):
                raise ValueError(f"{path.name}:{number}: unsupported sources/synonyms schema")
            stats["records"] += 1
            formats[row.get("type", "UNKNOWN")] += 1
            keys = {search_key(value) for value in [row["title"], *synonyms] if value.strip()}
            keys.discard("")
            aliases.update(keys)
            ids.update(set(sources))
            stats["title_and_synonym_strings"] += 1 + len(synonyms)
            stats["unique_alias_keys_within_records"] += len(keys)
            stats["without_source_ids"] += not sources
            stats["with_synonyms"] += bool(synonyms)
            stats["with_cyrillic_title_or_alias"] += any(
                "CYRILLIC" in unicodedata.name(c, "") for v in [row["title"], *synonyms] for c in v)
            stats["relationship_edges_not_identity"] += len(row.get("relatedAnime", []))
    if metadata is None or not stats["records"]:
        raise ValueError("Expected JSONL metadata and at least one record")
    report = {**stats, "formats": dict(sorted(formats.items())),
              "source_urls": len(ids), "source_urls_used_by_multiple_records": sum(v > 1 for v in ids.values()),
              "alias_keys_shared_by_multiple_records": sum(v > 1 for v in aliases.values()),
              "alias_collision_examples": sorted(k for k, v in aliases.items() if v > 1)[:15],
              "metadata": metadata}
    return report, set(ids)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=ROOT / "resources.lock.json")
    parser.add_argument("--cache-dir", type=Path, default=ROOT / ".cache")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache" / "audit.json")
    args = parser.parse_args()
    lock = read_lock(args.lock, args.cache_dir)
    bad = [r["path"] for r in lock["resources"]
           if not verified(r, target_path(args.cache_dir, r["path"]))]
    if bad:
        raise SystemExit("Run fetch/verify before audit; invalid files: " + ", ".join(bad))
    report = {"checked_at": datetime.now(timezone.utc).isoformat(),
              "lock_sha256": digest(args.lock), "verified_files": len(lock["resources"]),
              "verified_bytes": sum(r["bytes"] for r in lock["resources"]),
              "training_started": False, "production_enabled": False}
    snapshots = {}
    source_sets = {}
    for name in ("manami", "cedya"):
        snapshots[name], source_sets[name] = anime_snapshot(
            args.cache_dir / "data" / name / "anime-offline-database.jsonl")
    report["anime_snapshots"] = snapshots
    report["source_url_diff_not_entity_diff"] = {
        "shared": len(source_sets["manami"] & source_sets["cedya"]),
        "only_manami": len(source_sets["manami"] - source_sets["cedya"]),
        "only_cedya": len(source_sets["cedya"] - source_sets["manami"])}
    fribb = json.loads((args.cache_dir / "data/fribb/anime-list-full.json").read_text(encoding="utf-8"))
    if not isinstance(fribb, list):
        raise ValueError("Unexpected Fribb root; expected array")
    report["fribb"] = {"rows": len(fribb), "observed_fields": sorted({k for r in fribb for k in r}),
                       "usage": "quarantine; not approved training labels"}
    wiki = []
    for item in lock["resources"]:
        if item["source"] != "Wikidata":
            continue
        data = json.loads(target_path(args.cache_dir, item["path"]).read_text(encoding="utf-8"))
        for qid, entity in data["entities"].items():
            if str(entity["lastrevid"]) != item["revision"]:
                raise ValueError("Wikidata revision does not match lock")
            wiki.append({"qid": qid, "revision": entity["lastrevid"],
                         "label_en": entity.get("labels", {}).get("en", {}).get("value"),
                         "label_ru": entity.get("labels", {}).get("ru", {}).get("value"),
                         "label_languages": len(entity.get("labels", {})),
                         "alias_languages": len(entity.get("aliases", {}))})
    report["wikidata_samples"] = wiki
    potion_dir = args.cache_dir / "models/potion-2m"
    with (potion_dir / "model.safetensors").open("rb") as stream:
        header_size = struct.unpack("<Q", stream.read(8))[0]
        if header_size > 1_000_000:
            raise ValueError("Unexpected safetensors header size")
        tensors = json.loads(stream.read(header_size))
    report["model_resources"] = {
        "potion": {"config": json.loads((potion_dir / "config.json").read_text(encoding="utf-8")),
                   "tensor_headers": tensors, "inference_tested": False},
        "multilingual_minilm": {
            "onnx_bytes": (args.cache_dir / "models/multilingual-minilm/onnx/model_quint8_avx2.onnx").stat().st_size,
            "sentence_config": json.loads((args.cache_dir / "models/multilingual-minilm/sentence_bert_config.json").read_text(encoding="utf-8")),
            "inference_tested": False, "runtime_target": "offline desktop AVX2 baseline"}}
    report["usage_status_counts"] = dict(Counter(r["usage_status"] for r in lock["resources"]))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "anime_snapshots"}, indent=2, ensure_ascii=False))
    for name, stats in snapshots.items():
        print(name, json.dumps({k: v for k, v in stats.items() if k not in ("metadata", "alias_collision_examples")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
