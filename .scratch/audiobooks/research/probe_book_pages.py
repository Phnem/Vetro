"""Inspect public AB-05 sample pages cached in TEMP; no media is requested."""

from collections import Counter
from pathlib import Path
from urllib.parse import urlparse
import os
import re
import sys

from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")

NAMES = (
    "aknigi24-book",
    "audiokniga-one-book",
    "knigavuhe-book",
    "knigavuhe-collection",
    "realaudiobooks-book",
    "goldenaudiobooks-book",
    "101audiobooks-book",
    "audioaz-book",
)

for name in NAMES:
    path = Path(os.environ["TEMP"]) / f"ab05-{name}.html"
    raw = path.read_bytes()
    soup = BeautifulSoup(raw, "html.parser")
    print(f"\n== {name} ==")
    print("title:", soup.title.get_text(" ", strip=True) if soup.title else "none")
    print("h1:", [tag.get_text(" ", strip=True)[:90] for tag in soup.select("h1")[:3]])
    for key in ("og:title", "og:image", "og:type", "description"):
        tag = soup.find("meta", attrs={"property": key}) or soup.find("meta", attrs={"name": key})
        if tag:
            value = tag.get("content", "")
            print("meta:", key, value[:120] if key != "og:image" else urlparse(value).netloc)
    print("audio:", len(soup.select("audio")), "source:", len(soup.select("source")))
    print("json-ld:", len(soup.select('script[type="application/ld+json"]')))
    media_urls = re.findall(r"https?://[^\s\"'<>]+?\.(?:mp3|m4a|m3u8)(?:\?[^\s\"'<>]*)?", raw.decode("utf-8", errors="ignore"), re.I)
    print("media-urls:", len(media_urls), "hosts:", dict(Counter(urlparse(u).netloc for u in media_urls)))
    print("media extensions:", dict(Counter(u.split("?")[0].rsplit(".", 1)[-1] for u in media_urls)))
    print("images:", len(soup.select("img")))
    print("restricted-word:", any(word in soup.get_text(" ", strip=True).lower() for word in ("правообладател", "rights holder")))
    for audio in soup.select("audio")[:2]:
        print("audio-attrs:", {key: (urlparse(value).netloc if key in ("src", "data-src") else value[:80])
                               for key, value in audio.attrs.items() if isinstance(value, str)})
    print("chapter-like:", {key: len(soup.select(f'[class*="{key}"]')) for key in ("track", "chapter", "playlist")})
    text = soup.get_text(" ", strip=True)
    hit = text.lower().find("правообладател")
    if hit >= 0:
        print("rights-context:", text[max(0, hit - 60):hit + 110])
