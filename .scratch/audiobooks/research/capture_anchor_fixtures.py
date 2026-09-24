"""Capture compact public API examples for AB-05, without audio bytes."""

from pathlib import Path
import json
import time
import requests

ROOT = Path(__file__).resolve().parents[3]
DEST = ROOT / "app/src/test/resources/audiobooks"
HEADERS = {"User-Agent": "VetroAB05Research/1.0 (+manual source reconnaissance)"}


def get(url: str, params: dict | None = None) -> dict:
    if "librivox.org" in url:
        time.sleep(3)
    response = requests.get(url, params=params, headers=HEADERS, timeout=30)
    response.raise_for_status()
    print(response.status_code, response.url, len(response.content))
    return response.json()


def save(group: str, name: str, data: dict) -> None:
    folder = DEST / group
    folder.mkdir(exist_ok=True)
    (folder / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


book = get("https://librivox.org/api/feed/audiobooks/", {"id": 52, "format": "json", "extended": 1, "coverart": 1})
entry = book["books"][0]
entry["sections"] = entry.get("sections", [])[:2]
entry.pop("description", None)
save("librivox", "book.json", {"sourceUrl": "https://librivox.org/api/feed/audiobooks/?id=52&format=json&extended=1&coverart=1", "books": [entry], "note": "First two of 57 sections; description omitted."})

tracks = get("https://librivox.org/api/feed/audiotracks/", {"project_id": 52, "format": "json"})
tracks["sections"] = tracks.get("sections", [])[:2]
save("librivox", "tracks.json", tracks)

search = get("https://librivox.org/api/feed/audiobooks/", {"title": "^all", "format": "json", "limit": 2, "offset": 0})
for item in search.get("books", []):
    item.pop("description", None)
save("librivox", "search.json", search)

ia_search = get("https://archive.org/advancedsearch.php", {"q": "collection:librivoxaudio AND mediatype:audio AND title:Letters", "fl[]": ["identifier", "title"], "rows": 2, "page": 1, "output": "json"})
save("internet-archive", "search.json", {"sourceUrl": "https://archive.org/advancedsearch.php", "response": ia_search.get("response", {})})

metadata = get("https://archive.org/metadata/letters_brides_0709_librivox")
files = metadata.get("files", [])
selected = []
for suffix, count in ((".mp3", 2), (".m4b", 1), (".jpg", 1), (".xml", 1)):
    selected += [f for f in files if f.get("name", "").lower().endswith(suffix)][:count]
meta = metadata.get("metadata", {})
save("internet-archive", "item.json", {"sourceUrl": "https://archive.org/metadata/letters_brides_0709_librivox", "metadata": {k: meta.get(k) for k in ("identifier", "title", "creator", "mediatype", "collection")}, "files": selected, "originalFileCount": len(files), "note": "Representative files only; no media bytes."})
