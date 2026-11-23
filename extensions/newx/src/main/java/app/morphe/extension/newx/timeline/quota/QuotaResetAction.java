/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import android.app.Activity;

import app.morphe.extension.crimera.settings.SettingsActionHandler;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/** Settings action that restarts today's quota counters from zero. */
public final class QuotaResetAction implements SettingsActionHandler {
    @Override
    public void run(Activity activity) {
        DailyQuotaStore.resetToday();
        Utils.showToastShort(ResourceUtils.getStringOrThrow("piko_newx_daily_quota_reset_done"));
    }
}
