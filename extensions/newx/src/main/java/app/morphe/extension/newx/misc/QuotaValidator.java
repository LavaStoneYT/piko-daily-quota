package app.morphe.extension.newx.misc;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.crimera.settings.SettingsValueValidator;

/** Validates the daily-quota text inputs: empty (disabled) or a whole number in [0, 100000]. */
public final class QuotaValidator implements SettingsValueValidator {
    @Override
    @Nullable
    public String errorMessage(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        try {
            int n = Integer.parseInt(trimmed);
            if (n >= 0 && n <= 100000) return null;
        } catch (NumberFormatException ignored) {
            // fall through to error
        }
        return StringRef.str("piko_newx_daily_quota_invalid");
    }
}
