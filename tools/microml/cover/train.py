"""Train and export the stock-cover detector (LightGBM forest in the VMLF0002 format the app already reads).

    python tools/microml/cover/train.py --data tools/microml/.cache/cover --out tools/microml/.cache/cover/model

Writes cover_placeholder.vmlf and report.json (how many held-out real variants and real covers were classified how, and the
threshold). The threshold is picked so that no held-out real cover is flagged.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import lightgbm as lgb
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
from export.formats import Forest, forest_bytes  # noqa: E402
from features import FEATURES, SPEC  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--version", default="2026-11-18")
    ap.add_argument("--hard", help="hard negatives from mine_hard_negatives.py (npz with x): real covers, 4/5 train and 1/5 test")
    ap.add_argument("--min-threshold", type=float, default=0.97)
    args = ap.parse_args()
    d = np.load(os.path.join(args.data, "features.npz"))
    x, y, group, source = d["x"], d["y"], d["group"], d["source"]
    if args.hard:
        hx = np.load(args.hard)["x"]
        hard_test = np.random.default_rng(5).random(len(hx)) < 0.2
        x = np.concatenate([x, hx])
        y = np.concatenate([y, np.zeros(len(hx), dtype=y.dtype)])
        group = np.concatenate([group, np.where(hard_test, "test", "train")])
        source = np.concatenate([source, np.full(len(hx), "hard")])
        print(f"hard negatives: {len(hx)} ({int(hard_test.sum())} for the test)")
    train, test = group == "train", group == "test"
    print(f"train {int(train.sum())} (pos {int(y[train].sum())}), test {int(test.sum())} (pos {int(y[test].sum())})")

    params = dict(objective="binary", learning_rate=0.08, num_leaves=15, min_data_in_leaf=12, feature_fraction=0.5,
                  bagging_fraction=0.8, bagging_freq=1, lambda_l2=1.0, verbose=-1, seed=7, deterministic=True, num_threads=4)
    # Real covers are far more common than placeholders: weight the rare class so the forest does not ignore it.
    weight = np.where(y == 1, 6.0, 1.0)
    model = lgb.train(params, lgb.Dataset(x[train], y[train], weight=weight[train]), num_boost_round=120)

    blob = forest_bytes(model.model_to_string(), "sigmoid")
    forest = Forest(blob)
    probe = x[np.random.default_rng(1).choice(len(x), 200, replace=False)]
    ref = model.predict(probe)
    mine = np.array([forest.predict(v)[0] for v in probe])
    assert np.max(np.abs(ref - mine)) < 1e-4, "exported forest disagrees with LightGBM"

    p = np.array([forest.predict(v)[0] for v in x[test]])
    yt, st = y[test], source[test]
    real_neg = p[yt == 0]
    threshold = float(max(args.min_threshold, float(real_neg.max()) + 1e-3)) if len(real_neg) else args.min_threshold
    threshold = min(threshold, 0.995)
    flagged = p >= threshold
    report = {
        "spec": SPEC, "features": FEATURES, "version": args.version, "trees": len(forest.trees), "bytes": len(blob),
        "threshold": threshold,
        "test": {
            "real_variants": int(((yt == 1) & (st == "real")).sum()),
            "real_variants_found": int((flagged & (yt == 1) & (st == "real")).sum()),
            "augmented_variants": int(((yt == 1) & (st == "aug")).sum()),
            "augmented_found": int((flagged & (yt == 1) & (st == "aug")).sum()),
            "real_covers": int((yt == 0).sum()),
            "real_covers_flagged": int((flagged & (yt == 0)).sum()),
            "max_probability_on_real_covers": float(real_neg.max()) if len(real_neg) else None,
            "min_probability_on_placeholders": float(p[yt == 1].min()),
        },
        "sha256": hashlib.sha256(blob).hexdigest(),
    }
    os.makedirs(args.out, exist_ok=True)
    open(os.path.join(args.out, "cover_placeholder.vmlf"), "wb").write(blob)
    json.dump(report, open(os.path.join(args.out, "report.json"), "w"), indent=1)
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
