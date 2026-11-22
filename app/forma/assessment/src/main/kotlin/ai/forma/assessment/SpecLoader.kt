package ai.forma.assessment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A specification was rejected at load. [rule] is the numbered validation rule
 * in SCHEMA.md that rejected it, or 0 for a structural problem found while
 * parsing.
 */
class SpecValidationException(val rule: Int, message: String) : IllegalArgumentException(
    if (rule == 0) "specification malformed: $message"
    else "specification rejected by validation rule $rule: $message"
)

/**
 * Parses and validates an exercise specification.
 *
 * Failing at load is the point. 4.6 requires that a specification cannot
 * be authored without a provenance entry, and that requirement is only real if
 * the loader rejects a file that omits one. The alternative to failing here is
 * discovering mid-set that a fault has been firing silently.
 */
object SpecLoader {

    /**
     * The cooldown after which a returning fault is treated as new, when a
     * specification does not state one. Two seconds: long enough that a
     * dropped frame or a moment at the threshold is not read as the user
     * correcting themselves, short enough that a genuine correction followed
     * by a genuine relapse is heard about.
     */
    const val DEFAULT_SAME_CUE_RESET_MS = 2000L

    private val json = Json { ignoreUnknownKeys = true }

    fun load(text: String): ExerciseSpec {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SpecValidationException(0, "not a JSON object: ${e.message}")
        }
        val spec = parse(root)
        validate(spec)
        return spec
    }

    // ---------------------------------------------------------------- parsing

    private fun JsonObject.obj(key: String): JsonObject =
        this[key]?.jsonObject ?: throw SpecValidationException(0, "missing object '$key'")

    private fun JsonObject.objOrNull(key: String): JsonObject? = this[key]?.jsonObject

    private fun JsonObject.arr(key: String): JsonArray =
        this[key]?.jsonArray ?: throw SpecValidationException(0, "missing array '$key'")

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw SpecValidationException(0, "missing string '$key'")

    private fun JsonObject.strOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.let { if (it.content == "null") null else it.content }

    private fun JsonObject.dbl(key: String): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: throw SpecValidationException(0, "missing number '$key'")

    private fun JsonObject.dblOrNull(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    /** Present and an integer, or present and null. Absent is an error. */
    private fun JsonObject.intOrNullRequired(key: String): Int? {
        val v = this[key] ?: throw SpecValidationException(0, "missing '$key'")
        return v.jsonPrimitive.intOrNull
    }

    private fun JsonObject.int(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: throw SpecValidationException(0, "missing integer '$key'")

    private fun JsonObject.lng(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: throw SpecValidationException(0, "missing integer '$key'")

    private fun JsonObject.lngOrNull(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: throw SpecValidationException(0, "missing boolean '$key'")

    private fun JsonObject.strings(key: String): List<String> =
        arr(key).map { it.jsonPrimitive.content }

    private fun JsonObject.pair(key: String): Pair<Double, Double> {
        val a = arr(key)
        if (a.size != 2) throw SpecValidationException(0, "'$key' must have exactly two values")
        return a[0].jsonPrimitive.double() to a[1].jsonPrimitive.double()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.double(): Double =
        doubleOrNull ?: throw SpecValidationException(0, "expected a number, found '$content'")

    private fun parseMeasure(o: JsonObject): Measure = Measure(
        fn = o.str("fn"),
        args = o.strings("args"),
        normaliseBy = o.strOrNull("normalise_by"),
        aggregate = o.strOrNull("aggregate"),
    )

    private fun parseThreshold(o: JsonObject): Threshold = when (val type = o.str("type")) {
        "absolute" -> Threshold.Absolute(o.dbl("value"))
        "relative" -> Threshold.Relative(
            calibrationId = o.str("calibration_id"),
            factor = o.dblOrNull("factor"),
            offset = o.dblOrNull("offset"),
        )
        else -> throw SpecValidationException(0, "unknown threshold type '$type'")
    }

    private fun parse(root: JsonObject): ExerciseSpec {
        val framing = root.obj("framing").let {
            Framing(
                view = it.str("view"),
                distanceM = it.pair("distance_m"),
                cameraHeightM = it.pair("camera_height_m"),
                minVisibility = it.dbl("min_visibility"),
                requiredVisible = it.strings("required_visible"),
                guidance = it.str("guidance"),
                guidanceAsset = it.strOrNull("guidance_asset"),
                reminders = it.strings("reminders"),
            )
        }

        val calibration = root.objOrNull("calibration")?.let { c ->
            CalibrationSpec(
                id = c.str("id"),
                safeUnsupervised = c.bool("safe_unsupervised"),
                rationale = c.str("rationale"),
                onImplausible = c.str("on_implausible"),
                measures = c.arr("measures").map { m ->
                    val o = m.jsonObject
                    CalibrationMeasure(
                        id = o.str("id"),
                        instruction = o.strOrNull("instruction"),
                        instructionAsset = o.strOrNull("instruction_asset"),
                        signal = parseMeasure(o.obj("signal")),
                        statistic = o.str("statistic"),
                        holdMs = o.lngOrNull("hold_ms"),
                        stabilityTolerance = o.dblOrNull("stability_tolerance"),
                        plausibleRange = o.pair("plausible_range"),
                        during = o.strOrNull("during"),
                    )
                },
            )
        }

        val driver = root.obj("rep_driver").let { d ->
            RepDriver(
                signal = parseMeasure(d.obj("signal")),
                reports = d.obj("reports").let { Reports(it.str("type"), it.strOrNull("phase")) },
                direction = d.str("direction"),
                smoothing = d.obj("smoothing").let { Smoothing(it.str("type"), it.int("window_frames")) },
                hysteresis = d.obj("hysteresis").let {
                    Hysteresis(
                        descentEnter = parseThreshold(it.obj("descent_enter")),
                        ascentExit = parseThreshold(it.obj("ascent_exit")),
                    )
                },
                minRepMs = d.lng("min_rep_ms"),
                maxRepMs = d.lng("max_rep_ms"),
            )
        }

        val faults = root.arr("faults").map { f ->
            val o = f.jsonObject
            val code = o.str("code")
            val prov = o.objOrNull("provenance")
                ?: throw SpecValidationException(5, "fault '$code' has no provenance object")
            FaultSpec(
                code = code,
                description = o.str("description"),
                phases = o.strings("phases"),
                measure = parseMeasure(o.obj("measure")),
                operator = o.str("operator"),
                threshold = parseThreshold(o.obj("threshold")),
                minFrames = o.int("min_frames"),
                evaluate = Evaluate.of(o.strOrNull("evaluate")),
                fix = (o.objOrNull("coaching") ?: throw SpecValidationException(
                    5, "fault '$code' has no coaching object"
                )).str("fix"),
                provenance = Provenance(
                    status = prov.strOrNull("status").orEmpty(),
                    taxonomySource = prov.strOrNull("taxonomy_source"),
                    plainSource = prov.strOrNull("plain_source"),
                    thresholdSource = prov.strOrNull("threshold_source"),
                    note = prov.strOrNull("note"),
                ),
            )
        }

        // "SQ_FORWARD_LEAN": { "en": { "asset": ..., "text": ... } }
        // One locale today. The nesting is here so a second does not change
        // the file format, only the lookup.
        val cues = root.obj("cues").mapValues { (code, v) ->
            val en = v.jsonObject["en"]?.jsonObject
                ?: throw SpecValidationException(0, "cue '$code' has no 'en' entry")
            CueSpec(asset = en.str("asset"), text = en.str("text"))
        }

        val trigger = root.obj("trigger").let {
            TriggerSpec(
                confidenceFloor = it.dbl("confidence_floor"),
                debounceMs = it.lng("debounce_ms"),
                minGapMs = it.lng("min_gap_ms"),
                // Absent or null means no ceiling. Both keys must still
                // appear in the file, so that leaving one out by accident is
                // not silently read as "unlimited".
                maxCuesPerSet = it.intOrNullRequired("max_cues_per_set"),
                maxConsecutiveSameCue = it.intOrNullRequired("max_consecutive_same_cue"),
                sameCueResetMs = it.lngOrNull("same_cue_reset_ms") ?: DEFAULT_SAME_CUE_RESET_MS,
                priority = it.strings("priority"),
                silenceIsValidOutput = it.bool("silence_is_valid_output"),
            )
        }

        return ExerciseSpec(
            id = root.str("id"),
            version = root.str("version"),
            displayName = root.str("display_name"),
            status = root.str("status"),
            landmarks = root.strings("landmarks"),
            framing = framing,
            calibration = calibration,
            repDriver = driver,
            phases = root.arr("phases").map {
                PhaseSpec(it.jsonObject.str("name"), it.jsonObject.str("entered_when"))
            },
            faults = faults,
            cues = cues,
            trigger = trigger,
            excludedFaults = ((root["excluded_faults"] as? JsonArray) ?: JsonArray(emptyList())).map {
                val o = it.jsonObject
                ExcludedFault(o.str("code"), o.str("reason"), o.strOrNull("taxonomy_source"))
            },
        )
    }

    // ------------------------------------------------------------- validation

    private fun validate(s: ExerciseSpec) {
        val declared = s.landmarks.toSet()

        // Rule 1. Every landmark named anywhere is in the landmarks block.
        fun checkMeasure(m: Measure, where: String) {
            val used = buildSet {
                addAll(m.args)
                if (m.isMeanOfSides) addAll(m.args.map(Landmarks::mirror))
                m.normaliseBy?.let { addAll(Normalisers.landmarksFor(it, mirror = false)) }
                if (m.isMeanOfSides) {
                    m.normaliseBy?.let { addAll(Normalisers.landmarksFor(it, mirror = true)) }
                }
            }
            for (name in used) {
                if (!Landmarks.isKnown(name)) {
                    throw SpecValidationException(1, "$where names '$name', which is not a BlazePose landmark")
                }
                if (name !in declared) {
                    throw SpecValidationException(
                        1, "$where uses '$name', which the landmarks block does not declare"
                    )
                }
            }
        }
        checkMeasure(s.repDriver.signal, "rep_driver.signal")
        s.faults.forEach { checkMeasure(it.measure, "fault '${it.code}'") }
        s.calibration?.measures?.forEach { checkMeasure(it.signal, "calibration measure '${it.id}'") }
        for (name in s.framing.requiredVisible) {
            if (name !in declared) {
                throw SpecValidationException(
                    1, "framing.required_visible names '$name', which the landmarks block does not declare"
                )
            }
        }

        // Rule 2. Every fault has a cue. A fault with no cue is a silent fault.
        for (f in s.faults) {
            if (f.code !in s.cues) {
                throw SpecValidationException(2, "fault '${f.code}' has no entry in cues, so it would fire silently")
            }
        }

        // Rule 3. Every phase a fault is scoped to is declared.
        val phaseNames = s.phaseNames.toSet()
        for (f in s.faults) {
            for (p in f.phases) {
                if (p !in phaseNames) {
                    throw SpecValidationException(3, "fault '${f.code}' is scoped to undeclared phase '$p'")
                }
            }
        }

        // Rule 4. Every relative threshold names a declared calibration measure.
        // Rule 9. A relative threshold carries exactly one of factor or offset.
        fun checkThreshold(t: Threshold, where: String) {
            if (t !is Threshold.Relative) return
            val cal = s.calibration
                ?: throw SpecValidationException(
                    4, "$where is relative to '${t.calibrationId}' but the file has no calibration block"
                )
            if (cal.measure(t.calibrationId) == null) {
                throw SpecValidationException(
                    4, "$where is relative to '${t.calibrationId}', which the calibration block does not declare"
                )
            }
            val n = listOfNotNull(t.factor, t.offset).size
            if (n != 1) {
                throw SpecValidationException(
                    9,
                    "$where carries ${if (n == 0) "neither factor nor offset" else "both factor and offset"}; " +
                        "a ratio scales and takes a factor, an angle does not and takes an offset"
                )
            }
        }
        s.faults.forEach { checkThreshold(it.threshold, "fault '${it.code}' threshold") }
        checkThreshold(s.repDriver.hysteresis.descentEnter, "rep_driver.hysteresis.descent_enter")
        checkThreshold(s.repDriver.hysteresis.ascentExit, "rep_driver.hysteresis.ascent_exit")

        // Rule 5. Every fault carries a provenance with a known status.
        for (f in s.faults) {
            if (f.provenance.status.isBlank()) {
                throw SpecValidationException(5, "fault '${f.code}' has an empty provenance status")
            }
            if (f.provenance.status !in Provenance.VALID_STATUSES) {
                throw SpecValidationException(
                    5,
                    "fault '${f.code}' has provenance status '${f.provenance.status}'; " +
                        "expected one of ${Provenance.VALID_STATUSES}"
                )
            }
            if (f.provenance.taxonomySourced && f.provenance.taxonomySource.isNullOrBlank()) {
                throw SpecValidationException(
                    5, "fault '${f.code}' claims status '${f.provenance.status}' with no taxonomy_source"
                )
            }
            if (f.provenance.status == "confirmed" && f.provenance.thresholdSource.isNullOrBlank()) {
                throw SpecValidationException(
                    5, "fault '${f.code}' claims status 'confirmed' with no threshold_source"
                )
            }
            // Everything the fault screen shows comes from here. A fault
            // added without it would reach the trainee as a blank card.
            if (f.provenance.plainSource.isNullOrBlank()) {
                throw SpecValidationException(
                    5, "fault '${f.code}' has no provenance.plain_source"
                )
            }
            if (f.fix.isBlank()) {
                throw SpecValidationException(
                    5, "fault '${f.code}' has a blank coaching.fix"
                )
            }
        }

        // Rule 6. The hysteresis bounds do not cross.
        checkHysteresis(s)

        // Rule 7. Priority covers the fault set exactly.
        val codes = s.faults.map { it.code }.toSet()
        for (code in s.trigger.priority) {
            if (code !in codes) {
                throw SpecValidationException(7, "trigger.priority names '$code', which is not a declared fault")
            }
        }
        for (code in codes) {
            if (code !in s.trigger.priority) {
                throw SpecValidationException(
                    7,
                    "fault '$code' is absent from trigger.priority, so its behaviour alongside another fault " +
                        "is undefined"
                )
            }
        }

        // Rule 8. Calibration is safe unsupervised, or the file does not load.
        s.calibration?.let {
            if (!it.safeUnsupervised) {
                throw SpecValidationException(
                    8, "calibration declares safe_unsupervised false; 4.5 permits no other kind"
                )
            }
        }

        // Rule 10. Every `during` names an earlier measure in the same list.
        s.calibration?.let { cal ->
            val seen = mutableSetOf<String>()
            for (m in cal.measures) {
                m.during?.let { d ->
                    if (d !in seen) {
                        throw SpecValidationException(
                            10, "calibration measure '${m.id}' is taken during '$d', which is not an earlier measure"
                        )
                    }
                }
                seen += m.id
            }
        }

        // Rule 11. An excluded fault appears nowhere else.
        for (x in s.excludedFaults) {
            if (x.code in codes) throw SpecValidationException(11, "'${x.code}' is both excluded and declared as a fault")
            if (x.code in s.cues) throw SpecValidationException(11, "'${x.code}' is excluded but still has a cue")
            if (x.code in s.trigger.priority) {
                throw SpecValidationException(11, "'${x.code}' is excluded but still appears in trigger.priority")
            }
        }
    }

    /**
     * Rule 6, which is direction-dependent.
     *
     * For an exercise whose driver falls during the repetition, the enter
     * bound sits below the exit bound: the user passes below enter to start
     * the repetition and must come back above exit to finish it. For a hold,
     * whose driver rises on entry, the two are the other way round. Bounds
     * that cross would make a repetition impossible to complete or impossible
     * to avoid.
     */
    private fun checkHysteresis(s: ExerciseSpec) {
        val enter = s.repDriver.hysteresis.descentEnter
        val exit = s.repDriver.hysteresis.ascentExit

        val pair = when {
            enter is Threshold.Absolute && exit is Threshold.Absolute -> enter.value to exit.value
            enter is Threshold.Relative && exit is Threshold.Relative &&
                enter.calibrationId == exit.calibrationId &&
                enter.offset != null && exit.offset != null -> enter.offset to exit.offset
            else -> null // not statically comparable; resolved per user at runtime
        } ?: return

        val (e, x) = pair
        if (s.repDriver.entryIsIncreasing) {
            if (e <= x) throw SpecValidationException(
                6, "entry bound $e does not sit above exit bound $x for an increasing driver"
            )
        } else {
            if (e >= x) throw SpecValidationException(
                6, "entry bound $e does not sit below exit bound $x for a decreasing driver"
            )
        }
    }
}
