"""Metrics every MicroML model reports: classification, calibration, ranking, thresholds."""
from __future__ import annotations

import math

import numpy as np


def wilson_lower(successes: int, total: int, z: float = 1.96) -> float:
    if total == 0:
        return 0.0
    p = successes / total
    denom = 1 + z * z / total
    centre = p + z * z / (2 * total)
    margin = z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total))
    return (centre - margin) / denom


def binary_report(y: np.ndarray, p: np.ndarray, threshold: float) -> dict:
    y = np.asarray(y).astype(bool)
    pred = np.asarray(p) >= threshold
    tp = int((pred & y).sum())
    fp = int((pred & ~y).sum())
    fn = int((~pred & y).sum())
    tn = int((~pred & ~y).sum())
    precision = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    return {"threshold": float(threshold), "tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "accuracy": round((tp + tn) / max(1, len(y)), 5), "precision": round(precision, 5),
            "precision_lower95": round(wilson_lower(tp, tp + fp), 5), "recall": round(recall, 5),
            "f1": round(2 * precision * recall / (precision + recall), 5) if precision + recall else 0.0,
            "fpr": round(fp / (fp + tn), 6) if fp + tn else 0.0}


def threshold_for_precision(y: np.ndarray, p: np.ndarray, target: float, lower_bound: bool = True) -> float:
    """Smallest threshold whose precision (or its Wilson lower bound) still meets the target."""
    order = np.argsort(-p)
    ys = np.asarray(y)[order].astype(np.int64)
    ps = np.asarray(p)[order]
    tp = np.cumsum(ys)
    n = np.arange(1, len(ys) + 1)
    best = 1.0 + 1e-9
    last = np.flatnonzero(np.append(ps[1:] != ps[:-1], True))  # evaluate only where the score changes
    for i in last:
        value = wilson_lower(int(tp[i]), int(n[i])) if lower_bound else tp[i] / n[i]
        if value >= target:
            best = float(ps[i])
    return best


def best_f1_threshold(y: np.ndarray, p: np.ndarray) -> float:
    order = np.argsort(-p)
    ys = np.asarray(y)[order].astype(np.int64)
    ps = np.asarray(p)[order]
    tp = np.cumsum(ys)
    n = np.arange(1, len(ys) + 1)
    f1 = 2 * tp / (n + ys.sum())
    return float(ps[int(np.argmax(f1))])


def average_precision(y: np.ndarray, p: np.ndarray) -> float:
    order = np.argsort(-p, kind="stable")
    ys = np.asarray(y)[order].astype(np.float64)
    if ys.sum() == 0:
        return 0.0
    precision = np.cumsum(ys) / np.arange(1, len(ys) + 1)
    return float((precision * ys).sum() / ys.sum())


def roc_auc(y: np.ndarray, p: np.ndarray) -> float:
    y = np.asarray(y).astype(bool)
    ranks = np.empty(len(p), np.float64)
    order = np.argsort(p, kind="stable")
    sorted_p = np.asarray(p)[order]
    ranks[order] = np.arange(1, len(p) + 1)
    # average ranks over ties
    start = 0
    while start < len(p):
        end = start
        while end + 1 < len(p) and sorted_p[end + 1] == sorted_p[start]:
            end += 1
        if end > start:
            ranks[order[start:end + 1]] = (start + end + 2) / 2
        start = end + 1
    pos, neg = int(y.sum()), int((~y).sum())
    if pos == 0 or neg == 0:
        return 0.5
    return float((ranks[y].sum() - pos * (pos + 1) / 2) / (pos * neg))


def calibration(y: np.ndarray, p: np.ndarray, bins: int = 15) -> dict:
    y = np.asarray(y).astype(np.float64)
    p = np.asarray(p).astype(np.float64)
    edges = np.linspace(0, 1, bins + 1)
    which = np.clip(np.digitize(p, edges[1:-1]), 0, bins - 1)
    ece = 0.0
    mce = 0.0
    table = []
    for b in range(bins):
        mask = which == b
        if mask.any():
            gap = abs(float(p[mask].mean()) - float(y[mask].mean()))
            ece += gap * mask.mean()
            mce = max(mce, gap)
            table.append([round(float(p[mask].mean()), 4), round(float(y[mask].mean()), 4), int(mask.sum())])
    return {"ece": round(ece, 5), "mce": round(mce, 5), "brier": round(float(((p - y) ** 2).mean()), 6), "bins": table}


def fit_platt(y: np.ndarray, raw: np.ndarray, iterations: int = 200) -> tuple[float, float]:
    """Slope/intercept on the logit of `raw`, Newton's method on the validation set."""
    x = np.log(np.clip(raw, 1e-7, 1 - 1e-7) / (1 - np.clip(raw, 1e-7, 1 - 1e-7)))
    y = np.asarray(y).astype(np.float64)
    a, b = 1.0, 0.0
    for _ in range(iterations):
        z = 1 / (1 + np.exp(-(a * x + b)))
        g_a, g_b = ((z - y) * x).mean(), (z - y).mean()
        w = z * (1 - z) + 1e-9
        h_aa, h_ab, h_bb = (w * x * x).mean(), (w * x).mean(), w.mean()
        det = h_aa * h_bb - h_ab * h_ab
        if abs(det) < 1e-12:
            break
        step_a, step_b = (h_bb * g_a - h_ab * g_b) / det, (h_aa * g_b - h_ab * g_a) / det
        a, b = a - step_a, b - step_b
        if abs(step_a) + abs(step_b) < 1e-9:
            break
    return float(a), float(b)


def apply_platt(raw: np.ndarray, slope: float, intercept: float) -> np.ndarray:
    c = np.clip(raw, 1e-7, 1 - 1e-7)
    return 1 / (1 + np.exp(-(slope * np.log(c / (1 - c)) + intercept)))


def ranking_report(groups: list[tuple[np.ndarray, np.ndarray]], ks: tuple[int, ...] = (1, 3, 10)) -> dict:
    """groups: (relevance 0/1, score) per query. MRR, Recall@k (hit rate), nDCG@10."""
    mrr = 0.0
    hits = {k: 0 for k in ks}
    ndcg = 0.0
    n = 0
    for rel, score in groups:
        if rel.sum() == 0:
            continue
        n += 1
        order = np.argsort(-score, kind="stable")
        ranked = rel[order]
        first = int(np.flatnonzero(ranked)[0])
        mrr += 1 / (first + 1)
        for k in ks:
            hits[k] += int(ranked[:k].any())
        gains = ranked[:10] / np.log2(np.arange(2, 2 + len(ranked[:10])))
        ideal = np.sort(rel)[::-1][:10] / np.log2(np.arange(2, 2 + min(10, len(rel))))
        ndcg += gains.sum() / ideal.sum()
    out = {"queries": n, "mrr": round(mrr / max(1, n), 5), "ndcg@10": round(ndcg / max(1, n), 5)}
    for k in ks:
        out[f"recall@{k}"] = round(hits[k] / max(1, n), 5)
    return out


def multiclass_report(y: np.ndarray, prob: np.ndarray, names: list[str]) -> dict:
    pred = prob.argmax(axis=1)
    out = {"accuracy": round(float((pred == y).mean()), 5), "classes": {}}
    f1s = []
    for c, name in enumerate(names):
        tp = int(((pred == c) & (y == c)).sum())
        fp = int(((pred == c) & (y != c)).sum())
        fn = int(((pred != c) & (y == c)).sum())
        tn = int(((pred != c) & (y != c)).sum())
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        f1s.append(f1)
        out["classes"][name] = {"support": tp + fn, "precision": round(precision, 4), "recall": round(recall, 4),
                                "f1": round(f1, 4), "fpr": round(fp / (fp + tn), 5) if fp + tn else 0.0}
    out["macro_f1"] = round(float(np.mean(f1s)), 5)
    confidence = prob.max(axis=1)
    out["calibration"] = {k: v for k, v in calibration((pred == y), confidence).items() if k != "bins"}
    return out
