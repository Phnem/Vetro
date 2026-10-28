"""Build local distributable model/data pack; raw weights stay outside Git."""
from __future__ import annotations

import gzip
import argparse
import json
from pathlib import Path
import shutil
import zipfile

import lightgbm as lgb
import numpy as np
import pyarrow.parquet as pq

from features import NAMES,VERSION,encode,pair_features
from fetch_resources import ROOT,digest


def gzip_rows(path,rows):
    content="".join("\t".join(map(str,row))+"\n" for row in rows).encode()
    path.write_bytes(gzip.compress(content,compresslevel=9,mtime=0))


def prepare_feature_vectors(pairs,models):
    rows=pq.read_table(pairs/"validation.parquet").to_pylist()
    rng=np.random.default_rng(20261027);sample=[rows[int(i)] for i in rng.choice(len(rows),256,replace=False)]
    booster=lgb.Booster(model_file=str(models/"lightgbm/model.txt"));vectors=[]
    for r in sample:
        f=pair_features(r["a"],r["b"],r["kind_a"],r["kind_b"],r["year_a"],r["year_b"])
        vectors.append({"a":r["a"],"b":r["b"],"kind_a":r["kind_a"],"kind_b":r["kind_b"],
            "year_a":r["year_a"],"year_b":r["year_b"],"features":f.tolist(),"encoded_a":encode(r["a"]).tolist(),
            "raw_probability":float(booster.predict(f[None,:],num_threads=1)[0])})
    root=ROOT/".cache/benchmark-assets";root.mkdir(parents=True,exist_ok=True)
    (root/"feature-golden.json").write_text(json.dumps({"vectors":vectors},ensure_ascii=False),encoding="utf-8",newline="\n")
    shutil.copyfile(models/"lightgbm/model.vtmf",root/"model.vtmf")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models",type=Path,default=ROOT/".cache/models-v2")
    parser.add_argument("--corpus",type=Path,default=ROOT/".cache/corpus-v2")
    parser.add_argument("--pairs",type=Path,default=ROOT/".cache/pairs-v2")
    parser.add_argument("--release",default="v2")
    parser.add_argument("--features-only",action="store_true")
    args=parser.parse_args()
    if not args.release.isalnum():raise ValueError("Invalid release name")
    models=args.models;corpus=args.corpus
    if args.features_only:
        prepare_feature_vectors(args.pairs,models)
        return
    evaluation=json.loads((models/"evaluation.json").read_text(encoding="utf-8"))
    selection=json.loads((models/"selection.json").read_text())
    if selection["winner"]!="lightgbm" or evaluation["winner_locked_before_test"]!="lightgbm":
        raise ValueError("This mobile package supports the selected portable forest; selection changed")
    training=json.loads((models/"lightgbm/training.json").read_text())
    pairs_hash=digest(args.pairs/"pairs.json")
    if training["pairs_manifest_sha256"]!=pairs_hash or evaluation["inputs"]["pairs_sha256"]!=pairs_hash:
        raise ValueError("Training/evaluation/pair provenance mismatch")
    if evaluation["inputs"]["corpus_sha256"]!=digest(corpus/"corpus.json"):
        raise ValueError("Evaluated corpus changed; assign a new release version")
    root=ROOT/".cache/releases"/args.release;pack=root/"android-assets/titlematch/v1"
    pack.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(models/"lightgbm/model.vtmf",pack/"model.vtmf")
    entities=pq.read_table(corpus/"entities.parquet").to_pylist()
    aliases=pq.read_table(corpus/"aliases.parquet").to_pylist()
    ids=pq.read_table(corpus/"external_ids.parquet").to_pylist()
    # AAPT automatically decompresses and renames assets ending in .gz. Opaque names preserve bytes.
    gzip_rows(pack/"entities.bin",sorted((e["entity_id"],e["kind"],e["year"] or "",e["qid"]) for e in entities))
    gzip_rows(pack/"aliases.bin",sorted({(a["entity_id"].rsplit(":",1)[-1],a["search_key"],a["entity_id"]) for a in aliases}))
    gzip_rows(pack/"ids.bin",sorted((r["typed_id"],r["entity_id"]) for r in ids))
    for destination in (pack,ROOT/".cache/benchmark-assets/titlematch/v1",ROOT/".cache/releases/current/android-assets/titlematch/v1"):
        for previous in ("entities.tsv.gz","aliases.tsv.gz","ids.tsv.gz"):
            (destination/previous).unlink(missing_ok=True)
    manifest={"schema_version":1,"model_version":"vetro-titlematch-2026-10-27-"+args.release,"feature_version":VERSION,
        "feature_names":NAMES,"normalization":"nfkc-lower-words-v1","threshold":training["policy"]["threshold"],
        "calibration":training["calibration"],"selected_model":"LightGBM numeric forest",
        "status":"READY_FOR_GUARDED_SHADOW","learned_automatic_actions_enabled":False,
        "data_license":"CC0-1.0","model_license":"MIT","attribution":"Wikidata contributors; https://www.wikidata.org/wiki/Wikidata:Licensing",
        "corpus_sha256":digest(corpus/"corpus.json"),"pairs_sha256":digest(args.pairs/"pairs.json"),
        "test_report_sha256":digest(models/"evaluation.json"),
        "files":{p.name:{"sha256":digest(p),"bytes":p.stat().st_size} for p in sorted(pack.iterdir()) if p.name!="manifest.json"}}
    (pack/"manifest.json").write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8",newline="\n")
    # Golden feature/parity vectors are validation-only, not the sealed test.
    prepare_feature_vectors(args.pairs,models)
    benchmark_assets=ROOT/".cache/benchmark-assets/titlematch/v1"
    shutil.copytree(pack,benchmark_assets,dirs_exist_ok=True)
    shutil.copyfile(ROOT/"challenge.json",ROOT/".cache/benchmark-assets/challenge.json")
    shutil.copytree(pack,ROOT/".cache/releases/current/android-assets/titlematch/v1",dirs_exist_ok=True)
    archive=root/("vetro-titlematch-"+args.release+".zip")
    with zipfile.ZipFile(archive,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for p in sorted(pack.iterdir()):z.write(p,"android-assets/titlematch/v1/"+p.name)
        for name in ("weights.pt","charcnn.int8.tflite","export-report.json","deployment.json"):
            z.write(models/"charcnn"/name,"charcnn-alternative/"+name)
        z.write(models/"evaluation.json","evaluation.json")
        z.write(models/"selection.json","training/selection.json")
        for name in ("model.txt","training.json"):
            z.write(models/"lightgbm"/name,"training/lightgbm/"+name)
        z.write(models/"charcnn/training.json","training/charcnn/training.json")
        z.write(corpus/"corpus.json","training/corpus.json")
        z.write(args.pairs/"pairs.json","training/pairs.json")
        z.write(ROOT/"../../docs/titlematch/SOURCE_POLICY.json","SOURCE_POLICY.json")
        z.write(ROOT/"../../docs/titlematch/MODEL_CARD.md","MODEL_CARD.md")
        z.write(ROOT/"../../docs/titlematch/ANDROID_USAGE.md","ANDROID_USAGE.md")
        z.write(ROOT/"../../LICENSE","LICENSE")
        for p in sorted((ROOT/"../../app/src/main/java/com/example/myapplication/domain/titleidentity").glob("*.kt")):
            z.write(p,"android-sdk/"+p.name)
        for p in sorted((ROOT/"../../docs/titlematch").iterdir()):
            if p.is_file():z.write(p,"docs/titlematch/"+p.name)
        for p in sorted(ROOT.iterdir()):
            if p.is_file() and p.suffix in (".py",".txt",".md",".json"):
                z.write(p,"tools/titlematch/"+p.name)
        z.writestr("README.md","Vetro TitleMatch v2\n\nRead docs/titlematch/MODEL_CARD.md and docs/titlematch/ANDROID_USAGE.md.\nSelected Android assets are under android-assets/.\nKotlin SDK source is under android-sdk/.\nNo automatic learned hiding/writes; guarded shadow/manual ranking only.\n")
    snapshot=root/("wikidata-training-data-"+args.release+".zip")
    with zipfile.ZipFile(snapshot,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted((ROOT/".cache/wikidata-corpus").iterdir()):
            if p.is_file():z.write(p,"wikidata-corpus/"+p.name)
        for p in sorted(corpus.iterdir()):
            if p.suffix==".parquet" or p.name=="corpus.json":z.write(p,"training-corpus/"+p.name)
        z.write(args.pairs/"pairs.json","expected-pair-manifest.json")
        z.write(ROOT/"../../docs/titlematch/SOURCE_POLICY.json","SOURCE_POLICY.json")
        z.writestr("DATA_LICENSE.txt","Structured Wikidata data: CC0-1.0. Wikidata contributors.\nhttps://www.wikidata.org/wiki/Wikidata:Licensing\nIncludes frozen corrected corpus/group assignments for reproducibility.\nManami, cedya and Fribb data are not included.\n")
    checks="".join(digest(p)+"  "+p.name+"\n" for p in (archive,snapshot))
    (root/"SHA256SUMS").write_text(checks,encoding="utf-8",newline="\n")
    print(json.dumps({"archive":str(archive),"zip_bytes":archive.stat().st_size,"sha256":digest(archive),
                      "training_snapshot_bytes":snapshot.stat().st_size,"training_snapshot_sha256":digest(snapshot),
                      "pack_bytes":sum(p.stat().st_size for p in pack.iterdir()),"files":manifest["files"]},indent=2))


if __name__=="__main__":main()
