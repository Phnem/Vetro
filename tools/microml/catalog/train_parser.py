"""Train and measure the Catalog AI name parser (token tagger forest).

Reported against a regex baseline that mirrors what the app does today (FolderName for
audiobooks, a marker-stripping heuristic for video and manga). Field accuracy is the number
that matters to the app: was the title, the episode, the author read correctly.
"""
from __future__ import annotations

import argparse
import collections
import json
import re
import sys
import time
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from catalog.names import DOMAINS, LABELS, NAMES, assemble, features, tokenize  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import bucket_ids, normalize  # noqa: E402

FIELDS = ["title", "alt_title", "year", "season", "episode", "volume", "chapter", "author", "narrator"]
_BR = re.compile(r"[(\[]([^)\]]*)[)\]]\s*$")
_DASH = re.compile(r"\s+[-–—]\s+")
_READS = re.compile(r"(?i)^(читает|чтец|read by|narrated by)\s*")


def baseline(raw: str, domain: int) -> dict:
    """Today's behaviour: FolderName.parse for audiobooks; a marker-stripping regex pass for the rest."""
    out = dict.fromkeys(FIELDS)
    if domain == DOMAINS["audiobook"]:
        rest = raw.strip()
        m = _BR.search(rest)
        if m:
            inside = m.group(1).strip()
            if inside and not inside.isdigit():
                out["narrator"] = _READS.sub("", inside).strip() or None
            rest = (rest[:m.start()] + rest[m.end():]).strip()
        parts = [p.strip() for p in _DASH.split(rest, maxsplit=1)]
        if len(parts) == 2 and all(parts):
            out["author"], out["title"] = parts
        else:
            out["title"] = rest or raw.strip()
        return out
    text = re.sub(r"\.(mkv|mp4|avi|cbz|cbr|zip|pdf|m4b|mp3)$", "", raw.strip(), flags=re.I)
    m = re.search(r"[(\[. ]((?:19|20)[0-9]{2})[)\]. ]", text + " ")
    if m:
        out["year"] = int(m.group(1))
    m = re.search(r"(?i)\bs([0-9]{1,2})e([0-9]{1,4})\b", text)
    if m:
        out["season"], out["episode"] = int(m.group(1)), int(m.group(2))
    else:
        m = re.search(r"(?i)(?:\s-\s|\bep?\.?\s?|episode\s|серия\s)([0-9]{1,4})(?:v[0-9])?\b", text)
        if m:
            out["episode"] = int(m.group(1))
        m = re.search(r"(?i)(?:\bs|season\s|сезон\s)([0-9]{1,2})\b", text)
        if m:
            out["season"] = int(m.group(1))
    m = re.search(r"(?i)\bv(?:ol(?:ume)?\.?\s?)?([0-9]{1,3})\b|том\s([0-9]{1,3})", text)
    if m and domain == DOMAINS["manga"]:
        out["volume"] = int(m.group(1) or m.group(2))
    m = re.search(r"(?i)\bc(?:h(?:apter)?\.?\s?)?([0-9]{1,4})\b|глава\s([0-9]{1,4})", text)
    if m and domain == DOMAINS["manga"]:
        out["chapter"] = int(m.group(1) or m.group(2))
    title = re.sub(r"^\[[^\]]*\][ _]*", "", text)
    cut = re.search(r"(?i)[ ._]-[ ._]|[\[(]|[ .]s[0-9]{1,2}(e[0-9]+)?\b|[ .](19|20)[0-9]{2}\b|[ .][0-9]{3,4}p\b|\bepisode\b| v[0-9]{1,3}\b| c[0-9]{1,4}\b", title)
    title = title[:cut.start()] if cut and cut.start() > 0 else title
    out["title"] = title.replace("_", " ").replace(".", " ").strip() if (" " not in title.strip()) else title.strip()
    return out


def same(field: str, got, gold) -> bool:
    if gold is None:
        return got is None or field in ("alt_title",)
    if got is None:
        return False
    if isinstance(gold, int):
        return got == gold
    return normalize(str(got)) == normalize(str(gold))


def field_report(rows: list[dict], parsed: list[dict]) -> dict:
    """Per field: accuracy over all rows; precision/recall where the field is present/absent."""
    stats = {f: collections.Counter() for f in FIELDS}
    exact = 0
    for row, got in zip(rows, parsed):
        gold = row["gold"]
        ok_all = True
        for f in FIELDS:
            g, p = gold.get(f), got.get(f)
            ok = same(f, p, g)
            s = stats[f]
            s["n"] += 1
            s["correct"] += ok
            if g is not None:
                s["present"] += 1
                s["tp"] += ok
            if p is not None and f != "alt_title":
                s["predicted"] += 1
                s["predicted_ok"] += (g is not None and ok)
                s["spurious"] += g is None
            if g is None:
                s["absent"] += 1
            ok_all &= ok or f == "alt_title"
        exact += ok_all
    out = {"all_fields_exact": round(exact / max(1, len(rows)), 4), "n": len(rows)}
    for f, s in stats.items():
        if not s["present"] and not s["predicted"]:
            continue
        precision = s["predicted_ok"] / s["predicted"] if s["predicted"] else 1.0
        recall = s["tp"] / s["present"] if s["present"] else 0.0
        out[f] = {"accuracy": round(s["correct"] / s["n"], 4), "precision": round(precision, 4), "recall": round(recall, 4),
                  "f1": round(2 * precision * recall / (precision + recall), 4) if precision + recall else 0.0,
                  "fpr": round(s["spurious"] / s["absent"], 4) if s["absent"] else 0.0, "support": s["present"]}
    return out


class PersonLexicon:
    """Mean log-odds of a token's n-gram buckets (catalog/train_text.py), cached per token."""

    def __init__(self, path: Path):
        self.odds = np.load(path)["odds"] if path.exists() else None
        self.cache: dict[str, float] = {}

    def __call__(self, lower: str) -> float:
        if self.odds is None:
            return 0.0
        value = self.cache.get(lower)
        if value is None:
            ids = bucket_ids(normalize(lower), len(self.odds))
            value = float(self.odds[ids].mean()) if ids else 0.0
            self.cache[lower] = value
        return value


PERSON: PersonLexicon | None = None


def load(path: Path, limit: int = 0):
    rows = pq.read_table(path).to_pylist()
    if limit:
        rows = rows[:limit]
    X, y, owner = [], [], []
    for i, r in enumerate(rows):
        r["gold"] = json.loads(r["gold"])
        text, tokens = tokenize(r["raw"])
        r["_text"], r["_tokens"] = text, tokens
        X.extend(features(tokens, r["domain"], PERSON))
        y.extend(r["labels"])
        owner.extend([i] * len(tokens))
    return rows, np.asarray(X, np.float32), np.asarray(y, np.int32), np.asarray(owner, np.int64)


def parse_all(model, rows, X, owner) -> tuple[list[dict], np.ndarray]:
    prob = model.predict(X, num_threads=6)
    pred = prob.argmax(axis=1)
    starts = np.searchsorted(owner, np.arange(len(rows)))
    ends = np.searchsorted(owner, np.arange(len(rows)), side="right")
    return [assemble(r["_text"], r["_tokens"], pred[s:e].tolist()) for r, s, e in zip(rows, starts, ends)], prob


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=ROOT / ".cache/catalog")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/catalog/parser")
    parser.add_argument("--challenge", type=Path, default=ROOT / "catalog/challenge_names.json")
    parser.add_argument("--test", action="store_true")
    parser.add_argument("--evaluate-only", action="store_true", help="score the existing checkpoint without training or replacing it")
    parser.add_argument("--rounds", type=int, default=400)
    parser.add_argument("--leaves", type=int, default=31)
    parser.add_argument("--person", type=Path, default=ROOT / ".cache/catalog/text/person.npz")
    args = parser.parse_args()
    global PERSON
    PERSON = PersonLexicon(args.person)
    started = time.time()
    rows_v, Xv, yv, owner_v = load(args.data / "names-validation.parquet")
    if args.evaluate_only:
        model = lgb.Booster(model_file=str(args.output / "parser.txt"))
        report = json.loads((args.output / "report.json").read_text(encoding="utf-8"))
        if model.feature_name() != NAMES or report["labels"] != LABELS:
            raise ValueError("parser checkpoint uses incompatible features or labels")
    else:
        _, Xt, yt, _ = load(args.data / "names-train.parquet")
        # Early stopping uses the generated layouts seen during training.
        held = np.asarray([r["held_out_template"] for r in rows_v], bool)
        seen_v = ~held[owner_v]
        model = lgb.train({"objective": "multiclass", "num_class": len(LABELS), "learning_rate": 0.1, "num_leaves": args.leaves,
                           "min_data_in_leaf": 30, "feature_fraction": 0.8, "lambda_l2": 1.0, "verbose": -1, "num_threads": 6,
                           "seed": 20261110, "deterministic": True, "force_row_wise": True},
                          lgb.Dataset(Xt, yt, feature_name=NAMES), args.rounds,
                          valid_sets=[lgb.Dataset(Xv[seen_v], yv[seen_v], feature_name=NAMES)],
                          callbacks=[lgb.early_stopping(30, verbose=False)])
        args.output.mkdir(parents=True, exist_ok=True)
        model.save_model(str(args.output / "parser.txt"))
        report = {"feature_version": "name-features-v1", "features": NAMES, "labels": LABELS, "trees": model.num_trees(),
                  "train_tokens": int(len(yt)), "seconds": round(time.time() - started, 1)}

    def evaluate(rows, X, y, owner) -> dict:
        parsed, prob = parse_all(model, rows, X, owner)
        out = {"tokens": M.multiclass_report(y, prob, LABELS) if y is not None else None, "fields": field_report(rows, parsed),
               "baseline_fields": field_report(rows, [baseline(r["raw"], r["domain"]) for r in rows])}
        for name, pick in (("seen_templates", lambda r: not r.get("held_out_template")),
                           ("held_out_templates", lambda r: r.get("held_out_template"))):
            idx = [i for i, r in enumerate(rows) if pick(r)]
            if idx and len(idx) < len(rows):
                out[name] = {"model": field_report([rows[i] for i in idx], [parsed[i] for i in idx]),
                             "baseline": field_report([rows[i] for i in idx], [baseline(rows[i]["raw"], rows[i]["domain"]) for i in idx])}
        return out

    report["validation"] = evaluate(rows_v, Xv, yv, owner_v)
    if args.challenge.exists():
        items = json.loads(args.challenge.read_text(encoding="utf-8"))
        rows_c, X_c, owner_c = [], [], []
        for i, item in enumerate(items):
            text, tokens = tokenize(item["raw"])
            domain = DOMAINS[item.get("domain", "unknown")]
            rows_c.append({"raw": item["raw"], "domain": domain, "gold": item["gold"], "_text": text, "_tokens": tokens})
            X_c.extend(features(tokens, domain, PERSON))
            owner_c.extend([i] * len(tokens))
        X_c, owner_c = np.asarray(X_c, np.float32), np.asarray(owner_c, np.int64)
        parsed, _ = parse_all(model, rows_c, X_c, owner_c)
        report["challenge"] = {"note": "hand-written realistic names; editorial, not a random sample",
                               "model": field_report(rows_c, parsed),
                               "baseline": field_report(rows_c, [baseline(r["raw"], r["domain"]) for r in rows_c]),
                               "misses": [{"raw": r["raw"], "gold": r["gold"], "got": {k: v for k, v in p.items() if v is not None}}
                                          for r, p in zip(rows_c, parsed)
                                          if not all(same(f, p.get(f), r["gold"].get(f)) or f == "alt_title" for f in FIELDS)]}
    if args.test:
        rows_s, Xs, ys, owner_s = load(args.data / "names-test.parquet")
        report["test"] = evaluate(rows_s, Xs, ys, owner_s)
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    v = report["validation"]
    print(json.dumps({"trees": model.num_trees(), "token_acc": v["tokens"]["accuracy"], "token_macro_f1": v["tokens"]["macro_f1"],
                      "exact": [v["fields"]["all_fields_exact"], v["baseline_fields"]["all_fields_exact"]],
                      "held_out_exact": [v.get("held_out_templates", {}).get("model", {}).get("all_fields_exact"),
                                         v.get("held_out_templates", {}).get("baseline", {}).get("all_fields_exact")],
                      "title_acc": [v["fields"]["title"]["accuracy"], v["baseline_fields"]["title"]["accuracy"]],
                      "challenge_exact": [report.get("challenge", {}).get("model", {}).get("all_fields_exact"),
                                          report.get("challenge", {}).get("baseline", {}).get("all_fields_exact")]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
