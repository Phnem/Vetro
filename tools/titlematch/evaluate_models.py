"""Freeze winner using validation, then evaluate the sealed test exactly once per report."""
from __future__ import annotations

import argparse
from collections import Counter
from contextlib import closing
import json
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
from scipy.special import expit

from features import NAMES,legacy_score
from fetch_resources import ROOT,digest
from model_metrics import apply_calibration,calibrate,metrics,select_threshold


class LiteRunner:
    def __init__(self,path):
        import tensorflow as tf
        self.interpreter=tf.lite.Interpreter(model_path=str(path),num_threads=2)
        self.interpreter.allocate_tensors()
        self.inputs=self.interpreter.get_input_details();self.output=self.interpreter.get_output_details()[0]
        self.positions={i["index"]:next(v for k,v in {"title_a":0,"title_b":1,"features":2}.items() if k in i["name"]) for i in self.inputs}

    def predict(self,tokens,data):
        results=[]
        for idx,features in zip(data["indices"],data["features"]):
            values=[tokens[idx[0]][None,:].astype(np.int32),tokens[idx[1]][None,:].astype(np.int32),features[None,:]]
            for item in self.inputs:
                value=values[self.positions[item["index"]]]
                if item["dtype"]==np.int8:
                    scale,zero=item["quantization"]
                    value=np.clip(np.rint(value/scale)+zero,-128,127).astype(np.int8)
                self.interpreter.set_tensor(item["index"],value.astype(item["dtype"]))
            self.interpreter.invoke()
            raw=float(self.interpreter.get_tensor(self.output["index"]).item())
            scale,zero=self.output["quantization"]
            results.append(float(expit((raw-zero)*scale)))
        return np.asarray(results)


def finalize_charcnn(pairs: Path,models: Path):
    import torch
    from charcnn import CharCNN
    from export_charcnn import export
    torch.set_num_threads(4)
    folder=models/"charcnn"
    report=json.loads((folder/"training.json").read_text())
    model=CharCNN();model.load_state_dict(torch.load(folder/"weights.pt",weights_only=True));model.eval()
    train=np.load(pairs/"train.npz");validation=np.load(pairs/"validation.npz")
    tokens=np.load(pairs/"tokens.npy",mmap_mode="r")
    rng=np.random.default_rng(20261027)
    def vector(data,i):
        a,b=data["indices"][i]
        return tokens[a][None,:].astype(np.int32),tokens[b][None,:].astype(np.int32),data["features"][i:i+1]
    calibration=[vector(train,int(i)) for i in rng.choice(len(train["labels"]),512,replace=False)]
    verification=[vector(validation,int(i)) for i in rng.choice(len(validation["labels"]),256,replace=False)]
    exported=export(model,calibration,folder,verification)
    runner=LiteRunner(folder/"charcnn.int8.tflite")
    raw=runner.predict(tokens,validation)
    report["float_training_policy"]=report["policy"]
    report["calibration"]=calibrate(raw,validation["labels"])
    scores=apply_calibration(raw,report["calibration"])
    report["policy"]=select_threshold(scores,validation["labels"])
    report["validation_metrics"]=metrics(scores,validation["labels"],report["policy"]["threshold"])
    report["deployment_format"]="full-int8 TFLite logits"
    report["export"]=exported
    (folder/"deployment.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
    np.save(folder/"validation-int8-probabilities.npy",scores,allow_pickle=False)
    return report


def grouped_precision(scores,labels,rows,threshold,replicates=1500):
    groups=sorted({r[k] for r in rows for k in ("group_a","group_b")})
    positions={g:i for i,g in enumerate(groups)}
    a=np.asarray([positions[r["group_a"]] for r in rows]);b=np.asarray([positions[r["group_b"]] for r in rows])
    accepted=scores>=threshold
    tp=np.flatnonzero(accepted&(labels==1));fp=np.flatnonzero(accepted&(labels==0))
    rng=np.random.default_rng(20261027);values=[]
    for _ in range(replicates):
        weights=rng.poisson(1,len(groups))
        def count(indices):
            aa=a[indices];bb=b[indices]
            return np.where(aa==bb,weights[aa],weights[aa]*weights[bb]).sum()
        pos,neg=count(tp),count(fp)
        if pos+neg:values.append(pos/(pos+neg))
    return {"method":"pigeonhole Poisson bootstrap over both pair endpoint groups",
            "replicates":replicates,"groups":len(groups),
            "precision_lower95":float(np.quantile(values,.05)) if values else 0,
            "limit":"Generated pair distribution; no guarantee of production prevalence or label correctness"}


def evaluate(pairs: Path,models: Path,corpus: Path,test_context="first-heldout"):
    output=models/"evaluation.json"
    if output.exists():raise ValueError("Sealed test report already exists. Preserve it; use a new version for a new experiment.")
    reports={"lightgbm":json.loads((models/"lightgbm/training.json").read_text()),
             "charcnn":json.loads((models/"charcnn/deployment.json").read_text())}
    for r in reports.values():
        if r["test_used"] or r["pairs_manifest_sha256"]!=digest(pairs/"pairs.json"):
            raise ValueError("Invalid training provenance/test boundary")
    winner=max(reports,key=lambda name:(reports[name]["policy"]["validated_auto_threshold"],
        reports[name]["validation_metrics"]["recall"] or 0))
    (models/"selection.json").write_text(json.dumps({"winner":winner,"criterion":"validation strict-precision recall; test not read yet",
        "validation_reports":{k:digest(models/k/("deployment.json" if k=="charcnn" else "training.json")) for k in reports}},indent=2)+"\n",encoding="utf-8",newline="\n")
    # The sealed labels are first opened after model, export, calibration and winner are fixed.
    test=np.load(pairs/"test.npz");rows=pq.read_table(pairs/"test.parquet").to_pylist()
    labels=test["labels"];tokens=np.load(pairs/"tokens.npy",mmap_mode="r")
    import lightgbm as lgb
    raw={"lightgbm":lgb.Booster(model_file=str(models/"lightgbm/model.txt")).predict(test["features"]),
         "charcnn":LiteRunner(models/"charcnn/charcnn.int8.tflite").predict(tokens,test)}
    results={}
    for name,scores in raw.items():
        report=reports[name];p=apply_calibration(scores,report["calibration"]);threshold=report["policy"]["threshold"]
        result=metrics(p,labels,threshold);result["grouped_precision"]=grouped_precision(p,labels,rows,threshold)
        result["strata"]={}
        for stratum in sorted({r["stratum"] for r in rows}):
            mask=np.asarray([r["stratum"]==stratum for r in rows])
            result["strata"][stratum]=metrics(p[mask],labels[mask],threshold)
        result["domains"]={}
        for kind in sorted({r["kind_a"] for r in rows}):
            mask=np.asarray([r["kind_a"]==kind for r in rows]);result["domains"][kind]=metrics(p[mask],labels[mask],threshold)
        result["false_positive_examples"]=[{k:rows[i][k] for k in ("a","b","kind_a","stratum")} for i in np.flatnonzero((p>=threshold)&(labels==0))[:30]]
        np.save(models/name/"test-probabilities.npy",p,allow_pickle=False)
        results[name]=result
    legacy=np.asarray([legacy_score(r["a"],r["b"]) for r in rows])
    exact=test["features"][:,NAMES.index("normalized_equal")]
    result={"winner_locked_before_test":winner,"test_pairs":len(labels),"models":results,
            "evaluation_context":test_context,"prior_experiment_exposure":test_context!="first-heldout",
            "baselines":{"current_exact_normalization":metrics(exact,labels,.5),"legacy_fuzzy_structure":metrics(legacy,labels,.85)},
            "data_scope":"Wikidata CC0 alias identity plus synthetic typo perturbations and candidate negatives",
            "production_precision_certified":False,
            "reason":"Independent real deployment candidates and editorial label audit are separate release gates",
            "inputs":{"pairs_sha256":digest(pairs/"pairs.json"),"corpus_sha256":digest(corpus/"corpus.json")}}
    chosen=results[winner]
    result["generated_test_precision_gate_passed"]=(chosen["precision_lower95"]>=.999 and chosen["grouped_precision"]["precision_lower95"]>=.999)
    output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+"\n",encoding="utf-8",newline="\n")
    return result


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command",choices=["export-charcnn","test"])
    parser.add_argument("--pairs",type=Path,default=ROOT/".cache/pairs-v1")
    parser.add_argument("--models",type=Path,default=ROOT/".cache/models-v1")
    parser.add_argument("--corpus",type=Path,default=ROOT/".cache/corpus-v1")
    parser.add_argument("--test-context",choices=["first-heldout","post-correction-regression"],default="first-heldout")
    args=parser.parse_args()
    result=finalize_charcnn(args.pairs,args.models) if args.command=="export-charcnn" else evaluate(args.pairs,args.models,args.corpus,args.test_context)
    print(json.dumps(result,indent=2,ensure_ascii=True))
