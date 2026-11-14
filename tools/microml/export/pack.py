"""Assemble the MicroML packs for Android assets and the golden vectors for the Kotlin parity test.

Each pack is a folder with its own manifest, version and checksums. identity and catalog pin the
SHA-256 of the encoder table they were trained on: that hash is the interface. A pack whose inputs
did not change can be rebuilt or replaced alone; --only rebuilds just the named packs.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import random
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from catalog import names as N  # noqa: E402
from catalog.genres import PROTOTYPES, Normalizer  # noqa: E402
from catalog.train_parser import PersonLexicon  # noqa: E402
from catalog.train_text import LANGS  # noqa: E402
from export.formats import Forest, forest_bytes, quantize_rows, read_table, table_bytes  # noqa: E402
from identity.features import NAMES as IDENTITY_NAMES  # noqa: E402
from identity.features import VERSION as IDENTITY_VERSION  # noqa: E402
from identity.features import Encoder, Prepared, Record, pair_features  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import SPEC, bucket_ids, latin, markers, normalize, script, skeleton  # noqa: E402

LEXICON = {1: N.TECH, 5: N.SEASON_W, 6: N.EPISODE_W, 7: N.VOLUME_W, 8: N.CHAPTER_W, 9: N.PART_W, 10: N.NARRATOR_W,
           11: N.AUTHOR_W, 12: N.AUDIO_W, 19: N.OF_W}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class Pack:
    def __init__(self, out: Path, name: str, version: str):
        self.dir = out / "microml" / name
        self.dir.mkdir(parents=True, exist_ok=True)
        self.manifest = {"schema": 1, "pack": name, "version": version, "text_spec": SPEC, "files": {}, "models": {},
                         "license": {"weights": "MIT", "training_data": "CC0-1.0 (Wikidata structured data)"},
                         "status": "SHADOW"}

    def file(self, name: str, data: bytes) -> str:
        (self.dir / name).write_bytes(data)
        self.manifest["files"][name] = {"sha256": sha(data), "bytes": len(data)}
        return self.manifest["files"][name]["sha256"]

    def write(self) -> int:
        text = json.dumps(self.manifest, ensure_ascii=False, indent=1)
        (self.dir / "manifest.json").write_text(text, encoding="utf-8", newline="\n")
        return sum(f["bytes"] for f in self.manifest["files"].values()) + len(text.encode())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--encoder", type=Path, help="required when exporting core, identity or catalog")
    parser.add_argument("--identity", type=Path, help="required when exporting identity")
    parser.add_argument("--catalog", type=Path, default=ROOT / ".cache/catalog")
    parser.add_argument("--experience", type=Path, default=ROOT / ".cache/experience")
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/releases/current")
    parser.add_argument("--tag", default=datetime.date.today().isoformat())
    all_packs = {"core", "identity", "catalog", "experience"}
    parser.add_argument("--only", nargs="+", choices=sorted(all_packs), default=sorted(all_packs))
    args = parser.parse_args()
    assets = args.output / "android-assets"
    sizes: dict[str, int] = {}
    rng = random.Random(20261110)

    selected = set(args.only)
    if selected & {"core", "identity", "catalog"}:
        if args.encoder is None:
            parser.error("--encoder is required for core, identity and catalog")
        with np.load(args.encoder / "quantized.npz") as quantized:
            q = quantized["q"]
            encoder_bytes = table_bytes(q, quantized["code"], quantized["levels"])
        encoder_sha = sha(encoder_bytes)
        if "core" not in selected:
            installed = assets / "microml/core/encoder.vmle"
            if not installed.is_file() or sha(installed.read_bytes()) != encoder_sha:
                parser.error("the selected encoder must match the core installed in the output folder")
    if "identity" in selected and args.identity is None:
        parser.error("--identity is required to export identity")
    if "core" in args.only:
        encoder_report = json.loads((args.encoder / "report.json").read_text(encoding="utf-8"))
        core = Pack(assets, "core", f"core-{args.tag}")
        core.file("encoder.vmle", encoder_bytes)
        core.manifest["models"]["encoder"] = {"file": "encoder.vmle", "buckets": q.shape[0], "dim": q.shape[1]}
        core.manifest["metrics"] = {"validation_int8": encoder_report["validation_int8"]}
        sizes["core"] = core.write()

    if "identity" in args.only:
        identity_report = json.loads((args.identity / "report.json").read_text(encoding="utf-8"))
        same = lgb.Booster(model_file=str(args.identity / "same.txt"))
        relation = lgb.Booster(model_file=str(args.identity / "relation.txt"))
        same_bytes = forest_bytes(same.model_to_string(), "sigmoid")
        relation_bytes = forest_bytes(relation.model_to_string(), "softmax")
        if identity_report["features"] != IDENTITY_NAMES or identity_report["feature_version"] != IDENTITY_VERSION:
            raise ValueError("identity model uses an incompatible feature contract")
        pack = Pack(assets, "identity", f"identity-{args.tag}")
        pack.manifest["requires"] = {"core": encoder_sha}
        pack.file("same.vmlf", same_bytes)
        pack.file("relation.vmlf", relation_bytes)
        pack.manifest["models"]["same"] = {"file": "same.vmlf", "feature_version": IDENTITY_VERSION, "feature_names": IDENTITY_NAMES,
                                           "platt": identity_report["same"]["platt"], "thresholds": identity_report["same"]["thresholds"]}
        pack.manifest["models"]["relation"] = {"file": "relation.vmlf", "classes": ["SAME", "FRANCHISE", "ADAPTATION", "UNRELATED"]}
        pack.manifest["metrics"] = {k: identity_report[k]["at"] for k in ("validation", "test") if k in identity_report}
        sizes["identity"] = pack.write()

    if "catalog" in args.only:
        text_report = json.loads((args.catalog / "text/report.json").read_text(encoding="utf-8"))
        parser_report = json.loads((args.catalog / "parser/report.json").read_text(encoding="utf-8"))
        genre_report = json.loads((args.catalog / "genres/report.json").read_text(encoding="utf-8"))
        with np.load(args.catalog / "text/lang.npz") as lang:
            lang_bytes = table_bytes(*quantize_rows(lang["weights"]))
            bias = lang["bias"]
        with np.load(args.catalog / "text/person.npz") as person:
            person_bytes = table_bytes(*quantize_rows(person["odds"][:, None]))
        parser_model = lgb.Booster(model_file=str(args.catalog / "parser/parser.txt"))
        parser_bytes = forest_bytes(parser_model.model_to_string(), "softmax")
        # Match names.lex(): the first matching class wins.
        lexicon: dict[str, int] = {}
        for cls, words in LEXICON.items():
            for word in sorted(words):
                lexicon.setdefault(word, cls)
        assert parser_report["features"] == N.NAMES and parser_report["labels"] == N.LABELS
        pack = Pack(assets, "catalog", f"catalog-{args.tag}")
        pack.manifest["requires"] = {"core": encoder_sha}
        pack.file("parser.vmlf", parser_bytes)
        pack.file("person.vmle", person_bytes)
        pack.file("lang.vmle", lang_bytes)
        pack.manifest["models"]["parser"] = {"file": "parser.vmlf", "feature_version": N.VERSION, "labels": N.LABELS,
                                             "lexicon": lexicon, "person": "person.vmle"}
        pack.manifest["models"]["language"] = {"file": "lang.vmle", "labels": LANGS, "bias": bias.tolist()}
        pack.manifest["models"]["genres"] = {"prototypes": PROTOTYPES, "threshold": genre_report["threshold"]}
        pack.manifest["metrics"] = {"parser_challenge": parser_report.get("challenge", {}).get("model"),
                                    "language_validation_accuracy": text_report["lang_validation"]["accuracy"],
                                    "genres_challenge": {k: v for k, v in genre_report["challenge"].items() if k != "misses"}}
        sizes["catalog"] = pack.write()

    if "experience" in args.only:
        pack = Pack(assets, "experience", f"experience-{args.tag}")
        for model in ("genre_space", "taste"):
            name = f"{model}.json"
            contents = (args.experience / name).read_bytes()
            json.loads(contents)
            pack.file(name, contents)
            pack.manifest["models"][model] = {"file": name}
        sizes["experience"] = pack.write()

    if selected != all_packs:
        # A partial build cannot regenerate a fixture spanning every package.
        (args.output / "golden.json").unlink(missing_ok=True)
        print(json.dumps({"exported_bytes": sizes}))
        return

    # ---- golden vectors: everything the Kotlin runtime must reproduce
    encoder = Encoder(read_table(encoder_bytes))
    titles = pq.read_table(args.corpus / "titles.parquet", columns=["text", "lang"]).to_pylist()
    sample = [t["text"] for t in rng.sample(titles, 400) if len(t["text"]) <= 120]
    extra = ["Наруто: Ураганные хроники", "ジョジョの奇妙な冒険", "Shingeki no Kyojin Season 2", "Mob Psycho 100 III", "기생충", "第2期",
             "Сёдзё", "キャッチ", "Fullmetal Alchemist: Brotherhood", "Rocky IV", "İstanbul", "ΟΔΟΣ", "ß-Straße", "  ", "2001: A Space Odyssey"]
    buckets = q.shape[0]
    text_vectors = []
    for t in extra + sample[:150]:
        norm = normalize(t)
        seq, variants, years = markers(t)
        text_vectors.append({"text": t, "normalize": norm, "latin": latin(norm), "skeleton": skeleton(norm), "script": script(norm),
                             "buckets": bucket_ids(norm, buckets), "sequence": sorted(seq), "variants": sorted(variants),
                             "years": sorted(years), "embedding": [round(float(x), 6) for x in encoder.embed(norm)[:8]]})
    pairs_file = args.identity.parent / "pairs-validation.parquet"
    rows = rng.sample(pq.read_table(pairs_file).to_pylist(), 300)
    same_forest, relation_forest = Forest(same_bytes), Forest(relation_bytes)
    platt = identity_report["same"]["platt"]
    identity_vectors = []
    for r in rows:
        f = pair_features(Prepared(Record(r["titles_a"], r["kind_a"], r["year_a"], r["authors_a"]), encoder),
                          Prepared(Record(r["titles_b"], r["kind_b"], r["year_b"], r["authors_b"]), encoder))
        raw = float(same_forest.predict(f)[0])
        identity_vectors.append({"a": {"titles": r["titles_a"], "kind": r["kind_a"], "year": r["year_a"], "authors": r["authors_a"]},
                                 "b": {"titles": r["titles_b"], "kind": r["kind_b"], "year": r["year_b"], "authors": r["authors_b"]},
                                 "features": [float(x) for x in f],
                                 "same": float(M.apply_platt(np.asarray([raw]), platt["slope"], platt["intercept"])[0]),
                                 "relation": [float(x) for x in relation_forest.predict(f)]})
    lexicon_person = PersonLexicon(args.catalog / "text/person.npz")
    lexicon_person.odds = read_table(person_bytes)[:, 0]
    parser_forest = Forest(parser_bytes)
    challenge = json.loads((ROOT / "catalog/challenge_names.json").read_text(encoding="utf-8"))
    name_rows = pq.read_table(args.catalog / "names-validation.parquet", columns=["raw", "domain"]).to_pylist()
    parser_vectors = []
    for item in [{"raw": c["raw"], "domain": N.DOMAINS[c.get("domain", "unknown")]} for c in challenge] + rng.sample(name_rows, 120):
        text, tokens = N.tokenize(item["raw"])
        feats = N.features(tokens, item["domain"], lexicon_person)
        labels = [int(np.argmax(parser_forest.predict(np.asarray(f, np.float32)))) for f in feats]
        parser_vectors.append({"raw": item["raw"], "domain": item["domain"], "tokens": [t.text for t in tokens],
                               "first_features": [float(x) for x in feats[0]] if feats else [], "labels": labels,
                               "fields": N.assemble(text, tokens, labels)})
    lang_table = read_table(lang_bytes)
    lang_vectors = []
    for t in extra[:10] + sample[150:250]:
        ids = bucket_ids(normalize(t), lang_table.shape[0])
        if not ids:
            continue
        logits = lang_table[ids].sum(axis=0) / len(ids) + bias
        e = np.exp(logits - logits.max())
        lang_vectors.append({"title": t, "label": LANGS[int(np.argmax(e))], "probability": float(e.max() / e.sum())})
    normalizer = Normalizer(encoder, genre_report["threshold"])
    genre_vectors = [{"raw": raw, "tag": normalizer(raw)} for raw in
                     ["Science Fiction", "Комедия", "ホラー", "Action & Adventure", "Family", "Psychological", "мелодрама", "Krimi",
                      "Сверхъестественное", "Slice of Life", "Gourmet", "Abenteuer", "Sci-Fi & Fantasy", "военный", "Shounen"]]
    golden = {"text_spec": SPEC, "encoder_sha256": encoder_sha, "text": text_vectors, "identity": identity_vectors,
              "parser": parser_vectors, "language": lang_vectors, "genres": genre_vectors}
    (args.output / "golden.json").write_text(json.dumps(golden, ensure_ascii=False), encoding="utf-8", newline="\n")
    sizes["total"] = sum(sizes.values())
    (args.output / "sizes.json").write_text(json.dumps(sizes, indent=1), encoding="utf-8")
    print(json.dumps({"sizes_bytes": sizes, "golden": {k: len(v) for k, v in golden.items() if isinstance(v, list)}}))


if __name__ == "__main__":
    main()
