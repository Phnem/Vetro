"""Experience AI: the two pieces that can honestly be prepared off the device.

genre space   Which of the app's 30 tags occur together, learned from genre statements of
              ~490k Wikidata works of every media kind. It smooths a user's genre profile:
              liking Isekai says something about Fantasy even with no Fantasy title rated.
              Measured by leave-one-tag-out ranking on held-out works.

taste ranker  An on-device online learner over the five signals the recommendation scorer
              already computes. It starts exactly at today's hand-set weights and moves only
              as the user's own swipes disagree with them. There is no public preference data
              under a licence this project accepts, so its step size and prior strength are
              chosen in simulation, and the report says so. Real evidence comes from the
              prequential log the app keeps in shadow mode.
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

import numpy as np
import pyarrow.compute as pc
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from catalog.genres import PROTOTYPES  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import normalize  # noqa: E402

TAGS = list(PROTOTYPES)
# NormalizedScorer.kt: genre, relation, quality, feedback, novelty
PRIOR = [0.30, 0.35, 0.15, 0.15, 0.05]
SIGNALS = ["genre", "relation", "quality", "feedback", "novelty"]
MISSING_RATE = [0.05, 0.40, 0.10, 0.70, 0.15]
SCALE = 6.0  # logit scale: a full-range signal with weight 1 moves the logit by 6


def genre_space(corpus: Path) -> tuple[np.ndarray, dict]:
    exact = {normalize(t): tag for tag, texts in PROTOTYPES.items() for t in texts}
    genre_tag: dict[str, int] = {}
    for q, labels in json.loads((corpus / "genres.json").read_text(encoding="utf-8")).items():
        tags = {exact[normalize(t)] for lang, t in labels if lang in ("en", "ru") and normalize(t) in exact}
        if len(tags) == 1:
            genre_tag[q] = TAGS.index(next(iter(tags)))
    table = pq.read_table(corpus / "entities.parquet", columns=["kind", "stage", "genres"])
    table = table.filter(pc.greater(pc.list_value_length(table["genres"]), 0))
    sets = {"train": [], "test": []}
    kinds = collections.Counter()
    for kind, stage, genres in zip(table["kind"].to_pylist(), table["stage"].to_pylist(), table["genres"].to_pylist()):
        tags = sorted({genre_tag[g] for g in genres if g in genre_tag})
        if not tags:
            continue
        (sets["test"] if stage in ("validation", "test") else sets["train"]).append(tags)
        kinds[kind] += 1
    n = len(TAGS)
    count = np.zeros(n)
    pair = np.zeros((n, n))
    for tags in sets["train"]:
        for a in tags:
            count[a] += 1
            for b in tags:
                if a != b:
                    pair[a, b] += 1
    total = len(sets["train"])
    # Shrunk signed PMI: association beyond popularity; thin evidence is pulled towards zero.
    expected = np.outer(count, count) / max(total, 1)
    pmi = np.clip(np.log((pair + 1e-9) / (expected + 1e-9)), -3, 3)
    sim = np.round(pmi * (pair / (pair + 20.0)), 4)
    np.fill_diagonal(sim, 0.0)
    prior = np.log((count + 1) / (count.sum() + n))

    def leave_one_out(matrix: np.ndarray, offset: np.ndarray) -> dict:
        groups = []
        for tags in sets["test"]:
            if len(tags) < 2:
                continue
            for held in tags:
                rest = [t for t in tags if t != held]
                score = matrix[rest].sum(axis=0).astype(np.float64) + offset
                score[rest] = -1e9
                rel = np.zeros(n)
                rel[held] = 1
                groups.append((rel, score))
        return M.ranking_report(groups, ks=(1, 3, 5))

    nothing = np.zeros((n, n))
    report = {"tags": TAGS, "works_with_tags": {"train": len(sets["train"]), "held_out": len(sets["test"])},
              "by_kind": dict(kinds), "genre_items_mapped": len(genre_tag),
              "tag_counts": {TAGS[i]: int(count[i]) for i in np.argsort(-count)},
              "leave_one_out": {"genre_space": leave_one_out(sim, prior), "baseline_popularity": leave_one_out(nothing, prior),
                                "association_only": leave_one_out(sim, np.zeros(n))},
              "strongest_links": sorted(((TAGS[a], TAGS[b], float(sim[a, b])) for a in range(n) for b in range(a + 1, n) if sim[a, b] > 0),
                                        key=lambda t: -t[2])[:15]}
    report["prior"] = np.round(prior, 4).tolist()
    return sim, report


def simulate(rate: float, strength: float, users: int, events: int, drift: float, rng: np.random.Generator) -> np.ndarray:
    """Mean AUC of (learned, prior) at checkpoints, on fresh candidates, over simulated users."""
    prior = np.asarray(PRIOR) * SCALE
    checkpoints = [0, 10, 25, 50, 100, 200, 400]
    out = np.zeros((len(checkpoints), 2))
    dim = len(PRIOR) * 2 + 1

    def draw(n: int) -> np.ndarray:
        values = rng.beta(2, 2, size=(n, len(PRIOR)))
        missing = rng.random((n, len(PRIOR))) < np.asarray(MISSING_RATE)
        values[missing] = 0.0
        return np.concatenate([values, missing.astype(float), np.ones((n, 1))], axis=1)

    w0 = np.concatenate([prior, np.zeros(len(PRIOR)), [-prior.sum() / 2]])
    for _ in range(users):
        true = w0.copy()
        true[:len(PRIOR)] *= np.exp(rng.normal(0, drift, len(PRIOR)))       # this user's real priorities
        true[len(PRIOR):-1] = rng.normal(0, 0.4 * drift, len(PRIOR))        # what a missing signal means to them
        true[-1] = -true[:len(PRIOR)].sum() / 2 + rng.normal(0, 0.5 * drift)
        w = w0.copy()
        test = draw(400)
        y_test = rng.random(400) < 1 / (1 + np.exp(-(test @ true)))
        if y_test.all() or not y_test.any():
            continue
        seen = 0
        for c, target in enumerate(checkpoints):
            while seen < target:
                x = draw(1)[0]
                y = float(rng.random() < 1 / (1 + np.exp(-(x @ true))))
                p = 1 / (1 + np.exp(-(x @ w)))
                w -= rate * ((p - y) * x + strength * (w - w0))
                seen += 1
            out[c, 0] += M.roc_auc(y_test, test @ w)
            out[c, 1] += M.roc_auc(y_test, test @ w0)
    return out / users


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/experience")
    parser.add_argument("--users", type=int, default=400)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    sim, space_report = genre_space(args.corpus)
    (args.output / "genre_space.json").write_text(json.dumps({"tags": TAGS, "prior": space_report["prior"], "matrix": sim.tolist()}), encoding="utf-8")

    grid = []
    for rate in (0.01, 0.03, 0.1):
        for strength in (0.02, 0.1, 0.3):
            rng = np.random.default_rng(20261110)
            typical = simulate(rate, strength, args.users, 400, 0.6, rng)   # users who differ from the default
            matched = simulate(rate, strength, args.users, 400, 0.0, rng)   # users the default already fits
            grid.append({"rate": rate, "strength": strength,
                         "typical_gain_at_50": float(typical[3, 0] - typical[3, 1]),
                         "typical_gain_at_200": float(typical[5, 0] - typical[5, 1]),
                         "matched_loss_at_50": float(matched[3, 1] - matched[3, 0]),
                         "matched_loss_at_200": float(matched[5, 1] - matched[5, 0]),
                         "curve_typical": np.round(typical, 4).tolist(), "curve_matched": np.round(matched, 4).tolist()})
    # Do no harm first: the default-fitting user may lose at most 0.01 AUC; among those, the best gain at 50 swipes.
    safe = [g for g in grid if g["matched_loss_at_50"] <= 0.003 and g["matched_loss_at_200"] <= 0.003] or         [min(grid, key=lambda g: g["matched_loss_at_200"])]
    best = max(safe, key=lambda g: g["typical_gain_at_50"])
    taste = {"signals": SIGNALS, "prior": PRIOR, "scale": SCALE, "learning_rate": best["rate"], "prior_strength": best["strength"],
             "min_events": 10, "note": "hyper-parameters selected in simulation; verify with the on-device prequential log"}
    (args.output / "taste.json").write_text(json.dumps(taste, indent=1), encoding="utf-8")
    report = {"genre_space": space_report, "taste": {"selected": best, "checkpoints": [0, 10, 25, 50, 100, 200, 400],
                                                     "columns": ["learned_auc", "prior_auc"], "grid": grid,
                                                     "evidence": "SIMULATION ONLY",
                                                     "verdict": "useful" if best["typical_gain_at_50"] >= 0.01 else
                                                     "no measurable benefit in simulation; keep in shadow until the device log says otherwise"}}
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"works": space_report["works_with_tags"], "by_kind": space_report["by_kind"],
                      "loo": space_report["leave_one_out"], "links": space_report["strongest_links"][:8],
                      "taste": {k: best[k] for k in ("rate", "strength", "typical_gain_at_50", "typical_gain_at_200",
                                                     "matched_loss_at_50", "matched_loss_at_200")}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
