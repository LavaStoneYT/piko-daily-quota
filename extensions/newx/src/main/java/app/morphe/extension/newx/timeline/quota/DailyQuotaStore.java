/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.shared.Utils;

/**
 * Daily post-quota counters, persisted across app restarts and reset at local midnight.
 *
 * <p>Each quota kind keeps the number of distinct posts shown today plus a bounded window of
 * the most recent post entry IDs. X re-emits the whole accumulated timeline on every page
 * load, so the ID window is what keeps already-seen posts from counting twice; the window is
 * larger than the largest selectable quota, so a day's counted IDs always fit.
 */
public final class DailyQuotaStore {
    /** Quota kind covering the For You, Following and Ranked Following home tabs. */
    public static final String KIND_TIMELINE = "timeline";

    /** Quota kind covering the search result tabs. */
    public static final String KIND_SEARCH = "search";

    private static final String PREFS_NAME = "piko_newx_daily_quota";
    private static final String DAY_KEY_SUFFIX = ".day";
    private static final String COUNT_KEY_SUFFIX = ".count";
    private static final String IDS_KEY_SUFFIX = ".ids";
    private static final String TIMELINE_ANIMATION_DAY_KEY = "timeline.animation_day";
    private static final String UNLIMITED = "unlimited";
    /** Larger than the biggest selectable quota, so re-emission dedup stays exact for a day. */
    private static final int ID_WINDOW = 1024;

    static final Object LOCK = new Object();

    private DailyQuotaStore() {
    }

    /** Setting ID backing the given quota kind. */
    public static String settingIdFor(String kind) {
        if (KIND_SEARCH.equals(kind)) return "newx.timeline.daily_quota.search_posts";
        return "newx.timeline.daily_quota.posts";
    }

    /**
     * The configured daily post quota for [kind], or 0 when unlimited (the default, or the
     * setting is not registered because the patch was not selected).
     */
    public static int quota(String kind) {
        String id = settingIdFor(kind);
        if (!SettingsRegistry.isRegistered(id)) return 0;
        try {
            String value = SettingsRegistry.getStringOrDefault(id, UNLIMITED);
            if (value == null || UNLIMITED.equals(value)) return 0;
            return Integer.parseInt(value.trim());
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /** Opens (and if needed rolls over) today's counting session for [kind]. */
    static Session open(String kind) {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs();
            String today = today();
            String day = prefs.getString(kind + DAY_KEY_SUFFIX, null);
            int count = 0;
            ArrayDeque<String> ids = new ArrayDeque<>();
            if (today.equals(day)) {
                count = prefs.getInt(kind + COUNT_KEY_SUFFIX, 0);
                for (String id : readIds(prefs, kind + IDS_KEY_SUFFIX)) {
                    ids.addLast(id);
                    if (ids.size() > ID_WINDOW) ids.removeFirst();
                }
            }
            return new Session(kind, today, count, ids);
        }
    }

    /** True once per local day; the timeline end view consumes this before animating. */
    public static boolean consumeAnimationForToday() {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs();
            String today = today();
            String played = prefs.getString(TIMELINE_ANIMATION_DAY_KEY, null);
            if (today.equals(played)) return false;
            prefs.edit().putString(TIMELINE_ANIMATION_DAY_KEY, today).apply();
            return true;
        }
    }

    /**
     * Clears today's counters for both quota kinds and lets the end animation play again
     * (settings action).
     */
    public static void resetToday() {
        synchronized (LOCK) {
            SharedPreferences prefs = prefs();
            prefs.edit()
                    .putInt(KIND_TIMELINE + COUNT_KEY_SUFFIX, 0)
                    .putString(KIND_TIMELINE + IDS_KEY_SUFFIX, new JSONArray().toString())
                    .putInt(KIND_SEARCH + COUNT_KEY_SUFFIX, 0)
                    .putString(KIND_SEARCH + IDS_KEY_SUFFIX, new JSONArray().toString())
                    .remove(TIMELINE_ANIMATION_DAY_KEY)
                    .apply();
        }
    }

    private static SharedPreferences prefs() {
        Context context = Utils.getContext();
        if (context == null) throw new IllegalStateException("Shared context is not initialized");
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    private static List<String> readIds(SharedPreferences prefs, String key) {
        List<String> ids = new ArrayList<>();
        String raw = prefs.getString(key, null);
        if (raw == null) return ids;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i, null);
                if (id != null) ids.add(id);
            }
        } catch (JSONException ignored) {
            // A malformed window just loses dedup for old posts; start it over.
        }
        return ids;
    }

    /** One pass over a timeline emission: counts, dedups, and persists on commit. */
    static final class Session {
        private final String kind;
        private final String day;
        private final HashSet<String> idSet;
        private final ArrayDeque<String> ids;
        private int count;
        private boolean dirty;

        private Session(String kind, String day, int count, ArrayDeque<String> ids) {
            this.kind = kind;
            this.day = day;
            this.count = count;
            this.ids = ids;
            this.idSet = new HashSet<>(ids);
        }

        int count() {
            return count;
        }

        boolean seen(String id) {
            return id != null && idSet.contains(id);
        }

        void record(String id) {
            if (id == null || idSet.contains(id)) return;
            idSet.add(id);
            ids.addLast(id);
            if (ids.size() > ID_WINDOW) {
                String evicted = ids.removeFirst();
                if (evicted != null) idSet.remove(evicted);
            }
            count++;
            dirty = true;
        }

        void commit() {
            if (!dirty) return;
            synchronized (LOCK) {
                SharedPreferences prefs = prefs();
                JSONArray array = new JSONArray();
                for (String id : ids) array.put(id);
                prefs.edit()
                        .putString(kind + DAY_KEY_SUFFIX, day)
                        .putInt(kind + COUNT_KEY_SUFFIX, count)
                        .putString(kind + IDS_KEY_SUFFIX, array.toString())
                        .apply();
            }
            dirty = false;
        }
    }
}
