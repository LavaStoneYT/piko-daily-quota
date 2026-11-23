/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import app.morphe.extension.newx.timeline.NewXTimelineFilter;

/**
 * Entry IDs of the synthetic end-of-quota marker modules and the helpers the injected
 * renderer hook uses to recognize them.
 *
 * <p>The timeline marker replaces its whole row with the animated end view (see
 * {@link QuotaEndRenderer}); the search marker falls through to the module's native
 * footer text, which renders {@link #END_MESSAGE} as a plain themed text row.
 */
public final class QuotaEndMarkers {
    /** Marker module entry ID for the For You / Following timeline quota. */
    public static final String TIMELINE_END_ENTRY_ID = "piko-daily-quota-timeline-end";

    /** Marker module entry ID for the search quota. */
    public static final String SEARCH_END_ENTRY_ID = "piko-daily-quota-search-end";

    /** Message shown when a daily quota is reached. */
    public static final String END_MESSAGE = "You've reached the end";

    private QuotaEndMarkers() {
    }

    /**
     * Injection point of the module-footer renderer hook: true when [module] is the timeline
     * end marker whose row must be replaced with the animated end view.
     */
    public static boolean isTimelineEndModule(Object module) {
        if (module == null) return false;
        try {
            return TIMELINE_END_ENTRY_ID.equals(NewXTimelineFilter.getModuleEntryId(module));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
