# Resource audit — 2026-10-27

Initial research download completed: **30/30 locked files verified**, **272,846,225 bytes** (272.85 MB / 260.21 MiB).
All source/model bytes match the pinned SHA-256 and byte count. Full local machine-readable audit:
`tools/titlematch/.cache/audit.json`; download receipts: `.cache/download-report.json`.

## Anime snapshots

| Snapshot | Records | Title + synonym strings | Normalized alias keys shared across records | Records with Cyrillic text |
|---|---:|---:|---:|---:|
| manami | 41,537 | 284,731 | 15,522 | 3,887 |
| cedya | 38,270 | 277,316 | 7,880 | 3,858 |

Aliases counted here are raw source strings, not validated positive pairs. Collision keys
use the audit's NFKC/casefold/alphanumeric representation; they are not a new app identity rule.
Cyrillic text is a script observation, not a language annotation. Both snapshots have zero
missing source lists and zero source URLs reused across rows within that snapshot.

Shared source URLs: 189,411. Only in Manami: 170; only in cedya: 2,519.
These are URL differences, **not** an entity-level removal/addition report. Despite fewer rows,
cedya has more source URLs; the next ticket must examine changed grouping, merges/splits and
season granularity. Do not infer that the smaller snapshot is worse or that the union is safe.

## Other downloaded resources

- Fribb: 39,579 mapping rows, including season/episode_offset fields; quarantined pending rights review.
- Wikidata: five entity snapshots at fixed revisions, covering One Piece manga and anime
  separately, Inception, Game of Thrones and The Hobbit. These are schema samples, not a corpus.
- Potion: complete static-weight/tokenizer/config set; weight file 7,559,256 bytes.
- Multilingual MiniLM: desktop AVX2 INT8 ONNX, tokenizer, SentencePiece, configuration and
  pooling files; weight file 118,453,870 bytes; configured sentence length 128.

## Verification and limits

Nine network-independent tests pass: path confinement, cache reuse, same-size corruption,
failed/interrupted/oversized transfers, completed replacement, alias collision and schema failures.
Full cached re-fetch and independent verify completed without network downloads or integrity failures.
The audit parses both JSONL files, Fribb, all Wikidata revisions, model configs and Potion tensor header.

No inference or numerical model-quality benchmark has run. No CharCNN has been trained.
No training-eligible merged corpus or Parquet dataset has been produced. No Android behavior,
build dependencies, Gradle configuration, APK assets or destructive merge rules were changed.
The export toolchain and Python training environment remain a separate planned spike.

## Research decisions supported by the audit

1. Alias collisions are frequent: dictionary values must be sets, with scope and metadata gates.
2. The two anime snapshots are comparison inputs; neither is blindly substituted for the other.
3. Russian alias coverage is incomplete; targeted licensed multilingual data is necessary.
4. Learned title matching must beat both current rules and the dictionary-only baseline.

Source versions and eligibility: [SOURCES.md](SOURCES.md),
[lock](../../tools/titlematch/resources.lock.json), [plan](PLAN.md).
