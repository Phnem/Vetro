# Vetro MicroML

Identity, Catalog and Experience run on the device through Kotlin. Their initial mode is shadow evaluation. Each package has its own switch and rollback in Settings → Developer. The effects that can be enabled there are limited to search order, exact genre aliases, and recommendation ranking experiments.

| Package | Included models | App integration |
|---|---|---|
| Identity | Title embeddings plus classifiers for work identity and relationships | Search ordering for anime, films, series, manga and audiobooks. Additional observations during title enrichment and recommendation filtering. |
| Catalog | Filename parser, person-name lexicon, title language classifier and genre normalizer | Audiobook folder import, title language observations and genre mapping. Only exact aliases can fill an unmapped genre. |
| Experience | Genre associations and a local ranker that learns from feedback | Current recommendation feed for anime, movies and series. The genre model was trained across all corpus domains. |

The audio identity target is the literary work. A match carries no authority over editions, narrators, audio releases, or playback position. Identity scores never write metadata or external IDs. Evidence from TitleMatch retains priority in the manual candidate picker. Model results do not remove search candidates. Source Intelligence remains responsible for adaptive playback ranking.

## Use the prepared packages

Extract the bundle into `tools/microml/.cache/releases/current` so that directory contains `android-assets/microml`, `golden.json` and `sizes.json`. Gradle picks up the asset directory automatically. For a different layout, pass `-PmicroMlAssetsDir=<android-assets-directory>`. Without the assets, the app falls back to its existing algorithms.

Identity and Catalog declare the SHA-256 of the shared encoder they require. Experience loads independently of that encoder. To roll back a package, put its prior manifest and model files in `android-assets/microml/<package>/previous`. Include the compatible encoder in that directory's `core` subdirectory if it differs from the current encoder. If there is no usable previous package, the previous-version switch falls back to the existing Vetro algorithm. Use a distinct version string for each model release, since Experience stores learned weights by version.

## Export and verify from the local training cache

Run these commands from the repository root with the MicroML Python environment active. Export and evaluation use Python 3.12, NumPy, PyArrow, LightGBM, RapidFuzz, SciPy and scikit-learn. Encoder training also uses PyTorch. Training data and checkpoints live in the ignored `.cache` directory.

```powershell
$cache = 'tools/microml/.cache'
$encoder = "$cache/encoder/b15d128drop"
$pairs = "$cache/identity/b15d128drop-v2"
$identity = "$pairs/model-stable"
python tools/microml/identity/train.py --pairs $pairs --output $identity --test
python tools/microml/catalog/genres.py --encoder "$encoder/table.npy" --corpus "$cache/corpus-refs"
python tools/microml/catalog/train_parser.py --evaluate-only --test
python tools/microml/export/pack.py --encoder $encoder --identity $identity --tag 2026-11-10-stable
.\gradlew.bat -PmicroMlSmokeBuild=true :app:testDebugUnitTest --tests '*MicroMlParityTest' :app:assembleDebug :app:assembleDebugAndroidTest
```

To run the head-to-head Identity comparison, use `identity/evaluate.py --model <identity> --encoder <table.npy> --split test --v2 <titlematch-cache>`. Omit `--v2` if the old benchmark artifacts are unavailable. `export/pack.py --only experience` exports only Experience and needs no Identity or Catalog checkpoint. To export just Identity or Catalog, also supply the encoder that matches the output directory's core. A partial export removes the combined parity fixture, since its expected results may no longer match. Use a full export before running the parity suite.

The debug property gives the app the ID `com.phnem.vetro.micromlsmoke` for device testing in a separate app sandbox. The [validation report](../../docs/microml/VALIDATION.md) records the datasets, results, and remaining evidence gaps.
