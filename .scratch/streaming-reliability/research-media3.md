# Media3 API research for streaming reliability Level 1

## Audiobook gate update — 2026-09-23 (AB-02)

This section supersedes the older version-status snapshot below for the audiobook work.
The [official Media3 release table](https://developer.android.com/jetpack/androidx/releases/media3)
lists **1.11.1** (2026-09-10) as the latest stable release. The official dependency table
requires the same version for ExoPlayer, HLS, DASH, UI, session, Compose UI, OkHttp datasource,
and ExoPlayer WorkManager. `media3-ui-compose` supplies core state holders for a custom UI;
the Material3 UI artifact is unnecessary for Vetro's custom controls.

Media3 1.11.0 upgraded its own Kotlin compiler from 2.0.20 to 2.2.0. The app currently uses
Kotlin 2.1.0; AB-02 must validate the resulting metadata compatibility by compiling rather than
assuming that a Kotlin upgrade is required. The project's `minSdk` 26 is above the general
AndroidX 24 baseline. Release notes identify HLS, player surface, session, and ABR changes,
so compile plus device smoke are separate gates. The existing app uses `PlayerView`,
`DefaultLoadControl`, a custom `AdaptiveTrackSelection`, and standalone `MediaSession`; it does
not currently use `setCustomLayout` or a Media3 download service.

Official references:

- [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- [Media3 Compose UI](https://developer.android.com/media/media3/ui/compose)
- [Media3 ExoPlayer setup](https://developer.android.com/media/media3/exoplayer/hello-world)

Verified on 2026-07-31 against first-party Android documentation and the official
`androidx/media` repository. The project currently pins every Media3 artifact to
`1.4.1` in `gradle/libs.versions.toml`; its `minSdk` is 26.

## Executive conclusion

- Level 1 does **not** require a Media3 upgrade merely to configure a larger
  streaming buffer. In 1.4.1, use the generic
  `setBufferDurationsMs(...)` and
  `setPrioritizeTimeOverSizeThresholds(true)` on the `DefaultLoadControl`
  belonging to the streaming player. In this repository the streaming and local
  players are separate `ExoPlayer` instances, so that scoped usage does not alter
  the local player's load control.
- The names used in the attached plan,
  `setBufferDurationsMsForStreaming(...)` and
  `setPrioritizeTimeOverSizeThresholdsForStreaming(...)`, do not compile on
  1.4.1. They first exist in Media3 1.9.0.
- The intended six-argument `AdaptiveTrackSelection.Factory` overload already
  exists in 1.4.1. No upgrade is needed for conservative ABR tuning.
- `ExoTrackSelection.shouldCancelChunkLoad(...)` and `excludeTrack(...)` already
  exist in 1.4.1. However, stock `AdaptiveTrackSelection` does not override
  `shouldCancelChunkLoad`, so its inherited behavior is always `false`. A real
  stalled-chunk watchdog needs a custom `AdaptiveTrackSelection`/factory (or
  another custom `ExoTrackSelection`), not only the stock six-argument factory.
- The latest stable release is 1.10.1. It has a known, narrowly scoped DASH
  regression, issue #3326. The fix is in 1.11.0-rc01, which is not stable as of
  the research date.

## Version status

The official release table lists:

- stable: **1.10.1**;
- release candidate: **1.11.0-rc01**;
- latest table update: 2026-07-22.

Source: [official Media3 release notes](https://developer.android.com/jetpack/androidx/releases/media3#latest-update).

Media3 documentation also says all Media3 modules should use the same version.
An upgrade therefore needs to move `media3-exoplayer`, HLS, DASH, UI, session,
and OkHttp datasource together, not only `media3-exoplayer`.

Source: [official ExoPlayer setup guide](https://developer.android.com/media/media3/exoplayer/hello-world#add-dependency).

## `DefaultLoadControl.Builder`

### Media3 1.4.1

The streaming-only APIs are absent. The available relevant signatures are:

```java
Builder setBufferDurationsMs(
    int minBufferMs,
    int maxBufferMs,
    int bufferForPlaybackMs,
    int bufferForPlaybackAfterRebufferMs)

Builder setPrioritizeTimeOverSizeThresholds(
    boolean prioritizeTimeOverSizeThresholds)
```

The duration setter validates all start/rebuffer thresholds as non-negative,
requires `minBufferMs` to be at least both start thresholds, and requires
`maxBufferMs >= minBufferMs`. There is no local-vs-streaming split in this
version; the configured values govern every item played by that `LoadControl`.

Sources:

- [DefaultLoadControl 1.4.1 builder source](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L150-L220)
- [1.4.1 loading decision](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L382-L423)

The 1.4.1 defaults are 50,000 ms minimum and maximum buffer, 2,500 ms before
initial/seek playback, 5,000 ms after rebuffer, with time-over-size priority
disabled.

Source: [DefaultLoadControl 1.4.1 constants](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L44-L75).

### Media3 1.9.0 through current stable 1.10.1

The streaming/local split was added in 1.9.0. The official 1.9.0 notes describe
new local-playback setters and adjusted defaults; inspection of the official
1.8.1 and 1.9.0 tags confirms that both streaming-only method names appear in
1.9.0 and are absent in the earlier line.

Sources:

- [Media3 1.9.0 release notes](https://developer.android.com/jetpack/androidx/releases/media3#1.9.0)
- [DefaultLoadControl at tag 1.8.1](https://github.com/androidx/media/blob/1.8.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)
- [DefaultLoadControl at tag 1.9.0](https://github.com/androidx/media/blob/1.9.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java)

Current stable signatures are:

```java
Builder setBufferDurationsMsForStreaming(
    int minBufferMs,
    int maxBufferMs,
    int bufferForPlaybackMs,
    int bufferForPlaybackAfterRebufferMs)

Builder setPrioritizeTimeOverSizeThresholdsForStreaming(
    boolean prioritizeTimeOverSizeThresholds)
```

Media3 classifies an item as local when its URI has an empty scheme or a scheme
in `LOCAL_PLAYBACK_SCHEMES`; otherwise it uses the streaming values. The generic
setters in current Media3 copy their values into both the streaming and local
fields, while the `ForStreaming` setters update only streaming fields.

Sources:

- [1.10.1 streaming buffer setter and classification](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L311-L352)
- [1.10.1 streaming time-over-size setter](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L462-L481)
- [current official Builder reference](https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl.Builder)

`prioritizeTimeOverSizeThresholds=true` has a precise, limited effect. While the
buffered duration is below `minBuffer`, reaching the target byte allocation no
longer stops loading. It does **not** force loading all the way to `maxBuffer`:
once `minBuffer` is met, the byte target can still stop loading. The same flag
also prevents the byte target alone from satisfying the start/rebuffer
threshold.

Sources:

- [1.4.1 `shouldContinueLoading` and `shouldStartPlayback`](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L382-L423)
- [1.10.1 current logic](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/DefaultLoadControl.java#L763-L815)

## `AdaptiveTrackSelection.Factory`

Both 1.4.1 and 1.10.1 expose two different public six-argument constructors.
The overload intended by the Level 1 plan is the one whose fourth and fifth
arguments are integers:

```java
Factory(
    int minDurationForQualityIncreaseMs,
    int maxDurationForQualityDecreaseMs,
    int minDurationToRetainAfterDiscardMs,
    int maxWidthToDiscard,
    int maxHeightToDiscard,
    float bandwidthFraction)
```

The other six-argument overload is:

```java
Factory(
    int minDurationForQualityIncreaseMs,
    int maxDurationForQualityDecreaseMs,
    int minDurationToRetainAfterDiscardMs,
    float bandwidthFraction,
    float bufferedFractionToLiveEdgeForQualityIncrease,
    Clock clock)
```

Sources:

- [AdaptiveTrackSelection.Factory 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java#L65-L220)
- [AdaptiveTrackSelection.Factory 1.10.1](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java#L65-L220)
- [current official Factory reference](https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.Factory)

The shorter four-argument constructor supplies the defaults
`maxWidthToDiscard=1279` and `maxHeightToDiscard=719`. The six-argument overload
lets the app replace them with `0, 0`.

During `evaluateQueueSize`, Media3 may discard from the first sufficiently far
future chunk only when its bitrate and resolution are below the ideal track and
its positive, known width/height are less than or equal to the configured
maximums. Consequently `0, 0` prevents normal positive-dimension video chunks
from satisfying the discard predicate. It is a practical off-switch, but an
override that always returns `queue.size()` is the stronger guarantee if the
requirement is literally “never discard buffered chunks.”

Sources:

- [1.4.1 defaults](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java#L296-L302)
- [1.4.1 discard predicate](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java#L504-L546)

The constructor needed to inject this factory is also already present in 1.4.1:

```java
DefaultTrackSelector(Context context, ExoTrackSelection.Factory factory)
```

Source: [DefaultTrackSelector 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.java#L2362-L2372).

## Stalled-chunk cancellation APIs

### `shouldCancelChunkLoad`

The signature is identical in 1.4.1 and current stable:

```java
default boolean shouldCancelChunkLoad(
    long playbackPositionUs,
    Chunk loadingChunk,
    List<? extends MediaChunk> queue)
```

Semantics documented by Media3:

- it is only called while the selection is enabled;
- it may be called by discrete-chunk sources that support canceling an in-flight
  load;
- `loadingChunk` can be a `MediaChunk`, but can also be an initialization or
  encryption chunk;
- the default implementation returns `false`;
- a canonical reason to return `true` is a stuck high-quality chunk when loading
  a lower-quality alternative may avoid rebuffering;
- after cancellation, the source calls `evaluateQueueSize`, then calls
  `updateSelectedTrack` before the next load.

Sources:

- [ExoTrackSelection 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/ExoTrackSelection.java#L251-L263)
- [ExoTrackSelection 1.10.1](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/ExoTrackSelection.java#L251-L263)
- [current official ExoTrackSelection reference](https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/ExoTrackSelection)

Stock `AdaptiveTrackSelection` does not override this method in either tag, so
it inherits `false`. Its `Factory` is extensible and exposes a protected
`createAdaptiveTrackSelection(...)`, which is a viable seam for returning a
custom subclass.

Source: [1.4.1 factory creation seam](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.java#L232-L293).

HLS and DASH both forward their `ChunkSource.shouldCancelLoad(...)` decisions to
the active selection's `shouldCancelChunkLoad(...)`, so the hook applies to both
streaming formats used by the project.

Sources:

- [HLS delegation in 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer_hls/src/main/java/androidx/media3/exoplayer/hls/HlsChunkSource.java#L765-L776)
- [DASH delegation in 1.4.1](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/DefaultDashChunkSource.java#L348-L359)

`ChunkSampleStream.reevaluateBuffer()` already refuses to ask for cancellation
after a renderer has read samples from the loading media chunk. A custom
selection does not need to duplicate that safety check, but it should still
distinguish media chunks from initialization/encryption chunks when applying a
throughput watchdog.

Sources:

- [ChunkSampleStream 1.4.1 cancellation guard](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/chunk/ChunkSampleStream.java#L646-L664)
- [same guard in 1.10.1](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/chunk/ChunkSampleStream.java#L715-L733)

### `excludeTrack`

The signature is also identical in 1.4.1 and current stable:

```java
boolean excludeTrack(int index, long exclusionDurationMs)
```

`index` is the index **inside the selection**, not necessarily the index in the
original `TrackGroup`. Exclusion can fail when all other tracks are already
excluded. Excluding the currently selected track does not switch immediately;
it remains selected until the next `updateSelectedTrack(...)` call.

Sources:

- [ExoTrackSelection 1.4.1 contract](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/ExoTrackSelection.java#L264-L286)
- [BaseTrackSelection 1.4.1 implementation](https://github.com/androidx/media/blob/1.4.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/BaseTrackSelection.java#L167-L191)

Implementation implication for Level 1: exclude the loading representation and
return `true` from `shouldCancelChunkLoad` only when exclusion succeeds and the
watchdog cooldown allows it. Otherwise a cancellation loop can reload the same
track or repeatedly cancel without any eligible lower alternative.

## Upgrade constraints and issue #3326

### General constraints

- Media3 1.9.0 raised the library `minSdk` to 23. The project's `minSdk=26`
  satisfies this requirement.
- `DefaultLoadControl`, `AdaptiveTrackSelection`, and `ExoTrackSelection` are
  marked `@UnstableApi`. Media3 explicitly excludes such APIs from binary/API
  compatibility guarantees, so a 1.4.1 -> 1.9/1.10 upgrade needs a full compile,
  lint, and playback regression pass even though the specific signatures above
  are available.
- All Media3 artifacts must be upgraded as a set.

Sources:

- [1.9.0 minSdk change](https://developer.android.com/jetpack/androidx/releases/media3#1.9.0)
- [official UnstableApi contract](https://developer.android.com/reference/androidx/media3/common/util/UnstableApi)
- [official Media3 dependency guidance](https://developer.android.com/media/media3/exoplayer/hello-world#add-dependency)

### Issue #3326 status

Issue #3326 is closed. It is not a slow-mirror or buffer-depletion bug. It is an
`IndexOutOfBoundsException` during preparation of a specific DASH structure:
multi-period DASH (for example SSAI), an enabled subtitle track, and a subtitle
representation whose `SegmentTemplate` has an empty `<SegmentTimeline/>`.

The issue report states that 1.9.4 works and 1.10.0/1.10.1 regress. The official
fix commit adds empty-index guards and tests. The 1.11.0-rc01 release notes list
the fix; the current stable 1.10.1 release does not contain it.

Sources:

- [official issue #3326](https://github.com/androidx/media/issues/3326)
- [official fix commit `6c7dbd9`](https://github.com/androidx/media/commit/6c7dbd9bd89d10859ccd07a90c96cf59a1000f40)
- [1.11.0-rc01 release notes](https://developer.android.com/jetpack/androidx/releases/media3#1.11.0-rc01)

Practical choices for this repository:

1. **Lowest risk for Level 1:** stay on 1.4.1, use the generic load-control
   setters only in the streaming player, and add the conservative selector and
   custom cancellation hook there.
2. **If streaming-only API names are mandatory:** upgrade to at least 1.9.0.
   Pinning 1.9.4 avoids the specific regression reported in #3326; moving to
   stable 1.10.1 requires a targeted empty-`SegmentTimeline` DASH regression test
   if this source shape is possible.
3. **If the #3326 fix is mandatory immediately:** 1.11.0-rc01 contains it, but is
   prerelease. Otherwise wait for 1.11 stable before making that fix the basis of
   a production upgrade.
