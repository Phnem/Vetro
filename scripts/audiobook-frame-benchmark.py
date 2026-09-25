"""Measure the AB-10 square-player transition on the Xiaomi 1080x2400 smoke build.

Run only while the phone is unlocked with the AB-10 demo Activity visible.
The foreground package and a screen pixel are checked after every tap; a run
interrupted by another app is rejected instead of being reported as a pass.
"""

import io
import re
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image


DEVICE = "3871a9d6"
PACKAGE = "com.phnem.vetro.ab07smoke"
ADB = ["adb", "-s", DEVICE]
OUTPUT = Path(".scratch/audiobooks/reviews/assets/ab10-phone-20cycles-verified.txt")


def adb(*args: str) -> str:
    return subprocess.check_output(ADB + list(args), text=True, errors="replace")


def foreground() -> bool:
    activities = adb("shell", "dumpsys", "activity", "activities")
    match = re.search(r"topResumedActivity=.*", activities)
    return match is not None and PACKAGE in match.group()


def screen() -> str:
    png = subprocess.check_output(ADB + ["exec-out", "screencap", "-p"])
    image = Image.open(io.BytesIO(png)).convert("RGB")
    if image.size != (1080, 2400):
        raise RuntimeError(f"Unexpected display size: {image.size}")
    red, green, blue = image.getpixel((50, 400))
    if min(red, green, blue) > 180:
        return "mini"
    if max(red, green, blue) < 100:
        return "full"
    raise RuntimeError(f"Unrecognized surface pixel: {(red, green, blue)}")


def tap(x: int, y: int, expected: str) -> None:
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.43)
    if not foreground():
        raise RuntimeError("The audiobook Activity lost foreground")
    actual = screen()
    if actual != expected:
        raise RuntimeError(f"Expected {expected}, saw {actual}")


def main() -> int:
    if not foreground():
        raise RuntimeError("Open the AB-10 demo Activity before the benchmark")
    if screen() == "full":
        tap(135, 140, "mini")
    for _ in range(2):
        tap(900, 1750, "full")
        tap(135, 140, "mini")
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    started = time.monotonic()
    for cycle in range(1, 21):
        tap(900, 1750, "full")
        tap(135, 140, "mini")
        print(f"verified {cycle}/20", flush=True)
    report = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
    histogram = re.search(r"HISTOGRAM: (.*)", report)
    if histogram is None:
        raise RuntimeError("gfxinfo histogram missing")
    over_32 = sum(int(count) for millis, count in re.findall(r"(\d+)ms=(\d+)", histogram.group(1)) if int(millis) > 32)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    content = (
        f"Verified 20 mini-full-mini cycles; elapsed {time.monotonic()-started:.1f}s; "
        f"histogram frames >32ms: {over_32}\n\n{report}"
    )
    OUTPUT.write_bytes(("\n".join(line.rstrip() for line in content.splitlines()) + "\n").encode("utf-8"))
    print(f"saved {OUTPUT.resolve()}; >32ms={over_32}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"INVALID BENCHMARK: {error}", file=sys.stderr)
        sys.exit(1)
