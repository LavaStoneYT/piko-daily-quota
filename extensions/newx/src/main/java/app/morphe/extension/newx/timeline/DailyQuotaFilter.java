package app.morphe.extension.newx.timeline;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.newx.utils.NewXUtils;

/**
 * Applies the daily post quotas inside the shared single-pass timeline filter.
 *
 * <p>Counting / persistence / midnight reset are delegated to {@link DailyQuotaStore}. When a
 * surface reaches its quota the remaining posts are dropped from the returned item list; for the
 * home surface a native "Who to follow" module is kept appended so suggested accounts still render.
 * The end-of-feed message + animated checkmark are rendered by {@link QuotaEndCardView} through an
 * {@link EndCardListener} that a timeline-view hook registers (see DAILY_QUOTA_DESIGN.md).
 *
 * <p>Surface detection uses entry-id prefixes and is intentionally conservative: an unrecognized
 * surface (profile, replies, lists, ...) is left untouched.
 */
public final class DailyQuotaFilter {

    public interface EndCardListener {
        void onQuotaReached(String surface, boolean animate, boolean showSuggestions);
    }

    private static volatile EndCardListener endCardListener;

    public static void setEndCardListener(EndCardListener listener) {
        endCardListener = listener;
    }

    private DailyQuotaFilter() { }

    private static int parseQuota(String key) {
        try {
            String raw = SettingsRegistry.getStringOrDefault(key, "0");
            if (raw == null) return 0;
            raw = raw.trim();
            if (raw.isEmpty()) return 0;
            return Math.max(0, Integer.parseInt(raw));
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static Object unwrap(Object topLevel) {
        if (topLevel != null && NewXTimelineFilter.isTimelineModuleItem(topLevel)) {
            return NewXTimelineFilter.getModuleItem(topLevel);
        }
        return topLevel;
    }

    private static String entryIdOf(Object item) {
        if (item == null) return null;
        if (NewXTimelineFilter.isTimelineModule(item)) return NewXTimelineFilter.getModuleEntryId(item);
        if (NewXTimelineFilter.isTimelinePost(item)) return NewXTimelineFilter.getPostEntryId(item);
        return null;
    }

    /** Returns "home", "search", or null for an unrecognized surface. */
    private static String detectSurface(Iterable<?> items) {
        boolean home = false;
        boolean search = false;
        for (Object topLevel : items) {
            String entryId = entryIdOf(unwrap(topLevel));
            if (entryId == null) continue;
            if (entryId.startsWith("search")) search = true;
            else if (entryId.startsWith("home") || entryId.startsWith("who-to-follow")
                    || entryId.startsWith("conversation-")) home = true;
        }
        if (search) return DailyQuotaStore.SURFACE_SEARCH;
        if (home) return DailyQuotaStore.SURFACE_HOME;
        return null;
    }

    /** Applies the quota; returns the (possibly truncated) immutable item list. */
    public static Object applyQuota(Object timelineItems) {
        Context context = DailyQuotaStore.context();
        if (context == null || timelineItems == null) return timelineItems;
        int homeQuota = parseQuota("newx.timeline.daily_quota.home");
        int searchQuota = parseQuota("newx.timeline.daily_quota.search");
        if (homeQuota <= 0 && searchQuota <= 0) return timelineItems;
        if (!(timelineItems instanceof Iterable<?> iterable)) return timelineItems;

        List<Object> all = new ArrayList<>();
        for (Object item : iterable) all.add(item);

        String surface = detectSurface(all);
        if (surface == null) return timelineItems;
        int quota = DailyQuotaStore.SURFACE_HOME.equals(surface) ? homeQuota : searchQuota;
        if (quota <= 0) return timelineItems;

        boolean isHome = DailyQuotaStore.SURFACE_HOME.equals(surface);
        Object suggestions = null;
        List<Object> out = new ArrayList<>(all.size());
        boolean reached = false;
        for (Object topLevel : all) {
            Object item = unwrap(topLevel);
            if (isHome && suggestions == null && NewXTimelineFilter.isTimelineModule(item)) {
                String entryId = NewXTimelineFilter.getModuleEntryId(item);
                if (entryId != null && entryId.startsWith("who-to-follow")) suggestions = topLevel;
            }
            if (reached) continue; // drop the tail once the quota is hit
            if (NewXTimelineFilter.isTimelinePost(item)) {
                String postId = NewXUtils.identifierToString(NewXTimelineFilter.getPostId(item));
                reached = DailyQuotaStore.recordAndCheck(context, surface, postId, quota);
            }
            out.add(topLevel);
        }

        if (!reached) return timelineItems; // counted, but nothing to truncate yet

        boolean animate = isHome && DailyQuotaStore.consumeAnimation(context, surface);
        if (isHome && suggestions != null && !out.contains(suggestions)) out.add(suggestions);

        EndCardListener listener = endCardListener;
        if (listener != null) {
            final boolean animateFinal = animate;
            NewXUtils.runOnUiThread(() -> listener.onQuotaReached(surface, animateFinal, isHome));
        }
        return NewXTimelineFilter.immutableList(out);
    }
}
