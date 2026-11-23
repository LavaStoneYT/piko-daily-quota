/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.newx.timeline.NewXTimelineFilter;

/**
 * Applies the daily post quotas to a timeline emission.
 *
 * <p>Runs after the shared content filters, so it counts what is actually shown. Posts already
 * counted today keep their place in the list; posts that would push the running count past the
 * configured quota are dropped wherever they appear. At the cutoff a synthetic end marker
 * module (see {@link QuotaEndModuleFactory}) is inserted after the last shown post. On the home
 * timelines, modules that contain no posts (X's "Who to follow" style carousels) survive after
 * the marker as suggested accounts; in search, everything after the marker is removed.
 *
 * <p>X re-emits the whole accumulated timeline on every page load, including the marker this
 * filter injected earlier (it lives in the timeline state). Existing markers are dropped and
 * one fresh marker is re-placed, and already-counted posts are recognized through the
 * day-scoped ID window, so nothing counts twice.
 */
public final class DailyQuotaFilter {
    private static final List<String> HOME_TIMELINE_TYPES = List.of(
            "FOR_YOU",
            "FOLLOWING",
            "RANKED_FOLLOWING"
    );

    private static final List<String> SEARCH_TIMELINE_TYPES = List.of(
            "SEARCH_TOP",
            "SEARCH_LATEST",
            "SEARCH_MEDIA",
            "SEARCH_PEOPLE",
            "SEARCH_LISTS"
    );

    private DailyQuotaFilter() {
    }

    /** Injection point of the timeline success hook: applies the quota for [timelineType]. */
    public static Object apply(Object timelineType, Object timelineItems) {
        if (!(timelineType instanceof Enum<?>)) return timelineItems;
        String kind = kindFor(((Enum<?>) timelineType).name());
        if (kind == null) return timelineItems;
        int quota = DailyQuotaStore.quota(kind);
        if (quota <= 0) return timelineItems;
        if (!(timelineItems instanceof List<?> source)) return timelineItems;

        try {
            return filterKind(kind, quota, source);
        } catch (RuntimeException exception) {
            logFailure(kind, exception);
            return timelineItems;
        }
    }

    private static String kindFor(String timelineTypeName) {
        if (HOME_TIMELINE_TYPES.contains(timelineTypeName)) return DailyQuotaStore.KIND_TIMELINE;
        if (SEARCH_TIMELINE_TYPES.contains(timelineTypeName)) return DailyQuotaStore.KIND_SEARCH;
        return null;
    }

    private static Object filterKind(String kind, int quota, List<?> source) {
        boolean homeTimeline = DailyQuotaStore.KIND_TIMELINE.equals(kind);
        String markerEntryId =
                homeTimeline ? QuotaEndMarkers.TIMELINE_END_ENTRY_ID : QuotaEndMarkers.SEARCH_END_ENTRY_ID;

        // One lock-scoped pass: concurrent constructor calls cannot double-count a post.
        synchronized (DailyQuotaStore.LOCK) {
            DailyQuotaStore.Session session = DailyQuotaStore.open(kind);
            List<Object> result = null;
            List<String> pendingIds = new ArrayList<>();
            int newPosts = 0;
            int keptCount = 0;
            int lastPostKeptOrdinal = -1;
            boolean quotaReached = session.count() >= quota;

            for (Object item : source) {
                if (isMarkerModule(item)) {
                    // X re-emits the marker injected by an earlier pass; drop it and re-place
                    // one fresh marker after the scan.
                    if (result == null) result = prefixOf(source, keptCount);
                    continue;
                }

                List<String> ids = newPostIds(item, session, pendingIds);
                if (ids == null) {
                    // Not post-bearing. Home keeps these everywhere (they become the suggested
                    // accounts under the marker); search drops them once the quota is reached.
                    if (!homeTimeline && quotaReached) {
                        if (result == null) result = prefixOf(source, keptCount);
                        continue;
                    }
                    if (result != null) result.add(item);
                    keptCount++;
                    continue;
                }

                if (session.count() + newPosts + ids.size() > quota) {
                    // This item would push the count past the quota: drop it.
                    if (result == null) result = prefixOf(source, keptCount);
                    quotaReached = true;
                    continue;
                }

                newPosts += ids.size();
                pendingIds.addAll(ids);
                if (result != null) result.add(item);
                keptCount++;
                lastPostKeptOrdinal = keptCount - 1;
                if (session.count() + newPosts >= quota) quotaReached = true;
            }

            if (quotaReached) {
                if (result == null) result = new ArrayList<>(source);
                result.add(lastPostKeptOrdinal + 1, QuotaEndModuleFactory.createEndModule(
                        markerEntryId, QuotaEndMarkers.END_MESSAGE));
            }

            for (String id : pendingIds) session.record(id);
            session.commit();

            if (result == null) return source;
            return NewXTimelineFilter.immutableList(result);
        }
    }

    /** Copies [0, end) of the emission, starting the changed list lazily. */
    private static List<Object> prefixOf(List<?> source, int end) {
        List<Object> prefix = new ArrayList<>(Math.max(end, 1));
        for (int i = 0; i < end; i++) prefix.add(source.get(i));
        return prefix;
    }

    /** True when [item] is one of this feature's end marker modules. */
    private static boolean isMarkerModule(Object item) {
        if (!NewXTimelineFilter.isTimelineModule(item)) return false;
        String entryId = NewXTimelineFilter.getModuleEntryId(item);
        return QuotaEndMarkers.TIMELINE_END_ENTRY_ID.equals(entryId)
                || QuotaEndMarkers.SEARCH_END_ENTRY_ID.equals(entryId);
    }

    /**
     * The not-yet-counted post IDs a top-level item would add, or null when the item bears no
     * posts at all. Posts counted earlier today (or already staged in this pass) return an
     * empty list: the item stays without raising the count.
     */
    private static List<String> newPostIds(
            Object item,
            DailyQuotaStore.Session session,
            List<String> pending
    ) {
        if (NewXTimelineFilter.isTimelinePost(item)) {
            String id = NewXTimelineFilter.getPostEntryId(item);
            if (isNew(id, session, pending)) return List.of(id);
            return List.of();
        }
        if (NewXTimelineFilter.isTimelineModule(item)) {
            List<String> ids = null;
            List<?> children = NewXTimelineFilter.getModuleInnerContent(item);
            if (children == null) return null;
            for (Object child : children) {
                if (!NewXTimelineFilter.isTimelineModuleItem(child)) continue;
                Object inner = NewXTimelineFilter.getModuleItem(child);
                if (!NewXTimelineFilter.isTimelinePost(inner)) continue;
                String id = NewXTimelineFilter.getPostEntryId(inner);
                if (!isNew(id, session, pending)) continue;
                if (ids == null) ids = new ArrayList<>(4);
                ids.add(id);
            }
            return ids;
        }
        return null;
    }

    private static boolean isNew(String id, DailyQuotaStore.Session session, List<String> pending) {
        return id != null && !session.seen(id) && !pending.contains(id);
    }

    private static void logFailure(String kind, RuntimeException exception) {
        NewXLogger.printException(
                () -> "Failed to apply the " + kind + " daily quota", exception);
    }
}
