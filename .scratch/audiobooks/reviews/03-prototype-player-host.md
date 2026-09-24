# AB-03 review — player host prototype

Status: DONE_WITH_DEVIATIONS (frame budget and real Details flight deferred to AB-10)

## Evidence

- Isolated worktree/branch: `C:\Users\2004i\.codex\worktrees\audiobooks-player-prototype\Vetro-collection`, `codex/audiobooks-player-prototype`.
- Host is a single Compose surface above `NavHost`, with `originRect` flight, mini/full expansion,
  drag, predictive back, dimmed/scaled workspace, fake cover and three frosted controls.
- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:assembleDebugAndroidTest` passed.
- API 37 emulator test: 20 open/expand/collapse cycles passed twice; the second run also checked
  normal back from full to mini and mini to dismissed. No RenderThread crash observed.
- [20-cycle video](assets/ab03-prototype-20-cycles.mp4) and [short GIF](assets/ab03-prototype-emulator.gif).
- Emulator `gfxinfo` during stress: with recording, 505/666 janky frames (75.83%, p50 46 ms);
  without recording, 273/374 (72.99%, p50 48 ms). This fails the 0 frames over 32 ms target.
- Removing a redundant runtime blur over the fake gradient yielded 471/702 janky frames
  (67.09%, p50 38 ms) in a later no-recording sample. Moving animation state reads from
  composition into layout/graphics layers yielded 521/789 (66.03%, p50 42 ms). Emulator
  measurements vary, and neither experiment meets the frame budget.
- Xiaomi 2211133C, API 36: the same 20-cycle test and full → mini → dismissed back sequence
  passed. Without screen recording, `FrameMetrics` reported 18/2966 frames over 32 ms during
  arrival/expand/collapse, while `gfxinfo` p50 was 9 ms and p95 21–22 ms. A shorter overlap
  between mini/full glass controls yielded 28/2966 over 32 ms and was reverted.
- [Physical-device video](assets/ab03-phone-prototype.mp4),
  [GIF](assets/ab03-phone-prototype.gif), and [gfxinfo sample](assets/ab03-phone-gfxinfo-during.txt).

## Spec assessment

- D-04 single host: structurally supported. `originRect` geometry gives a cover flight without
  composing `layerBackdrop` inside `sharedBounds`.
- Flight from the actual audiobook Details screen: untested; only the fake Books cover exists.
- Player controls are decorative in this geometry prototype; playback is out of scope for AB-03.
- The fake atmosphere uses a smooth tinted gradient. The production player must cache a blurred
  version of the actual cover rather than rerendering a full-surface blur during the morph.
- `sharedElement` was intentionally not used for the host; the geometry mechanism is the chosen
  alternative, and 20 cycles validate that mechanism rather than a `sharedElement` variant.
- MIUI rejects ADB input and background Activity launches. The debug-only exported test Activity
  is launched from ADB while the instrumentation process waits; Compose semantics drive the
  cycles, and Android back dispatcher is invoked within the test. The separate prototype package
  is installed alongside the user's original Vetro.

## Deviation and follow-up

The ticket's absolute 0 frames over 32 ms target was not met on physical hardware. The host
architecture is viable for subsequent implementation, but AB-10 must profile and optimize the
production player with a real cover, cache its blurred backdrop, test the Details origin flight,
and repeat the physical frame gate before the prototype is merged into main.

## Repository hygiene

The main workspace had many unrelated dirty files before AB-03. Prototype code remains in its
worktree and has not been merged into main. No pre-existing changes were reset.
