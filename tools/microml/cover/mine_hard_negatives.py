"""Find real covers the current detector finds suspicious or that are mostly white, to train on as hard negatives.

    python tools/microml/cover/mine_hard_negatives.py --model tools/microml/.cache/cover/model/cover_placeholder.vmlf ^
        --dictionary media-dictionary --out tools/microml/.cache/cover/hard.npz --sample 30000

Keeps every cover whose score is >= 0.01 or whose share of near-white cells is >= 0.3 (the placeholder is a pale drawing on white).
"""
from __future__ import annotations

import argparse
import os
import random
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
from export.formats import Forest  # noqa: E402
from features import cover_features  # noqa: E402

WHITE_SHARE_INDEX = 305


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--dictionary", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--sample", type=int, default=30000)
    ap.add_argument("--seed", type=int, default=99)
    args = ap.parse_args()
    forest = Forest(open(args.model, "rb").read())
    files = []
    for kind in ("anime", "cinema"):
        folder = os.path.join(args.dictionary, kind, "cover_images", "small")
        files += [os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".avif")]
    random.Random(args.seed).shuffle(files)
    keep_x, keep_p = [], []
    checked = 0
    for path in files[: args.sample]:
        try:
            f = cover_features(np.asarray(Image.open(path).convert("RGB")))
        except Exception:
            continue
        if f is None:
            continue
        checked += 1
        p = float(forest.predict(f)[0])
        if p >= 0.01 or f[WHITE_SHARE_INDEX] >= 0.3:
            keep_x.append(f); keep_p.append(p)
        if checked % 5000 == 0:
            print(f"checked {checked}, kept {len(keep_x)}", flush=True)
    np.savez_compressed(args.out, x=np.stack(keep_x), p=np.asarray(keep_p, dtype=np.float32))
    print(f"checked {checked}, kept {len(keep_x)}, max score {max(keep_p):.3f}", flush=True)


if __name__ == "__main__":
    main()
