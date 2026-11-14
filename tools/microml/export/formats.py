"""Binary formats read by the Kotlin MicroML runtime. All little-endian.

VMLF0002 forest
    magic[8] | i32 features | i32 classes | i32 trees | i32 link (0 raw, 1 sigmoid, 2 softmax)
    per tree: i32 internal | i32 leaves
              internal x (i16 feature, i16 left, i16 right, f32 threshold)   child < 0 -> leaf ~child
              leaves x f32 value
    Multiclass trees are interleaved: tree t adds to class t % classes.
    Thresholds are rounded DOWN to float32, which keeps every `x <= t` decision of a float32 input exact.

VMLE0001 table (hashed n-gram embeddings or linear classifier weights)
    magic[8] | i32 buckets | i32 dim | f32 levels[256] | u8 code[buckets] | i8 q[buckets*dim]
    row r = q[r] * levels[code[r]]
"""
from __future__ import annotations

import struct

import numpy as np

FOREST_MAGIC = b"VMLF0002"
TABLE_MAGIC = b"VMLE0001"
LINKS = {"raw": 0, "sigmoid": 1, "softmax": 2}


def _floor32(value: float) -> np.float32:
    t = np.float32(value)
    if float(t) > value:
        t = np.nextafter(t, np.float32(-np.inf), dtype=np.float32)
    return t


def forest_bytes(model_text: str, link: str) -> bytes:
    """Serialise a LightGBM model string (numerical splits only, no missing values in the data)."""
    header: dict[str, str] = {}
    trees: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for line in model_text.splitlines():
        if line.startswith("Tree="):
            current = {}
            trees.append(current)
        elif line.startswith("end of trees"):
            break
        elif "=" in line:
            k, v = line.split("=", 1)
            (current if current is not None else header)[k] = v
    features = int(header["max_feature_idx"]) + 1
    classes = int(header.get("num_tree_per_iteration", "1"))
    out = bytearray(FOREST_MAGIC)
    out += struct.pack("<iiii", features, classes, len(trees), LINKS[link])
    for tree in trees:
        leaves = int(tree["num_leaves"])
        values = np.asarray(tree["leaf_value"].split(), dtype=np.float64)
        if "shrinkage" in tree and leaves == 1:
            pass  # a constant tree already carries its shrunken value
        if leaves == 1:
            out += struct.pack("<ii", 0, 1) + struct.pack("<f", float(values[0]))
            continue
        feature = np.asarray(tree["split_feature"].split(), dtype=np.int64)
        threshold = [float(x) for x in tree["threshold"].split()]
        left = np.asarray(tree["left_child"].split(), dtype=np.int64)
        right = np.asarray(tree["right_child"].split(), dtype=np.int64)
        decision = np.asarray(tree["decision_type"].split(), dtype=np.int64)
        if np.any(decision & 1):
            raise ValueError("categorical splits are not supported")
        if leaves > 32767:
            raise ValueError("tree too large")
        out += struct.pack("<ii", leaves - 1, leaves)
        for i in range(leaves - 1):
            out += struct.pack("<hhhf", int(feature[i]), int(left[i]), int(right[i]), float(_floor32(threshold[i])))
        out += values.astype("<f4").tobytes()
    return bytes(out)


class Forest:
    """Reference reader: the same arithmetic the Kotlin runtime performs."""

    def __init__(self, data: bytes):
        if data[:8] != FOREST_MAGIC:
            raise ValueError("bad magic")
        self.features, self.classes, count, self.link = struct.unpack_from("<iiii", data, 8)
        offset = 24
        self.trees = []
        for _ in range(count):
            internal, leaves = struct.unpack_from("<ii", data, offset)
            offset += 8
            nodes = np.frombuffer(data, dtype=np.dtype([("f", "<i2"), ("l", "<i2"), ("r", "<i2"), ("t", "<f4")]),
                                  count=internal, offset=offset)
            offset += internal * 10
            values = np.frombuffer(data, dtype="<f4", count=leaves, offset=offset)
            offset += leaves * 4
            self.trees.append((nodes, values))
        if offset != len(data):
            raise ValueError("trailing bytes")

    def raw(self, x: np.ndarray) -> np.ndarray:
        x = np.asarray(x, dtype=np.float32)
        sums = np.zeros(self.classes, dtype=np.float64)
        for t, (nodes, values) in enumerate(self.trees):
            node = 0 if len(nodes) else -1
            while node >= 0:
                n = nodes[node]
                node = int(n["l"] if x[n["f"]] <= n["t"] else n["r"])
            sums[t % self.classes] += float(values[~node])
        return sums

    def predict(self, x: np.ndarray) -> np.ndarray:
        s = self.raw(x)
        if self.link == 1:
            return 1 / (1 + np.exp(-s))
        if self.link == 2:
            e = np.exp(s - s.max())
            return e / e.sum()
        return s


def table_bytes(q: np.ndarray, code: np.ndarray, levels: np.ndarray) -> bytes:
    buckets, dim = q.shape
    if buckets & (buckets - 1) or code.shape != (buckets,) or levels.shape != (256,):
        raise ValueError("bad table shape")
    return (TABLE_MAGIC + struct.pack("<ii", buckets, dim) + levels.astype("<f4").tobytes()
            + code.astype(np.uint8).tobytes() + q.astype(np.int8).tobytes())


def read_table(data: bytes) -> np.ndarray:
    if data[:8] != TABLE_MAGIC:
        raise ValueError("bad magic")
    buckets, dim = struct.unpack_from("<ii", data, 8)
    levels = np.frombuffer(data, dtype="<f4", count=256, offset=16)
    code = np.frombuffer(data, dtype=np.uint8, count=buckets, offset=16 + 1024)
    q = np.frombuffer(data, dtype=np.int8, count=buckets * dim, offset=16 + 1024 + buckets).reshape(buckets, dim)
    return q.astype(np.float32) * levels[code][:, None]


def quantize_rows(table: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Row-wise int8 with 256 log-spaced scale levels (shared with encoder/train_encoder.py)."""
    import math
    peak = np.abs(table).max(axis=1)
    positive = peak[peak > 0]
    lo = max(float(positive.min()), 1e-6) if len(positive) else 1e-6
    hi = max(float(peak.max()), lo * 1.0001)
    levels = np.exp(np.linspace(math.log(lo), math.log(hi), 256)).astype(np.float32) / 127.0
    code = np.clip(np.searchsorted(levels * 127.0, peak, side="left"), 0, 255).astype(np.uint8)
    q = np.clip(np.rint(table / levels[code][:, None]), -127, 127).astype(np.int8)
    return q, code, levels
