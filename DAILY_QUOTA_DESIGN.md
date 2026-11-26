# Daily post quotas for X (NewX) — design & status

Base: `crimera/piko` branch `x-lite` @ `86fb221bf9d901cf8d3f8d3dcd94fa498a9966fb`
(= the source behind `crimera/piko-newx` **v3.75.0**, targeting **X 12.41.0-prod.01**).
This branch (`quota`) adds the feature on top of that exact commit.

## Feature
1. **For You / Following (home)** — user sets a daily post quota. At the quota: an animated
   checkmark + "You've reached the end", then suggested accounts. The animation plays once per
   local day; afterwards the message + suggested accounts still appear, without animation.
2. **Search** — user sets a daily post quota. At the quota: the same "You've reached the end"
   text, **no** animation, **no** checkmark, **no** suggested accounts.

Quotas count unique posts shown, persist across restarts, and reset at local midnight.
`0` / empty disables a quota.

## Implemented (grounded in the existing NewX architecture)
- `extensions/newx/.../timeline/DailyQuotaStore.java` — SharedPreferences persistence; local-day
  roll via `LocalDate.toEpochDay()` (midnight reset); unique-post counting; once-per-day animation
  flag. Covered by `DailyQuotaStoreTest` (Robolectric).
- `extensions/newx/.../misc/QuotaValidator.java` — integer validator for the settings inputs.
- `patches/.../newx/timeline/DailyPostQuotaPatch.kt` — two `newXTextInput` settings
  (`newx.timeline.daily_quota.home`, `newx.timeline.daily_quota.search`) under the Timeline category.
- `extensions/newx/.../timeline/DailyQuotaFilter.java` — applied at the end of the existing
  single-pass filter `NewXTimelineFilter.filterTimelineItems` (the real hook installed by
  `TimelineFilterPatch` on the timeline-success constructor). Counts posts via the patch-time
  bridged model helpers, truncates the tail at the quota, and keeps a native `who-to-follow` module
  appended for the home surface (suggested accounts render natively).
- `extensions/newx/.../timeline/QuotaEndCardView.java` — the animated circular-checkmark card +
  message (PathMeasure trim driven by ValueAnimator); static `attachTo(ViewGroup, animate)`.
- Strings added to `patches/src/main/resources/addresources/values/newx/strings.xml`.
- `.github/workflows/build-quota.yml` — builds the `.mpp` (NewX-only) on GitHub Actions.

## Requires the stock APK + a logged-in device to finalize / verify (NOT done here)
- **End-card view attach hook.** `DailyQuotaFilter` fires `DailyQuotaFilter.EndCardListener` on the
  UI thread when a quota is reached; a hook on the timeline's RecyclerView/fragment must register a
  listener that calls `QuotaEndCardView.attachTo(...)`. The exact obfuscated view class/fingerprint
  can only be derived from the X 12.41.0 APK and confirmed on a device. Until wired, counting /
  truncation / suggested-accounts work, but the checkmark+text overlay is not attached.
- **Surface detection refinement.** `detectSurface` uses entry-id prefixes (`search*` vs
  `home*`/`who-to-follow*`/`conversation-*`). This must be validated against real home/search
  payloads; ambiguous surfaces are left untouched (fail-safe).
- **Functional verification.** Requires installing the patched APK on an emulator signed into an X
  account and exercising both timelines across a midnight boundary.

## Build
`./gradlew buildAndroid` (CI workflow does the NewX-only cleanup first). Output:
`patches/build/libs/patches-*.mpp`. GitHub Packages read access (`read:packages`) is required for
`MorpheApp/registry`, `crimera/piko-patches-library`, `crimera/morphe-bytecode` (set `PKG_TOKEN`).

## Verification status
- Unit-tested: quota store logic (counting, midnight reset, persistence, once-per-day animation).
- NOT verified on a device: end-card rendering/animation, surface detection, suggested-accounts
  placement, and the full patched-APK behavior. These need the APK + a logged-in emulator.
