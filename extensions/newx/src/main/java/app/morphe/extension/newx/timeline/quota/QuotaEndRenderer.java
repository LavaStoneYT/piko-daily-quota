/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import kotlin.jvm.functions.Function1;

/**
 * Renders the timeline end marker's replacement row through Compose view interop.
 *
 * <p>{@link #renderTimelineEnd} is the injection target of the module-footer renderer hook:
 * it receives the footer row's Composer and Modifier and composes the custom end view in
 * place of the native footer text. {@link #invokeAndroidView} is the bridge stub whose body
 * the patch rewrites with the release-resolved AndroidView composable; its parameters stage
 * the range invoke's operand span (v0 = factory, v1 = modifier, v2 = update, v3 = composer,
 * v4/v5 = the two changed ints).
 */
public final class QuotaEndRenderer {
    /** Stable factory: a single instance keeps the interop node skippable across recompositions. */
    static final Function1<Object, Object> VIEW_FACTORY = new QuotaEndViewFactory();

    /** No-op update callback; the view reads its own state when it attaches. */
    static final Function1<Object, Object> VIEW_UPDATE = new QuotaEndViewBinder();

    private QuotaEndRenderer() {
    }

    /**
     * Injection point: composes the animated end view for the timeline quota marker.
     */
    public static void renderTimelineEnd(Object composer, Object modifier) {
        invokeAndroidView(VIEW_FACTORY, modifier, VIEW_UPDATE, composer, null, null);
    }

    /**
     * Bridge: {@code AndroidView(factory, modifier, update, composer, 0, 0)}.
     */
    static void invokeAndroidView(
            Object factory,
            Object modifier,
            Object update,
            Object composer,
            Object span4,
            Object span5
    ) {
    }
}
