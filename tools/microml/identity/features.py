"""identity-features-v2: symmetric record-pair features for Identity AI.

A record is what the app actually holds: up to four titles in any scripts, a media kind,
an optional year and optional author names. External IDs are never inputs: they are
verified evidence handled by rules before the model is asked.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
from rapidfuzz.distance import JaroWinkler, Levenshtein

from vml.text import bucket_ids, markers, normalize, script, skeleton

VERSION = "identity-features-v2"
MAX_TITLES = 4
KINDS = {"ANIME": 0, "MANGA": 1, "MOVIE": 2, "SERIES": 3, "BOOK_WORK": 4, "AUDIOBOOK": 5}
NAMES = [
    "ss_lev", "ss_jw", "ss_token_jaccard", "ss_token_subset", "ss_substring", "ss_bigram_jaccard", "ss_prefix",
    "sk_lev", "sk_jw", "sk_token_jaccard", "sk_token_subset", "sk_substring", "sk_bigram_jaccard",
    "enc_max", "enc_soft", "enc_mean_vec", "enc_cross_max", "enc_min_rowmax",
    "exact_key", "exact_skeleton", "has_same_script_pair", "has_cross_script_pair",
    "n_titles_min", "n_titles_max", "min_key_len", "max_key_len",
    "seq_equal", "seq_conflict", "seq_one_sided", "seq_jaccard", "variant_equal", "variant_diff",
    "title_year_conflict", "same_kind", "kind_lo", "kind_hi", "both_years", "year_equal", "year_diff",
    "both_authors", "author_sk_lev", "author_token_jaccard",
]


class Encoder:
    """Float reference of the hashed n-gram encoder (text-spec-v1)."""

    def __init__(self, table: np.ndarray):
        self.table = np.asarray(table, dtype=np.float32)
        self.buckets = self.table.shape[0]

    def embed(self, norm: str) -> np.ndarray:
        ids = bucket_ids(norm, self.buckets)
        if not ids:
            return np.zeros(self.table.shape[1], np.float32)
        v = self.table[ids].sum(axis=0)
        n = float(np.linalg.norm(v))
        return v / n if n > 0 else v


@dataclass
class Record:
    titles: list[str]
    kind: str = "ANIME"
    year: int | None = None
    authors: list[str] = field(default_factory=list)


class Prepared:
    __slots__ = ("norm", "key", "tokens", "skel", "skel_key", "skel_tokens", "script", "vec", "seq", "variants",
                 "years", "kind", "year", "authors")

    def __init__(self, record: Record, encoder: Encoder | None):
        seen: set[str] = set()
        self.norm = []
        for t in record.titles:
            n = normalize(t)[:200]
            if n and n not in seen:
                seen.add(n)
                self.norm.append(n)
            if len(self.norm) == MAX_TITLES:
                break
        self.key = [n.replace(" ", "") for n in self.norm]
        self.tokens = [frozenset(n.split(" ")) for n in self.norm]
        self.skel = [skeleton(n) for n in self.norm]
        self.skel_key = [s.replace(" ", "") for s in self.skel]
        self.skel_tokens = [frozenset(s.split(" ")) for s in self.skel]
        self.script = [script(n) for n in self.norm]
        self.vec = np.stack([encoder.embed(n) for n in self.norm]) if (encoder and self.norm) else None
        seq: set[int] = set()
        variants: set[str] = set()
        years: set[int] = set()
        for t in record.titles[:MAX_TITLES * 2]:
            s, v, y = markers(t)
            seq |= s
            variants |= v
            years |= y
        self.seq, self.variants, self.years = seq, variants, years
        self.kind = KINDS.get(record.kind, 0)
        self.year = record.year if record.year and record.year > 0 else None
        self.authors = [frozenset(w for w in skeleton(normalize(a)).split(" ") if len(w) >= 2) for a in record.authors[:4]]
        self.authors = [a for a in self.authors if a]


def _jaccard(a, b) -> float:
    if not a and not b:
        return 0.0
    return len(a & b) / len(a | b)


def _bigrams(s: str) -> set[str]:
    return {s[i:i + 2] for i in range(len(s) - 1)}


def stabilize_features(features: np.ndarray) -> np.ndarray:
    """Keep cosine rounding noise out of training bins (including cached v1 pairs)."""
    cosines = np.clip(features[..., 13:18].astype(np.float64), -1, 1)
    features[..., 13:18] = np.floor(cosines * 10000 + 0.5) / 10000
    return features


def _string_sims(x: str, y: str, kx: str, ky: str, tx: frozenset, ty: frozenset) -> tuple[float, ...]:
    longest = max(len(kx), len(ky), 1)
    shortest = min(len(kx), len(ky))
    prefix = 0
    while prefix < shortest and kx[prefix] == ky[prefix]:
        prefix += 1
    return (
        Levenshtein.normalized_similarity(x, y),
        JaroWinkler.normalized_similarity(x, y),
        _jaccard(tx, ty),
        1.0 if (tx <= ty or ty <= tx) else 0.0,
        shortest / longest if (kx and ky and (kx in ky or ky in kx)) else 0.0,
        _jaccard(_bigrams(kx), _bigrams(ky)),
        prefix / longest,
    )


def pair_features(a: Prepared, b: Prepared) -> np.ndarray:
    f = np.zeros(len(NAMES), dtype=np.float32)
    if not a.norm or not b.norm:
        return f
    ss = [0.0] * 7
    sk = [0.0] * 6
    exact_key = exact_skel = has_ss = has_cs = 0.0
    for i in range(len(a.norm)):
        for j in range(len(b.norm)):
            if a.script[i] == b.script[j]:
                has_ss = 1.0
                sims = _string_sims(a.norm[i], b.norm[j], a.key[i], b.key[j], a.tokens[i], b.tokens[j])
                for k in range(7):
                    if sims[k] > ss[k]:
                        ss[k] = sims[k]
                if a.key[i] == b.key[j]:
                    exact_key = 1.0
            else:
                has_cs = 1.0
            sims = _string_sims(a.skel[i], b.skel[j], a.skel_key[i], b.skel_key[j], a.skel_tokens[i], b.skel_tokens[j])
            for k in range(6):
                if sims[k] > sk[k]:
                    sk[k] = sims[k]
            if a.skel_key[i] == b.skel_key[j] and a.skel_key[i]:
                exact_skel = 1.0
    f[0:7] = ss
    f[7:13] = sk
    if a.vec is not None and b.vec is not None:
        sim = a.vec @ b.vec.T
        row, col = sim.max(axis=1), sim.max(axis=0)
        f[13] = float(sim.max())
        f[14] = float((row.mean() + col.mean()) / 2)
        ma, mb = a.vec.sum(axis=0), b.vec.sum(axis=0)
        denom = float(np.linalg.norm(ma) * np.linalg.norm(mb))
        f[15] = float(ma @ mb) / denom if denom > 0 else 0.0
        cross = [sim[i, j] for i in range(len(a.norm)) for j in range(len(b.norm)) if a.script[i] != b.script[j]]
        f[16] = float(max(cross)) if cross else 0.0
        f[17] = float(min(row.min(), col.min()))
    f[18], f[19], f[20], f[21] = exact_key, exact_skel, has_ss, has_cs
    f[22] = min(len(a.norm), len(b.norm))
    f[23] = max(len(a.norm), len(b.norm))
    lengths = [len(k) for k in a.key] + [len(k) for k in b.key]
    f[24] = min(min(lengths), 40)
    f[25] = min(max(lengths), 80)
    f[26] = 1.0 if a.seq == b.seq else 0.0
    f[27] = 1.0 if (a.seq and b.seq and not (a.seq & b.seq)) else 0.0
    f[28] = 1.0 if bool(a.seq) != bool(b.seq) else 0.0
    f[29] = _jaccard(a.seq, b.seq)
    f[30] = 1.0 if a.variants == b.variants else 0.0
    f[31] = min(len(a.variants ^ b.variants), 3) / 3
    f[32] = 1.0 if (a.years and b.years and not (a.years & b.years)) else 0.0
    f[33] = 1.0 if a.kind == b.kind else 0.0
    f[34] = min(a.kind, b.kind)
    f[35] = max(a.kind, b.kind)
    if a.year is not None and b.year is not None:
        f[36] = 1.0
        f[37] = 1.0 if a.year == b.year else 0.0
        f[38] = min(abs(a.year - b.year), 10) / 10
    if a.authors and b.authors:
        f[39] = 1.0
        best_lev = best_jac = 0.0
        for x in a.authors:
            sx = " ".join(sorted(x))
            for y in b.authors:
                best_lev = max(best_lev, Levenshtein.normalized_similarity(sx, " ".join(sorted(y))))
                best_jac = max(best_jac, _jaccard(x, y))
        f[40], f[41] = best_lev, best_jac
    return stabilize_features(f)
