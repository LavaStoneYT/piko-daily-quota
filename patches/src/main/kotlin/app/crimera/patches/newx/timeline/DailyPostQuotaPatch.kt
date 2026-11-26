package app.crimera.patches.newx.timeline

import app.crimera.patches.newx.misc.extension.newXExtensionPatch
import app.crimera.patches.newx.models.newXTimelineModelAdapterPatch
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.newXTextInput
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.settings.settingStrings
import app.morphe.patcher.patch.bytecodePatch

private const val QUOTA_VALIDATOR_DESCRIPTOR =
    "Lapp/morphe/extension/newx/misc/QuotaValidator;"

/**
 * Adds two user-configurable daily post quotas:
 *  - For You / Following (home): at the quota, an animated checkmark + "You've reached the end"
 *    (animation once per day) followed by suggested accounts.
 *  - Search: at the quota, the same end text with no animation, checkmark or suggested accounts.
 *
 * Counting, persistence and the local-midnight reset live in
 * [app.morphe.extension.newx.timeline.DailyQuotaStore]; the quota is applied inside the shared
 * single-pass timeline filter ([app.morphe.extension.newx.timeline.NewXTimelineFilter]) via
 * [app.morphe.extension.newx.timeline.DailyQuotaFilter]. A value of 0 (or empty) disables a quota.
 */
@Suppress("unused")
val newXDailyPostQuotaPatch =
    bytecodePatch(
        name = "NewX: Daily post quotas",
        description = "Limits how many posts For You/Following and Search show per day, with an end-of-feed message.",
    ) {
        unsupportedOnMount()
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(newXTimelineModelAdapterPatch, newXTimelineFilterPatch, newXExtensionPatch)

        newXTextInput(
            id = "newx.timeline.daily_quota.home",
            category = Categories.TIMELINE,
            strings = settingStrings("piko_newx_daily_quota_home"),
            order = 250,
            defaultValue = "0",
            validatorClassDescriptor = QUOTA_VALIDATOR_DESCRIPTOR,
        )

        newXTextInput(
            id = "newx.timeline.daily_quota.search",
            category = Categories.TIMELINE,
            strings = settingStrings("piko_newx_daily_quota_search"),
            order = 251,
            defaultValue = "0",
            validatorClassDescriptor = QUOTA_VALIDATOR_DESCRIPTOR,
        )
    }
