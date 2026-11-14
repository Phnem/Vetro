"""Build record-pair datasets for Identity AI from one corpus stage.

A record imitates what the app holds: one to four titles (possibly in different
scripts), a kind, maybe a year, maybe authors. Two views of one entity are a positive;
franchise neighbours, encoder nearest neighbours and random entities are negatives.
Everything happens inside a single stage, so no entity crosses a split.
"""
from __future__ import annotations

import argparse
import collections
import json
import multiprocessing as mp
import random
import sys
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from identity.features import NAMES, Encoder, Prepared, Record, pair_features  # noqa: E402
from vml.text import key, normalize, script  # noqa: E402

REL = {"SAME": 0, "FRANCHISE": 1, "ADAPTATION": 2, "UNRELATED": 3, "UNKNOWN": -1}
LANG_WEIGHT = {"en": 5, "ru": 5, "ja": 3, "ja-Latn": 3, "uk": 1.5, "de": 1, "fr": 1, "es": 1, "zh": 1, "ko": 1,
               "it": 0.7, "pl": 0.7, "pt": 0.7}
LINKS = ("series", "part_of", "follows", "followed_by", "based_on", "derivative", "has_part")

_ENC: Encoder | None = None


def typo(text: str, rng: random.Random) -> str:
    if len(text) < 5:
        return text
    i = rng.randrange(1, len(text) - 1)
    mode = rng.randrange(3)
    if mode == 0:
        return text[:i] + text[i + 1:]
    if mode == 1:
        return text[:i] + text[i + 1] + text[i] + text[i + 2:]
    return text[:i] + text[i] + text[i:]


def view(titles: list[tuple[str, str]], rng: random.Random, limit: int) -> list[str]:
    """Sample up to `limit` titles, weighted towards the languages the app actually stores."""
    pool = list(titles)
    out: list[str] = []
    seen: set[str] = set()
    while pool and len(out) < limit:
        weights = [LANG_WEIGHT.get(lang, 0.3) for lang, _ in pool]
        lang, text = pool.pop(rng.choices(range(len(pool)), weights)[0])
        k = key(text)
        if k and k not in seen:
            seen.add(k)
            out.append(text)
    return out


def positive_views(titles: list[tuple[str, str]], rng: random.Random) -> tuple[list[str], list[str], str] | None:
    mode = rng.random()
    if mode < 0.35 or len(titles) == 1:
        a, b = view(titles, rng, rng.randint(1, 3)), view(titles, rng, rng.randint(1, 3))
        stratum = "pos_overlap"
    elif mode < 0.75:
        langs = sorted({lang for lang, _ in titles})
        if len(langs) < 2:
            return None
        rng.shuffle(langs)
        cut = rng.randint(1, len(langs) - 1)
        left = set(langs[:cut])
        a = view([t for t in titles if t[0] in left], rng, rng.randint(1, 3))
        b = view([t for t in titles if t[0] not in left], rng, rng.randint(1, 3))
        stratum = "pos_disjoint_lang"
    else:
        picks = view(titles, rng, 2)
        if len(picks) < 2:
            return None
        a, b = picks[:1], picks[1:]
        stratum = "pos_single"
    if not a or not b:
        return None
    if rng.random() < 0.06:
        a = [typo(a[0], rng)] + a[1:]
    return a, b, stratum


def noisy_year(year: int | None, rng: random.Random) -> int | None:
    if year is None or rng.random() < 0.15:
        return None
    return year + rng.choice((-1, 1)) if rng.random() < 0.10 else year  # premieres differ by country


def _features(job: tuple[list[dict], str]) -> np.ndarray:
    global _ENC
    rows, encoder_path = job
    if _ENC is None:
        _ENC = Encoder(np.load(encoder_path).astype(np.float32))
    out = np.zeros((len(rows), len(NAMES)), np.float32)
    for i, r in enumerate(rows):
        a = Prepared(Record(r["titles_a"], r["kind_a"], r["year_a"], r["authors_a"]), _ENC)
        b = Prepared(Record(r["titles_b"], r["kind_b"], r["year_b"], r["authors_b"]), _ENC)
        out[i] = pair_features(a, b)
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--encoder", type=Path, required=True, help="weights.npy of the shared encoder")
    parser.add_argument("--stage", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".cache/identity")
    parser.add_argument("--max-entities", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20261110)
    args = parser.parse_args()
    rng = random.Random(f"{args.seed}:{args.stage}")
    # Filter inside Arrow: materialising all 490k entities as Python dicts costs gigabytes this machine does not have.
    columns = ["qid", "kind", "year", "group", "stage", "authors", *LINKS]
    table = pq.read_table(args.corpus / "entities.parquet", columns=columns)
    ents = table.filter(pc.equal(table["stage"], args.stage)).to_pylist()
    del table
    by_q = {e["qid"]: e for e in ents}
    generic = set(json.loads((args.corpus / "generic_keys.json").read_text(encoding="utf-8")))
    titles: dict[str, list[tuple[str, str]]] = collections.defaultdict(list)
    table = pq.read_table(args.corpus / "titles.parquet", columns=["qid", "lang", "text"])
    table = table.filter(pc.is_in(table["qid"], value_set=pa.array(list(by_q))))
    for q, lang, text in zip(table["qid"].to_pylist(), table["lang"].to_pylist(), table["text"].to_pylist()):
        if len(text) <= 200 and normalize(text):
            titles[q].append((lang, text))
    del table
    author_names: dict[str, list[str]] = {}
    author_file = args.corpus / "authors.json"
    if author_file.exists():
        author_names = json.loads(author_file.read_text(encoding="utf-8"))
    qids = sorted(titles, key=lambda x: int(x[1:]))
    if args.max_entities:
        qids = rng.sample(qids, min(args.max_entities, len(qids)))
    members: dict[str, list[str]] = collections.defaultdict(list)
    for q in qids:
        members[by_q[q]["group"]].append(q)

    def authors_of(q: str) -> list[str]:
        names: list[str] = []
        for a in by_q[q]["authors"][:2]:
            pool = author_names.get(a, [])
            if pool:
                names.append(rng.choices(pool, [LANG_WEIGHT.get(lang, 0.3) for lang, _ in pool])[0][1])
        return names if rng.random() > 0.1 else []

    def kind_of(q: str, audio: bool) -> str:
        k = by_q[q]["kind"]
        return "AUDIOBOOK" if (audio and k == "BOOK_WORK") else k

    def relation(a: str, b: str) -> str:
        ea, eb = by_q[a], by_q[b]
        direct = adaptation = False
        for f in LINKS:
            if b in ea[f] or a in eb[f]:
                direct = True
                adaptation |= f in ("based_on", "derivative")
        shared_hub = any(set(ea[f]) & set(eb[f]) for f in ("series", "part_of", "based_on"))
        if (direct and adaptation) or (shared_hub and ea["kind"] != eb["kind"]):
            return "ADAPTATION" if ea["kind"] != eb["kind"] else "FRANCHISE"
        if direct or shared_hub:
            return "FRANCHISE"
        return "UNKNOWN"

    def ambiguous(a: str, b: str) -> bool:
        """Same text, same kind, compatible year and no stated relation: likely a duplicate item, not a negative."""
        ea, eb = by_q[a], by_q[b]
        if ea["kind"] != eb["kind"]:
            return False
        ka = {key(t) for _, t in titles[a]}
        kb = {key(t) for _, t in titles[b]}
        if not (ka & kb):
            return False
        ya, yb = ea["year"], eb["year"]
        return ya is None or yb is None or abs(ya - yb) <= 1

    # Encoder nearest neighbours between entity mean vectors: the hard, lexically or semantically close negatives.
    encoder = Encoder(np.load(args.encoder).astype(np.float32))
    means = np.zeros((len(qids), encoder.table.shape[1]), np.float32)
    for i, q in enumerate(qids):
        vecs = [encoder.embed(normalize(t)) for _, t in titles[q][:6]]
        v = np.sum(vecs, axis=0)
        n = float(np.linalg.norm(v))
        means[i] = v / n if n > 0 else v
    neighbours: list[np.ndarray] = []
    for start in range(0, len(qids), 256):  # small chunks: the machine runs close to its commit limit
        sims = means[start:start + 256] @ means.T
        sims[np.arange(sims.shape[0]), np.arange(start, start + sims.shape[0])] = -2
        # .copy(): a bare slice is a view that keeps the whole 256 x N index array alive for every chunk.
        neighbours.extend(np.argpartition(-sims, 12, axis=1)[:, :12].copy())

    rows: list[dict] = []
    skipped: collections.Counter = collections.Counter()
    # Same kind and same year says nothing by itself: thousands of unrelated works share both.
    by_kind_year: dict[tuple[str, int | None], list[str]] = collections.defaultdict(list)
    by_kind: dict[str, list[str]] = collections.defaultdict(list)
    for q in qids:
        by_kind_year[(by_q[q]["kind"], by_q[q]["year"])].append(q)
        by_kind[by_q[q]["kind"]].append(q)

    def add(a: str, b: str, ta: list[str], tb: list[str], same: int, rel: str, stratum: str, audio: bool) -> None:
        if rng.random() < 0.5:
            a, b, ta, tb = b, a, tb, ta
        year_a, year_b = noisy_year(by_q[a]["year"], rng), noisy_year(by_q[b]["year"], rng)
        # "Bare" comparisons are common in the app: one title against one title, often with no year at all.
        if rng.random() < 0.30:
            ta, tb = ta[:1], tb[:1]
            if rng.random() < 0.6:
                year_a = year_b = None
        rows.append({"qid_a": a, "qid_b": b, "titles_a": ta, "titles_b": tb,
                     "kind_a": kind_of(a, audio), "kind_b": kind_of(b, audio and rng.random() < 0.7),
                     "year_a": year_a, "year_b": year_b,
                     "authors_a": authors_of(a), "authors_b": authors_of(b),
                     "same": same, "rel": REL[rel], "stratum": stratum})

    for idx, q in enumerate(qids):
        mine = [t for t in titles[q] if key(t[1]) not in generic] or titles[q]
        audio = rng.random() < 0.3
        for _ in range(2):
            views = positive_views(mine, rng)
            if views is None:
                skipped["no_second_view"] += 1
                continue
            a, b, stratum = views
            add(q, q, a, b, 1, "SAME", stratum, audio)
        if rng.random() < 0.4:  # the same title mistyped: what a search box actually receives
            lang, text = rng.choice(mine)
            if len(key(text)) >= 5:
                add(q, q, [text], [typo(text, rng) if rng.random() < 0.8 else typo(typo(text, rng), rng)], 1, "SAME", "pos_typo", audio)
        peers = by_kind_year[(by_q[q]["kind"], by_q[q]["year"])] if by_q[q]["year"] is not None else by_kind[by_q[q]["kind"]]
        for _ in range(2):
            other = rng.choice(peers)
            if other != q and by_q[other]["group"] != by_q[q]["group"] and not ambiguous(q, other):
                add(q, other, view(mine, rng, rng.randint(1, 3)), view(titles[other], rng, rng.randint(1, 3)), 0,
                    "UNRELATED", "neg_same_year", audio)
        group = [m for m in members[by_q[q]["group"]] if m != q]
        for other in rng.sample(group, min(3, len(group))):
            if ambiguous(q, other) and relation(q, other) == "UNKNOWN":
                skipped["ambiguous_group"] += 1
            else:
                rel = relation(q, other)
                add(q, other, view(mine, rng, rng.randint(1, 3)), view(titles[other], rng, rng.randint(1, 3)), 0, rel,
                    {"FRANCHISE": "neg_franchise", "ADAPTATION": "neg_adaptation"}.get(rel, "neg_group_other"), audio)
        hard = [qids[j] for j in neighbours[idx] if by_q[qids[j]]["group"] != by_q[q]["group"]]
        for other in rng.sample(hard, min(2, len(hard))):
            if ambiguous(q, other):
                skipped["ambiguous_hard"] += 1
                continue
            add(q, other, view(mine, rng, rng.randint(1, 3)), view(titles[other], rng, rng.randint(1, 3)), 0,
                "UNKNOWN", "neg_hard", audio)
        other = rng.choice(qids)
        if by_q[other]["group"] != by_q[q]["group"]:
            add(q, other, view(mine, rng, rng.randint(1, 3)), view(titles[other], rng, rng.randint(1, 3)), 0,
                "UNRELATED", "neg_random", audio)

    encoder_path = str(args.encoder)
    jobs = [(rows[i:i + 4000], encoder_path) for i in range(0, len(rows), 4000)]
    with mp.Pool(6) as pool:
        X = np.concatenate(pool.map(_features, jobs))
    # A hard negative that still looks like a numbered sibling may be an unlinked sequel: unknown relation.
    names = {n: i for i, n in enumerate(NAMES)}
    for i, r in enumerate(rows):
        if r["stratum"] == "neg_hard":
            sibling = X[i, names["sk_token_subset"]] > 0 or X[i, names["ss_prefix"]] >= 0.6 or X[i, names["sk_substring"]] >= 0.5
            r["rel"] = REL["UNKNOWN"] if sibling else REL["UNRELATED"]
        r["cross_only"] = int(X[i, names["has_same_script_pair"]] == 0)
        r["scripts"] = "".join(sorted({str(script(normalize(t))) for t in r["titles_a"] + r["titles_b"]}))
    args.output.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(args.output / f"pairs-{args.stage}.npz", X=X,
                        same=np.asarray([r["same"] for r in rows], np.int8),
                        rel=np.asarray([r["rel"] for r in rows], np.int8))
    pq.write_table(pa.Table.from_pylist(rows), args.output / f"pairs-{args.stage}.parquet", compression="zstd")
    report = {"stage": args.stage, "entities": len(qids), "pairs": len(rows), "features": NAMES,
              "strata": dict(collections.Counter(r["stratum"] for r in rows)),
              "relations": dict(collections.Counter(r["rel"] for r in rows)),
              "cross_only_positives": sum(1 for r in rows if r["same"] and r["cross_only"]),
              "skipped": dict(skipped)}
    (args.output / f"pairs-{args.stage}.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "features"}))


if __name__ == "__main__":
    main()
