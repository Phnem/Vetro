# Vetro Book Catalogue API

Read-only HTTP API of the Vetro book catalogue: search, book cards with descriptions, genres and series, and cover images.
Base URL: `https://api.vetrostudio.bid`. Russian version: [README.ru.md](README.ru.md). Terms of use: [terms.md](terms.md).

| | |
|---|---|
| Format | JSON, UTF-8; covers are AVIF images |
| Protocol | HTTPS only, CORS enabled for browsers |
| Catalogue | about 269,000 books and about 190,000 anime, films and series, with covers, refreshed regularly |
| Access | an API key per client, obtained through a Telegram bot, in three plans |
| Availability | best effort, no uptime guarantee (see [terms](terms.md)) |

## Getting a key

1. Open the Vetro API bot in Telegram (link: see the Vetro Studio site) and choose a language.
2. Tell the bot about your project: its name, a short description, the kind (website, mobile app, server, other), the domains
   (for websites), whether it is commercial and roughly how many requests a day you expect. The bot recommends a plan.
3. Read and accept the [terms](terms.md), pay for the plan in Telegram Stars (30 days per payment).
4. The bot shows your key **once**. The service stores only a hash of it. If you lose it, `/rotate` issues a new one.

Bot commands: `/plans` plans and prices, `/buy` new key or renewal, `/key` your keys and expiry dates, `/rotate` replace a key,
`/usage` your request counts, `/revoke` disable a key, `/lang` language, `/cancel` leave the current dialog.
Prices and limits below are the current ones; the bot always shows the live values before you pay.

## Plans

| Plan | Per second | Per minute | Per day (UTC) | `limit` / `ids` max | Covers | Offline index | Price per 30 days |
|---|---|---|---|---|---|---|---|
| Basic | 2 | 30 | 2,000 | 20 / 10 | no | no | 100 ⭐ |
| Pro | 5 | 120 | 20,000 | 50 / 40 | yes | no | 400 ⭐ |
| Ultra | 20 | 600 | 200,000 | 50 / 40 | yes | yes | 1,500 ⭐ |

"Per second" is a burst size, not a bandwidth cap. Values above a plan's `limit` or `ids` maximum are trimmed, not rejected.
A key issued to a website works only from the domains you gave to the bot.

## Authentication

Send the key in one header:

```http
Authorization: Bearer vk_pro_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

`X-Api-Key: vk_…` is accepted as well. For `<img>` tags, where headers cannot be set, covers also accept `?key=vk_…`.
Keys look like `vk_<plan>_<32 characters>`. Never put a key you cannot afford to lose in a public page without the domain restriction.
Only `GET /v1/health` works without a key.

## Requests

All paths are under `https://api.vetrostudio.bid`. Book ids are nine-digit strings (`000000771`).

### `GET /v1/health`
`{"ok":true}`. No key needed.

### `GET /v1/search?q=<text>&limit=<n>`
Full-text search over titles and authors in both languages.
`limit` defaults to 20 and is capped by the plan.

```json
{ "q": "dune", "items": [
  { "id": "000001192", "ru": "Дюна", "en": "Dune series", "ra": "Кевин Джей Андерсон", "ea": "Kevin J. Anderson", "y": 1965, "f": 5, "cv": 6013617 }
] }
```

`ru`/`en` titles, `ra`/`ea` authors, `y` year, `cv` cover revision (use it as `?v=` when you request the cover),
`f` flags: bit 1 = has a cover, bit 2 = the cover has a restricted licence (see [terms](terms.md)), bit 4 = core catalogue.

### `GET /v1/books/{id}` and `GET /v1/books?ids=1,2,3`
One book with its details card, or up to `ids` maximum books without details. The details card holds optional keys: `orig` original
title, `type`, `alts` other titles, `authors`, `series`, `tr` translations, `ed` editions, `ext` external ids, `cover`, `desc`
`{t, l}` description and its language, `gen` genre keys (`scifi`, `fantasy`, …; names are up to the client), `ol` original language,
`coll` collection flag, `ch` chapters, `dur` typical length in minutes, `rate` `{v, n}` rating and vote count. Every key can be missing.

### `GET /covers/{small|large}/{id}.avif?v={cv}`
The cover image. Needs a plan with covers. AVIF is displayed by browsers and by Android 12 and later; use another source on older systems.
Responses are immutable: cache them forever, and change `v` when the catalogue's `cv` changes.

### Anime, films and series: `/v1/media/...`

The same catalogue also covers about 190,000 anime, films and TV series (titles in Russian, English, romaji and the original language).
These routes use the same key, the same plan limits and the same errors as the book routes: `/v1/media/search` counts as a search,
cards and batches count as book lookups, covers count as covers.

- `GET /v1/media/search?q=<text>&type=<anime|movie|tv>&limit=<n>`: `type` is optional. Results are ordered by relevance with a boost for well-known titles.
- `GET /v1/media/{id}`: one title with its card. Ids are one letter and eight digits: `A` anime, `M` movie, `S` series (`A00000050`).
- `GET /v1/media?ids=A00000050,S00000110`: up to `ids` maximum titles, rows only.
- `GET /covers/media/{small|large}/{id}.avif?v={cv}`: covers, same rules as book covers.

```json
{ "q": "attack on titan", "items": [
  { "id": "A00000050", "t": "anime", "ru": "Атака титанов", "en": "Attack on Titan", "ro": "Shingeki no Kyojin", "orig": "進撃の巨人", "y": 2013, "st": "tv", "f": 3, "cv": 11003245 }
] }
```

Row keys: `t` is `anime`, `movie` or `tv_series`; `ru`, `en`, `ro` (romaji), `orig` titles; `y` year; `st` subtype (`tv`, `movie`, `ova`, `special`, …); `f` flags
(bit 1 = has a cover, bit 2 = the cover has a restricted licence); `cv` cover revision. The card (`detail`) holds optional keys: `desc` `{t, l}`
description and language, `gen` genres, `fr` `{t, i, n}` franchise title, position and size, `ol` original language, `seas` seasons, `eps` episodes,
`dur` minutes per episode, `st` release status, `ext` ids in other databases (`mal`, `shikimori`, `anilist`, `kitsu`, `tmdb`, `imdb`, `tvmaze`, `tvdb`, `wikidata`)
and `cover` `{src, lic, w, h}` the source and licence of the cover. Search matches Latin, Cyrillic and transliterated titles; Japanese characters are not searchable.
Some titles exist both as `anime` and as `tv_series` because they come from different databases.

### `GET /v1/manifest` and `GET /v1/index/...` (Ultra)
The offline search index used by the Vetro app: a manifest plus versioned base and delta files (zstd-compressed SQLite). `Range` requests are supported.

## Responses, caching and limits

Successful responses carry `X-RateLimit-Limit-Minute`, `X-RateLimit-Remaining-Minute`, `X-RateLimit-Limit-Day`, `X-RateLimit-Remaining-Day`.
Search and book responses are cacheable at the edge (10 minutes and a day). Respect `Cache-Control` and `ETag` where they are present.

Errors are JSON, `{"error": "<code>"}`:

| Status | `error` | Meaning |
|---|---|---|
| 401 | `missing_key`, `invalid_key` | no key, or an unknown key |
| 403 | `key_revoked`, `key_expired` | the key was revoked or its 30 days ended; renew in the bot |
| 403 | `origin_not_allowed` | a browser request from a domain not on the key |
| 403 | `tier_forbids_route` | covers or index on a plan without them |
| 429 | `rate_limited` | a limit was hit: `scope` is `second`, `minute` or `day`; wait `retry_after` seconds (header `Retry-After`) |
| 400, 404 | | malformed request, unknown book |

Retry on 429 and 5xx with exponential backoff; do not retry other 4xx.

## Quick start

```js
const API = "https://api.vetrostudio.bid";
const KEY = "vk_pro_...";
const res = await fetch(`${API}/v1/search?q=${encodeURIComponent("Дюна")}&limit=5`, { headers: { Authorization: `Bearer ${KEY}` } });
const { items } = await res.json();
const cover = `${API}/covers/small/${items[0].id}.avif?v=${items[0].cv}&key=${KEY}`; // for an <img src>
```

```bash
curl -H "Authorization: Bearer $KEY" "https://api.vetrostudio.bid/v1/books/000000771"
```

## Technical characteristics

- Runs on Cloudflare Workers at the edge; data in Cloudflare D1 (SQLite with FTS5) and R2 (covers, index files).
- Responses are served from the Cloudflare edge; cached responses are fast, searches that reach the database take longer. No latency is guaranteed.
- Request metadata (IP addresses, user agents) is not logged. Per-key counters (requests per route and day) are kept to enforce plans and show `/usage`.
- The catalogue is refreshed without downtime; ids are stable. New fields may appear in responses, so ignore keys you do not know.
- Changes that break existing clients would come under a new path prefix (`/v2`).

## Licence

The source code of the API and the apps is MIT-licensed (see `LICENSE` in the repository). The data is covered by the [terms](terms.md), not by the MIT licence.
