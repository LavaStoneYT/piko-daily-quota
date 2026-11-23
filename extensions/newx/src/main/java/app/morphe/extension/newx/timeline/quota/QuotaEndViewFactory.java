/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.newx.timeline.quota;

import android.content.Context;
import android.view.View;

import kotlin.jvm.functions.Function1;

/** AndroidView factory for the timeline end marker row. */
final class QuotaEndViewFactory implements Function1<Object, Object> {
    @Override
    public Object invoke(Object context) {
        return new QuotaEndView((Context) context);
    }
}
