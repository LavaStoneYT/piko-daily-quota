package app.crimera.patches.newx.timeline

import app.crimera.patches.newx.models.newXTimelineModelAdapterPatch
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.Constants.TIMELINE_FILTER_DESCRIPTOR
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.scopedMatchAll
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.p0Register

private const val ENUM_DESCRIPTOR = "Ljava/lang/Enum;"
private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val FILTER_TIMELINE_ITEMS_DESCRIPTOR =
    "$TIMELINE_FILTER_DESCRIPTOR->filterTimelineItemsWithType($ENUM_DESCRIPTOR$OBJECT_DESCRIPTOR)$OBJECT_DESCRIPTOR"

/** Installs the shared single-pass timeline filter hook used by individual features. */
internal val newXTimelineFilterPatch =
    bytecodePatch(default = false) {
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(newXTimelineModelAdapterPatch)

        execute {
            val successConstructor =
                requireExactlyOne(
                    "NewX timeline success constructor",
                    NewXTimelineSuccessFingerprint.scopedMatchAll(),
                )
            val method = successConstructor.method
            // `p1` is the constructor's second argument, the timeline type; `p2` is the third,
            // the `timelineItems` list it stores. The hook rewrites the items register in
            // place so the field keeps the filtered value, and passes the timeline type so
            // per-surface features (daily quotas) can scope themselves.
            val typeRegister = method.p0Register + 1
            val itemsRegister = method.p0Register + 2
            method.insertHook(
                index = 0,
                // The old plain insertion left every incoming label on the constructor head, so a
                // branch that reached it keeps skipping the hook, exactly as before.
                relocateBranchTargets = false,
            ) {
                invokeStatic(
                    methodReference(FILTER_TIMELINE_ITEMS_DESCRIPTOR),
                    typeRegister,
                    itemsRegister,
                )
                moveResult(itemsRegister, OBJECT_DESCRIPTOR)
            }
        }
    }
