"""Catalog AI text models over hashed n-grams (text-spec-v1).

lang    13-way title language / variety classifier (EN vs romaji is the hard, useful pair).
person  one log-odds number per n-gram bucket: does this token look like part of a person's
        name or part of a work title. Closed-form naive Bayes; feeds the name parser.

Labels come from Wikidata structure, not from guesses: a language is trusted only when the
work's country or original language agrees with the label's language tag.
"""
from __future__ import annotations

import argparse
import collections
import json
import random
import re
import sys
import time
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
from scipy import sparse
from sklearn.linear_model import SGDClassifier

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from encoder.train_encoder import hash_texts  # noqa: E402
from export.formats import quantize_rows  # noqa: E402
from vml import metrics as M  # noqa: E402
from vml.text import normalize, script  # noqa: E402

LANGS = ["EN", "ROMAJI", "RU", "UK", "JA", "ZH", "KO", "DE", "FR", "ES", "IT", "PL", "PT"]
ENGLISH_COUNTRY = {"Q30", "Q145", "Q16", "Q408", "Q664", "Q27"}
ORIGIN = {"DE": ({"Q183", "Q40"}, {"Q188"}), "FR": ({"Q142"}, {"Q150"}), "ES": ({"Q29", "Q96", "Q414"}, {"Q1321"}),
          "IT": ({"Q38"}, {"Q652"}), "PL": ({"Q36"}, {"Q809"}), "PT": ({"Q45", "Q155"}, {"Q5146"})}
JAPAN, JAPANESE, ENGLISH = "Q17", "Q5287", "Q1860"
_SYL = r"(?:kk|ss|tt|pp|tch|cch)?(?:ky|gy|sh|ch|ny|hy|by|py|my|ry|ts|[kgsztdnhbpmyrwjf])?[aiueo]"
ROMAJI_WORD = re.compile(rf"(?:{_SYL}|n(?![aiueoy])|m(?=[bp]))+")
PARTICLES = {"no", "wa", "ga", "wo", "ni", "de", "na", "ka", "mo", "yo", "ne", "da", "desu", "kara", "made", "tte", "to", "o", "e"}
ROMAJI_ENDING = ("ou", "uu", "ii", "tsu", "shi", "chi", "kun", "chan", "sama", "san", "hen", "ken", "dan")
UK_ONLY = set("іїєґ")
RU_ONLY = set("ыэъё")


def looks_romaji(norm: str) -> bool:
    words = norm.split(" ")
    if len(words) < 2 or not norm.isascii():
        return False
    if not all(ROMAJI_WORD.fullmatch(w) or w.isdigit() for w in words):
        return False
    return any(w in PARTICLES for w in words[1:-1] or words) or sum(w.endswith(ROMAJI_ENDING) for w in words) >= 1


def csr(texts: list[str], buckets: int) -> sparse.csr_matrix:
    indices, offsets = hash_texts(texts, buckets)
    data = np.ones(len(indices), np.float32)
    m = sparse.csr_matrix((data, indices, offsets), shape=(len(texts), buckets))
    # Bag mean: the phone divides the summed rows by the number of n-grams.
    counts = np.maximum(np.diff(offsets), 1).astype(np.float32)
    return sparse.diags(1.0 / counts) @ m


def collect(corpus: Path) -> tuple[dict[str, list[tuple[str, int]]], dict[str, list[str]], dict[str, list[str]]]:
    ents = {e["qid"]: e for e in pq.read_table(corpus / "entities.parquet",
                                               columns=["qid", "kind", "stage", "countries", "orig_lang", "lang_of_work"]).to_pylist()}
    by_entity: dict[str, list[tuple[str, str]]] = collections.defaultdict(list)
    table = pq.read_table(corpus / "titles.parquet", columns=["qid", "lang", "text", "source"])
    for q, lang, text, source in zip(table["qid"].to_pylist(), table["lang"].to_pylist(), table["text"].to_pylist(), table["source"].to_pylist()):
        if len(text) <= 120:
            by_entity[q].append((lang, text, source))
    data: dict[str, list[tuple[str, int]]] = collections.defaultdict(list)
    title_tokens: dict[str, list[str]] = collections.defaultdict(list)
    for q, items in by_entity.items():
        e = ents[q]
        split = "train" if e["stage"] in ("enc", "gbm") else e["stage"]
        countries, langs = set(e["countries"]), set(e["orig_lang"]) | set(e["lang_of_work"])
        japanese = JAPAN in countries or JAPANESE in langs or e["kind"] in ("ANIME", "MANGA")
        english = bool(countries & ENGLISH_COUNTRY) or ENGLISH in langs
        en_keys = {normalize(t) for lang, t, _ in items if lang == "en"}
        ru_keys = {normalize(t) for lang, t, _ in items if lang == "ru"}
        seen: set[str] = set()
        for lang, text, source in items:
            norm = normalize(text)
            if not norm or norm in seen or len(norm) < 3:
                continue
            seen.add(norm)
            sc = script(norm)
            label = None
            if lang == "ja-Latn" and source == "reading":
                label = "ROMAJI"
            elif sc == 0 and japanese and lang in ("en", "ja", "ja-Latn") and looks_romaji(norm):
                label = "ROMAJI"
            elif lang == "en" and sc == 0 and english and not japanese and norm.isascii():
                label = "EN"
            elif lang == "ru" and sc == 1 and not (set(norm) & UK_ONLY):
                label = "RU"
            elif lang == "uk" and sc == 1 and norm not in ru_keys and (set(norm) & UK_ONLY or not set(norm) & RU_ONLY):
                label = "UK" if set(norm) & UK_ONLY else None
            elif lang == "ja" and sc == 2 and any(0x3040 <= ord(c) < 0x3100 for c in norm):
                label = "JA"
            elif lang == "zh" and sc == 2 and not any(0x3040 <= ord(c) < 0x3100 for c in norm) and not japanese:
                label = "ZH"
            elif lang == "ko" and sc == 3:
                label = "KO"
            elif lang.upper() in ORIGIN and sc == 0 and norm not in en_keys:
                wanted_country, wanted_lang = ORIGIN[lang.upper()]
                if countries & wanted_country or langs & wanted_lang:
                    label = lang.upper()
            if label:
                data[split].append((norm, LANGS.index(label)))
            if split == "train" and lang in ("en", "ru") and len(title_tokens[lang]) < 3_000_000:
                title_tokens[lang].extend(norm.split(" "))
    people: dict[str, list[str]] = collections.defaultdict(list)
    author_file = corpus / "authors.json"
    if author_file.exists():
        for q, names in json.loads(author_file.read_text(encoding="utf-8")).items():
            split = ("train", "validation", "test")[0 if int(q[1:]) % 10 < 8 else 1 if int(q[1:]) % 10 == 8 else 2]
            for lang, name in names:
                if lang in ("en", "ru", "uk", "de", "fr", "es"):
                    people[split].extend(w for w in normalize(name).split(" ") if len(w) >= 2)
    return data, people, title_tokens


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/catalog/text")
    parser.add_argument("--buckets", type=int, default=1 << 15)
    parser.add_argument("--person-buckets", type=int, default=1 << 16)
    parser.add_argument("--cap", type=int, default=60000, help="max training titles per language")
    parser.add_argument("--test", action="store_true")
    args = parser.parse_args()
    started = time.time()
    rng = random.Random(20261110)
    data, people, title_tokens = collect(args.corpus)
    report: dict = {"labels": LANGS, "buckets": args.buckets, "text_spec": "text-spec-v1"}

    def balanced(rows: list[tuple[str, int]], cap: int) -> list[tuple[str, int]]:
        by: dict[int, list[str]] = collections.defaultdict(list)
        for text, label in rows:
            by[label].append(text)
        out = []
        for label, texts in by.items():
            rng.shuffle(texts)
            out.extend((t, label) for t in texts[:cap])
        rng.shuffle(out)
        return out

    train = balanced(data["train"], args.cap)
    val = balanced(data["validation"], 6000)
    report["class_counts"] = {"train": dict(collections.Counter(LANGS[y] for _, y in train)),
                              "validation": dict(collections.Counter(LANGS[y] for _, y in val))}
    Xt, yt = csr([t for t, _ in train], args.buckets), np.asarray([y for _, y in train])
    Xv, yv = csr([t for t, _ in val], args.buckets), np.asarray([y for _, y in val])
    clf = SGDClassifier(loss="log_loss", alpha=2e-6, max_iter=40, tol=1e-4, random_state=20261110, n_jobs=6, class_weight="balanced")
    clf.fit(Xt, yt)
    weights = np.zeros((args.buckets, len(LANGS)), np.float32)
    bias = np.zeros(len(LANGS), np.float32)
    for column, cls in enumerate(clf.classes_):
        weights[:, cls] = clf.coef_[column]
        bias[cls] = clf.intercept_[column]

    # Temperature: SGD on bag-mean features leaves logits far too flat; one scalar fixes the confidence.
    raw_v = Xv @ weights + bias
    best_t, best_nll = 1.0, 1e18
    for t in np.geomspace(0.01, 3, 80):
        z = raw_v / t
        z = z - z.max(axis=1, keepdims=True)
        nll = float(-(z[np.arange(len(yv)), yv] - np.log(np.exp(z).sum(axis=1))).mean())
        if nll < best_nll:
            best_t, best_nll = float(t), nll
    weights /= best_t
    bias /= best_t
    report["temperature"] = best_t
    # From here on the weights are exactly what the phone reconstructs from int8.
    q, code, levels = quantize_rows(weights)
    weights = q.astype(np.float32) * levels[code][:, None]

    def predict(X) -> np.ndarray:
        logits = X @ weights + bias
        logits -= logits.max(axis=1, keepdims=True)
        e = np.exp(logits)
        return e / e.sum(axis=1, keepdims=True)

    def lang_eval(X, y) -> dict:
        prob = predict(X)
        out = M.multiclass_report(y, prob, LANGS)
        pair = (y == 0) | (y == 1)
        romaji = prob[pair][:, 1] / (prob[pair][:, 0] + prob[pair][:, 1])
        out["romaji_vs_en"] = M.binary_report((y[pair] == 1), romaji, 0.5)
        out["romaji_vs_en"]["roc_auc"] = round(M.roc_auc((y[pair] == 1), romaji), 5)
        return out

    report["lang_validation"] = lang_eval(Xv, yv)
    if args.test:
        test = balanced(data["test"], 6000)
        report["lang_test"] = lang_eval(csr([t for t, _ in test], args.buckets), np.asarray([y for _, y in test]))

    # Person-name lexicon: smoothed log-odds per bucket, token score = mean over the token's buckets.
    def bucket_counts(tokens: list[str]) -> np.ndarray:
        indices, _ = hash_texts(tokens, args.person_buckets)
        return np.bincount(indices, minlength=args.person_buckets).astype(np.float64)

    person_tokens = people["train"]
    work_tokens = [w for lang in ("en", "ru") for w in title_tokens[lang] if len(w) >= 2]
    rng.shuffle(work_tokens)
    work_tokens = work_tokens[:max(len(person_tokens) * 3, 100000)]
    cp, cw = bucket_counts(person_tokens), bucket_counts(work_tokens)
    odds = (np.log((cp + 0.5) / (cp.sum() + 0.5 * len(cp))) - np.log((cw + 0.5) / (cw.sum() + 0.5 * len(cw)))).astype(np.float32)
    odds = np.clip(odds, -6, 6)
    pq_, pcode, plevels = quantize_rows(odds[:, None])
    odds = (pq_.astype(np.float32) * plevels[pcode][:, None])[:, 0]

    def person_scores(tokens: list[str]) -> np.ndarray:
        indices, offsets = hash_texts(tokens, args.person_buckets)
        sums = np.add.reduceat(odds[indices], offsets[:-1]) if len(indices) else np.zeros(len(tokens))
        return sums / np.maximum(np.diff(offsets), 1)

    val_person = people["validation"][:40000]
    val_work = [w for q in data["validation"][:60000] for w in q[0].split(" ") if len(w) >= 2 and q[1] in (0, 2)][:len(val_person)]
    scores = np.concatenate([person_scores(val_person), person_scores(val_work)])
    truth = np.concatenate([np.ones(len(val_person)), np.zeros(len(val_work))])
    report["person_validation"] = dict(M.binary_report(truth, scores, 0.0), roc_auc=round(M.roc_auc(truth, scores), 5),
                                       tokens=[len(person_tokens), len(work_tokens)])
    args.output.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(args.output / "lang.npz", weights=weights, bias=bias)
    np.savez_compressed(args.output / "person.npz", odds=odds)
    report["seconds"] = round(time.time() - started, 1)
    (args.output / "report.json").write_text(json.dumps(report, indent=1, ensure_ascii=False), encoding="utf-8")
    v = report["lang_validation"]
    print(json.dumps({"counts": report["class_counts"]["train"], "lang_acc": v["accuracy"], "macro_f1": v["macro_f1"],
                      "per_class_f1": {k: d["f1"] for k, d in v["classes"].items()},
                      "romaji_vs_en": {k: v["romaji_vs_en"][k] for k in ("precision", "recall", "f1", "fpr", "roc_auc")},
                      "person": {k: report["person_validation"][k] for k in ("precision", "recall", "f1", "fpr", "roc_auc")},
                      "ece": v["calibration"]["ece"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
