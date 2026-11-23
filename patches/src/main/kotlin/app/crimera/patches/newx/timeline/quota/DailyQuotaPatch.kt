/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.newx.timeline.quota

import app.crimera.bytecode.Target
import app.crimera.bytecode.fieldReference
import app.crimera.bytecode.insertHook
import app.crimera.bytecode.methodReference
import app.crimera.patches.common.classDefFlatMap
import app.crimera.patches.common.requireExactlyOne
import app.crimera.patches.common.scopedMatchAll
import app.crimera.patches.common.semantic.patchBridge
import app.crimera.patches.common.semantic.requireBridge
import app.crimera.patches.newx.models.newXTimelineModelAdapterPatch
import app.crimera.patches.newx.models.resolvedNewXTimelineModels
import app.crimera.patches.newx.settings.Categories
import app.crimera.patches.newx.settings.Groups
import app.crimera.patches.newx.settings.newXSettings
import app.crimera.patches.newx.timeline.newXTimelineFilterPatch
import app.crimera.patches.newx.utils.unsupportedOnMount
import app.crimera.patches.newx.utils.Constants.COMPATIBILITY_NEW_X
import app.crimera.patches.newx.utils.Constants.EXTENSION_PACKAGE
import app.crimera.patches.settings.action
import app.crimera.patches.settings.choice
import app.crimera.patches.settings.group
import app.crimera.patches.settings.settingStrings
import app.crimera.patches.settings.singleChoice
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val OBJECT_DESCRIPTOR = "Ljava/lang/Object;"
private const val STRING_DESCRIPTOR = "Ljava/lang/String;"
private const val LIST_DESCRIPTOR = "Ljava/util/List;"
private const val ENUM_DESCRIPTOR = "Ljava/lang/Enum;"
private const val MODIFIER_DESCRIPTOR = "Landroidx/compose/ui/Modifier;"
private const val COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"
private const val COMPOSE_RUNTIME_PACKAGE = "Landroidx/compose/runtime/"
private const val FUNCTION1_DESCRIPTOR = "Lkotlin/jvm/functions/Function1;"
private const val FUNCTION3_DESCRIPTOR = "Lkotlin/jvm/functions/Function3;"
private const val QUOTA_PACKAGE = "$EXTENSION_PACKAGE/timeline/quota"
private const val QUOTA_MODULE_FACTORY_DESCRIPTOR = "$QUOTA_PACKAGE/QuotaEndModuleFactory;"
private const val QUOTA_RENDERER_DESCRIPTOR = "$QUOTA_PACKAGE/QuotaEndRenderer;"
private const val QUOTA_MARKERS_DESCRIPTOR = "$QUOTA_PACKAGE/QuotaEndMarkers;"
private const val VIEWINTEROP_PACKAGE = "Landroidx/compose/ui/viewinterop/"
private const val URT_UI_PACKAGE = "Lcom/x/urt/ui/"

/** The sealed ModuleDisplayType discriminator of the carousel singleton. */
private const val CAROUSEL_DISCRIMINATOR = "com.x.models.timelinemodule.ModuleDisplayType.Carousel"

private object ModuleDisplayTypeSerializerFingerprint : Fingerprint(
    strings = listOf(CAROUSEL_DISCRIMINATOR),
)

/** The footer model's text field and the display-type enum constructor argument it pairs with. */
private data class ResolvedQuotaFooterModel(
    val descriptor: String,
    val constructor: Method,
    val displayTypeEnumDescriptor: String,
    val classicField: Field,
)

context(context: BytecodePatchContext)
private fun resolveFooterModel(): ResolvedQuotaFooterModel {
    val models = resolvedNewXTimelineModels()
    val footerDescriptor = models.moduleFooterField.type
    val footerClass = context.mutableClassDefBy(footerDescriptor)
    val instanceFields = footerClass.fields.filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) }
    if (instanceFields.size != 3) {
        throw PatchException(
            "Expected three NewX module footer instance fields in $footerDescriptor: $instanceFields",
        )
    }
    val stringFields = instanceFields.filter { it.type == STRING_DESCRIPTOR }
    if (stringFields.size != 1) {
        throw PatchException("NewX module footer text field is ambiguous: $stringFields")
    }
    val enumFields = instanceFields.filter { field ->
        context.classDefByOrNull(field.type)?.superclass == ENUM_DESCRIPTOR
    }
    if (enumFields.size != 1) {
        throw PatchException("NewX module footer display-type field is ambiguous: $enumFields")
    }
    val displayTypeEnumDescriptor = enumFields[0].type

    val constructors = footerClass.methods.filter { method ->
        val parameters = method.parameterTypes.map(CharSequence::toString)
        method.name == "<init>" &&
            parameters.size == 3 &&
            parameters[0] == stringFields[0].type &&
            parameters[2] == displayTypeEnumDescriptor
    }
    if (constructors.size != 1) {
        throw PatchException(
            "Expected one NewX module footer constructor (String, details, display type) " +
                "in $footerDescriptor: $constructors",
        )
    }
    val constructor = constructors[0]
    if (constructor.parameterTypes.map(CharSequence::toString)[1] !in instanceFields.map { it.type }) {
        throw PatchException(
            "NewX module footer constructor display-details parameter does not match a field: $constructor",
        )
    }

    val enumClass = context.mutableClassDefBy(displayTypeEnumDescriptor)
    val classicFields = enumClass.fields.filter { field ->
        field.name == "Classic" && field.type == displayTypeEnumDescriptor
    }
    if (classicFields.size != 1) {
        throw PatchException(
            "Expected one Classic constant on the NewX module footer display type: " +
                "${enumClass.fields.joinToString()}",
        )
    }
    return ResolvedQuotaFooterModel(
        descriptor = footerDescriptor,
        constructor = constructor,
        displayTypeEnumDescriptor = displayTypeEnumDescriptor,
        classicField = classicFields[0],
    )
}

/**
 * The Carousel display-type singleton, resolved from the sealed hierarchy's serializer
 * registrations: the discriminator string is always registered beside the singleton field
 * read that names the case. The same registration can appear in more than one serializer
 * factory (the Companion's `serializer()` and a lazy factory share it), so the singleton
 * field is the tuple that must be unique, not the factory.
 */
context(context: BytecodePatchContext)
private fun resolveCarouselSingleton(): FieldReference {
    val displayTypeInterface = resolvedNewXTimelineModels().moduleDisplayTypeField.type
    val serializerMatches = ModuleDisplayTypeSerializerFingerprint.scopedMatchAll()
    val singletonFields = serializerMatches.flatMap { match ->
        val instructions = match.method.instructions.toList()
        instructions.withIndex().mapNotNull { (index, instruction) ->
            if ((instruction as? ReferenceInstruction)
                    ?.getReference<StringReference>()?.string != CAROUSEL_DISCRIMINATOR
            ) {
                return@mapNotNull null
            }
            // The registration shape is `sget singleton; new-array Annotation[]; const-string
            // name`: walk back over the annotation array to the singleton read.
            val sget = instructions
                .subList((index - 3).coerceAtLeast(0), index)
                .lastOrNull { candidate -> candidate.opcode == Opcode.SGET_OBJECT }
                ?: return@mapNotNull null
            sget.getReference<FieldReference>()
        }
    }.distinct()
    val field = requireExactlyOne(
        "NewX carousel module display-type singleton",
        singletonFields,
    )
    val owner = context.classDefByOrNull(field.definingClass)
        ?: throw PatchException("Carousel display type owner is missing: ${field.definingClass}")
    if (displayTypeInterface !in owner.interfaces || field.type != field.definingClass) {
        throw PatchException(
            "Carousel singleton candidate does not implement $displayTypeInterface: $field " +
                "(interfaces=${owner.interfaces})",
        )
    }
    return field
}

/**
 * The Compose `AndroidView` overload `AndroidView(factory, modifier, update, ...)`, resolved by
 * its unique signature inside the view interop package plus the full overload that must sit
 * beside it in the same owner.
 */
context(context: BytecodePatchContext)
private fun resolveAndroidView(): Method {
    val candidates = classDefFlatMap { classDef ->
        if (!classDef.type.startsWith(VIEWINTEROP_PACKAGE)) {
            return@classDefFlatMap emptyList<Method>()
        }
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(
                    FUNCTION1_DESCRIPTOR,
                    MODIFIER_DESCRIPTOR,
                    FUNCTION1_DESCRIPTOR,
                    COMPOSER_DESCRIPTOR,
                    "I",
                    "I",
                )
        }
    }
    if (candidates.size != 1) {
        throw PatchException(
            "Expected one Compose AndroidView composable in $VIEWINTEROP_PACKAGE: $candidates",
        )
    }
    val candidate = candidates[0]
    val owner = context.mutableClassDefBy(candidate.definingClass)
    val hasFullOverload = owner.methods.any { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(
                FUNCTION1_DESCRIPTOR,
                MODIFIER_DESCRIPTOR,
                FUNCTION1_DESCRIPTOR,
                FUNCTION1_DESCRIPTOR,
                FUNCTION1_DESCRIPTOR,
                COMPOSER_DESCRIPTOR,
                "I",
                "I",
            )
    }
    if (!hasFullOverload) {
        throw PatchException(
            "AndroidView candidate owner ${candidate.definingClass} has no full overload beside " +
                candidate.toString(),
        )
    }
    return candidate
}

/** The module-footer text composable: `footer(footer, modifier, onClick, composer, changed)`. */
context(context: BytecodePatchContext)
private fun resolveFooterTextComposable(footer: ResolvedQuotaFooterModel): Method {
    val candidates = classDefFlatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(
                    footer.descriptor,
                    MODIFIER_DESCRIPTOR,
                    FUNCTION1_DESCRIPTOR,
                    COMPOSER_DESCRIPTOR,
                    "I",
                ) &&
                method.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)
                        ?.getReference<FieldReference>()
                    reference != null &&
                        reference.definingClass == footer.descriptor &&
                        reference.type == footer.displayTypeEnumDescriptor
                } == true
        }
    }
    return requireExactlyOne(
        "NewX module footer text composable for ${footer.descriptor}",
        candidates,
    )
}

/**
 * The URT timeline module-footer row renderer: the `Function3` item content lambda in
 * `com.x.urt.ui` that calls the footer text composable.
 */
context(context: BytecodePatchContext)
private fun resolveFooterRenderer(footerTextComposable: Method): ClassDef {
    val targetParams = footerTextComposable.parameterTypes.map(CharSequence::toString)
    val candidates = classDefFlatMap { classDef ->
        if (!classDef.type.startsWith(URT_UI_PACKAGE) || FUNCTION3_DESCRIPTOR !in classDef.interfaces) {
            return@classDefFlatMap emptyList<ClassDef>()
        }
        classDef.methods.filter { method ->
            method.name == "invoke" &&
                method.parameterTypes.map(CharSequence::toString) == listOf(
                    OBJECT_DESCRIPTOR,
                    OBJECT_DESCRIPTOR,
                    OBJECT_DESCRIPTOR,
                ) &&
                method.returnType == OBJECT_DESCRIPTOR &&
                method.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)
                        ?.getReference<MethodReference>()
                    reference != null &&
                        reference.definingClass == footerTextComposable.definingClass &&
                        reference.name == footerTextComposable.name &&
                        reference.returnType == footerTextComposable.returnType &&
                        reference.parameterTypes.map(CharSequence::toString) == targetParams
                } == true
        }.map { classDef }
    }.distinct()
    return requireExactlyOne(
        "NewX URT timeline module-footer renderer",
        candidates,
        describe = { it.type },
    )
}

/**
 * The insertion index after the renderer lambda's skippability prelude
 * (`runtime skippability(IZ)Z; move-result; if-eqz`), so skipping recompositions still falls
 * through to the original skip path instead of running the hook.
 */
private fun resolvePreludeEnd(method: Method): Int {
    val instructions = method.implementation?.instructions?.toList().orEmpty()
    val sites = instructions.withIndex().filter { (index, instruction) ->
        index + 2 < instructions.size &&
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            (instruction as? ReferenceInstruction)
                ?.getReference<MethodReference>()
                ?.let { reference ->
                    // The prelude calls the composer implementation's skippability check; its
                    // owner is an obfuscated runtime type, so anchor on the package and shape.
                    reference.definingClass.startsWith(COMPOSE_RUNTIME_PACKAGE) &&
                        reference.parameterTypes.map(CharSequence::toString) == listOf("I", "Z") &&
                        reference.returnType == "Z"
                } == true &&
            instructions[index + 1].opcode == Opcode.MOVE_RESULT &&
            instructions[index + 2].opcode == Opcode.IF_EQZ
    }
    if (sites.size != 1) {
        throw PatchException(
            "Expected one skippability prelude in the NewX module-footer renderer, found " +
                "${sites.size}: ${sites.map { it.index }}",
        )
    }
    return sites[0].index + 3
}

/** Resolves the marker module synthesis and the AndroidView bridges in the extension. */
internal val newXDailyQuotaBridgesPatch =
    bytecodePatch(default = false) {
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(newXTimelineModelAdapterPatch)

        execute {
            val models = resolvedNewXTimelineModels()
            val footer = resolveFooterModel()
            val carousel = resolveCarouselSingleton()
            val androidView = resolveAndroidView()

            val factoryClass = mutableClassDefBy(QUOTA_MODULE_FACTORY_DESCRIPTOR)
            val newFooterBridge = factoryClass.requireBridge(
                "newFooter",
                "$STRING_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR",
                OBJECT_DESCRIPTOR,
            )
            newFooterBridge.patchBridge {
                // Registers v0..v3: the stub's parameter block stages the constructor invoke.
                move(1, 0, STRING_DESCRIPTOR) // footer text
                constInt(2, 0) // null display details
                sget(3, footer.classicField)
                newInstance(0, footer.descriptor)
                invokeDirect(footer.constructor, 0, 1, 2, 3)
                returnObject(0)
            }

            val newModuleBridge = factoryClass.requireBridge(
                "newModule",
                "$STRING_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR" +
                    "$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR" +
                    "$OBJECT_DESCRIPTOR",
                OBJECT_DESCRIPTOR,
            )
            newModuleBridge.patchBridge {
                // Registers v0..v8 stage the range invoke of the module constructor:
                // (innerContent, header, footer, displayType, sortIndex, entryId, clientEventInfo).
                move(7, 0, STRING_DESCRIPTOR) // entry ID
                move(3, 1, footer.descriptor) // footer model
                checkCast(3, footer.descriptor)
                constInt(2, 0) // null module header
                invokeStatic(
                    methodReference("Ljava/util/Collections;->emptyList()$LIST_DESCRIPTOR"),
                )
                moveResult(1, LIST_DESCRIPTOR) // empty inner content
                sget(4, carousel)
                constLong(5, 0L) // sort index (v5/v6)
                constInt(8, 0) // null client event info
                newInstance(0, models.moduleDescriptor)
                invokeDirect(
                    models.moduleConstructor,
                    0,
                    1,
                    2,
                    3,
                    4,
                    5,
                    6,
                    7,
                    8,
                )
                returnObject(0)
            }

            val rendererClass = mutableClassDefBy(QUOTA_RENDERER_DESCRIPTOR)
            val androidViewBridge = rendererClass.requireBridge(
                "invokeAndroidView",
                OBJECT_DESCRIPTOR.repeat(6),
                "V",
            )
            androidViewBridge.patchBridge {
                // Registers v0..v5 stage the AndroidView composable call. The stub parameters
                // are Object, so narrow them to the composable's declared types first.
                checkCast(0, FUNCTION1_DESCRIPTOR)
                checkCast(1, MODIFIER_DESCRIPTOR)
                checkCast(2, FUNCTION1_DESCRIPTOR)
                checkCast(3, COMPOSER_DESCRIPTOR)
                constInt(4, 0) // changed
                constInt(5, 0) // changed1
                invokeStatic(
                    androidView,
                    0, // factory
                    1, // modifier
                    2, // update
                    3, // composer
                    4,
                    5,
                )
                returnVoid()
            }
        }
    }

/**
 * Replaces the timeline end marker's footer row with the animated end view by hooking the
 * URT module-footer renderer.
 */
internal val newXDailyQuotaEndRendererHookPatch =
    bytecodePatch(default = false) {
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(newXTimelineModelAdapterPatch, newXDailyQuotaBridgesPatch)

        execute {
            val models = resolvedNewXTimelineModels()
            val footer = resolveFooterModel()
            val footerTextComposable = resolveFooterTextComposable(footer)
            val renderer = resolveFooterRenderer(footerTextComposable)
            val rendererClass = mutableClassDefBy(renderer.type)

            val invokeMethod = rendererClass.methods.singleOrNull { method ->
                method.name == "invoke" &&
                    method.parameterTypes.map(CharSequence::toString) == listOf(
                        OBJECT_DESCRIPTOR,
                        OBJECT_DESCRIPTOR,
                        OBJECT_DESCRIPTOR,
                    ) &&
                    method.returnType == OBJECT_DESCRIPTOR
            } ?: throw PatchException("Module-footer renderer invoke is missing in ${renderer.type}")

            val moduleFields = rendererClass.fields.filter { it.type == models.moduleDescriptor }
            if (moduleFields.size != 1) {
                throw PatchException(
                    "Expected one module field on the NewX module-footer renderer: $moduleFields",
                )
            }
            val moduleField = moduleFields[0]
            // The padding modifier the native footer row composes with. The renderer reads its
            // own modifier fields around the footer composable; the first read in body order is
            // the padded one the row lays out with.
            val instructions = invokeMethod.instructions.toList()
            val modifierFieldsByName = rendererClass.fields
                .filter { it.type == MODIFIER_DESCRIPTOR }
                .associateBy { it.name }
            if (modifierFieldsByName.isEmpty()) {
                throw PatchException("Module-footer renderer has no modifier field: ${renderer.type}")
            }
            val modifierReads = instructions.withIndex().mapNotNull { (index, instruction) ->
                if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
                val reference = (instruction as? ReferenceInstruction)
                    ?.getReference<FieldReference>()
                    ?: return@mapNotNull null
                if (reference.definingClass != renderer.type) return@mapNotNull null
                val field = modifierFieldsByName[reference.name] ?: return@mapNotNull null
                index to field
            }
            if (modifierReads.isEmpty()) {
                throw PatchException(
                    "NewX module-footer renderer reads none of its modifier fields: ${renderer.type}",
                )
            }
            // newx-resolver-lint: allow instruction-order raw-first because bytecode order is the contract
            var firstReadIndex = Int.MAX_VALUE
            var firstReadField: Field? = null
            for ((readIndex, field) in modifierReads) {
                if (readIndex < firstReadIndex) {
                    firstReadIndex = readIndex
                    firstReadField = field
                }
            }
            val modifierField = firstReadField ?: throw PatchException(
                "NewX module-footer renderer modifier reads are empty: ${renderer.type}",
            )

            val insertionIndex = resolvePreludeEnd(invokeMethod)
            val composerRegister = invokeMethod.p0Register + 2
            invokeMethod.insertHook(
                index = insertionIndex,
                // Every path that reaches the footer body must decide between the end view and
                // the native footer, so any label on the body start relocates onto the hook.
                relocateBranchTargets = true,
            ) {
                val moduleRegister = scratchRegister()
                val markerRegister = scratchRegister()
                iget(moduleRegister, invokeMethod.p0Register, moduleField)
                invokeStatic(
                    methodReference(
                        "$QUOTA_MARKERS_DESCRIPTOR->isTimelineEndModule($OBJECT_DESCRIPTOR)Z",
                    ),
                    moduleRegister,
                )
                moveResult(markerRegister, "Z")
                ifEqz(markerRegister, Target.Original)
                val modifierRegister = scratchRegister()
                iget(modifierRegister, invokeMethod.p0Register, modifierField)
                invokeStatic(
                    methodReference(
                        "$QUOTA_RENDERER_DESCRIPTOR->renderTimelineEnd(" +
                            "$OBJECT_DESCRIPTOR$OBJECT_DESCRIPTOR)V",
                    ),
                    composerRegister,
                    modifierRegister,
                )
                val unitRegister = scratchRegister()
                sget(unitRegister, fieldReference("Lkotlin/Unit;->a:Lkotlin/Unit;"))
                returnObject(unitRegister)
            }
        }
    }

private fun quotaOptions() = listOf(
    choice("unlimited", "piko_newx_quota_unlimited"),
    choice("20", "piko_newx_quota_20"),
    choice("50", "piko_newx_quota_50"),
    choice("100", "piko_newx_quota_100"),
    choice("150", "piko_newx_quota_150"),
    choice("200", "piko_newx_quota_200"),
    choice("300", "piko_newx_quota_300"),
    choice("500", "piko_newx_quota_500"),
)

/**
 * Adds two user-configurable daily post quotas. The For You and Following timelines end with
 * an animated checkmark, a "You've reached the end" message and X's own suggested-account
 * modules; search results end with the plain message. Counters persist across restarts and
 * reset at local midnight.
 */
@Suppress("unused")
val newXDailyQuotaPatch =
    bytecodePatch(
        name = "NewX: Daily post quotas",
        description =
            "Limits how many posts you can view each day. For You and Following end with an " +
                "animated end-of-feed message and suggested accounts; search ends with the plain " +
                "message. Counters reset at local midnight.",
    ) {
        unsupportedOnMount()
        compatibleWith(COMPATIBILITY_NEW_X)
        dependsOn(
            newXTimelineModelAdapterPatch,
            newXTimelineFilterPatch,
            newXDailyQuotaBridgesPatch,
            newXDailyQuotaEndRendererHookPatch,
        )

        newXSettings {
            category(Categories.TIMELINE) {
                group(Groups.DAILY_QUOTA) {
                    singleChoice(
                        id = "newx.timeline.daily_quota.posts",
                        strings = settingStrings("piko_newx_daily_quota_timeline"),
                        order = 100,
                        defaultValue = "unlimited",
                        options = quotaOptions(),
                    )

                    singleChoice(
                        id = "newx.timeline.daily_quota.search_posts",
                        strings = settingStrings("piko_newx_daily_quota_search"),
                        order = 200,
                        defaultValue = "unlimited",
                        options = quotaOptions(),
                    )

                    action(
                        id = "newx.timeline.daily_quota.reset",
                        strings = settingStrings("piko_newx_daily_quota_reset"),
                        order = 300,
                        handlerClassDescriptor = "$QUOTA_PACKAGE/QuotaResetAction;",
                    )
                }
            }
        }
    }
