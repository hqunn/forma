package ai.forma.assessment

/**
 * How the declared phase names map onto the states the segmenter actually has.
 *
 * **This is a known gap between the format and the evaluator, and it is
 * recorded rather than hidden.** SCHEMA.md gives each phase an `entered_when`
 * string such as *"driver at local minimum, within 10 degrees of it"*. Those
 * strings are prose, not an expression language, and nothing here parses them.
 * The evaluator instead derives phases from the driver state machine and binds
 * them to the declared names by role.
 *
 * The consequence is a real constraint on what a specification may declare: a
 * counting exercise must declare exactly four phases in the order idle,
 * descent, bottom, ascent, and a hold must name its engaged phase in
 * `reports.phase`. Both MVP shapes satisfy this. An exercise that does not
 * fails at construction here with a message saying so, rather than silently
 * evaluating the wrong phase.
 *
 * Closing the gap means either an expression parser for `entered_when` or an
 * explicit `role` field per phase. The second is far cheaper and is the
 * recommendation, and 7.4.1 of the report records why.
 */
private class PhaseRoles(spec: ExerciseSpec) {
    val idle: String
    val descent: String
    val bottom: String
    val ascent: String

    init {
        val names = spec.phaseNames
        if (spec.repDriver.reports.isDuration) {
            val engaged = spec.repDriver.reports.phase
                ?: throw IllegalArgumentException(
                    "exercise '${spec.id}' reports a duration but reports.phase does not name the held phase"
                )
            if (engaged !in names) {
                throw IllegalArgumentException(
                    "exercise '${spec.id}' reports duration over phase '$engaged', which is not declared"
                )
            }
            val other = names.firstOrNull { it != engaged }
                ?: throw IllegalArgumentException("exercise '${spec.id}' declares only one phase")
            idle = other
            // A hold has no descent, bottom or ascent. All three collapse onto
            // the held phase so that a fault scoped to it is evaluated
            // throughout.
            descent = engaged
            bottom = engaged
            ascent = engaged
        } else {
            if (names.size != 4) {
                throw IllegalArgumentException(
                    "exercise '${spec.id}' counts repetitions, so it must declare exactly four phases " +
                        "in the order idle, descent, bottom, ascent; found ${names.size}: $names"
                )
            }
            idle = names[0]
            descent = names[1]
            bottom = names[2]
            ascent = names[3]
        }
    }
}

data class SegmentUpdate(
    val phase: String,
    /** True on the single frame at which a repetition was counted. */
    val repCompleted: Boolean,
    val repCount: Int,
    /** Accumulated time in the held phase. Always 0 for a counting exercise. */
    val holdMs: Long,
    /** The smoothed driver value, or null if it could not be measured this frame. */
    val driver: Double?,
    /** True while the user is inside a repetition or a hold. */
    val engaged: Boolean,
)

/**
 * Segments the driver signal into repetitions or a hold.
 *
 * One scalar signal, two hysteresis bounds. The same block does both jobs:
 * what differs between a squat and a plank is only whether the segments are
 * counted or timed.
 */
class Segmenter(
    private val spec: ExerciseSpec,
    calibration: Map<String, Double>,
) {
    private val driver = spec.repDriver
    private val roles = PhaseRoles(spec)
    private val enterBound = Thresholds.resolve(driver.hysteresis.descentEnter, calibration)
    private val exitBound = Thresholds.resolve(driver.hysteresis.ascentExit, calibration)

    private val window = ArrayDeque<Double>()

    private var engaged = false
    private var segmentStartMs = 0L

    /**
     * Time accrued in the hold currently under way, and the timestamp of the
     * last frame that contributed to it.
     *
     * A hold is counted frame by frame rather than as the span between entry
     * and exit, because the two are not the same thing. If the trainee walks
     * out of shot the driver stops being measurable, but the wall clock does
     * not stop, and a span measured from entry would keep crediting them for
     * a plank nobody is holding. [lastHeldTickMs] is cleared on any frame the
     * driver cannot be read, so the next readable frame resumes the clock
     * instead of back-filling the gap.
     */
    private var segmentMs = 0L
    private var lastHeldTickMs: Long? = null
    private var extremeInSegment = 0.0
    private var previous: Double? = null

    var repCount: Int = 0
        private set

    var holdMs: Long = 0L
        private set

    /**
     * The band, in driver units, within which the user counts as being at the
     * turning point. Taken from the phases block's prose and hardcoded here;
     * see [PhaseRoles] for why the prose is not parsed.
     */
    private val bottomBand = 10.0

    /**
     * How much the driver must change between frames to count as still moving
     * rather than as noise around a turning point, in driver units per frame.
     *
     * **The value has to sit above the measurement's own noise.** Landmark
     * jitter on a subject standing perfectly still measured 0.00111 in
     * normalised units on run `sweep-20261104-204742`, which works through
     * the knee angle to roughly 0.3 degrees a frame. A stillness test set
     * below that floor can never be satisfied by a moving human: almost
     * every frame resolves to descent or ascent, and the bottom phase exists
     * only at an exact turning point that never occurs. A fault scoped to
     * `bottom` and needing three consecutive frames there then raises
     * nothing, and it does so silently, because every other indicator is
     * healthy.
     *
     * 1.0 is above the noise floor and well below the two to three degrees a
     * frame a squat descends at, so the band it opens is the real pause at
     * the bottom of a repetition rather than an instant. [SquatFixture]
     * drives the bottom through a turning point rather than holding it at a
     * constant angle, so the suite exercises the band the way a body does.
     *
     * **This is a workaround, not the right design.** Depth is a property of
     * a repetition, not of a frame: the question is whether the lowest point
     * reached the standard, and that is answerable once per repetition at the
     * extreme rather than by counting frames near it. A fault evaluated at
     * repetition boundaries would need no stillness test at all.
     */
    private val STILL_MOVING = 1.0

    fun onFrame(value: Double?, timestampMs: Long): SegmentUpdate {
        if (value == null) {
            // No reading. Hold the state rather than guessing: a dropped frame
            // must not count a repetition or end a hold. The hold clock does
            // stop, because an unreadable frame is not evidence of a hold.
            lastHeldTickMs = null
            return SegmentUpdate(
                phase = if (engaged) roles.bottom else roles.idle,
                repCompleted = false,
                repCount = repCount,
                holdMs = currentHoldMs(),
                driver = null,
                engaged = engaged,
            )
        }

        val smoothed = smooth(value)
        val wasEngaged = engaged
        var completed = false

        if (driver.entryIsIncreasing) {
            // A hold: the driver rises on entry. Credit the time since the
            // last readable held frame before deciding whether this frame
            // ends the hold, so the closing fragment is not lost.
            if (engaged) {
                val since = lastHeldTickMs
                if (since != null) segmentMs += timestampMs - since
            }
            if (!engaged && smoothed >= enterBound) {
                engaged = true
                segmentStartMs = timestampMs
                segmentMs = 0L
            } else if (engaged && smoothed < exitBound) {
                if (segmentMs >= driver.minRepMs) holdMs += segmentMs
                segmentMs = 0L
                engaged = false
            }
            lastHeldTickMs = if (engaged) timestampMs else null
        } else {
            // A repetition: the driver falls during the descent and must come
            // back above the higher exit bound to complete.
            if (!engaged && smoothed < enterBound) {
                engaged = true
                segmentStartMs = timestampMs
                extremeInSegment = smoothed
            } else if (engaged) {
                if (smoothed < extremeInSegment) extremeInSegment = smoothed
                if (smoothed >= exitBound) {
                    val duration = timestampMs - segmentStartMs
                    // A segment shorter than min_rep_ms is a twitch and a
                    // segment longer than max_rep_ms is someone who walked
                    // away mid-repetition. Neither is counted.
                    if (duration in driver.minRepMs..driver.maxRepMs) {
                        repCount++
                        completed = true
                    }
                    engaged = false
                }
            }
        }

        val phase = resolvePhase(smoothed, wasEngaged)
        previous = smoothed

        return SegmentUpdate(
            phase = phase,
            repCompleted = completed,
            repCount = repCount,
            holdMs = currentHoldMs(),
            driver = smoothed,
            engaged = engaged,
        )
    }

    private fun currentHoldMs(): Long =
        if (driver.entryIsIncreasing && engaged) holdMs + segmentMs else holdMs

    private fun resolvePhase(smoothed: Double, wasEngaged: Boolean): String {
        if (!engaged) return roles.idle
        if (driver.entryIsIncreasing) return roles.bottom // the held phase

        val prev = previous
        if (prev == null || !wasEngaged) return roles.descent

        // Order matters here. Testing the bottom band first would make every
        // frame of a descent read as the bottom, because the running extreme
        // tracks a falling driver exactly and the band is therefore always
        // satisfied. A still-falling driver is a descent whatever its value.
        //
        // A still-rising driver is likewise an ascent whatever its value, and
        // that half was missing until 2026-11-03. Without it the band alone
        // decided, so the frames climbing out of the bottom kept the label
        // `bottom` until the driver had travelled the full ten degrees. A
        // depth fault is scoped to `bottom` and fires above a threshold, so
        // any repetition that cleared its depth threshold by less than the
        // band width raised insufficient depth on the way back up: a false
        // cue on a correct repetition. The push-up showed it the first time
        // it was ever run. The squat had it too and its fixture missed it,
        // passing depth by 10.5 degrees against a 10 degree band.
        //
        // The declared phase is "driver at local minimum, within 10 degrees
        // of it". A driver that is measurably rising is not at a local
        // minimum, so this is the implementation catching up with the prose
        // rather than a change of meaning.
        val delta = smoothed - prev
        return when {
            delta < -STILL_MOVING -> roles.descent
            delta > STILL_MOVING -> roles.ascent
            smoothed <= extremeInSegment + bottomBand -> roles.bottom
            else -> roles.ascent
        }
    }

    private fun smooth(value: Double): Double {
        val n = driver.smoothing.windowFrames.coerceAtLeast(1)
        window.addLast(value)
        while (window.size > n) window.removeFirst()
        return when (driver.smoothing.type) {
            "median" -> window.sorted()[window.size / 2]
            "mean" -> window.average()
            "none" -> value
            else -> throw IllegalArgumentException("unknown smoothing '${driver.smoothing.type}'")
        }
    }

    /** Clears per-set state. Calibration-derived bounds are kept. */
    fun resetSet() {
        window.clear()
        engaged = false
        previous = null
        repCount = 0
        holdMs = 0L
        segmentMs = 0L
        lastHeldTickMs = null
    }
}
