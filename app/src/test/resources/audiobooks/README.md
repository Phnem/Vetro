# AB-05 reconnaissance fixtures

Captured 2026-09-23. `robots.txt` files are raw responses. `search.json`, `book.json`, `book-2.json`, `collection.json` and `restricted.json` are compact **derived structural snapshots** of public pages: source URL, headings, sample links, form shape, media-host counts and explicit restriction signal. They omit media URLs, page scripts, long descriptions and audio bytes. They are suitable for source capability/contract assertions, not for testing an HTML parser against original markup. AB-15 must add minimal parser inputs for the chosen permitted source.

`librivox/` and `internet-archive/` hold compact public API responses with two sample sections/files. No authentication data or private server addresses are present. Audiokniga.one has no search response because its robots.txt disallows the search route. Local Folder and Audiobookshelf require device/server fixtures in their implementation tickets.

Refresh public-page captures with `.scratch/audiobooks/research/capture_ab05.py`; API captures with `capture_anchor_fixtures.py`. The scripts only request metadata pages/API, never media files. Recheck robots.txt before running them later.

