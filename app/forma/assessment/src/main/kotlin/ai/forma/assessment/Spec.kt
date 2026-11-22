package ai.forma.assessment

/**
 * The in-memory form of one exercise specification file.
 *
 * The file format is defined in docs/specs/SCHEMA.md and these types follow it
 * block for block. Nothing here interprets a specification; that is the job of
 * [Measurements], [Segmenter], [FaultEvaluator] and [TriggerPolicy].
 */

data class Measure(
    val fn: String,
    val args: List<String>,
    val normaliseBy: String? = null,
    val aggregate: String? = null,
) {
    val isMeanOfSides get() = aggregate == "mean_of_sides"
}

sealed interface Threshold {
    data class Absolute(val value: Double) : Threshold

    /**
     * Resolved against the value this user produced during calibration.
     *
     * Carries exactly one of [factor] or [offset], never both: a ratio or a
     * distance scales and takes a factor, an angle does not and takes an
     * offset. Validation rule 9 enforces the exclusivity, because a threshold
     * carrying both has no defined meaning.
     */
    data class Relative(
        val calibrationId: String,
        val factor: Double?,
        val offset: Double?,
    ) : Threshold
}

/**
 * Why this project believes a fault is a fault, and why it believes the
 * number at which it fires.
 *
 * The two fields are separate because they are different claims with
 * different evidence. Conflating them is how an invented threshold acquires a
 * citation that does not support it.
 */
data class Provenance(
    val status: String,
    val taxonomySource: String?,
    /**
     * The same attribution in a sentence a trainee can read.
     *
     * [taxonomySource] is a citation: authors, journal, volume, the quoted
     * criterion. It belongs in the report and it is unreadable on a phone,
     * which the fault screen proved by printing the first 120 characters of
     * it and stopping mid-clause. This field is the claim the citation
     * supports, said plainly, and the citation stays beside it unchanged.
     */
    val plainSource: String?,
    val thresholdSource: String?,
    val note: String?,
) {
    val taxonomySourced get() = status == "taxonomy_confirmed" || status == "confirmed"

    /** Chapter 11.3: only a fully confirmed fault contributes to a headline result. */
    val reportableAsHeadline get() = status == "confirmed"

    companion object {
        val VALID_STATUSES = setOf("pending", "taxonomy_confirmed", "confirmed")
    }
}

/**
 * When a fault is decided.
 *
 * [CONTINUOUS] asks whether the constraint is being violated right now, and
 * raises once it has been for [FaultSpec.minFrames] consecutive frames. That
 * is right for a posture held wrongly: hips sagging in a plank is a fact
 * about the present moment.
 *
 * [PER_REP] asks a different question, once, when the repetition closes: did
 * the extreme this measurement reached during the repetition violate the
 * constraint. That is right for range of motion, where the fault is a
 * property of the whole repetition rather than of any frame in it.
 *
 * The distinction was forced by a defect rather than designed. Squat depth
 * was continuous and scoped to the bottom phase, so it needed several
 * consecutive frames near the turning point, and whether those frames
 * existed depended on how fast the user squatted. Two repetitions to the
 * same depth, one smooth and one with a slight pause, got different answers:
 * on set-20261105-000329 the first six repetitions raised and the last four
 * did not. A range-of-motion fault must depend on the range and on nothing
 * else.
 */
enum class Evaluate {
    CONTINUOUS,
    PER_REP;

    companion object {
        fun of(name: String?): Evaluate = when (name) {
            null, "continuous" -> CONTINUOUS
            "per_rep" -> PER_REP
            else -> throw IllegalArgumentException("unknown evaluate '$name'")
        }
    }
}

data class FaultSpec(
    val code: String,
    val description: String,
    val phases: List<String>,
    val measure: Measure,
    val operator: String,
    val threshold: Threshold,
    /**
     * For a continuous fault, consecutive violating frames before it is
     * raised. For a per-repetition fault, the least number of frames that
     * must have been measurable during the repetition for its extreme to be
     * trusted, so that one noisy frame cannot decide a repetition.
     */
    val minFrames: Int,
    /**
     * What to do differently next time, addressed to the trainee.
     *
     * [description] says what the system saw; this says what to do about it.
     * The two are different writing jobs and conflating them produces text
     * that does neither: a description that nags, or advice that cannot be
     * checked against the measurement.
     */
    val fix: String,
    val provenance: Provenance,
    val evaluate: Evaluate = Evaluate.CONTINUOUS,
)

data class PhaseSpec(val name: String, val enteredWhen: String)

/**
 * What the user is shown and what the session record stores.
 *
 * Most exercises count repetitions. A hold does not: a plank's result is
 * seconds, and reporting "1 repetition" for a ninety-second hold would be
 * true and useless. This field is where the format bent to fit the second
 * kind of movement, and 4.6.1 of the report counts that cost.
 */
data class Reports(val type: String, val phase: String?) {
    val isDuration get() = type == "duration"
}

data class Smoothing(val type: String, val windowFrames: Int)

data class Hysteresis(val descentEnter: Threshold, val ascentExit: Threshold)

data class RepDriver(
    val signal: Measure,
    val reports: Reports,
    val direction: String,
    val smoothing: Smoothing,
    val hysteresis: Hysteresis,
    val minRepMs: Long,
    val maxRepMs: Long,
) {
    val entryIsIncreasing get() = direction == "increasing_is_entry"
}

data class CueSpec(val asset: String, val text: String)

data class TriggerSpec(
    val confidenceFloor: Double,
    val debounceMs: Long,
    val minGapMs: Long,
    /**
     * The budget for a whole set, or null for no ceiling.
     *
     * Null is declared rather than implied by a large number, because a
     * movement where the system should keep correcting until the trainee
     * fixes the fault is a different intent from one with a generous budget,
     * and the file should say which it means.
     */
    val maxCuesPerSet: Int?,
    /** After how many in a row repeating becomes nagging, or null for no limit. */
    val maxConsecutiveSameCue: Int?,
    /**
     * How long a fault must be absent before a reappearance counts as a new
     * episode rather than a continuation of the last one.
     *
     * [maxConsecutiveSameCue] exists to stop the system repeating itself
     * about one thing the user is already failing to fix. It is not meant to
     * silence the same correction for the rest of the session once they have
     * fixed it and let it slip again. Without a reset the two are the same
     * thing, and a hold suffers worst: one fault is live for its whole
     * duration, so nothing ever resets the counter and the exercise goes
     * permanently quiet after two cues.
     *
     * The gap has to be long enough that a flicker in the measurement does
     * not read as a correction, which is what makes this a cooldown rather
     * than an edge test.
     */
    val sameCueResetMs: Long,
    val priority: List<String>,
    val silenceIsValidOutput: Boolean,
)

data class CalibrationMeasure(
    val id: String,
    val instruction: String?,
    /** The spoken form of [instruction]. The user cannot read the screen. */
    val instructionAsset: String?,
    val signal: Measure,
    val statistic: String,
    val holdMs: Long?,
    val stabilityTolerance: Double?,
    val plausibleRange: Pair<Double, Double>,
    val during: String?,
)

data class CalibrationSpec(
    val id: String,
    val safeUnsupervised: Boolean,
    val rationale: String,
    val measures: List<CalibrationMeasure>,
    val onImplausible: String,
) {
    fun measure(id: String) = measures.firstOrNull { it.id == id }
}

data class Framing(
    val view: String,
    val distanceM: Pair<Double, Double>,
    val cameraHeightM: Pair<Double, Double>,
    val minVisibility: Double,
    val requiredVisible: List<String>,
    val guidance: String,
    /** The spoken form of [guidance]. The user cannot read the screen. */
    val guidanceAsset: String?,
    /**
     * What stays true for the whole set, phrased as a request.
     *
     * [guidance] is an instruction for getting into position once. These are
     * the conditions the measurement depends on throughout, and the screen
     * shows them while the trainee sets up rather than grading them.
     */
    val reminders: List<String>,
)

/**
 * A fault considered for this exercise and deliberately left out.
 *
 * Recorded in the specification rather than only in a decision record,
 * because a fault silently absent and a fault deliberately excluded look
 * identical from outside unless the file says which it is.
 */
data class ExcludedFault(
    val code: String,
    val reason: String,
    val taxonomySource: String?,
)

data class ExerciseSpec(
    val id: String,
    val version: String,
    val displayName: String,
    val status: String,
    val landmarks: List<String>,
    val framing: Framing,
    val calibration: CalibrationSpec?,
    val repDriver: RepDriver,
    val phases: List<PhaseSpec>,
    val faults: List<FaultSpec>,
    val cues: Map<String, CueSpec>,
    val trigger: TriggerSpec,
    val excludedFaults: List<ExcludedFault>,
) {
    fun fault(code: String) = faults.first { it.code == code }
    val phaseNames get() = phases.map { it.name }

    /** Every calibration id any threshold in this file resolves against. */
    val requiredCalibrationIds: Set<String>
        get() {
            val ids = mutableSetOf<String>()
            fun collect(t: Threshold) {
                if (t is Threshold.Relative) ids += t.calibrationId
            }
            faults.forEach { collect(it.threshold) }
            collect(repDriver.hysteresis.descentEnter)
            collect(repDriver.hysteresis.ascentExit)
            return ids
        }
}

/** Resolves a threshold to a number for this user. */
object Thresholds {
    fun resolve(t: Threshold, calibration: Map<String, Double>): Double = when (t) {
        is Threshold.Absolute -> t.value
        is Threshold.Relative -> {
            val base = calibration[t.calibrationId]
                ?: throw IllegalStateException(
                    "no calibration value for '${t.calibrationId}'; this user has not calibrated"
                )
            when {
                t.offset != null -> base + t.offset
                t.factor != null -> base * t.factor
                else -> throw IllegalStateException("relative threshold has neither factor nor offset")
            }
        }
    }
}
