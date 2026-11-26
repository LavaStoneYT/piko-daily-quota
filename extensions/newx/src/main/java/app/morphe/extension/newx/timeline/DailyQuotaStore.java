package app.morphe.extension.newx.timeline;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Persistent per-day post counters for the daily quota feature.
 *
 * <p>State lives in {@link SharedPreferences} so it survives app restarts. A "day" is the local
 * calendar day ({@link LocalDate#toEpochDay()} in the system zone), so the counter naturally resets
 * at local midnight: the first access on a new day sees a different epoch-day and clears the slate.
 *
 * <p>Posts are counted uniquely by id so re-binding / re-emitting the same post (X re-sends the whole
 * accumulated timeline on every page) never double counts. The animation flag is separate and is
 * consumed at most once per day.
 */
public final class DailyQuotaStore {

    public static final String SURFACE_HOME = "home";
    public static final String SURFACE_SEARCH = "search";

    private static final String PREFS = "piko_daily_quota";

    /** Overridable clock so unit tests can simulate midnight without waiting. */
    static LongSupplier dayClock = DailyQuotaStore::localEpochDay;

    private DailyQuotaStore() { }

    private static long localEpochDay() {
        return LocalDate.now(ZoneId.systemDefault()).toEpochDay();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String k(String surface, String suffix) {
        return surface + "_" + suffix;
    }

    /** Clears the surface's counters if the stored day is not today. Returns today's epoch-day. */
    private static long rollDay(SharedPreferences p, String surface) {
        long today = dayClock.getAsLong();
        if (p.getLong(k(surface, "day"), Long.MIN_VALUE) != today) {
            p.edit()
                    .putLong(k(surface, "day"), today)
                    .putInt(k(surface, "count"), 0)
                    .putStringSet(k(surface, "seen"), new HashSet<>())
                    .apply();
        }
        return today;
    }

    /**
     * Records that a post was shown and reports whether the surface has now reached its quota.
     * A non-positive quota means "disabled" and never reports reached.
     */
    public static synchronized boolean recordAndCheck(Context context, String surface, String postId, int quota) {
        if (quota <= 0 || postId == null || postId.isEmpty()) return false;
        SharedPreferences p = prefs(context);
        rollDay(p, surface);
        Set<String> seen = new HashSet<>(p.getStringSet(k(surface, "seen"), new HashSet<>()));
        if (seen.add(postId)) {
            int count = seen.size();
            p.edit().putStringSet(k(surface, "seen"), seen).putInt(k(surface, "count"), count).apply();
        }
        return p.getInt(k(surface, "count"), 0) >= quota;
    }

    /** Number of unique posts shown today on the surface (0 after a midnight roll). */
    public static synchronized int countToday(Context context, String surface) {
        SharedPreferences p = prefs(context);
        rollDay(p, surface);
        return p.getInt(k(surface, "count"), 0);
    }

    public static synchronized boolean isReached(Context context, String surface, int quota) {
        return quota > 0 && countToday(context, surface) >= quota;
    }

    /**
     * Returns true exactly once per local day per surface: the first call on a day marks the
     * animation shown and returns true; later calls the same day return false. Used so the
     * checkmark animation plays once per day while the end message keeps appearing statically.
     */
    public static synchronized boolean consumeAnimation(Context context, String surface) {
        SharedPreferences p = prefs(context);
        long today = rollDay(p, surface);
        if (p.getLong(k(surface, "anim_day"), Long.MIN_VALUE) != today) {
            p.edit().putLong(k(surface, "anim_day"), today).apply();
            return true;
        }
        return false;
    }
}
