# F-Droid release notes

F-Droid builds Vetro from the tags in `Phnem/Vetro` using the recipe
[`metadata/com.phnem.vetro.yml`](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.phnem.vetro.yml)
in `fdroiddata`. The `checkupdates` bot opens an "Update Vetro to NNN" merge request for every new
tag; that MR only merges when the build passes F-Droid's source scanner.

## Why F-Droid stopped at 3.3.4

`bot: Update Vetro to 335` ([!50634](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50634))
failed with:

```
ERROR: Found usual suspect 'libs.play.services.cast.framework: com.google.android.gms...' at app/build.gradle.kts
ERROR: Could not build app com.phnem.vetro: Can't build due to 1 error while scanning
```

Google Cast (`com.google.android.gms:play-services-cast-framework`, added in 3.3.5) is a
proprietary dependency. The MR carries the labels `non-free-builddeps` and `waiting-for-upstream`,
and every later tag (3.3.6, 3.3.7, 3.3.8) piles up behind it.

## What the repo does about it

Google Cast now lives in `app/src/cast/` (adapter, options provider and the dependency line in
`cast-deps.gradle`). `app/build.gradle.kts` applies that folder only while it exists. The script is
Groovy on purpose: lint crashes on any extra `.kts` build script (`findFirCompiledSymbol`).
Without it, `app/src/nocast/` supplies an empty `createCastAdapter()` and the app falls back to
DLNA. GitHub, Obtainium and Komi Store builds are unchanged.

## What has to change in `fdroiddata`

Add `rm` to the build entry (and keep it for every later one), so the scanner never sees the folder:

```yaml
  - versionName: v3.3.8-Beta
    versionCode: 338
    commit: <tag commit>
    subdir: app
    submodules: true        # whisper.cpp is a git submodule
    rm:
      - app/src/cast
    gradle:
      - yes
```

Then close !50634 (or push the change to the bot's branch) and let `checkupdates` open the next MR.
`AutoUpdateMode: Version` copies the previous build entry, so once `rm` is there it carries over.
The tag must be pushed and its `app/build.gradle.kts` must carry the same `versionName` and `versionCode`.

## Manga auto-translation is off in the F-Droid build

The feature (`manga/translate`) can be switched on only when the APK is signed with the author's
key: `OfficialBuild` compares the SHA-256 of the signing certificate with `OFFICIAL_SHA256`
(`e80ab80f…3894`, the value `apksigner verify --print-certs` prints for a GitHub release). F-Droid
re-signs with its own key, so Settings shows a plaque explaining why the switch is unavailable. The
same switch also needs at least one connected AI provider (BYOK); without it the plaque says so.

If F-Droid ever publishes the author-signed APK (reproducible builds with `Binaries:`), the check
passes there too, which is the intended outcome. Rotating the release key means adding the new
fingerprint next to the old one.

Open point: the feature adds `com.microsoft.onnxruntime:onnxruntime-android` (MIT, Maven Central).
It is not on the scanner's list of known proprietary libraries, but the first F-Droid build after
this change should confirm it. If the scanner objects, the same `src/cast` trick works: move the
dependency and `manga/translate/pipeline` into an optional source set that the recipe `rm`s.

## Release checklist

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts` and add
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Commit, then `git tag vX.Y.Z-Stable` on that commit and push the tag.
3. `gh release create` with the signed APK.
4. Watch the bot MR in `fdroiddata`; if its `fdroid build` job fails, the log is the first place to look.
