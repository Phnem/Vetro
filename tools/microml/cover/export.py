"""Put the trained detector into the app and write the golden numbers the Kotlin test compares against.

    python tools/microml/cover/export.py --model tools/microml/.cache/cover/model --data tools/microml/.cache/cover ^
        --assets app/src/main/assets/microml/cover --golden app/src/test/resources/microml/cover_golden.json

The model is tiny (a few tens of KB) and is tracked in git on purpose: a release built in a clean checkout still carries it.
Golden file: features and score of (a) a synthetic image defined by a formula the test repeats, (b) the feature vector of a
real placeholder variant (numbers only, no picture).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, ".."))
from export.formats import Forest  # noqa: E402
from features import FEATURES, SPEC, cover_features  # noqa: E402

SYNTH_W, SYNTH_H = 37, 53


def synthetic() -> np.ndarray:
    """Deterministic test picture; the Kotlin test builds the same pixels from the same formula."""
    y, x = np.mgrid[0:SYNTH_H, 0:SYNTH_W]
    r = (x * 7 + y * 3) % 256
    g = (x * y) % 256
    b = (x * 5 + y * 11) % 256
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--data", required=True)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--golden", required=True)
    args = ap.parse_args()
    blob = open(os.path.join(args.model, "cover_placeholder.vmlf"), "rb").read()
    report = json.load(open(os.path.join(args.model, "report.json")))
    forest = Forest(blob)
    os.makedirs(args.assets, exist_ok=True)
    open(os.path.join(args.assets, "cover_placeholder.vmlf"), "wb").write(blob)
    manifest = {
        "schema": 1, "spec": SPEC, "version": f"cover-{report['version']}", "file": "cover_placeholder.vmlf",
        "bytes": len(blob), "sha256": hashlib.sha256(blob).hexdigest(), "threshold": round(report["threshold"], 4),
        "features": FEATURES, "metrics": report["test"], "status": "SHADOW",
    }
    json.dump(manifest, open(os.path.join(args.assets, "manifest.json"), "w"), indent=1)

    d = np.load(os.path.join(args.data, "features.npz"))
    pick = np.flatnonzero((d["group"] == "test") & (d["y"] == 1) & (d["source"] == "real"))[0]
    synth = cover_features(synthetic())
    golden = {
        "synthetic": {"width": SYNTH_W, "height": SYNTH_H, "features": [float(v) for v in synth],
                      "score": float(forest.predict(synth)[0])},
        "placeholder": {"features": [float(v) for v in d["x"][pick]], "score": float(forest.predict(d["x"][pick])[0])},
    }
    os.makedirs(os.path.dirname(args.golden), exist_ok=True)
    json.dump(golden, open(args.golden, "w"))
    print("exported", manifest["version"], len(blob), "bytes; threshold", manifest["threshold"])


if __name__ == "__main__":
    main()
