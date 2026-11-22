package ai.forma.assessment

/**
 * A raised fault with everything needed to explain it after the set.
 *
 * [RaisedFault] is allocated for every raised fault on every frame and stays
 * lean for that reason. This is built once per fault per segment, at the
 * moment the fault is logged, and carries what the device would otherwise
 * forget: which repetition it belonged to, the phase it was raised in, and
 * the per-side decomposition of the number it fired on.
 *
 * It exists because the 2026-09-18 squat set reported six cues and no way to
 * tell a correct one from an incorrect one. A record that cannot explain its
 * own result is not evidence.
 */
data class FaultObservation(
    val code: String,
    /** The combined value that violated the constraint. */
    val value: Double,
    val threshold: Double,
    val sinceMs: Long,
    /**
     * The repetition being performed, counting from 1. Always 1 for a hold,
     * which counts no repetitions.
     */
    val rep: Int,
    val phase: String,
    val breakdown: MeasureBreakdown,
)

/** Everything the assessment layer has to say about one frame. */
/**
 * What one completed repetition did, for every fault the exercise declares.
 *
 * [extremes] is how far each fault's measurement went in the direction that
 * would violate it, over the phases that fault is scoped to. A code absent
 * from the map was never measurable during this repetition, which is a third
 * outcome distinct from passing and from failing.
 */
data class RepOutcome(
    val rep: Int,
    val extremes: Map<String, Double>,
    val raised: Set<String>,
)

data class SessionUpdate(
    val phase: String,
    val repCount: Int,
    val holdMs: Long,
    val repCompleted: Boolean,
    /** Every fault currently violating its constraint, spoken or not. */
    val raised: List<RaisedFault>,
    /** The one cue the user hears, or null. Null is the common case. */
    val cue: SpokenCue?,
    val driver: Double?,
)

/**
 * One exercise, one user, one set.
 *
 * This is the whole live path of the assessment layer and it contains no
 * Android type, no I/O and no allocation of anything the garbage collector
 * will have to chase. Chapter 6 calls that the region split: work with a hard
 * deadline happens here, and everything with a file handle or a network
 * socket happens on the other side of the boundary.
 *
 * The practical consequence is Chapter 11 tier T1. A recorded landmark
 * sequence is a complete, replayable fixture, so the rules can be exercised
 * without a camera, a phone or a person.
 */
class AssessmentSession(
    val spec: ExerciseSpec,
    private val calibration: Map<String, Double> = emptyMap(),
) {
    init {
        val missing = spec.requiredCalibrationIds - calibration.keys
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException(
                "exercise '${spec.id}' needs calibration values for $missing before a set can start"
            )
        }
    }

    private val segmenter = Segmenter(spec, calibration)
    private val faults = FaultEvaluator(spec, calibration)
    private val trigger = TriggerPolicy(spec)

    private val driverSignal = spec.repDriver.signal
    private val minVisibility = spec.framing.minVisibility

    /** Faults raised at any point in this set, in order, whether or not spoken. */
    val faultLog = mutableListOf<FaultObservation>()
    private val loggedThisSegment = mutableSetOf<String>()

    /**
     * One entry per completed repetition, carrying how close each fault came
     * to firing whether or not it did.
     *
     * A raise tells you the system found a fault. It cannot tell you whether
     * a repetition with no raise was performed correctly or was missed, and
     * those are the two halves of a recall figure. On set-20261105-000329,
     * ten squats instructed to be shallow raised the depth fault on the
     * first six and nothing on the last four, and the record could not say
     * whether the user had started squatting deeper after being cued twice
     * or whether the detector had stopped seeing it. The extreme the measure
     * actually reached in each repetition answers that, and it is the number
     * a recall figure has to be computed against.
     */
    val repLog = mutableListOf<RepOutcome>()
    private val extremesThisSegment = mutableMapOf<String, Double>()
    private val measuredThisSegment = mutableMapOf<String, Int>()
    private val samplesThisSegment = mutableMapOf<String, MutableList<Double>>()
    private val raisedThisSegment = mutableSetOf<String>()

    /** The measurement behind each fault code, for explaining a raise. */
    private val measureByCode = spec.faults.associate { it.code to it.measure }

    fun onFrame(frame: Frame): SessionUpdate {
        val driverValue = Measurements.evaluate(driverSignal, frame, minVisibility)
        val segment = segmenter.onFrame(driverValue, frame.timestampMs)

        val raised = faults.onFrame(frame, segment.phase).toMutableList()

        // How close each fault came this repetition, raised or not. Only the
        // phases a fault is scoped to count: a depth measurement taken while
        // the user is standing is not a shallow repetition. This runs before
        // the per-repetition decision below, which reads what it writes.
        for (f in spec.faults) {
            val sampleNow = when (f.evaluate) {
                // Scoped to its phases, and the extreme kept is the worst
                // reading seen there: a posture held wrongly is a fact about
                // frames.
                Evaluate.CONTINUOUS -> segment.phase in f.phases
                // Every frame of the repetition, because the question is how
                // close the repetition came to the standard at its best.
                Evaluate.PER_REP -> segment.engaged
            }
            if (!sampleNow) continue
            val v = Measurements.evaluate(f.measure, frame, minVisibility) ?: continue
            if (f.evaluate == Evaluate.PER_REP) {
                samplesThisSegment.getOrPut(f.code) { mutableListOf() } += v
            } else {
                val worst = extremesThisSegment[f.code]
                val moreViolating = when (f.operator) {
                    "lt", "lte" -> worst == null || v < worst
                    else -> worst == null || v > worst
                }
                if (moreViolating) extremesThisSegment[f.code] = v
            }
            measuredThisSegment[f.code] = (measuredThisSegment[f.code] ?: 0) + 1
        }

        // A per-repetition fault is decided here, once, on the frame that
        // closes the repetition, from the extreme the measurement reached
        // rather than from any run of frames. Two repetitions to the same
        // depth therefore get the same answer whatever the tempo.
        if (segment.repCompleted) {
            for (f in spec.faults) {
                if (f.evaluate != Evaluate.PER_REP) continue
                val best = bestOf(f) ?: continue
                extremesThisSegment[f.code] = best
                if (!faults.violatesThreshold(f.code, best)) continue
                raised += RaisedFault(
                    code = f.code,
                    value = best,
                    threshold = faults.resolved.getValue(f.code),
                    sinceMs = frame.timestampMs,
                )
            }
        }

        for (f in raised) {
            // One entry per fault per segment: a fault held for forty frames
            // is one occurrence, not forty. The breakdown is therefore
            // computed at most once per fault per repetition, which is why it
            // can afford to allocate.
            if (loggedThisSegment.add(f.code)) {
                faultLog += FaultObservation(
                    code = f.code,
                    value = f.value,
                    threshold = f.threshold,
                    sinceMs = f.sinceMs,
                    rep = if (segment.repCompleted &&
                        spec.fault(f.code).evaluate == Evaluate.PER_REP
                    ) segment.repCount else segment.repCount + 1,
                    phase = segment.phase,
                    breakdown = Measurements.explain(
                        measureByCode.getValue(f.code), frame, minVisibility,
                    ),
                )
            }
        }

        raisedThisSegment += raised.map { it.code }

        val cue = trigger.select(raised, frame.timestampMs, confidence = framingConfidence(frame))

        // Why a raised fault did not become a cue, counted over the set.
        // A set that detects faults and says nothing looks identical to a set
        // that detected nothing, and the difference is the first thing worth
        // knowing when one goes quiet.
        for ((_, why) in trigger.suppressed) {
            suppressedCounts[why] = (suppressedCounts[why] ?: 0) + 1
        }

        // Every continuous fault's measurement, recorded whether or not it
        // violated anything. Without this a quiet set is unexplainable.
        for (f in spec.faults) {
            if (f.evaluate == Evaluate.PER_REP) continue
            if (segment.phase !in f.phases) continue
            val v = Measurements.evaluate(f.measure, frame, minVisibility)
            if (v == null) {
                unmeasuredFrames[f.code] = (unmeasuredFrames[f.code] ?: 0) + 1
                continue
            }
            measuredFrames[f.code] = (measuredFrames[f.code] ?: 0) + 1
            val r = measuredRange[f.code]
            if (r == null) measuredRange[f.code] = doubleArrayOf(v, v)
            else { r[0] = minOf(r[0], v); r[1] = maxOf(r[1], v) }
        }

        if (segment.repCompleted) {
            repLog += RepOutcome(
                rep = segment.repCount,
                extremes = extremesThisSegment.toMap(),
                raised = raisedThisSegment.toSet(),
            )
            extremesThisSegment.clear()
            measuredThisSegment.clear()
            samplesThisSegment.clear()
            raisedThisSegment.clear()
            loggedThisSegment.clear()
        }

        return SessionUpdate(
            phase = segment.phase,
            repCount = segment.repCount,
            holdMs = segment.holdMs,
            repCompleted = segment.repCompleted,
            raised = raised,
            cue = cue,
            driver = segment.driver,
        )
    }

    /**
     * How close this repetition came to satisfying [f], at its best.
     *
     * Not the single best frame. The `min_frames`-th best, so one frame in
     * which a landmark jumped cannot pass a repetition on its own, and the
     * figure still describes a position the body actually held. Null when the
     * measurement was unavailable that often, which is a third outcome: the
     * repetition was neither passed nor failed, it was not seen.
     *
     * Deliberately independent of the repetition driver. The driver is the
     * knee angle and needs the ankle, and the ankle is what the engine loses
     * at the bottom of a squat: replaying data/replay/20261105_010719.mp4,
     * the left ankle scored 0.17 to 0.44 through every bottom, under the
     * 0.45 floor, leaving the driver unmeasurable on 183 of 513 frames.
     * Sampling a fault at the driver's extreme therefore sampled it partway
     * down. The deepest repetition in that clip reached +0.0732 and was
     * recorded as -0.2154.
     */
    private fun bestOf(f: FaultSpec): Double? {
        val xs = samplesThisSegment[f.code] ?: return null
        if (xs.size < f.minFrames) return null
        val passingIsLarger = f.operator == "lt" || f.operator == "lte"
        val sorted = if (passingIsLarger) xs.sortedDescending() else xs.sorted()
        return sorted[f.minFrames - 1]
    }

    /**
     * How much of what this exercise needs to see is actually visible.
     *
     * The mean visibility of the landmarks the framing block declares
     * required. The trigger policy compares it against `confidence_floor`,
     * which is what stops the system speaking confidently about a body it can
     * barely see.
     */
    /**
     * The visibility of a required landmark, taking the better of the two
     * sides.
     *
     * Every measurement in these specifications aggregates `mean_of_sides`,
     * so a landmark that is dim on the named side and clear on the mirrored
     * one still yields a measurement. The gate has to test what the rules
     * will actually use, or it refuses sets the system could assess.
     *
     * This is not hypothetical. In a plank the near shoulder is partly
     * occluded by the head and the arm: across the recorded plank sets the
     * left shoulder scored a median of 0.42 and fell under the floor on most
     * frames, while the hip and ankle stayed well clear. Testing the named
     * side alone made the gate flicker and the set hard to start, on the one
     * exercise where the body is closest to the camera.
     */
    private fun bestSideVisibility(frame: Frame, name: String): Double {
        val here = frame.point(name)?.visibility ?: 0.0
        val there = frame.point(Landmarks.mirror(name))?.visibility ?: 0.0
        return maxOf(here, there)
    }

    fun framingConfidence(frame: Frame): Double {
        val required = spec.framing.requiredVisible
        if (required.isEmpty()) return 1.0
        return required.sumOf { bestSideVisibility(frame, it) } / required.size
    }

    /** True when the user is positioned well enough for the set to begin. */
    fun framingSatisfied(frame: Frame): Boolean =
        spec.framing.requiredVisible.all { bestSideVisibility(frame, it) >= minVisibility }

    /** Reason to the number of frames a cue was withheld for it. */
    val suppressedCounts = mutableMapOf<String, Int>()

    /**
     * How far each continuous fault's measurement travelled over the set, and
     * how often it could be measured at all.
     *
     * A hold reports no repetitions, so [repLog] is empty for it and a set
     * that raises nothing leaves no trace of why. These three numbers
     * separate the three reasons: the landmark was never visible, the
     * measurement was available and never came near the threshold, or it came
     * close and the confirmation window was never filled.
     */
    val measuredRange = mutableMapOf<String, DoubleArray>()
    val measuredFrames = mutableMapOf<String, Int>()
    val unmeasuredFrames = mutableMapOf<String, Int>()

    /** The resolved threshold for a fault code, for the session record. */
    fun thresholdFor(code: String): Double = faults.resolved[code] ?: 0.0

    fun startNewSet() {
        segmenter.resetSet()
        faults.resetSet()
        trigger.resetSet()
        faultLog.clear()
        loggedThisSegment.clear()
        repLog.clear()
        suppressedCounts.clear()
        measuredRange.clear()
        measuredFrames.clear()
        unmeasuredFrames.clear()
        extremesThisSegment.clear()
        measuredThisSegment.clear()
        samplesThisSegment.clear()
        raisedThisSegment.clear()
    }

    val cuesSpokenThisSet get() = trigger.cuesSpokenThisSet
}
