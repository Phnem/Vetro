"""Analyse the tables collected from consenting installs (tools/microml-collector).

Input is a D1 dump:  wrangler d1 export vetro-microml --remote --output dump.sql
Nothing in it names a title or a person. Four analyses, one report:

taste     robust aggregate of the on-device ranker weights, compared with today's prior.
pairs     the current Identity model scored on real, user-labelled pairs: the evidence the
          offline corpus could not give (precision at the shipped threshold, calibration).
genres    unmapped genre strings reported by >=20 installs, with the normaliser's proposal.
counters  shadow-mode observation rates per day, so a regression shows up as a trend.

Nothing here changes a shipped model. A proposal becomes a release only through the usual
pack build, the held-out Wikidata evaluation and the on-device parity test.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

import lightgbm as lgb
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from catalog.genres import Normalizer  # noqa: E402
from identity.features import NAMES, VERSION, Encoder  # noqa: E402
from vml import metrics as M  # noqa: E402

MIN_INSTALLS_FOR_PRIOR = 50
MIN_PAIRS_FOR_VERDICT = 500


def trimmed_mean(values: np.ndarray, trim: float = 0.1) -> np.ndarray:
    """Coordinate-wise trimmed mean: a few poisoned or broken installs cannot move it."""
    ordered = np.sort(values, axis=0)
    k = int(len(ordered) * trim)
    return ordered[k:len(ordered) - k].mean(axis=0) if len(ordered) - 2 * k > 0 else ordered.mean(axis=0)


def load(dump: Path) -> sqlite3.Connection:
    db = sqlite3.connect(":memory:")
    db.executescript(dump.read_text(encoding="utf-8"))
    return db


def analyse_taste(db: sqlite3.Connection, taste_config: Path | None) -> dict:
    out: dict = {}
    prior = None
    if taste_config and taste_config.exists():
        cfg = json.loads(taste_config.read_text(encoding="utf-8"))
        signals = np.asarray(cfg["prior"]) * cfg["scale"]
        prior = np.concatenate([signals, np.zeros(len(signals)), [-signals.sum() / 2]])
    for kind in ("ANIME", "MOVIE", "TV"):
        rows = db.execute("SELECT install, version, events, weights FROM taste WHERE type = ?", (kind,)).fetchall()
        # The latest row per install only; a version mix is reported, not averaged blindly.
        latest: dict[str, tuple] = {}
        for install, version, events, weights in rows:
            latest[install] = (version, events, np.asarray(json.loads(weights), float))
        if not latest:
            continue
        versions = sorted({v for v, _, _ in latest.values()})
        top = max(versions, key=lambda v: sum(1 for x in latest.values() if x[0] == v))
        weights = np.stack([w for v, _, w in latest.values() if v == top])
        events = np.asarray([e for v, e, _ in latest.values() if v == top])
        entry = {"installs": len(weights), "versions": versions, "median_events": float(np.median(events)),
                 "trimmed_mean": [round(float(x), 4) for x in trimmed_mean(weights)],
                 "spread_p10_p90": [[round(float(a), 3), round(float(b), 3)] for a, b in zip(np.quantile(weights, .1, axis=0), np.quantile(weights, .9, axis=0))]}
        if prior is not None:
            entry["prior"] = [round(float(x), 4) for x in prior]
            shift = trimmed_mean(weights) - prior
            entry["shift_from_prior"] = [round(float(x), 4) for x in shift]
            entry["verdict"] = ("proposal: move the prior to the trimmed mean" if len(weights) >= MIN_INSTALLS_FOR_PRIOR and np.abs(shift).max() > 0.3
                                else "keep the prior" if len(weights) >= MIN_INSTALLS_FOR_PRIOR else f"need {MIN_INSTALLS_FOR_PRIOR} installs, have {len(weights)}")
        out[kind] = entry
    return out


def analyse_pairs(db: sqlite3.Connection, identity_dir: Path | None, only_version: str = VERSION) -> dict:
    rows = db.execute("SELECT source, label, kind_a, kind_b, features FROM pairs WHERE features_version = ?", (only_version,)).fetchall()
    out: dict = {"pairs": len(rows), "features_version": only_version}
    if not rows or identity_dir is None:
        return out
    X = np.asarray([json.loads(r[4]) for r in rows], np.float32)
    y = np.asarray([r[1] for r in rows])
    report = json.loads((identity_dir / "report.json").read_text(encoding="utf-8"))
    if report["features"] != NAMES:
        return dict(out, error="identity model and collector use different feature contracts")
    booster = lgb.Booster(model_file=str(identity_dir / "same.txt"))
    platt = report["same"]["platt"]
    p = M.apply_platt(booster.predict(X, num_threads=4), platt["slope"], platt["intercept"])
    thresholds = report["same"]["thresholds"]
    out["labels"] = {"same": int(y.sum()), "different": int((1 - y).sum())}
    out["verdict_ready"] = len(rows) >= MIN_PAIRS_FOR_VERDICT
    if y.min() != y.max():
        out["roc_auc"] = round(M.roc_auc(y, p), 5)
        out["calibration"] = {k: v for k, v in M.calibration(y, p).items() if k != "bins"}
        out["at_shipped_thresholds"] = {name: M.binary_report(y, p, t) for name, t in thresholds.items()}
    by_source = {}
    for source in sorted({r[0] for r in rows}):
        idx = np.asarray([i for i, r in enumerate(rows) if r[0] == source])
        item = {"n": int(len(idx)), "positives": int(y[idx].sum())}
        if y[idx].min() != y[idx].max():
            item["roc_auc"] = round(M.roc_auc(y[idx], p[idx]), 5)
        item["fp_at_p99"] = int(((p[idx] >= thresholds["p99"]) & (y[idx] == 0)).sum())
        by_source[source] = item
    out["by_source"] = by_source
    # Pairs the model gets confidently wrong are the training signal the offline corpus lacks.
    wrong = np.flatnonzero(((p >= thresholds["p99"]) & (y == 0)) | ((p <= 0.05) & (y == 1)))
    out["confident_errors"] = int(len(wrong))
    out["_training_rows"] = (X, y)
    return out


def analyse_genres(db: sqlite3.Connection, encoder: Path | None) -> dict:
    rows = db.execute("SELECT text, installs FROM genre_candidates ORDER BY installs DESC").fetchall()
    out: dict = {"candidates": len(rows), "proposals": []}
    if not rows or encoder is None:
        out["proposals"] = [{"text": t, "installs": n} for t, n in rows]
        return out
    normalizer = Normalizer(Encoder(np.load(encoder).astype(np.float32)), 0.0)
    for text, installs in rows:
        tag, score, exact = normalizer.score(text)
        out["proposals"].append({"text": text, "installs": installs, "nearest_tag": tag, "similarity": round(score, 3),
                                 "suggestion": "add as alias" if score >= 0.6 else "needs a human: no close tag"})
    return out


def analyse_counters(db: sqlite3.Connection) -> dict:
    rows = db.execute("SELECT day, key, value FROM counters ORDER BY day").fetchall()
    days: dict[str, dict[str, int]] = {}
    for day, key, value in rows:
        days.setdefault(day, {})[key] = value
    out: dict = {"days": len(days)}

    def ratio(day: dict, a: str, b: str):
        total = day.get(a, 0) + day.get(b, 0)
        return round(day.get(a, 0) / total, 4) if total else None

    out["identity_search_other_top_share"] = {d: ratio(v, "identity:search_other_top", "identity:search_same_top") for d, v in days.items()}
    out["genre_unmapped_share"] = {d: ratio(v, "catalog:genre_unmapped", "catalog:genre_recovered") for d, v in days.items()}
    out["packs_in_use"] = {d: {k: v for k, v in vals.items() if k.startswith("pack:")} for d, vals in days.items()}
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dump", type=Path, help="wrangler d1 export output (.sql)")
    parser.add_argument("--identity", type=Path, help="folder with same.txt and report.json (model-stable)")
    parser.add_argument("--encoder", type=Path, help="encoder table.npy")
    parser.add_argument("--taste", type=Path, help="experience/taste.json of the shipped pack")
    parser.add_argument("--output", type=Path, default=Path("collect-report"))
    args = parser.parse_args()
    db = load(args.dump)
    pairs = analyse_pairs(db, args.identity)
    training = pairs.pop("_training_rows", None)
    report = {"taste": analyse_taste(db, args.taste), "pairs": pairs, "genres": analyse_genres(db, args.encoder),
              "counters": analyse_counters(db)}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    if training is not None:
        np.savez_compressed(args.output / "labelled_pairs.npz", X=training[0], y=training[1])
    print(json.dumps({k: (v if k != "genres" else {"candidates": v["candidates"]}) for k, v in report.items()}, ensure_ascii=False)[:3000])


if __name__ == "__main__":
    main()
