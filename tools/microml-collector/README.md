# Vetro MicroML collector

A Cloudflare Worker that receives **anonymous, schema-checked data** from installs whose owners switched on
*Settings → Smart models → Help improve Vetro* and writes it as plain JSON files into the owner's R2 bucket
`vetro-contrib`. The promise made to users is in [PRIVACY.MD](../../PRIVACY.MD) §5; this folder is what makes it checkable.

## What it accepts

Two routes, strictly validated (`src/worker.js`); anything outside the schema is rejected with 422 and nothing is stored.

| Route | File written | Content |
|---|---|---|
| `POST /v1/batch` (≤ 64 KB, one per install per day) | `microml/<day>/<install>.json` | the 11 weights of the on-device taste ranker and its rating count; 42 similarity numbers per record pair with a yes/no label from the user's own action; genre names a catalogue returned that the app could not map; counters of what the models did; model pack versions. The `install` id is 16 random bytes replaced every 30 days |
| `POST /v1/sources` (≤ 128 KB, ≤ 100 records) | `sources/<day>/<uuid>.json` | public book records that **another book site** returned during a search and the Vetro catalogue did not have: source site id, the book's address on that site, title, authors, narrators, year, cover link, duration. No install id, no search text |

Never accepted: what the user typed, collection titles, file names, paths, notes, accounts, IP addresses, timestamps finer
than a day. The Worker does not store `CF-Connecting-IP`; the rate limiter only counts, and observability is off.

## Deploy (once)

```bash
cd tools/microml-collector
npx wrangler login
npx wrangler r2 bucket create vetro-contrib
npx wrangler deploy        # custom domain contrib.vetrostudio.bid, bucket binding CONTRIB
```

The app build already points at `https://contrib.vetrostudio.bid` (default in `app/build.gradle.kts`, overridable with
`MICROML_COLLECTOR_URL` in `local.properties`). F-Droid and source rebuilds are excluded by the signature check.

## Read what was collected

```bash
npx wrangler r2 object get vetro-contrib/microml/2026-11-15/<install>.json
```

or open the bucket in the Cloudflare dashboard (R2 → vetro-contrib). The `sources/` folder is the queue of books to add to
the catalogue (deduplicate by source + key, then match against `book-dictionary`).

## Test

```bash
node --test test/worker.test.mjs
```

The tests run the real Worker code against an in-memory bucket.

## Known limits

* An open-source app cannot keep a secret key, so the endpoints are public. Defences are the strict schema, size limits,
  one training batch per install per day and an edge rate limit (30 requests a minute), not authentication. A determined
  attacker can submit plausible fake rows, so nothing collected enters a model or the catalogue without an offline check.
* The files are raw (the earlier version aggregated in D1 and kept a genre only after 20 installs reported it): the owner
  sees every batch. `tools/microml/collect/analyze.py` still reads a D1 dump and has to be pointed at the downloaded files.
* Files are never deleted automatically; remove old days from the bucket by hand or add an R2 lifecycle rule
  (the policy promises at most 400 days).
