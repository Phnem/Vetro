"""Head-to-head evaluation of Identity AI against TitleMatch v2 and the legacy matcher.

A. TitleMatch v2's own pairs (validation while developing, test once at the end), restricted
   to entities that sit in this corpus' matching held-out split, so neither model saw them.
   Both models are scored exactly as v2 was: one title against one title.
B. Ranking: an encoder shortlist of 20 candidates per query, re-ordered by each scorer.
"""
from __future__ import annotations

import argparse
import collections
import importlib.util
import json
import random
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from identity.features import Encoder, Prepared, Record, pair_features  # noqa: E402
from identity.train import REL_NAMES, legacy_score, legacy_scores, load, strata_report  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import normalize  # noqa: E402


class Model:
    def __init__(self, folder: Path, encoder: Encoder):
        self.report = json.loads((folder / "report.json").read_text(encoding="utf-8"))
        self.same = lgb.Booster(model_file=str(folder / "same.txt"))
        self.encoder = encoder
        platt = self.report["same"]["platt"]
        self.slope, self.intercept = platt["slope"], platt["intercept"]
        self.thresholds = self.report["same"]["thresholds"]

    def score(self, pairs: list[tuple[Record, Record]]) -> np.ndarray:
        cache: dict[int, Prepared] = {}

        def prep(r: Record) -> Prepared:
            k = id(r)
            if k not in cache:
                cache[k] = Prepared(r, self.encoder)
            return cache[k]

        X = np.stack([pair_features(prep(a), prep(b)) for a, b in pairs])
        return M.apply_platt(self.same.predict(X, num_threads=6), self.slope, self.intercept)


def v2_compare(model: Model, corpus: Path, v2: Path, split: str) -> dict:
    stage = pq.read_table(corpus / "entities.parquet", columns=["qid", "stage"])
    stage_of = dict(zip(stage["qid"].to_pylist(), stage["stage"].to_pylist()))
    rows = pq.read_table(v2 / f"pairs-v2/{split}.parquet").to_pylist()
    v2_prob = np.load(v2 / f"models-v2/lightgbm/{split}-probabilities.npy")
    manifest_threshold = 0.9966730246494925  # docs/titlematch + released manifest.json
    keep = [i for i, r in enumerate(rows)
            if stage_of.get(r["entity_a"].split(":")[1]) == split and stage_of.get(r["entity_b"].split(":")[1]) == split]
    sub = [rows[i] for i in keep]
    y = np.asarray([r["label"] for r in sub])
    pv2 = v2_prob[keep]
    mine = model.score([(Record([r["a"]], r["kind_a"], r["year_a"]), Record([r["b"]], r["kind_b"], r["year_b"])) for r in sub])
    legacy = np.asarray([legacy_score(r["a"], r["b"]) for r in sub])
    out = {"split": split, "v2_pairs_total": len(rows), "pairs_compared": len(sub), "coverage": round(len(sub) / len(rows), 4),
           "positives": int(y.sum()),
           "v2_on_all_its_pairs": M.binary_report(np.asarray([r["label"] for r in rows]), v2_prob, manifest_threshold),
           "titlematch_v2": dict(M.binary_report(y, pv2, manifest_threshold), pr_auc=round(M.average_precision(y, pv2), 5)),
           "legacy_0.85": M.binary_report(y, legacy, 0.85),
           "identity_ai": {name: M.binary_report(y, mine, t) for name, t in model.thresholds.items()}}
    out["identity_ai"]["pr_auc"] = round(M.average_precision(y, mine), 5)
    # Matched operating point: the Identity AI threshold that makes no more false positives than v2 does here.
    order = np.sort(mine[y == 0])[::-1]
    allowed = int(out["titlematch_v2"]["fp"])
    matched = float(order[allowed]) + 1e-12 if allowed < len(order) else 0.0
    out["identity_ai"]["at_v2_false_positive_count"] = M.binary_report(y, mine, matched)
    strata: dict[str, dict] = {}
    for name in sorted({r["stratum"] for r in sub}):
        idx = np.asarray([i for i, r in enumerate(sub) if r["stratum"] == name])
        yy = y[idx]
        entry = {"n": int(len(idx))}
        for label, prob, t in (("v2", pv2, manifest_threshold), ("identity_p999", mine, model.thresholds["p999"]),
                               ("identity_p99", mine, model.thresholds["p99"]), ("identity_matched_fp", mine, matched),
                               ("legacy", legacy, 0.85)):
            hit = prob[idx] >= t
            entry[label] = round(float(hit[yy == 1].mean()), 4) if yy.any() else {"fp": int(hit.sum())}
        strata[name] = entry
    out["strata_recall_or_fp"] = strata
    return out


def ranking(model: Model, corpus: Path, stage: str, queries: int, seed: int = 11) -> dict:
    rng = random.Random(seed)
    table = pq.read_table(corpus / "entities.parquet", columns=["qid", "kind", "year", "stage", "group"])
    ents = table.filter(pc.equal(table["stage"], stage)).to_pylist()
    by_q = {e["qid"]: e for e in ents}
    titles = pq.read_table(corpus / "titles.parquet", columns=["qid", "lang", "text"])
    titles = titles.filter(pc.is_in(titles["qid"], value_set=pa.array(list(by_q))))
    per: dict[str, list[tuple[str, str]]] = collections.defaultdict(list)
    for q, lang, text in zip(titles["qid"].to_pylist(), titles["lang"].to_pylist(), titles["text"].to_pylist()):
        if len(text) <= 200 and normalize(text):
            per[q].append((lang, text))
    qids = sorted(per, key=lambda x: int(x[1:]))
    # Catalogue side: every entity shows up to three of its titles. Query side: other titles of a sampled entity.
    catalogue: dict[str, Record] = {}
    query: dict[str, Record] = {}
    for q in qids:
        items = list(per[q])
        rng.shuffle(items)
        seen: dict[str, str] = {}
        for lang, text in items:
            seen.setdefault(normalize(text), text)
        texts = list(seen.values())
        half = max(1, min(3, len(texts) - 1)) if len(texts) > 1 else len(texts)
        catalogue[q] = Record(texts[:half], by_q[q]["kind"], by_q[q]["year"])
        if len(texts) > half:
            query[q] = Record(texts[half:half + 2], by_q[q]["kind"], by_q[q]["year"])
    enc = model.encoder
    matrix = np.zeros((len(qids), enc.table.shape[1]), np.float32)
    for i, q in enumerate(qids):
        v = np.sum([enc.embed(normalize(t)) for t in catalogue[q].titles], axis=0)
        n = float(np.linalg.norm(v))
        matrix[i] = v / n if n else v
    chosen = rng.sample(sorted(query), min(queries, len(query)))
    groups = {"encoder": [], "identity_ai": [], "legacy": []}
    shortlist_hit = 0
    pairs, spans = [], []
    for q in chosen:
        v = np.sum([enc.embed(normalize(t)) for t in query[q].titles], axis=0)
        sims = matrix @ (v / max(float(np.linalg.norm(v)), 1e-9))
        top = np.argpartition(-sims, 20)[:20]
        top = top[np.argsort(-sims[top])]
        rel = np.asarray([float(qids[j] == q) for j in top])
        shortlist_hit += int(rel.any())
        groups["encoder"].append((rel, sims[top].astype(np.float64)))
        groups["legacy"].append((rel, np.asarray([max(legacy_score(a, b) for a in query[q].titles for b in catalogue[qids[j]].titles)
                                                  for j in top])))
        spans.append((len(pairs), rel))
        pairs.extend((query[q], catalogue[qids[j]]) for j in top)
    scores = model.score(pairs)
    for start, rel in spans:
        groups["identity_ai"].append((rel, scores[start:start + 20]))
    # Queries whose answer missed the shortlist stay in the denominator: recall@k is end-to-end.
    out = {"stage": stage, "queries": len(chosen), "catalogue": len(qids), "shortlist_recall@20": round(shortlist_hit / len(chosen), 4)}
    for name, g in groups.items():
        r = M.ranking_report(g)
        scale = r["queries"] / len(chosen)
        out[name] = {"mrr": round(r["mrr"] * scale, 4), "recall@1": round(r["recall@1"] * scale, 4),
                     "recall@3": round(r["recall@3"] * scale, 4), "ndcg@10": round(r["ndcg@10"] * scale, 4)}
    return out


def evaluate_pairs(model: Model, folder: Path, stage: str) -> dict:
    features, labels, relations, rows = load(folder.parent, stage)
    raw = model.same.predict(features, num_threads=6)
    prob = M.apply_platt(raw, model.slope, model.intercept)
    relation = lgb.Booster(model_file=str(folder / "relation.txt"))
    known = relations >= 0
    return {
        "pairs": len(labels),
        "at": {name: M.binary_report(labels, prob, threshold) for name, threshold in model.thresholds.items()},
        "legacy_0.85": M.binary_report(labels, legacy_scores(rows), 0.85),
        "pr_auc": M.average_precision(labels, prob),
        "calibration_raw": M.calibration(labels, raw),
        "calibration_platt": M.calibration(labels, prob),
        "relation": M.multiclass_report(relations[known], relation.predict(features[known], num_threads=6), REL_NAMES),
        "strata_at_p99": strata_report(rows, labels, prob, model.thresholds["p99"]),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--encoder", type=Path, required=True)
    parser.add_argument("--v2", type=Path, help="optional TitleMatch cache with pairs-v2 and models-v2")
    parser.add_argument("--split", choices=["validation", "test"], default="validation")
    parser.add_argument("--queries", type=int, default=3000)
    args = parser.parse_args()
    model = Model(args.model, Encoder(np.load(args.encoder).astype(np.float32)))
    report = {"pairs": evaluate_pairs(model, args.model, args.split),
              "ranking": ranking(model, args.corpus, args.split, args.queries)}
    if args.v2 is not None:
        report["v2_compatible"] = v2_compare(model, args.corpus, args.v2, args.split)
    (args.model / f"compare-{args.split}.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    c = report.get("v2_compatible")
    if c is None:
        print(json.dumps({"pairs": report["pairs"]["pairs"], "at": report["pairs"]["at"], "ranking": report["ranking"]}))
        return
    keys = ("precision", "recall", "f1", "fp", "fpr")
    print(json.dumps({"coverage": c["coverage"], "pairs": c["pairs_compared"],
                      "v2": {k: c["titlematch_v2"][k] for k in keys}, "legacy": {k: c["legacy_0.85"][k] for k in keys},
                      "identity": {n: {k: v[k] for k in keys} for n, v in c["identity_ai"].items() if isinstance(v, dict)},
                      "strata": c["strata_recall_or_fp"], "ranking": report["ranking"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
