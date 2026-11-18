"""Features of a cover image for the stock-placeholder detector (contract `cover-features-v1`).

The Kotlin side (app/.../microml/CoverFeatures.kt) computes exactly the same numbers from decoded pixels, with integer
arithmetic where it matters, so the model sees the same input on the phone as in training.

Input: RGB uint8 array (H, W, 3), both sides >= 16. Output: float32 vector of FEATURES numbers.
  [0, 256)    luma of a 16x16 grid: mean over each cell (cells are integer partitions: rows [i*H//16, (i+1)*H//16)),
              luma = (299 R + 587 G + 114 B) / 255000
  [256, 304)  mean R, G, B of a 4x4 grid (same partitioning), each in 0..1, order cell-major then channel
  304         mean luma of the whole image
  305         share of the 16x16 cells whose mean luma is above 0.9
  306         aspect ratio W / H
"""
from __future__ import annotations

import numpy as np

GRID = 16
COLOR_GRID = 4
FEATURES = GRID * GRID + COLOR_GRID * COLOR_GRID * 3 + 3
SPEC = "cover-features-v1"
MIN_SIDE = 16
WHITE_LEVEL = 0.9


def _edges(size: int, parts: int) -> np.ndarray:
    return (np.arange(parts + 1, dtype=np.int64) * size) // parts


def _cell_sums(plane: np.ndarray, parts: int) -> tuple[np.ndarray, np.ndarray]:
    """Integer sums of a (H, W) int64 plane over a parts x parts grid, and the pixel count of every cell."""
    h, w = plane.shape
    er, ec = _edges(h, parts), _edges(w, parts)
    rows = np.add.reduceat(plane, er[:-1], axis=0)
    sums = np.add.reduceat(rows, ec[:-1], axis=1)
    counts = np.outer(np.diff(er), np.diff(ec))
    return sums, counts


def cover_features(rgb: np.ndarray) -> np.ndarray | None:
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError("expected an RGB image")
    h, w, _ = rgb.shape
    if h < MIN_SIDE or w < MIN_SIDE:
        return None
    px = rgb.astype(np.int64)
    luma = 299 * px[:, :, 0] + 587 * px[:, :, 1] + 114 * px[:, :, 2]
    sums, counts = _cell_sums(luma, GRID)
    grid = sums / (counts * 255000.0)
    out = np.zeros(FEATURES, dtype=np.float32)
    out[: GRID * GRID] = grid.reshape(-1)
    pos = GRID * GRID
    channels = []
    for c in range(3):
        s, n = _cell_sums(px[:, :, c], COLOR_GRID)
        channels.append(s / (n * 255.0))
    color = np.stack(channels, axis=-1)  # (4, 4, 3): cell-major, then channel
    out[pos : pos + COLOR_GRID * COLOR_GRID * 3] = color.reshape(-1)
    pos += COLOR_GRID * COLOR_GRID * 3
    out[pos] = luma.sum() / (h * w * 255000.0)
    out[pos + 1] = np.count_nonzero(grid > WHITE_LEVEL) / float(GRID * GRID)
    out[pos + 2] = w / h
    return out
