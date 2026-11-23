/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import kotlin.jvm.functions.Function1;

/** No-op AndroidView update callback; the end view reads its own state on attach. */
final class QuotaEndViewBinder implements Function1<Object, Object> {
    @Override
    public Object invoke(Object view) {
        return null;
    }
}
