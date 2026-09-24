# Current handoff

## Goal and canonical documents

Add audiobooks as Vetro's fifth media type: a Books home with animated shelves, details, mini/full player, background playback, local folder, multi-source search/aggregation, offline downloads, and safe local-first storage. Follow `.scratch/audiobooks/MASTER_PLAN.md` and `spec/`. User decisions Q1–Q8 and WorkId UUID are recorded there.

## State on 2026-09-24

AB-01…AB-09 completed, some with documented deviations in their tickets and `EXECUTION_LOG.md`. Active next ticket: **AB-10 mini/full player UI**. One ticket at a time per `ticket-autopilot`.

AB-09 implemented `LocalFolderSource`, SAF grant, bounded scan, natural audio ordering, stable UUID identities, file/embedded chapter metadata, and compact Books entry. Three Xiaomi tests passed. User manually selected a synthetic folder; `SampleBook · 2` appeared and `dumpsys media_session` showed `PLAYING` for the smoke app. The temporary button now reports playback start. Fixture and saved grant were removed; see `reviews/09-local-folder-source.md`. Real chapter corpus still needs AB-38 validation.

AB-10 is implementing. `AudiobookPlayerHost.kt` is wired above NavHost and in the debug smoke Activity. Mini/full screen, controls, chapter list, speed, progress and logical URI resumption are present. Emulator API 37 AndroidTest passed with generated local WAVs, including chapter jump and 1.5× speed; screenshots are in `reviews/assets`. A lifecycle crash was fixed by binding MediaController with application context. Physical Xiaomi UI/frame smoke is pending user unlock. Full morph, origin flight, real art/background and 20-cycle frame gate remain; do not mark AB-10 done yet. See `issues/10-player-ui.md`.

AB-08 added `BookTimeline`, `MediaManifest`, stable `TrackUriCodec`, `ManifestResolver`, `VetroAudioDataSource`, queue builder, and one-time service retry for 401/403/410. JVM tests, three Xiaomi Android tests, and release build passed. HTTP 403/410 integration smoke belongs to AB-17 when the first live online source exists; chapter title changes inside M4B belong to AB-10; stream cache belongs to AB-17. See `issues/08-timeline-and-resolving-data-source.md` and `reviews/08-timeline-and-resolving-data-source.md`.

AB-07 MediaLibraryService runs in the main Android process. Xiaomi/emulator playback, notification and resumption after stopService passed. Full process kill/reboot/BT/call/long background remains AB-38. Release startup was smoke-tested in an isolated package, then removed.

AB-05 source cards and structural fixtures are under `research/` and `app/src/test/resources/audiobooks/`. Aknigi24 and Audiokniga.one cannot currently be full streaming adapters due to their robots rules. Knigavuhe is first RU candidate pending permitted media resolve. LibriVox/IA, Audiobookshelf and Local Folder are anchors. Respect rights, site restrictions, and avoid storing signed URLs or secrets.

## Device and workspace

Xiaomi serial: `3871a9d6`. Our packages are main `com.phnem.vetro` and one audiobook smoke app `com.phnem.vetro.ab07smoke`. `com.phnem.vetro.perf` belongs to another agent per user and must not be touched. Use `scripts/audiobook-smoke.ps1 -DeviceId 3871a9d6 -TestClass <fully-qualified-class>`; it builds with `-PaudiobookSmokeBuild=true`, installs with `adb install -r`, and removes `.test` in `finally`. Do not install another side-by-side audiobook variant. AB-09 fixture was removed and only the audiobook smoke app's data was cleared after test.

The workspace has many pre-existing and parallel-agent uncommitted changes. Never reset or overwrite them. Audiobook work is mostly under `.scratch/audiobooks/` and `app/src/{main,test,androidTest}/.../audiobooks/`, with deliberate integration changes in Gradle, manifest and Koin. Another agent's `com.phnem.vetro.echoic` package on emulator must be left alone.

AB-03 player host and AB-04 shelf motion prototypes live in separate worktrees under `C:\Users\2004i\.codex\worktrees\`; their smoke videos and frame measurements are in `reviews/assets/`. Production integration happens in AB-10/AB-30. Media3 is 1.11.1 and existing video smoke passed in AB-02.

## Next work

Create detailed AB-10 ticket from `spec/10-screen-player.md` and `issues/03-prototype-player-host.md`. Integrate the isolated player prototype into production Books flow with mini/full player, controls, timeline and chapters, then run the physical Xiaomi frame gate. AB-03 prototype missed the strict target (18/2966 frames >32 ms), so profile and optimize before declaring AB-10 done. Preserve other agent's workspace changes.
