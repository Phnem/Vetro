# Vetro MicroML collector

A Cloudflare Worker + D1 database that receives **anonymous, schema-checked batches of numbers** from installs whose
owners switched on *Settings → Developer → MicroML: help train the models*. It exists so the on-device models can be
re-calibrated and retrained on real use, which the offline Wikidata corpus cannot show. The promise made to users is
in [PRIVACY.MD](../../PRIVACY.MD) §5; this folder is what makes it checkable.

## What it accepts

One `POST /v1/batch` per install per day, at most 64 KB, strictly validated (`src/worker.js`, `validate`). Anything
outside the schema is rejected with 422 and nothing is stored.

| Table | Content | Linked to an install id? |
|---|---|---|
| `taste` | the 11 weights of the on-device taste ranker and its rating count, per content type and ISO week | yes (random id, replaced every 30 days) |
| `pairs` | 42 similarity numbers of two records, a yes/no label from the user's own action, source of the label | **no** |
| `genre_strings` | genre names a catalog returned that the app could not map; visible only through the `genre_candidates` view (≥ 20 installs) | yes, to count distinct installs; purged after 30 days if rare |
| `counters` | shadow-mode counters and pack versions, summed per day | no |
| `uploads` | (install, day) to allow one batch per day; deleted after 3 days | yes |

Never accepted: titles, file names, paths, notes, catalog IDs, accounts, IP addresses, timestamps finer than a day.
The Worker does not read or store `CF-Connecting-IP`; observability is disabled in `wrangler.toml`.

## Deploy (once)

```bash
cd tools/microml-collector
npx wrangler login
npx wrangler d1 create vetro-microml          # copy database_id into wrangler.toml
npx wrangler d1 execute vetro-microml --remote --file=schema.sql
npx wrangler deploy                            # prints https://vetro-microml-collector.<account>.workers.dev
```

Put that URL in `local.properties` as `MICROML_COLLECTOR_URL=` and rebuild. A build without the URL has no
contribution feature at all; F-Droid and source rebuilds are excluded by the signature check regardless.

Add a Cloudflare rate-limiting rule for `POST /v1/batch` (for example 10 requests per minute per IP) in the dashboard:
rate limiting acts at the edge and does not require the Worker to see or keep an address.

## Test

```bash
node --test test/worker.test.mjs
```

The tests run the real Worker code against an SQLite copy of `schema.sql`.

## Analyse

```bash
npx wrangler d1 export vetro-microml --remote --output dump.sql
python tools/microml/collect/analyze.py dump.sql \
  --identity tools/microml/.cache/identity/b15d128drop-v2/model-stable \
  --encoder  tools/microml/.cache/encoder/b15d128drop/table.npy \
  --taste    tools/microml/.cache/releases/current/android-assets/microml/experience/taste.json
```

The report covers taste weights (trimmed mean against today's prior), the shipped Identity model on real labelled
pairs (precision at the shipped thresholds, calibration, confident errors saved for retraining), genre proposals, and
daily counter trends. Nothing in it changes a shipped model by itself: a proposal becomes a release only through the
normal pack build, the held-out Wikidata evaluation and the on-device parity test.

## Known limits

* An open-source app cannot keep a secret key, so the endpoint is public. Defences are the strict schema, one batch per
  install per day, edge rate limiting and robust aggregation (trimmed mean, confident-error review), not authentication.
  A determined attacker can still submit plausible fake rows; that is why no collected row ever enters training without
  passing the offline held-out gate.
* `pairs` carry no install id, so a withdrawn contribution cannot be removed from them; they contain only similarity
  numbers. Switching the option off deletes the local identifier and queue and stops all uploads.
* Genre strings are the only free text. The schema allows letters, digits, spaces and `& ' . -` up to 40 characters, and
  the 20-install threshold keeps one person's unusual strings out of any analysis.
