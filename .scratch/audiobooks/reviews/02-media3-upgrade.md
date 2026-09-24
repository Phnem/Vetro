# AB-02 review — Media3 gate

Date: 2026-09-23
State: done with documented deviations

## Standards

- Direct Media3 dependencies all use one `media3` version catalog key (`1.11.1`).
- The new Compose artifact is the core state-holder library; no prebuilt Material3 media UI
  was added. The WorkManager artifact is reserved for the audiobook download stage.
- No existing video player source was changed. A deprecation warning appears in
  `StreamingPlaybackDiagnostics.kt`, but it compiles and has no demonstrated behavior change.
- The temporary debug manifest used to reach protected player Activity screens during smoke
  was removed. The final merged debug manifest again has `exported=false` on both screens.

## Specification

- PASS: stable version, single version catalog pin, dependencies added.
- PASS: debug Kotlin compile, app/network unit tests, debug and release packaging.
- PASS (controlled emulator): HLS and ABR (telemetry recorded 180p → 360p), progressive MP4,
  locally owned file playback with network disabled, PiP with an active video window.
- DEFERRED to AB-38: live provider, download, cancel, re-resolve, and legacy wizard scenarios
  in `media/SMOKE_MATRIX.md`. Its Consumet/Gogoanime and jut.su video rows are obsolete in
  current source code and must be rewritten before execution.

## Decision

No code-level blocking finding. Core Media3 video scenarios passed before audiobook code.
Provider-dependent end-to-end video QA remains a release gate; this deviation does not claim
that downloads or URL refresh were tested.
