package ai.forma.assessment

/**
 * A fault that has been violating its constraint for at least `min_frames`
 * consecutive frames.
 *
 * Raised is not the same as spoken. The trigger policy decides what the user
 * hears; everything raised is recorded regardless, which is what lets a
 * session summary report faults that were never voiced.
 */
data class RaisedFault(
    val code: String,
    /** The measured value that violated the constraint. */
    val value: Double,
    /** The threshold it was compared against, resolved for this user. */
    val threshold: Double,
    /** When the fault first reached min_frames, for the trigger policy's debounce. */
    val sinceMs: Long,
)

/**
 * Evaluates every declared fault constraint against one frame.
 *
 * A violated constraint *is* a discrete fault code, which is what 4.4
 * requires. No conversion step exists because the method produces exactly the
 * shape the feedback path consumes.
 */
class FaultEvaluator(
    private val spec: ExerciseSpec,
    calibration: Map<String, Double>,
) {
    private val minVisibility = spec.framing.minVisibility
    /** Each fault's threshold, resolved for this user. Read by the session
     *  for the per-repetition faults it decides itself. */
    val resolved: Map<String, Double> =
        spec.faults.associate { it.code to Thresholds.resolve(it.threshold, calibration) }

    /** Whether [value] breaks [code]'s constraint. Shared with the session. */
    fun violatesThreshold(code: String, value: Double): Boolean =
        violates(spec.fault(code).operator, value, resolved.getValue(code))

    private val consecutive = mutableMapOf<String, Int>()
    private val raisedSince = mutableMapOf<String, Long>()

    /** Every fault that reached min_frames this frame and has not since cleared. */
    fun onFrame(frame: Frame, phase: String): List<RaisedFault> {
        val raised = mutableListOf<RaisedFault>()

        for (fault in spec.faults) {
            // A per-repetition fault is not a question about this frame.
            // AssessmentSession decides it once, at the repetition boundary,
            // from the extreme the measurement reached.
            if (fault.evaluate == Evaluate.PER_REP) continue

            if (phase !in fault.phases) {
                clear(fault.code)
                continue
            }

            val value = Measurements.evaluate(fault.measure, frame, minVisibility)
            if (value == null) {
                // Unavailable is not "passing". The counter is cleared so an
                // occluded stretch cannot accumulate toward a fault, but no
                // fault is raised from a frame that was never measured.
                clear(fault.code)
                continue
            }

            val threshold = resolved.getValue(fault.code)
            if (!violates(fault.operator, value, threshold)) {
                clear(fault.code)
                continue
            }

            val n = (consecutive[fault.code] ?: 0) + 1
            consecutive[fault.code] = n
            if (n >= fault.minFrames) {
                val since = raisedSince.getOrPut(fault.code) { frame.timestampMs }
                raised += RaisedFault(fault.code, value, threshold, since)
            }
        }

        return raised
    }

    private fun clear(code: String) {
        consecutive.remove(code)
        raisedSince.remove(code)
    }

    private fun violates(operator: String, value: Double, threshold: Double): Boolean = when (operator) {
        "gt" -> value > threshold
        "gte" -> value >= threshold
        "lt" -> value < threshold
        "lte" -> value <= threshold
        else -> throw IllegalArgumentException("unknown operator '$operator'")
    }

    fun resetSet() {
        consecutive.clear()
        raisedSince.clear()
    }
}
