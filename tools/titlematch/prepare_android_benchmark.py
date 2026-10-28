"""Prepare public validation vectors for a separate, offline benchmark APK."""
import json
import argparse
from pathlib import Path
import shutil

import lightgbm as lgb
import numpy as np

from evaluate_models import LiteRunner
from fetch_resources import ROOT,digest


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models",type=Path,default=ROOT/".cache/models-v2")
    parser.add_argument("--pairs",type=Path,default=ROOT/".cache/pairs-v2")
    args=parser.parse_args()
    models=args.models;pairs=args.pairs;out=ROOT/".cache/benchmark-assets"
    out.mkdir(parents=True,exist_ok=True)
    data=np.load(pairs/"validation.npz");tokens=np.load(pairs/"tokens.npy",mmap_mode="r")
    rng=np.random.default_rng(20261027);selection=rng.choice(len(data["labels"]),64,replace=False)
    sample={k:data[k][selection] for k in ("labels","features","indices")}
    forest=lgb.Booster(model_file=str(models/"lightgbm/model.txt")).predict(sample["features"])
    neural=LiteRunner(models/"charcnn/charcnn.int8.tflite").predict(tokens,sample)
    vectors=[]
    for i,(a,b) in enumerate(sample["indices"]):
        vectors.append({"features":sample["features"][i].tolist(),"title_a":tokens[a].astype(int).tolist(),
            "title_b":tokens[b].astype(int).tolist(),"forest_raw_probability":float(forest[i]),"neural_raw_probability":float(neural[i])})
    shutil.copyfile(models/"lightgbm/model.vtmf",out/"model.vtmf")
    shutil.copyfile(models/"charcnn/charcnn.int8.tflite",out/"charcnn.int8.tflite")
    report={"source":"public CC0 validation vectors","forest_sha256":digest(out/"model.vtmf"),
            "neural_sha256":digest(out/"charcnn.int8.tflite"),"vectors":vectors}
    (out/"vectors.json").write_text(json.dumps(report),encoding="utf-8",newline="\n")
    print("64 validation vectors prepared; no user collection data")


if __name__=="__main__":main()
