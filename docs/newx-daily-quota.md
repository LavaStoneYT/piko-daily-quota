# NewX daily post quotas

Adds `NewX: Daily post quotas`, two user-configurable daily limits on how many posts can be
viewed, plus the end-of-feed presentation for each surface.

## Settings (Piko settings, Timeline category, "Daily post quotas" group)

| Setting | Values | Meaning |
|---|---|---|
| Timeline daily quota | Unlimited, 20..500 | For You / Following / Ranked Following combined daily post budget |
| Search daily quota | Unlimited, 20..500 | Search tabs (Top/Latest/Media/People/Lists) daily post budget |
| Reset today's counters | action | Clears both counters and lets the animation play again |

## Behavior

- The counter is per local day (`java.time.LocalDate`), persisted in the
  `piko_newx_daily_quota` shared preferences, so it survives restarts and resets at local
  midnight.
- Posts are counted by entry ID. X re-emits the accumulated timeline on every page load, so a
  1024-entry day-scoped ID window deduplicates re-emissions; the window is larger than the
  largest selectable quota.
- The quota pass runs after the shared content filters (it counts what is shown) and inside the
  same lock as the counter store, so concurrent timeline constructions cannot double-count.
- Timeline quota reached: a synthetic end module is inserted after the last shown post. Its
  footer row is replaced by an animated checkmark + fading "You've reached the end" message
  (custom view through Compose `AndroidView` interop). The animation plays once per day
  (`consumeAnimationForToday`); afterwards the same view renders statically. X's post-free
  modules (who-to-follow style carousels) survive after the marker as suggested accounts.
- Search quota reached: the same message as the module footer's native text row, with no
  animation, no checkmark and no suggested accounts; everything after the cutoff is dropped.

## Implementation map

Extension (`extensions/newx/.../timeline/quota/`):

- `DailyQuotaStore` — persistence, day rollover, ID window, once-per-day animation flag.
- `DailyQuotaFilter` — the emission pass: dedup, cutoff, marker placement (home vs search),
  re-placement of markers from earlier emissions that live on in X's timeline state.
- `QuotaEndModuleFactory` — bridge stubs that the patch rewrites into the release module and
  footer constructors (`UrtTimelineModule(emptyList, null, footer, Carousel, 0, entryId, null)`
  with a `ModuleFooter(text, null, Classic)`).
- `QuotaEndMarkers` — marker entry IDs and the `isTimelineEndModule` injection point.
- `QuotaEndRenderer` / `QuotaEndView` — the `AndroidView` bridge stub and the animated end
  view; the animation starts from a pre-draw listener once the row is actually visible.
- `QuotaResetAction` — settings action handler.

Patch (`patches/.../timeline/quota/DailyQuotaPatch.kt`):

- `newXDailyQuotaBridgesPatch` — resolves the footer model (3 instance fields: text, nullable
  details, display-type enum), the Carousel singleton (from the sealed hierarchy's serializer
  registrations: the discriminator string is registered beside the singleton field read in
  every factory, so the singleton field is the unique tuple), and the `AndroidView` composable
  (unique `(factory, modifier, update, composer, changed)` signature in the interop package
  with the full overload beside it). Rewrites the three bridge stubs; parameter registers stage
  the range invokes and are narrowed with `checkCast` where the composable's declared types
  require it.
- `newXDailyQuotaEndRendererHookPatch` — resolves the module-footer text composable (unique
  static `(footer, Modifier, Function1, Composer, I)` reading the footer display-type field),
  then the URT module-footer row renderer (the unique `Function3` under `com/x/urt/ui` that
  calls it). Inserts after the renderer's skippability prelude: `isTimelineEndModule(module)`
  → `renderTimelineEnd(composer, modifier)` → return `Unit`, else fall through to the original
  footer.
- `newXDailyQuotaPatch` — settings contribution and dependency wiring.
- `TimelineFilterPatch` now passes the timeline type: the success-constructor hook calls
  `NewXTimelineFilter.filterTimelineItemsWithType(Enum, Object)`, which runs the shared content
  filters and then `DailyQuotaFilter.apply`.

## Verification (12.41.0-prod.01)

- `:patches:build`, `:patches:lintNewxResolvers`, `:patches:checkExtensionDescriptors` (289
  references) pass.
- Patching 12.41.0-prod.01 with the full default set reports `Applied: NewX: Daily post
  quotas`; the patched APK contains the constructor hook in the timeline success constructor,
  the renderer hook in the module-footer lambda, and the three rewritten bridges.
- `--verify-with-sdk` dex verification: the only cross-DEX findings are X's own optional
  Stripe/Amazon references, identical to the original APK.
- Android 16 x86_64 emulator: installs, boots, reaches the login screen with no crashes and no
  Piko errors. End-to-end quota behavior needs a logged-in account, which was not available;
  the emulator also required an `extractNativeLibs=true` repack because X ships no x86_64
  native libraries and SoLoader probes the device's primary ABI inside the APK.

Known limits: the animation-once flag is global (first quota surface that renders it wins);
`SEARCH_PEOPLE` results are gated by the search quota but, not being posts, do not count
toward it.
