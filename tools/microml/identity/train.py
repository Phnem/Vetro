"""Train Identity AI's two forests: SAME-entity (binary) and RELATION (4 classes).

Thresholds and calibration come from the validation stage only. The test stage is read
only with --test, once, after every choice is frozen.
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pyarrow.parquet as pq
from rapidfuzz.distance import Levenshtein

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from identity.features import NAMES, VERSION, stabilize_features  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import normalize  # noqa: E402

REL_NAMES = ["SAME", "FRANCHISE", "ADAPTATION", "UNRELATED"]


def legacy_score(a: str, b: str) -> float:
    """sync/TitleMatcher.kt: exact, token subset, substring, then max(Jaccard, Levenshtein)."""
    x, y = normalize(a), normalize(b)
    if not x or not y:
        return 0.0
    if x == y:
        return 1.0
    ta, tb = set(x.split()), set(y.split())
    short, long = (ta, tb) if len(ta) <= len(tb) else (tb, ta)
    if short and (len(short) >= 2 or sum(map(len, short)) >= 5) and short <= long:
        return 0.92
    s, l = (x, y) if len(x) <= len(y) else (y, x)
    if len(s) >= 4 and s in l:
        return 0.87
    return max(len(ta & tb) / len(ta | tb), Levenshtein.normalized_similarity(x, y))


def legacy_scores(rows: list[dict]) -> np.ndarray:
    return np.asarray([max(legacy_score(a, b) for a in r["titles_a"] for b in r["titles_b"]) for r in rows])


def load(folder: Path, stage: str):
    data = np.load(folder / f"pairs-{stage}.npz")
    rows = pq.read_table(folder / f"pairs-{stage}.parquet").to_pylist()
    return stabilize_features(data["X"]), data["same"].astype(np.int32), data["rel"].astype(np.int32), rows


def strata_report(rows: list[dict], y: np.ndarray, p: np.ndarray, threshold: float) -> dict:
    out: dict[str, dict] = {}
    groups: dict[str, list[int]] = collections.defaultdict(list)
    for i, r in enumerate(rows):
        groups["stratum:" + r["stratum"]].append(i)
        groups["kind:" + r["kind_a"] + ("" if r["kind_a"] == r["kind_b"] else "+" + r["kind_b"])].append(i)
        if r["same"]:
            groups["positives:" + ("cross_script_only" if r["cross_only"] else "has_same_script")].append(i)
    for name, idx in sorted(groups.items()):
        ii = np.asarray(idx)
        positives = int(y[ii].sum())
        pred = p[ii] >= threshold
        entry = {"n": len(idx)}
        if positives:
            entry["recall"] = round(float(pred[y[ii] == 1].mean()), 4)
        if positives < len(idx):
            entry["fpr"] = round(float(pred[y[ii] == 0].mean()), 5)
            entry["fp"] = int(pred[y[ii] == 0].sum())
        out[name] = entry
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pairs", type=Path, default=ROOT / ".cache/identity")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--leaves", type=int, default=63)
    parser.add_argument("--rounds", type=int, default=1500)
    parser.add_argument("--lr", type=float, default=0.05)
    parser.add_argument("--drop", nargs="*", default=[], help="feature names to ablate")
    parser.add_argument("--seed", type=int, default=20261110)
    args = parser.parse_args()
    started = time.time()
    Xt, yt, rt, _ = load(args.pairs, "gbm")
    Xv, yv, rv, rows_v = load(args.pairs, "validation")
    keep = [i for i, n in enumerate(NAMES) if n not in set(args.drop)]
    names = [NAMES[i] for i in keep]
    base = {"learning_rate": args.lr, "num_leaves": args.leaves, "min_data_in_leaf": 40, "feature_fraction": 0.8,
            "bagging_fraction": 0.8, "bagging_freq": 1, "lambda_l2": 1.0, "seed": args.seed, "verbose": -1,
            "num_threads": 6, "deterministic": True, "force_row_wise": True}
    same = lgb.train(dict(base, objective="binary", metric="binary_logloss"),
                     lgb.Dataset(Xt[:, keep], yt, feature_name=names), args.rounds,
                     valid_sets=[lgb.Dataset(Xv[:, keep], yv, feature_name=names)],
                     callbacks=[lgb.early_stopping(80, verbose=False)])
    mt, mv = rt >= 0, rv >= 0
    relation = lgb.train(dict(base, objective="multiclass", num_class=4, metric="multi_logloss", num_leaves=31),
                         lgb.Dataset(Xt[mt][:, keep], rt[mt], feature_name=names), args.rounds,
                         valid_sets=[lgb.Dataset(Xv[mv][:, keep], rv[mv], feature_name=names)],
                         callbacks=[lgb.early_stopping(80, verbose=False)])
    raw_v = same.predict(Xv[:, keep], num_threads=6)
    slope, intercept = M.fit_platt(yv, raw_v)
    pv = M.apply_platt(raw_v, slope, intercept)
    thresholds = {"p999": M.threshold_for_precision(yv, pv, 0.999), "p99": M.threshold_for_precision(yv, pv, 0.99),
                  "f1": M.best_f1_threshold(yv, pv)}
    args.output.mkdir(parents=True, exist_ok=True)
    same.save_model(str(args.output / "same.txt"))
    relation.save_model(str(args.output / "relation.txt"))
    importance = sorted(zip(names, same.feature_importance("gain").round(1).tolist()), key=lambda t: -t[1])
    report = {"features": names, "feature_version": VERSION,
              "train_pairs": int(len(yt)), "validation_pairs": int(len(yv)),
              "same": {"trees": same.num_trees(), "platt": {"slope": slope, "intercept": intercept},
                       "thresholds": thresholds, "importance_top": importance[:15]},
              "relation": {"trees": relation.num_trees()}, "seconds": round(time.time() - started, 1)}

    def evaluate(tag: str, X, y, rel, rows) -> dict:
        raw = same.predict(X[:, keep], num_threads=6)
        p = M.apply_platt(raw, slope, intercept)
        legacy = legacy_scores(rows)
        out = {"pairs": int(len(y)), "positives": int(y.sum()),
               "roc_auc": round(M.roc_auc(y, p), 6), "pr_auc": round(M.average_precision(y, p), 6),
               "calibration_raw": {k: v for k, v in M.calibration(y, raw).items() if k != "bins"},
               "calibration_platt": M.calibration(y, p),
               "at": {name: M.binary_report(y, p, t) for name, t in thresholds.items()},
               "strata_at_p99": strata_report(rows, y, p, thresholds["p99"]),
               "strata_at_p999": strata_report(rows, y, p, thresholds["p999"]),
               "baseline_legacy_0.85": M.binary_report(y, legacy, 0.85),
               "baseline_legacy_strata": strata_report(rows, y, legacy, 0.85),
               "baseline_legacy_pr_auc": round(M.average_precision(y, legacy), 6)}
        mask = rel >= 0
        prob = relation.predict(X[mask][:, keep], num_threads=6)
        out["relation"] = M.multiclass_report(rel[mask], prob, REL_NAMES)
        np.save(args.output / f"{tag}-probabilities.npy", p.astype(np.float32))
        return out

    report["validation"] = evaluate("validation", Xv, yv, rv, rows_v)
    if args.test:
        Xs, ys, rs, rows_s = load(args.pairs, "test")
        report["test"] = evaluate("test", Xs, ys, rs, rows_s)
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    v = report["validation"]
    print(json.dumps({"trees": [same.num_trees(), relation.num_trees()], "roc_auc": v["roc_auc"], "pr_auc": v["pr_auc"],
                      "at": {k: {m: a[m] for m in ("precision", "recall", "f1", "fpr")} for k, a in v["at"].items()},
                      "legacy": {m: v["baseline_legacy_0.85"][m] for m in ("precision", "recall", "f1", "fpr")},
                      "relation": {"acc": v["relation"]["accuracy"], "macro_f1": v["relation"]["macro_f1"]},
                      "ece": [v["calibration_raw"]["ece"], v["calibration_platt"]["ece"]],
                      "importance": importance[:8]}))


if __name__ == "__main__":
    main()
