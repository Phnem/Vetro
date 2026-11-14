"""Embed corpus titles with a large multilingual teacher (desktop only, never shipped).

Two uses: an upper-bound reference for the tiny encoder's retrieval numbers, and soft
targets for distillation. The teacher's weights do not enter the app; only the student
table trained to imitate its geometry does.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from encoder.train_encoder import load_split, retrieval_report  # noqa: E402


def main() -> None:
    import torch
    from sentence_transformers import SentenceTransformer
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=ROOT / ".cache/corpus")
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--stages", nargs="+", default=["validation"])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch", type=int, default=256)
    parser.add_argument("--dim", type=int, default=0, help="keep the first N dims (Matryoshka models) or 0 for all")
    args = parser.parse_args()
    model = SentenceTransformer(str(args.model), device="cuda", model_kwargs={"torch_dtype": torch.float16})
    model.max_seq_length = 48
    args.output.mkdir(parents=True, exist_ok=True)
    for stage in args.stages:
        texts, owner, groups, _, _ = load_split(args.corpus, stage)
        started = time.time()
        order = np.argsort([len(t) for t in texts])
        vectors = np.zeros((len(texts), args.dim or model.get_sentence_embedding_dimension()), np.float16)
        for i in range(0, len(order), args.batch * 64):
            chunk = order[i:i + args.batch * 64]
            emb = model.encode([texts[j] for j in chunk], batch_size=args.batch, convert_to_numpy=True,
                               normalize_embeddings=False, show_progress_bar=False)
            if args.dim:
                emb = emb[:, :args.dim]
            emb = emb / np.maximum(np.linalg.norm(emb, axis=1, keepdims=True), 1e-9)
            vectors[chunk] = emb.astype(np.float16)
            print(json.dumps({"stage": stage, "done": int(min(len(order), i + len(chunk))), "of": len(order),
                              "seconds": round(time.time() - started, 1)}), flush=True)
        np.save(args.output / f"{stage}.npy", vectors)
        (args.output / f"{stage}.texts.json").write_text(json.dumps(texts, ensure_ascii=False), encoding="utf-8")
        device = torch.device("cuda")
        report = retrieval_report(torch.from_numpy(vectors.astype(np.float32)).to(device), texts, owner, groups, device)
        (args.output / f"{stage}.report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
        print(json.dumps({"stage": stage, "report": report}), flush=True)


if __name__ == "__main__":
    main()
