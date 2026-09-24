# AB-04 review — shelf fan morph

Status: DONE (prototype gate). Production integration remains AB-30.

## Evidence

- Isolated worktree/branch: `C:\Users\2004i\.codex\worktrees\audiobooks-shelf-prototype\Vetro-collection`, `codex/audiobooks-shelf-prototype`.
- Five fake fan cards on `WorkspacePage.BOOKS`; A uses a precomposed overlay and one progress value. B uses a nested NavHost with shared titles and the first three covers.
- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` passed. On Xiaomi API 36, Compose semantics completed 12 open/close cycles per variant. A also passed interrupt at 100 ms and Android Back.
- Real device clock test (two warmups per variant, eight measured cycles, no screen recording): A open 0/880 frames >32 ms, p95 16.8 ms, max 24.9 ms; A close 0/640, p95 11.2 ms, max 17.6 ms. B open 32/822, p95 26.8 ms, max 72.2 ms; B close 36/804, p95 30.4 ms, max 102.7 ms.
- [Layer video](assets/ab04-layer-prototype.mp4) and [route video](assets/ab04-route-prototype.mp4) show the two transitions. The corrected downward swipe returned A to its card on the emulator.

## Decision

Use A in the production Books page. Keeping the overlay composed but hidden is essential to its frame budget. Keep the underlying list in composition to retain scroll, and bind dock visibility to the expansion progress. `shelfExpand()` starts at damping 0.92/stiffness 186.6; `shelfCollapse()` at damping 1.0/stiffness 438.6. AB-30 should retune with real covers and run the same physical frame gate.

## Limits

- The prototype uses fake covers and a simple page. Final cover loading, real shelf content, predictive-back scrubbing and offscreen-origin handling belong to AB-30; accessibility polish belongs to AB-35.
- The isolated debug package `com.phnem.vetro.shelfprototype` was installed alongside the user's Vetro. Prototype code has not been merged into main because the main workspace contains unrelated uncommitted work.
