"""Capture small, reproducible public-page metadata for AB-05 (never media)."""

from pathlib import Path
from urllib.parse import urljoin, urlparse
import json
import os
import re
import sys

import requests
from bs4 import BeautifulSoup

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "app/src/test/resources/audiobooks"
TEMP = Path(os.environ["TEMP"])

SOURCES = {
    "aknigi24": ("https://aknigi24.com/search?q=%D0%A7%D0%B5%D1%85%D0%BE%D0%B2", "book"),
    "knigavuhe": ("https://knigavuhe.org/search/?q=%D0%A7%D0%B5%D1%85%D0%BE%D0%B2", "book"),
    "realaudiobooks": ("https://realaudiobooks.com/?s=the+time+machine", "the-time-machine"),
    "goldenaudiobooks": ("https://goldenaudiobooks.com/?s=mark+twain", "audiobook"),
    "101audiobooks": ("https://101audiobooks.com/?s=arthur+c+clarke", "audiobook"),
    "audioaz": ("https://audioaz.com/en/search?q=the+time+machine", "/en/audiobook/"),
}

PAGES = {
    "aknigi24": ("https://aknigi24.com/book/liminalnye-prostranstva-travm-pucok-percepcii-eremenko-filipp", "book"),
    "audiokniga-one": ("https://audiokniga.one/17630-sbornik-radiopostanovok-1.html", "book"),
    "knigavuhe": ("https://knigavuhe.org/book/khameleon-5/", "book"),
    "knigavuhe-collection": ("https://knigavuhe.org/book/rasskazy-86/", "collection"),
    "realaudiobooks": ("https://realaudiobooks.com/the-time-machine-h-g-wells-baud/", "book"),
    "goldenaudiobooks": ("https://goldenaudiobooks.com/mark-twain-adventures-huckleberry-finn-audiobook/", "book"),
    "101audiobooks": ("https://101audiobooks.com/arthur-c-clarke-rama-ii-audiobook/", "book"),
    "audioaz": ("https://audioaz.com/en/audiobook/the-time-machine-version-7-by-h-g-wells", "book"),
}

EXTRA_PAGES = {
    "aknigi24": "https://aknigi24.com/book/ves-cexov-cast-1-cexov-anton-abdullaev-dzaxangir",
    "audiokniga-one": "https://audiokniga.one/24863-moe-prokljatie.html",
    "realaudiobooks": "https://realaudiobooks.com/m-mitchell-waldrop-the-dream-machine-m-mitchell-waldrop-unabridged/",
    "goldenaudiobooks": "https://goldenaudiobooks.com/ernest-cline-ready-player-one-audiobook-free-online/",
    "101audiobooks": "https://101audiobooks.com/arthur-c-clarke-2001-audiobook/",
    "audioaz": "https://audioaz.com/en/audiobook/the-time-machine-version-2-by-h-g-wells",
    "knigavuhe-restricted": "https://knigavuhe.org/book/kontrapunkt/",
}


def summarize(html: bytes, url: str, kind: str) -> dict:
    soup = BeautifulSoup(html, "html.parser")
    canonical = soup.find("link", rel="canonical")
    og_image = soup.find("meta", attrs={"property": "og:image"})
    forms = []
    for form in soup.select("form")[:4]:
        inputs = [str(field.get("name")) for field in form.select("input[name]")[:8]]
        forms.append({"action": urljoin(url, form.get("action") or ""), "method": (form.get("method") or "GET").upper(), "inputs": inputs})
    links = []
    for a in soup.select("a[href]"):
        href = urljoin(url, a.get("href", ""))
        if urlparse(href).netloc != urlparse(url).netloc:
            continue
        text = a.get_text(" ", strip=True)
        if not text or len(text) > 120 or href in [x["url"] for x in links]:
            continue
        if kind == "search" and not any(part in href for part in ("/book/", "audiobook", "the-time-machine")):
            continue
        links.append({"url": href, "text": text})
        if len(links) == 12:
            break
    raw = html.decode("utf-8", errors="ignore")
    media = re.findall(r"https?://[^\s\"'<>]+?\.(?:mp3|m4a|m3u8)(?:\?[^\s\"'<>]*)?", raw, re.I)
    hosts = sorted({urlparse(item).netloc for item in media})
    headings = [x.get_text(" ", strip=True)[:120] for x in soup.select("h1,h2")[:10]]
    page_text = soup.get_text(" ", strip=True)
    restrictions = [phrase for phrase in (
        "Доступ к аудиокниге ограничен по просьбе правообладателя",
        "Файлов не найдено",
    ) if phrase.lower() in page_text.lower()]
    return {
        "capturedAt": "2026-09-23", "kind": kind, "url": url,
        "title": soup.title.get_text(" ", strip=True) if soup.title else None,
        "canonical": canonical.get("href") if canonical else None,
        "headings": headings,
        "ogImageHost": urlparse(og_image.get("content", "")).netloc if og_image else None,
        "forms": forms, "sampleLinks": links,
        "mediaElementCount": len(soup.select("audio")),
        "sourceElementCount": len(soup.select("source")),
        "mediaUrlCount": len(media), "mediaHosts": hosts,
        "jsonLdCount": len(soup.select('script[type="application/ld+json"]')),
        "restrictionEvidence": restrictions,
        "htmlBytes": len(html),
        "note": "Compact structural fixture. Media URLs, descriptions and page scripts intentionally omitted.",
    }


for name, (url, hint) in SOURCES.items():
    response = requests.get(url, timeout=20, headers={"User-Agent": "VetroAB05Research/1.0 (+manual source reconnaissance)"})
    print(name, response.status_code, response.url, len(response.content))
    if response.ok:
        (TEMP / f"ab05-{name}-search.html").write_bytes(response.content)
        fixture = summarize(response.content, response.url, "search")
        target = DEST / name / "search.json"
        target.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

for name, (url, kind) in PAGES.items():
    if name == "knigavuhe-collection":
        cache = TEMP / "ab05-knigavuhe-collection.html"
    elif name == "knigavuhe":
        cache = TEMP / "ab05-knigavuhe-book.html"
    else:
        cache = TEMP / f"ab05-{name}-book.html"
    if not cache.exists():
        print("missing", cache)
        continue
    fixture = summarize(cache.read_bytes(), url, kind)
    target_name = "collection.json" if kind == "collection" else "book.json"
    target = DEST / ("knigavuhe" if name == "knigavuhe-collection" else name) / target_name
    target.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

for name, url in EXTRA_PAGES.items():
    response = requests.get(url, timeout=20, headers={"User-Agent": "VetroAB05Research/1.0 (+manual source reconnaissance)"})
    print(name, response.status_code, response.url, len(response.content))
    if response.ok:
        fixture = summarize(response.content, response.url, "restricted" if name.endswith("restricted") else "book")
        directory = "knigavuhe" if name == "knigavuhe-restricted" else name
        file_name = "restricted.json" if name == "knigavuhe-restricted" else "book-2.json"
        (DEST / directory / file_name).write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
