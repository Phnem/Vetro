# Current handoff

## Checkout isolation

Audiobook implementation continues only in `D:\AndroidStudioProjects\Vetro-collection-audiobooks` on `codex/audiobooks-implementation`, forked from shared baseline `5b5d618`. The original `D:\AndroidStudioProjects\Vetro-collection` checkout stays on the other agent's `perf/v3.3.5`; do not edit or build there for audiobook work. User paused feature work until this separation was complete. The latest visual corrections and two attached references are recorded in `issues/10-player-ui.md` and `references/img/`.

## Goal and canonical documents

Add audiobooks as Vetro's fifth media type: a Books home with animated shelves, details, mini/full player, background playback, local folder, multi-source search/aggregation, offline downloads, and safe local-first storage. Follow `.scratch/audiobooks/MASTER_PLAN.md` and `spec/`. User decisions Q1–Q8 and WorkId UUID are recorded there.

## State on 2026-09-24

AB-01…AB-09 completed, some with documented deviations in their tickets and `EXECUTION_LOG.md`. Active next ticket: **AB-10 mini/full player UI**. One ticket at a time per `ticket-autopilot`.

AB-09 implemented `LocalFolderSource`, SAF grant, bounded scan, natural audio ordering, stable UUID identities, file/embedded chapter metadata, and compact Books entry. Three Xiaomi tests passed. User manually selected a synthetic folder; `SampleBook · 2` appeared and `dumpsys media_session` showed `PLAYING` for the smoke app. The temporary button now reports playback start. Fixture and saved grant were removed; see `reviews/09-local-folder-source.md`. Real chapter corpus still needs AB-38 validation.

AB-10 is implementing. `AudiobookPlayerHost.kt` is wired above NavHost and in the debug smoke Activity. Mini/full screen, controls, chapter list, speed, progress and logical URI resumption are present. Emulator API 37 AndroidTest passed repeatedly with generated local WAVs, including chapter jump and 1.5× speed. A lifecycle crash was fixed by binding MediaController with application context. Xiaomi mini/full UI and three 20-cycle runs passed without crash. The current transition has a moving shell and placeholder cover flight; last run measured 44/3826 frames >32 ms, so the strict frame gate is still open. Real cover/origin, cached backdrop, further profiling and accessibility remain. See `issues/10-player-ui.md` and `reviews/assets/ab10-phone-20cycles-art-shell.txt`.

AB-08 added `BookTimeline`, `MediaManifest`, stable `TrackUriCodec`, `ManifestResolver`, `VetroAudioDataSource`, queue builder, and one-time service retry for 401/403/410. JVM tests, three Xiaomi Android tests, and release build passed. HTTP 403/410 integration smoke belongs to AB-17 when the first live online source exists; chapter title changes inside M4B belong to AB-10; stream cache belongs to AB-17. See `issues/08-timeline-and-resolving-data-source.md` and `reviews/08-timeline-and-resolving-data-source.md`.

AB-07 MediaLibraryService runs in the main Android process. Xiaomi/emulator playback, notification and resumption after stopService passed. Full process kill/reboot/BT/call/long background remains AB-38. Release startup was smoke-tested in an isolated package, then removed.

AB-05 source cards and structural fixtures are under `research/` and `app/src/test/resources/audiobooks/`. Aknigi24 and Audiokniga.one cannot currently be full streaming adapters due to their robots rules. Knigavuhe is first RU candidate pending permitted media resolve. LibriVox/IA, Audiobookshelf and Local Folder are anchors. Respect rights, site restrictions, and avoid storing signed URLs or secrets.

## Device and workspace

Xiaomi serial: `3871a9d6`. Our packages are main `com.phnem.vetro` and one audiobook smoke app `com.phnem.vetro.ab07smoke`. `com.phnem.vetro.perf` belongs to another agent per user and must not be touched. Use `scripts/audiobook-smoke.ps1 -DeviceId 3871a9d6 -TestClass <fully-qualified-class>`; it builds with `-PaudiobookSmokeBuild=true`, installs with `adb install -r`, and removes `.test` in `finally`. Do not install another side-by-side audiobook variant. AB-09 fixture was removed and only the audiobook smoke app's data was cleared after test.

The workspace has many pre-existing and parallel-agent uncommitted changes. Never reset or overwrite them. Audiobook work is mostly under `.scratch/audiobooks/` and `app/src/{main,test,androidTest}/.../audiobooks/`, with deliberate integration changes in Gradle, manifest and Koin. Another agent's `com.phnem.vetro.echoic` package on emulator must be left alone.

AB-03 player host and AB-04 shelf motion prototypes live in separate worktrees under `C:\Users\2004i\.codex\worktrees\`; their smoke videos and frame measurements are in `reviews/assets/`. Production integration happens in AB-10/AB-30. Media3 is 1.11.1 and existing video smoke passed in AB-02.

## Next work

Continue AB-10 in the audiobook namespace. The 2026-09-25 square-card visual revision uses real folder or embedded cover art, a full-screen cover background, and a mini ⇄ full scale transition. The full player now stays composed while hidden; its controls and accessibility semantics are disabled. The foreground-checked Xiaomi benchmark improved from 34/3816 to 2/3930 histogram frames >32 ms over 20 cycles. The strict zero-frame gate remains open. Evidence and screenshots are in `issues/10-player-ui.md` and `reviews/assets/`; the benchmark command is `python scripts/audiobook-frame-benchmark.py` while the unlocked AB-10 demo Activity is visible. A physical AndroidTest hung in MIUI instrumentation and was stopped; its runner was removed. Run the UI test on an available emulator, verify corner dragging and real cover crop, then profile the remaining slow frames. Do not close AB-10 yet. The other agent is concurrently changing video/local-player files, Settings, navigation and shared UI; inspect before editing, do not reset or revert their work. Our phone package is only `com.phnem.vetro.ab07smoke`; `com.phnem.vetro.perf` belongs to the other agent and must remain.
