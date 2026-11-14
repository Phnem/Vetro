# MicroML shadow release, 10 November 2026

The release contains three independent packages and a shared encoder. All model files and manifests total **8,199,995 bytes**. Identity is 1,509,692 bytes, Catalog 2,453,216, Experience 7,779, and the encoder with its manifest 4,229,308. MicroML entries occupy 6,906,873 compressed bytes inside the tested APK. The checksummed bundle including parity fixtures is 6,899,408 bytes.

The corpus contains 492,585 Wikidata entities and 2,078,364 titles. It includes 8,346 anime entries, 20,652 manga entries, 278,563 films, 88,584 series and 96,440 literary works. Audiobook identity examples model the underlying work, using examples derived from literary works. They do not validate release or narration identity. The corpus splits by connected groups of works and has a separate stage for training the title encoder. Replaying the encoder's input selection reproduced its training and validation counts.

| Evaluation | MicroML | Comparison |
|---|---:|---:|
| Identity search, top 1 across 3,000 test queries | 68.10% | 28.83% legacy scorer and 42.40% encoder alone |
| Identity search, nDCG@10 | 0.7006 | 0.4206 legacy scorer |
| TitleMatch head-to-head, recall at five false positives | 58.17% | 67.48% TitleMatch v2 |
| Folder/name parsing, all fields correct on generated test data | 83.54% | 32.03% parser baseline |
| Folder/name parsing, layouts withheld from training | 41.66% | 0.00% parser baseline |
| Genre completion on held-out works, recall@1 / recall@3 | 39.13% / 69.89% | 32.85% / 51.07% by tag popularity |

Identity search ranks the same 20-candidate encoder shortlist for all scorers. Misses remain in the denominator. Its shortlist recall was 71.70%. This measures an offline search replay. The head-to-head uses 46,900 pairs from the overlapping test split of TitleMatch v2. Its fixed operating point of five false positives is a retrospective comparison. Keep TitleMatch's verified decisions and use Identity AI as an experimental search ranker.

On 315,024 MicroML test pairs at the validation-selected `p99` threshold, binary identity achieved **92.76% accuracy**, **99.05% precision**, **75.10% recall**, **85.43% F1**, and **0.284% FPR**. Expected calibration error was 0.00153. Relationship classification achieved 93.95% accuracy and 82.38% macro F1, with top-label ECE 0.0051. Wiki relations provide partial labels, and generated pairs reflect the evaluation generator's distribution.

Catalog token classification reached 97.12% accuracy, 96.14% macro F1 and 0.0199 ECE on the parser test. The language model reached 91.83% accuracy, 84.92% macro F1 and 0.0156 ECE on validation. The EN/romaji binary slice reached 92.39% precision, 98.99% recall, 95.58% F1 and 1.48% FPR. The person-name lexicon had 82.33% F1 and 14.94% FPR on validation, and is used as a parser input.

Genre normalization got 95 of 99 hand-written examples right versus 65 for the baseline. On 6,401 held-out Wikidata strings, 105 of 309 accepted tags were correct under the weak labels. Precision was 33.98%, recall 23.54%, F1 27.81%, and FPR on unmapped strings 3.39%. Probabilistic genre guesses and folder parsing stay observational. Exact curated genre aliases have their own experiment switch.

Experience's genre completion result measures hidden tags, with movies dominating the genre corpus. It does not validate recommendation gains in production. The taste ranker improved simulated AUC by 0.0053 at 50 ratings and 0.0079 at 200 for users whose preferences differ from the starting weights. Real usage evidence is still needed. Manga and audiobook searches use Identity, but those domains do not have swipe-based feeds in the current recommendation engine. Playback remains on the existing Source Intelligence and provider logic.

## Android evidence

The debug APK was installed under a separate application ID on Xiaomi 2211133C, Android API 36. The focused Gradle suite passed 117 tests with no skips, including 5 parity tests with generated assets. Device tests exercise package loading, independent disable and rollback, and runtime fallback. The performance test uses local records and no network. It records cold package load time, process PSS before and after loading, and warm wall and CPU time.

| Operation | Warm p50 | Warm p95 | Mean thread CPU |
|---|---:|---:|---:|
| Identity pair, including both record preparations and relationship scores | 0.850 ms | 1.142 ms | 0.847 ms |
| Identity search, 20 candidates including preparation | 6.476 ms | 6.858 ms | 6.066 ms |
| Catalog folder parse | 2.483 ms | 3.122 ms | 2.530 ms |
| Catalog title language | 0.098 ms | 0.140 ms | 0.100 ms |
| Catalog genre | 0.154 ms | 0.237 ms | 0.163 ms |
| Experience genre profile and 40 taste scores | 0.194 ms | 0.353 ms | 0.215 ms |

Cold loading took 157 ms for Identity including the common encoder, 100 ms for Catalog with the encoder already loaded, and 15 ms for Experience. In the same process, PSS was 323,756 KiB before loading and 349,773 KiB after loading all packages. The intermediate values were 340,764 KiB after Identity and 349,519 KiB after Catalog. These are process snapshots, including transient allocations and unrelated app memory, so the 25.4 MiB difference is an observed change in PSS rather than retained model RAM.

Power could not be measured reliably on this device in the available setup. Perfetto recorded 25 USB-connected battery current samples and reported `power_rail_empty_packet`. Battery current on USB is net battery flow and cannot isolate the energy used by inference. CPU time is recorded as a separate quantity. See [Perfetto's power counter documentation](https://perfetto.dev/docs/data-sources/battery-counters).

Detailed local reports live in `tools/microml/.cache/identity/b15d128drop-v2/model-stable/compare-test.json`, `catalog/parser/report.json`, `catalog/text/report.json`, `catalog/genres/report.json`, and `experience/report.json` under the same cache. Device output is in `.scratch/microml-engine/device-results.txt`.
