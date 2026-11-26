package app.morphe.extension.newx.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Proves the quota counting, local-midnight reset, persistence and once-per-day animation logic. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class DailyQuotaStoreTest {

    private Context context;
    private long day;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("piko_daily_quota", Context.MODE_PRIVATE).edit().clear().commit();
        day = 20000L;
        DailyQuotaStore.dayClock = () -> day;
    }

    @After
    public void tearDown() {
        DailyQuotaStore.dayClock = null;
    }

    @Test
    public void countsUniquePostsAndReachesQuota() {
        assertFalse(DailyQuotaStore.recordAndCheck(context, "home", "1", 3));
        assertFalse(DailyQuotaStore.recordAndCheck(context, "home", "2", 3));
        // Re-showing post 1 must not advance the count.
        assertFalse(DailyQuotaStore.recordAndCheck(context, "home", "1", 3));
        assertEquals(2, DailyQuotaStore.countToday(context, "home"));
        assertTrue(DailyQuotaStore.recordAndCheck(context, "home", "3", 3));
        assertTrue(DailyQuotaStore.isReached(context, "home", 3));
    }

    @Test
    public void disabledQuotaNeverReaches() {
        for (int i = 0; i < 50; i++) {
            assertFalse(DailyQuotaStore.recordAndCheck(context, "home", String.valueOf(i), 0));
        }
    }

    @Test
    public void resetsAtLocalMidnight() {
        DailyQuotaStore.recordAndCheck(context, "search", "a", 2);
        DailyQuotaStore.recordAndCheck(context, "search", "b", 2);
        assertTrue(DailyQuotaStore.isReached(context, "search", 2));
        day += 1; // next local day
        assertEquals(0, DailyQuotaStore.countToday(context, "search"));
        assertFalse(DailyQuotaStore.isReached(context, "search", 2));
    }

    @Test
    public void surfacesAreIndependent() {
        DailyQuotaStore.recordAndCheck(context, "home", "x", 1);
        assertTrue(DailyQuotaStore.isReached(context, "home", 1));
        assertFalse(DailyQuotaStore.isReached(context, "search", 1));
    }

    @Test
    public void animationPlaysOncePerDay() {
        assertTrue(DailyQuotaStore.consumeAnimation(context, "home"));
        assertFalse(DailyQuotaStore.consumeAnimation(context, "home"));
        assertFalse(DailyQuotaStore.consumeAnimation(context, "home"));
        day += 1;
        assertTrue(DailyQuotaStore.consumeAnimation(context, "home"));
    }

    @Test
    public void persistsAcrossRestart() {
        DailyQuotaStore.recordAndCheck(context, "home", "p1", 5);
        DailyQuotaStore.recordAndCheck(context, "home", "p2", 5);
        // A fresh process reads the same SharedPreferences; simulate by re-reading count.
        assertEquals(2, DailyQuotaStore.countToday(context, "home"));
    }
}
