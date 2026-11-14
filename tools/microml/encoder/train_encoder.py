"""Train the shared Vetro title encoder (hashed n-gram embedding bag, text-spec-v1).

One table E[buckets, dim]; a title vector is the L2-normalised sum of the rows its
n-grams hash to. Trained from scratch with a symmetric InfoNCE loss: two titles of
the same Wikidata entity attract, titles of other franchise groups repel.
"""
from __future__ import annotations

import argparse
import json
import math
import multiprocessing as mp
import sys
import time
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from vml.text import SPEC, bucket_ids, normalize, script  # noqa: E402


def _hash_chunk(args: tuple[list[str], int]) -> tuple[np.ndarray, np.ndarray]:
    texts, buckets = args
    lengths = np.zeros(len(texts), dtype=np.int32)
    flat: list[int] = []
    for i, text in enumerate(texts):
        ids = bucket_ids(text, buckets)
        lengths[i] = len(ids)
        flat.extend(ids)
    return np.asarray(flat, dtype=np.int32), lengths


def hash_texts(texts: list[str], buckets: int, workers: int = 6) -> tuple[np.ndarray, np.ndarray]:
    """CSR (indices, offsets) of bucket ids for normalised texts."""
    chunk = 20000
    jobs = [(texts[i:i + chunk], buckets) for i in range(0, len(texts), chunk)]
    if len(jobs) > 2:
        with mp.Pool(workers) as pool:
            parts = pool.map(_hash_chunk, jobs)
    else:
        parts = [_hash_chunk(j) for j in jobs]
    indices = np.concatenate([p[0] for p in parts]) if parts else np.zeros(0, np.int32)
    lengths = np.concatenate([p[1] for p in parts]) if parts else np.zeros(0, np.int32)
    offsets = np.zeros(len(texts) + 1, dtype=np.int64)
    np.cumsum(lengths, out=offsets[1:])
    return indices, offsets


def load_split(corpus: Path, split: str, max_titles: int = 16):
    """Per entity: distinct normalised titles. Returns (texts, entity_of_text, group_of_entity, kind, qids)."""
    ents = pq.read_table(corpus / "entities.parquet", columns=["qid", "kind", "group", "stage"]).to_pylist()
    keep = {e["qid"]: e for e in ents if e["stage"] == split}
    generic = set(json.loads((corpus / "generic_keys.json").read_text(encoding="utf-8")))
    by_entity: dict[str, dict[str, str]] = {}
    table = pq.read_table(corpus / "titles.parquet", columns=["qid", "lang", "text"])
    for q, lang, text in zip(table["qid"].to_pylist(), table["lang"].to_pylist(), table["text"].to_pylist()):
        if q not in keep:
            continue
        norm = normalize(text)
        if not norm or len(norm) > 160 or norm.replace(" ", "") in generic:
            continue
        slot = by_entity.setdefault(q, {})
        if norm not in slot and len(slot) < max_titles:
            slot[norm] = lang
    extra_groups: dict[str, str] = {}
    if split == "enc":
        # Names of people, genres and series: the same "two labels of one thing" signal as titles.
        # One in seven is withheld so the genre normaliser and author matching can be measured on unseen names.
        for name in ("authors", "genres"):  # series hubs are corpus entities with their own split
            path = corpus / f"{name}.json"
            if not path.exists():
                continue
            for q, labels in json.loads(path.read_text(encoding="utf-8")).items():
                if int(q[1:]) % 7 == 0 or q in by_entity or q in keep:
                    continue
                slot: dict[str, str] = {}
                for lang, text in labels:
                    norm = normalize(text)
                    if norm and len(norm) <= 160 and norm not in slot and len(slot) < max_titles:
                        slot[norm] = lang
                if len(slot) >= 2:
                    by_entity[q] = slot
                    extra_groups[q] = "ref:" + q
    qids = sorted(by_entity, key=lambda x: int(x[1:]))
    texts: list[str] = []
    owner: list[int] = []
    for i, q in enumerate(qids):
        for norm in by_entity[q]:
            texts.append(norm)
            owner.append(i)
    group_of = {q: extra_groups.get(q) or keep[q]["group"] for q in qids}
    groups = {g: i for i, g in enumerate(sorted(set(group_of.values())))}
    kinds = [keep[q]["kind"] if q in keep else "REF" for q in qids]
    return texts, np.asarray(owner, np.int32), np.asarray([groups[group_of[q]] for q in qids], np.int32), kinds, qids


class Bags:
    """CSR bags on the GPU with a fast batched gather."""

    def __init__(self, indices: np.ndarray, offsets: np.ndarray, device):
        import torch
        self.indices = torch.from_numpy(indices.astype(np.int64)).to(device)
        self.offsets = torch.from_numpy(offsets).to(device)
        self.device = device

    def batch(self, rows):
        import torch
        start = self.offsets[rows]
        length = self.offsets[rows + 1] - start
        total = int(length.sum())
        bag_offsets = torch.cumsum(length, 0) - length
        position = torch.arange(total, device=self.device) - torch.repeat_interleave(bag_offsets, length)
        flat = self.indices[torch.repeat_interleave(start, length) + position]
        return flat, bag_offsets


def embed_all(table, bags: Bags, n: int, batch: int = 65536):
    import torch
    import torch.nn.functional as F
    out = []
    with torch.no_grad():
        for i in range(0, n, batch):
            rows = torch.arange(i, min(n, i + batch), device=bags.device)
            flat, offs = bags.batch(rows)
            out.append(F.normalize(F.embedding_bag(flat, table, offs, mode="sum"), dim=1))
    return torch.cat(out)


def retrieval_report(vectors, texts: list[str], owner: np.ndarray, group_of_entity: np.ndarray, device,
                     max_queries: int = 20000, seed: int = 7) -> dict:
    """Leave-one-title-out retrieval inside one split: query a title, find another title of the same entity."""
    import torch
    rng = np.random.default_rng(seed)
    scripts = np.asarray([script(t) for t in texts], np.int8)
    owner_t = torch.from_numpy(owner.astype(np.int64)).to(device)
    group_t = torch.from_numpy(group_of_entity.astype(np.int64)).to(device)[owner_t]
    counts = np.bincount(owner)
    eligible = np.flatnonzero(counts[owner] >= 2)
    queries = rng.choice(eligible, min(max_queries, len(eligible)), replace=False)
    result: dict[str, dict] = {}
    script_t = torch.from_numpy(scripts.astype(np.int64)).to(device)
    names = {0: "latin", 1: "cyrillic", 2: "cjk", 3: "hangul", 4: "other", -1: "none"}
    stats: dict[str, list[float]] = {}
    for start in range(0, len(queries), 2048):
        q = torch.from_numpy(queries[start:start + 2048]).to(device)
        sims = vectors[q] @ vectors.T
        sims[torch.arange(len(q), device=device), q] = -2.0
        top = sims.topk(10, dim=1).indices
        same_entity = owner_t[top] == owner_t[q][:, None]
        same_group = group_t[top] == group_t[q][:, None]
        # Cross-script view: best target restricted to titles written in another script.
        for target_script in (0, 1, 2):
            mask = (script_t != target_script)[None, :] | (script_t[q] == target_script)[:, None]
            cross = sims.masked_fill(mask, -2.0)
            has = ((owner_t[None, :] == owner_t[q][:, None]) & ~mask).any(dim=1)
            hit1 = (owner_t[cross.argmax(dim=1)] == owner_t[q]) & has
            top10 = cross.topk(10, dim=1).indices
            hit10 = (owner_t[top10] == owner_t[q][:, None]).any(dim=1) & has
            for src in (0, 1, 2):
                sel = has & (script_t[q] == src)
                if int(sel.sum()):
                    name = f"{names[src]}->{names[target_script]}"
                    s = stats.setdefault(name, [0, 0, 0])
                    s[0] += int(sel.sum())
                    s[1] += int(hit1[sel].sum())
                    s[2] += int(hit10[sel].sum())
        s = stats.setdefault("any", [0, 0, 0, 0])
        s[0] += len(q)
        s[1] += int(same_entity[:, 0].sum())
        s[2] += int(same_entity.any(dim=1).sum())
        s[3] += int(same_group.any(dim=1).sum())
    for name, s in stats.items():
        result[name] = {"queries": s[0], "recall@1": round(s[1] / s[0], 4), "recall@10": round(s[2] / s[0], 4)}
        if len(s) > 3:
            result[name]["group_recall@10"] = round(s[3] / s[0], 4)
    return result


def quantize(table: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Row-wise int8: q[row] * scales[code[row]]. 256 log-spaced scale levels."""
    peak = np.abs(table).max(axis=1)
    lo, hi = max(float(peak[peak > 0].min()), 1e-6), float(peak.max())
    levels = np.exp(np.linspace(math.log(lo), math.log(hi), 256)).astype(np.float32) / 127.0
    code = np.clip(np.searchsorted(levels * 127.0, peak, side="left"), 0, 255).astype(np.uint8)
    q = np.clip(np.rint(table / levels[code][:, None]), -127, 127).astype(np.int8)
    return q, code, levels


def main() -> None:
    import torch
    import torch.nn.functional as F
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--buckets", type=int, default=1 << 16)
    parser.add_argument("--dim", type=int, default=64)
    parser.add_argument("--steps", type=int, default=12000)
    parser.add_argument("--batch", type=int, default=4096)
    parser.add_argument("--lr", type=float, default=0.05)
    parser.add_argument("--temperature", type=float, default=0.05)
    parser.add_argument("--typo", type=float, default=0.10)
    parser.add_argument("--seed", type=int, default=20261110)
    args = parser.parse_args()
    assert args.buckets & (args.buckets - 1) == 0
    torch.manual_seed(args.seed)
    rng = np.random.default_rng(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    started = time.time()

    texts, owner, group_of_entity, _, qids = load_split(args.corpus, "enc")
    indices, offsets = hash_texts(texts, args.buckets)
    bags = Bags(indices, offsets, device)
    v_texts, v_owner, v_groups, _, _ = load_split(args.corpus, "validation")
    v_indices, v_offsets = hash_texts(v_texts, args.buckets)
    v_bags = Bags(v_indices, v_offsets, device)
    print(json.dumps({"train_titles": len(texts), "train_entities": len(qids), "val_titles": len(v_texts),
                      "avg_grams": round(len(indices) / max(1, len(texts)), 1),
                      "prep_seconds": round(time.time() - started, 1)}), flush=True)

    # Entities with two or more distinct titles supply the positive pairs.
    first = np.zeros(len(qids) + 1, dtype=np.int64)
    np.cumsum(np.bincount(owner, minlength=len(qids)), out=first[1:])
    multi = np.flatnonzero(first[1:] - first[:-1] >= 2)
    counts = (first[1:] - first[:-1])[multi]
    group_t = torch.from_numpy(group_of_entity.astype(np.int64)).to(device)

    table = torch.nn.Parameter(torch.randn(args.buckets, args.dim, device=device) * 0.1)
    optimizer = torch.optim.Adam([table], lr=args.lr)
    schedule = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=args.steps, pct_start=0.05)
    log = []
    for step in range(1, args.steps + 1):
        pick = rng.integers(0, len(multi), args.batch)
        ents = multi[pick]
        a = (rng.random(args.batch) * counts[pick]).astype(np.int64)
        b = (a + 1 + (rng.random(args.batch) * (counts[pick] - 1)).astype(np.int64)) % counts[pick]
        rows_a = torch.from_numpy(first[ents] + a).to(device)
        rows_b = torch.from_numpy(first[ents] + b).to(device)
        fa, oa = bags.batch(rows_a)
        fb, ob = bags.batch(rows_b)
        weight = None
        if args.typo > 0:  # drop a random share of n-grams: cheap stand-in for typos and truncated titles
            weight = (torch.rand(fa.shape[0], device=device) > args.typo * float(rng.random())).float()
        za = F.normalize(F.embedding_bag(fa, table, oa, mode="sum", per_sample_weights=weight), dim=1)
        zb = F.normalize(F.embedding_bag(fb, table, ob, mode="sum"), dim=1)
        logits = za @ zb.T / args.temperature
        g = group_t[torch.from_numpy(ents).to(device)]
        same = (g[:, None] == g[None, :]) & ~torch.eye(args.batch, dtype=torch.bool, device=device)
        logits = logits.masked_fill(same, -1e4)  # same franchise is not a negative
        target = torch.arange(args.batch, device=device)
        loss = (F.cross_entropy(logits, target) + F.cross_entropy(logits.T, target)) / 2
        optimizer.zero_grad(set_to_none=True)
        loss.backward()
        optimizer.step()
        schedule.step()
        if step % 500 == 0 or step == args.steps:
            entry = {"step": step, "loss": round(float(loss), 4), "seconds": round(time.time() - started, 1)}
            if step % 2000 == 0 or step == args.steps:
                vectors = embed_all(table.detach(), v_bags, len(v_texts))
                entry["val"] = retrieval_report(vectors, v_texts, v_owner, v_groups, device, max_queries=6000)["any"]
            log.append(entry)
            print(json.dumps(entry), flush=True)

    weights = table.detach().float().cpu().numpy()
    q, code, levels = quantize(weights)
    args.output.mkdir(parents=True, exist_ok=True)
    np.save(args.output / "weights.npy", weights.astype(np.float16))
    np.savez_compressed(args.output / "quantized.npz", q=q, code=code, levels=levels)
    # The table exactly as the phone reconstructs it: every downstream model trains on these numbers.
    np.save(args.output / "table.npy", q.astype(np.float32) * levels[code][:, None])
    fp = embed_all(table.detach(), v_bags, len(v_texts))
    deq = torch.from_numpy(q.astype(np.float32) * levels[code][:, None]).to(device)
    report = {"spec": SPEC, "buckets": args.buckets, "dim": args.dim, "steps": args.steps, "batch": args.batch,
              "train_titles": len(texts), "train_entities": len(qids), "seconds": round(time.time() - started, 1),
              "artifact_bytes": int(q.size + code.size + levels.size * 4),
              "validation_float": retrieval_report(fp, v_texts, v_owner, v_groups, device),
              "validation_int8": retrieval_report(embed_all(deq, v_bags, len(v_texts)), v_texts, v_owner, v_groups, device),
              "log": log, "args": {k: str(v) for k, v in vars(args).items()}}
    (args.output / "report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("artifact_bytes", "validation_float", "validation_int8")}), flush=True)


if __name__ == "__main__":
    main()
