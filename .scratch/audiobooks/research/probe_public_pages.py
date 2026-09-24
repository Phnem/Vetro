"""Inspect already downloaded public HTML for AB-05; performs no network requests."""

from pathlib import Path
import os
import sys

from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")


NAMES = (
    "aknigi24",
    "audiokniga-one",
    "knigavuhe",
    "realaudiobooks",
    "goldenaudiobooks",
    "101audiobooks",
    "audioaz",
)

for name in NAMES:
    path = Path(os.environ["TEMP"]) / f"ab05-{name}.html"
    soup = BeautifulSoup(path.read_bytes(), "html.parser")
    print(f"\n== {name} ==")
    print("title:", soup.title.get_text(" ", strip=True) if soup.title else "none")
    for form in soup.find_all("form")[:4]:
        inputs = [(entry.get("name"), entry.get("type")) for entry in form.find_all("input")[:8]]
        print("form:", form.get("action"), form.get("method"), inputs)
    for anchor in soup.select("a[href]")[:8]:
        print("link:", anchor.get("href"), anchor.get_text(" ", strip=True)[:45])
    for anchor in [a for a in soup.select("a[href]") if "/book/" in a.get("href", "")][:3]:
        print("book-link:", anchor.get("href"), anchor.get_text(" ", strip=True)[:45])
    print("audio-tags:", len(soup.find_all("audio")))
    print("json-ld:", len(soup.find_all("script", attrs={"type": "application/ld+json"})))
