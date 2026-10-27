# TitleMatch research resources

See [the plan](../../docs/titlematch/PLAN.md) and
[source policy](../../docs/titlematch/SOURCES.md).
This directory does not change application behavior or package resources into APKs.

Run with Python 3.11+ (standard library; no installation or API key required):

```powershell
python tools/titlematch/fetch_resources.py fetch
python tools/titlematch/fetch_resources.py verify
python tools/titlematch/audit_resources.py
python -m unittest discover -s tools/titlematch -p "test_*.py"
```

The reviewed `resources.lock.json` pins all resource bytes. `fetch` verifies cached
files, retries failed transfers and installs only a fully verified download. It
never executes model code. Interrupted transfers restart that file; completed
files are reused. Run one fetch process per cache. Errors produce nonzero exit.

Downloads, logs and future training outputs belong under `.cache/` (already
ignored by the repository). Do not move weights or raw datasets into Git or
Android assets. Cache paths in the lock are relative to this directory's `.cache`.
The default cache is in the isolated worktree, not the original project folder.

To update sources, review an explicit upstream release/commit and its rights,
then update URL, revision, size and SHA-256 together. GitHub release digests and
Hugging Face LFS SHA-256 were checked when the lock was prepared. For ordinary
Git files we verified their Git blob SHA-1 and recorded SHA-256. Wikidata entity
samples are revision-pinned and SHA-256 checksummed on first retrieval. A hash
proves byte integrity, not data quality or permission to redistribute.

Potion is an English baseline. Multilingual MiniLM's ONNX AVX2 INT8 checkpoint
is an offline desktop comparison resource, not a trainable Vetro checkpoint or
an Android runtime choice. Load it later with tokenizer + attention-mask-aware
mean pooling and the model's recorded sequence-length configuration. Train the
custom CharCNN from scratch only after dataset and export gates pass.

The fetched files are **research resources**, not an approved training set.
`usage_status` records restrictions. No downloader invokes AniList, Shikimori,
Jikan or TMDB APIs. Fribb and mixed-source anime data remain under source review.
Wikidata samples demonstrate schema across domains; they are not a training corpus.

## CC0 training preparation

The continuation adds a separate Wikidata corpus; AOD/Fribb remain excluded.
The policy is [SOURCE_POLICY.json](../../docs/titlematch/SOURCE_POLICY.json).

```powershell
uv venv --python 3.12 tools/titlematch/.cache/train-venv
uv pip install --python tools/titlematch/.cache/train-venv/Scripts/python.exe numpy pyarrow rapidfuzz lightgbm scikit-learn
python tools/titlematch/collect_wikidata.py --page-size 200 --limit-per-domain 10000
tools/titlematch/.cache/train-venv/Scripts/python.exe tools/titlematch/data_pipeline.py
python tools/titlematch/audit_snapshot_diff.py
```

The collector uses bounded ID lists, caches and hashes every raw response, and
does not query third-party catalogues. Source pages are WDQS response snapshots;
they do not claim per-entity revision history. A bounded QID-ordered sample has
selection bias. Corpus tables include entities, aliases, typed IDs, relations,
evidence and quarantine. Alias collisions remain multi-valued. External-ID
conflicts are excluded. Franchise/part-of, same-QID adaptation and alias-collision
groups are assigned together before training pairs exist. Editorial challenge
groups are held out; this fixture set is not a representative production benchmark.
