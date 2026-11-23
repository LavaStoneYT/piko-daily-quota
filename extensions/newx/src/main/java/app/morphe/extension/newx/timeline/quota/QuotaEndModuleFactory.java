/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

/**
 * Builds the synthetic end-of-quota marker module.
 *
 * <p>The marker is a timeline module with no header, an empty carousel body and a text
 * footer: it renders as one small text row (search) or as the animated end view through the
 * footer renderer hook (home timelines).
 *
 * <p>{@link #newFooter} and {@link #newModule} are bridge stubs whose bodies the patch
 * rewrites with the release-resolved module constructors. Their extra parameters are not
 * values: they reserve the contiguous register spans the range invokes stage their operands
 * through, which is why callers pass null for them.
 */
public final class QuotaEndModuleFactory {
    private QuotaEndModuleFactory() {
    }

    /** Creates the marker module for [entryId] with a [footerText] footer. */
    public static Object createEndModule(String entryId, String footerText) {
        Object footer = newFooter(footerText, null, null, null);
        return newModule(entryId, footer, null, null, null, null, null, null, null);
    }

    /**
     * Bridge: {@code new ModuleFooter(footerText, null, Classic)}.
     *
     * <p>Parameter registers v0..v3 stage the constructor invoke:
     * v0 = new instance, v1 = text, v2 = null display details, v3 = Classic display type.
     */
    static Object newFooter(String footerText, Object span1, Object span2, Object span3) {
        return null;
    }

    /**
     * Bridge: {@code new UrtTimelineModule(emptyList(), null, footer, Carousel, 0L, entryId, null)}.
     *
     * <p>Parameter registers v0..v8 stage the constructor invoke:
     * v0 = new instance, v1 = inner content, v2 = header, v3 = footer, v4 = display type,
     * v5/v6 = sort index, v7 = entry ID, v8 = client event info.
     */
    static Object newModule(
            String entryId,
            Object footer,
            Object span2,
            Object span3,
            Object span4,
            Object span5,
            Object span6,
            Object span7,
            Object span8
    ) {
        return null;
    }
}
