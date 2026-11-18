"""Training set for the Shikimori stock-cover detector.

    python tools/microml/cover/build_dataset.py --phone-covers <folder with the collection's images> ^
        --dictionary media-dictionary --out tools/microml/.cache/cover

Positives: the placeholder variants that really sit in the collection (found as the largest group of look-alike images:
a girl holding a hand up, "404 not found" on her shirt) plus augmented copies (rescaled, re-encoded, slightly cropped,
stretched, noisy). Negatives: real covers from the phone and a random sample of the media dictionary. A different stock
image from another source (the manami "no cover" tanuki) is not a negative by intent, but it is rare enough in the sample to
be left alone.
Writes features.npz (x, y, group, source) and positives-real.json (which real variants are held out for the test).
"""
from __future__ import annotations

import argparse
import io
import json
import os
import random
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from features import FEATURES, cover_features  # noqa: E402

EXTS = (".webp", ".jpg", ".jpeg", ".png")


def load_rgb(path: str) -> np.ndarray | None:
    try:
        return np.asarray(Image.open(path).convert("RGB"))
    except Exception:
        return None


def small_signature(rgb: np.ndarray) -> np.ndarray:
    im = Image.fromarray(rgb).convert("L").resize((16, 16), Image.LANCZOS)
    a = np.asarray(im, dtype=np.float32).ravel()
    return (a - a.mean()) / (a.std() + 1e-6)


def find_placeholder_group(paths: list[str]) -> list[str]:
    """The biggest group of near-identical images (correlation of 16x16 thumbnails > 0.97)."""
    sigs, ok = [], []
    for p in paths:
        rgb = load_rgb(p)
        if rgb is not None and min(rgb.shape[:2]) >= 16:
            sigs.append(small_signature(rgb)); ok.append(p)
    sig = np.stack(sigs)
    corr = sig @ sig.T / sig.shape[1]
    best: list[int] = []
    used: set[int] = set()
    for i in range(len(ok)):
        if i in used:
            continue
        group = [j for j in range(len(ok)) if corr[i, j] > 0.97 and j not in used]
        used.update(group)
        if len(group) > len(best):
            best = group
    return [ok[i] for i in best]


def augment(base: Image.Image, rng: random.Random) -> np.ndarray:
    """One re-encoded and re-scaled variant of the placeholder, as the app can meet it."""
    im = base.convert("RGB")
    w, h = im.size
    # the visible area can be cropped a little by whoever served the file
    left, top = int(w * rng.uniform(0, 0.03)), int(h * rng.uniform(0, 0.03))
    right, bottom = w - int(w * rng.uniform(0, 0.03)), h - int(h * rng.uniform(0, 0.03))
    im = im.crop((left, top, right, bottom))
    scale = rng.choice([0.2, 0.3, 0.4, 0.5, 0.7, 1.0, 1.0, 1.4, 2.0])
    stretch = rng.uniform(0.94, 1.06)
    size = (max(24, int(im.width * scale * stretch)), max(24, int(im.height * scale)))
    im = im.resize(size, rng.choice([Image.LANCZOS, Image.BILINEAR, Image.BICUBIC, Image.NEAREST]))
    buf = io.BytesIO()
    if rng.random() < 0.5:
        im.save(buf, "JPEG", quality=rng.randint(25, 95))
    else:
        im.save(buf, "WEBP", quality=rng.randint(25, 95))
    arr = np.asarray(Image.open(io.BytesIO(buf.getvalue())).convert("RGB")).astype(np.float32)
    arr = arr * rng.uniform(0.95, 1.05) + rng.uniform(-6, 6)
    if rng.random() < 0.5:
        arr = arr + np.random.default_rng(rng.randint(0, 1 << 30)).normal(0, rng.uniform(0.5, 4), arr.shape)
    return np.clip(arr, 0, 255).astype(np.uint8)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--phone-covers", required=True)
    ap.add_argument("--dictionary", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--negatives", type=int, default=14000)
    ap.add_argument("--augment", type=int, default=3000)
    ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)

    paths = [os.path.join(r, f) for r, _, fs in os.walk(args.phone_covers) for f in fs if f.lower().endswith(EXTS)]
    group = find_placeholder_group(paths)
    print(f"phone images: {len(paths)}; placeholder variants found: {len(group)}")
    if len(group) < 4:
        raise SystemExit("could not find the placeholder in the collection")
    in_group = set(group)

    # Real variants: every second one (by size) is held out for the test, the rest feed the augmentation.
    group.sort(key=lambda p: Image.open(p).size)
    held_out = group[1::2]
    held_set = set(held_out)
    train_base = [p for p in group if p not in held_set]
    print(f"held out for the test: {len(held_out)}; used for training: {len(train_base)}")

    x, y, grp, src = [], [], [], []

    def add(rgb, label, group_name, source):
        f = cover_features(rgb)
        if f is not None:
            x.append(f); y.append(label); grp.append(group_name); src.append(source)

    for p in held_out:
        add(load_rgb(p), 1, "test", "real")
    bases = [Image.open(p) for p in train_base]
    for p in train_base:
        add(load_rgb(p), 1, "train", "real")
    for _ in range(args.augment):
        add(augment(rng.choice(bases), rng), 1, "train", "aug")
    # a small augmented test set made from the held-out variants only
    held_images = [Image.open(p) for p in held_out]
    for _ in range(400):
        add(augment(rng.choice(held_images), rng), 1, "test", "aug")

    # Negatives: the rest of the phone's covers, then a random sample of the dictionary.
    for p in paths:
        if p in in_group:
            continue
        rgb = load_rgb(p)
        if rgb is not None:
            add(rgb, 0, "test" if rng.random() < 0.25 else "train", "phone")
    dict_files = []
    for kind in ("anime", "cinema"):
        folder = os.path.join(args.dictionary, kind, "cover_images", "small")
        if os.path.isdir(folder):
            dict_files += [os.path.join(folder, f) for f in os.listdir(folder) if f.endswith(".avif")]
    rng.shuffle(dict_files)
    print(f"dictionary covers available: {len(dict_files)}")
    taken = 0
    for p in dict_files:
        if taken >= args.negatives:
            break
        rgb = load_rgb(p)
        if rgb is None:
            continue
        add(rgb, 0, "test" if rng.random() < 0.2 else "train", "dictionary")
        taken += 1
        if taken % 2000 == 0:
            print(f"  negatives: {taken}")
    xs = np.stack(x)
    assert xs.shape[1] == FEATURES
    np.savez_compressed(os.path.join(args.out, "features.npz"), x=xs, y=np.asarray(y, dtype=np.int8),
                        group=np.asarray(grp), source=np.asarray(src))
    json.dump({"held_out": [os.path.basename(p) for p in held_out], "train": [os.path.basename(p) for p in train_base]},
              open(os.path.join(args.out, "positives-real.json"), "w"), indent=1)
    print("saved", xs.shape, "positives", int(sum(y)), "negatives", int(len(y) - sum(y)))


if __name__ == "__main__":
    main()
