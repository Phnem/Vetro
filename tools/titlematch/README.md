# Vetro TitleMatch — training and Android model pack

See [the plan](../../docs/titlematch/PLAN.md) and
[source policy](../../docs/titlematch/SOURCES.md).
The v2 model is trained and packaged. Current runtime behavior and readiness
limits are documented in [MODEL_CARD.md](../../docs/titlematch/MODEL_CARD.md).

Run with Python 3.11+ (standard library; no installation or API key required):

```powershell
python tools/titlematch/fetch_resources.py fetch
python tools/titlematch/fetch_resources.py verify
python tools/titlematch/audit_resources.py
python -m unittest discover -s tools/titlematch -p "test_fetch_resources.py"
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

The initial fetched files are **research resources**, not the approved training set.
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

## Reproduce the released model

Use Python 3.12 (the system's Python 3.14 is not the ML environment):

```powershell
uv venv --python 3.12 tools/titlematch/.cache/venv
uv pip install --python tools/titlematch/.cache/venv/Scripts/python.exe --index-strategy unsafe-best-match -r tools/titlematch/requirements-ml.txt
```

The pinned environment uses both PyPI and the official PyTorch CPU wheel index.
The explicit index strategy lets uv resolve each pinned version across these two
indexes; its default first-index strategy cannot resolve this lock.

The separate `wikidata-training-data-v2.zip` contains frozen CC0 raw responses,
receipts and `training-corpus/` Parquet files, including original group assignments.
Verify `SHA256SUMS` before extracting. This is sufficient to regenerate pairs
without re-querying a mutable WDQS endpoint. For example, extract it into
`tools/titlematch/.cache/frozen-v2`, then run:

```powershell
tools/titlematch/.cache/venv/Scripts/python.exe tools/titlematch/prepare_pairs.py --corpus tools/titlematch/.cache/frozen-v2/training-corpus --output tools/titlematch/.cache/repro-pairs
tools/titlematch/.cache/venv/Scripts/python.exe tools/titlematch/train_models.py lightgbm --pairs tools/titlematch/.cache/repro-pairs --output tools/titlematch/.cache/repro-models
tools/titlematch/.cache/venv/Scripts/python.exe tools/titlematch/train_models.py charcnn --pairs tools/titlematch/.cache/repro-pairs --output tools/titlematch/.cache/repro-models
tools/titlematch/.cache/venv/Scripts/python.exe tools/titlematch/evaluate_models.py export-charcnn --pairs tools/titlematch/.cache/repro-pairs --models tools/titlematch/.cache/repro-models
tools/titlematch/.cache/venv/Scripts/python.exe tools/titlematch/evaluate_models.py test --pairs tools/titlematch/.cache/repro-pairs --models tools/titlematch/.cache/repro-models --corpus tools/titlematch/.cache/frozen-v2/training-corpus --test-context post-correction-regression
```

Use a new output directory for each experiment. The test command refuses to
overwrite an existing sealed report. v2 remains a post-correction regression,
including when reproduced; do not relabel it a fresh independent test.

Full verification on the existing local artifacts:

```powershell
tools/titlematch/.cache/venv/Scripts/python.exe -m unittest discover -s tools/titlematch -p "test_*.py"
$env:ANDROID_HOME='C:/Users/2004i/AppData/Local/Android/Sdk' # replace with your SDK path
./gradlew.bat -p tools/titlematch/kotlin-verification test jar
./gradlew.bat -p tools/titlematch/android-benchmark assembleDebug
python tools/titlematch/run_android_benchmark.py
```

The benchmark installs only `com.phnem.vetro.titlematchbench`, uses public
validation vectors and never accesses the user's collection. It rejects stale
result files using a unique run ID and checks the model version. The screen stays
awake only while the benchmark activity is visible. Main Vetro
was compiled/assembled but is not installed by these commands.

Build the local distribution with `package_model.py`. Its model ZIP includes the
Kotlin SDK, weights, model card, source policy, inference/training scripts and
locked dependencies. The data snapshot is separate. Both have SHA-256 entries.
Generated Android assets use opaque `.bin` names for gzip content: `.gz` asset
names are rewritten by AAPT and cannot preserve the required file/hash contract.
