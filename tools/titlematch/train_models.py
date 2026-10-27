"""Train alternatives on train; choose epochs/calibration/threshold on validation only."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import time

import numpy as np

from features import MAX_BYTES,NAMES,VERSION
from fetch_resources import ROOT,digest
from model_metrics import apply_calibration,calibrate,metrics,select_threshold


def predict_charcnn(model,tokens,data,batch_size=1024):
    import torch
    predictions=[]
    model.eval()
    with torch.inference_mode():
        for offset in range(0,len(data["labels"]),batch_size):
            idx=data["indices"][offset:offset+batch_size]
            a=torch.from_numpy(tokens[idx[:,0]].astype(np.int64))
            b=torch.from_numpy(tokens[idx[:,1]].astype(np.int64))
            features=torch.from_numpy(data["features"][offset:offset+batch_size])
            predictions.append(torch.sigmoid(model(a,b,features)).numpy())
    return np.concatenate(predictions)


def train(kind,pairs,output,epochs=8):
    from sklearn.metrics import log_loss
    seed=20261027
    np.random.seed(seed)
    started=time.time()
    output.mkdir(parents=True,exist_ok=True)
    train_data=np.load(pairs/"train.npz")
    validation=np.load(pairs/"validation.npz")
    history=[]
    if kind=="lightgbm":
        import lightgbm as lgb
        from export_forest import export_forest,load_forest,portable_predict
        model=lgb.LGBMClassifier(n_estimators=1000,num_leaves=31,max_depth=8,learning_rate=.035,
            min_child_samples=100,reg_lambda=5.,n_jobs=6,random_state=seed,deterministic=True,force_col_wise=True,verbosity=-1)
        model.fit(train_data["features"],train_data["labels"],
            eval_set=[(validation["features"],validation["labels"])],eval_metric="binary_logloss",
            callbacks=[lgb.early_stopping(70),lgb.log_evaluation(100)])
        booster=model.booster_
        booster.save_model(str(output/"model.txt"))
        scores=model.predict_proba(validation["features"])[:,1]
        forest=export_forest(booster,output/"model.vtmf")
        portable=portable_predict(load_forest(output/"model.vtmf"),validation["features"][:512])
        error=float(np.max(np.abs(portable-scores[:512])))
        if error>1e-10:raise ValueError("Portable forest parity failed")
        model_info={"best_iteration":model.best_iteration_,"portable":forest,"portable_max_error":error}
    else:
        import torch
        from charcnn import CharCNN
        torch.manual_seed(seed);torch.set_num_threads(6)
        tokens=np.load(pairs/"tokens.npy",mmap_mode="r")
        model=CharCNN();optimizer=torch.optim.AdamW(model.parameters(),lr=.002,weight_decay=.01)
        loss_fn=torch.nn.BCEWithLogitsLoss()
        best=float("inf");stale=0
        indices=train_data["indices"];features=train_data["features"];labels=train_data["labels"]
        rng=np.random.default_rng(seed)
        for epoch in range(epochs):
            start=time.time();model.train();total=0.
            permutation=rng.permutation(len(labels))
            for offset in range(0,len(labels),512):
                selection=permutation[offset:offset+512];idx=indices[selection]
                a=torch.from_numpy(tokens[idx[:,0]].astype(np.int64));b=torch.from_numpy(tokens[idx[:,1]].astype(np.int64))
                f=torch.from_numpy(features[selection]);y=torch.from_numpy(labels[selection].astype(np.float32))
                optimizer.zero_grad(set_to_none=True)
                loss=loss_fn(model(a,b,f),y);loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(),5.)
                optimizer.step();total+=float(loss.detach())*len(selection)
            scores=predict_charcnn(model,tokens,validation)
            current=float(log_loss(validation["labels"],np.clip(scores,1e-7,1-1e-7)))
            row={"epoch":epoch+1,"train_loss":total/len(labels),"validation_loss":current,"seconds":time.time()-start}
            history.append(row);print(json.dumps(row),flush=True)
            if current<best-1e-4:
                best=current;stale=0;torch.save(model.state_dict(),output/"weights.pt")
            else:
                stale+=1
                if stale>=3:break
        model.load_state_dict(torch.load(output/"weights.pt",weights_only=True))
        scores=predict_charcnn(model,tokens,validation)
        model_info={"parameters":sum(p.numel() for p in model.parameters()),"epochs":len(history),"history":history}
    calibration=calibrate(scores,validation["labels"])
    calibrated=apply_calibration(scores,calibration)
    policy=select_threshold(calibrated,validation["labels"])
    report={"model":kind,"seed":seed,"feature_version":VERSION,"feature_names":NAMES,
        "max_utf8_bytes":MAX_BYTES,"pairs_manifest_sha256":digest(pairs/"pairs.json"),
        "train_pairs":len(train_data["labels"]),"validation_pairs":len(validation["labels"]),
        "test_used":False,"calibration":calibration,"policy":policy,
        "validation_metrics":metrics(calibrated,validation["labels"],policy["threshold"]),
        "training_seconds":time.time()-started,"model_info":model_info}
    (output/"training.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8",newline="\n")
    np.save(output/"validation-probabilities.npy",calibrated,allow_pickle=False)
    print(json.dumps(report,indent=2),flush=True)


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind",choices=["lightgbm","charcnn"])
    parser.add_argument("--pairs",type=Path,default=ROOT/".cache/pairs-v1")
    parser.add_argument("--output",type=Path,default=ROOT/".cache/models-v1")
    parser.add_argument("--epochs",type=int,default=8)
    args=parser.parse_args()
    train(args.kind,args.pairs,args.output/args.kind,args.epochs)
