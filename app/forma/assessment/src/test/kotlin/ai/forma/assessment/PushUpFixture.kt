package ai.forma.assessment

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Synthetic push-up frames.
 *
 * The same kind of object as [SquatFixture] and for the same reason: geometry
 * whose answer is known in advance, so that a rule can be shown to compute
 * what it claims to compute. It is not a recording and it is not evidence
 * about real movement.
 *
 * It exists because `push-up.json` had never been run. The squat's
 * `debounce_ms` was found to mute that exercise on the day the evaluator first
 * executed, and the push-up carries the same value with a different fault
 * scoping, so the question of whether it has the same problem could only be
 * settled by running it.
 *
 * Sagittal view, subject horizontal, head toward -x, feet toward +x.
 * Normalised image coordinates, y downward.
 */
object PushUpFixture {

    const val FRAME_MS = 33L // ~30 fps

    private const val UPPER_ARM = 0.13
    private const val FOREARM = 0.13

    /** The hand stays planted. Everything else moves relative to it. */
    private val WRIST = Point(0.30, 0.92, 0.95)

    /** Horizontal run from the hand to the feet. */
    private const val BODY_RUN = 0.52
    private const val ANKLE_Y = 0.93

    /**
     * One frame at a given elbow angle.
     *
     * @param elbowAngleDeg interior angle at the elbow: about 175 at the top of
     *   a push-up, less as the user descends. This is the repetition driver.
     * @param hipOffset how far the hips sit off the shoulder-to-ankle line, in
     *   units of that line's length. Positive is below the line, which is a
     *   sag; negative is a pike. The measure is normalised by the same length,
     *   so this value is what `signed_offset_from_line` returns.
     */
    fun frame(
        timestampMs: Long,
        elbowAngleDeg: Double,
        hipOffset: Double = 0.0,
        visibility: Double = 0.95,
    ): Frame {
        // Shoulder-to-wrist distance follows from the elbow angle by the law of
        // cosines, which is what makes the driver signal exact rather than
        // approximated: the frame is built from the angle the test asked for.
        val theta = Math.toRadians(elbowAngleDeg)
        val d = sqrt(UPPER_ARM * UPPER_ARM + FOREARM * FOREARM - 2 * UPPER_ARM * FOREARM * cos(theta))
        val shoulder = Point(WRIST.x, WRIST.y - d, visibility)

        // Elbow placed by intersecting the two arm segments. It sits behind the
        // hand, toward the feet, which is the tucked position the specification
        // assumes when it excludes PU_ELBOW_FLARE as unobservable from the side.
        val t = (d * d + FOREARM * FOREARM - UPPER_ARM * UPPER_ARM) / (2 * d)
        val h = sqrt((FOREARM * FOREARM - t * t).coerceAtLeast(0.0))
        val elbow = Point(WRIST.x + h, WRIST.y - t, visibility)

        val ankle = Point(WRIST.x + BODY_RUN, ANKLE_Y, visibility)

        // Perpendicular offset from the shoulder-to-ankle line, scaled by its
        // length, matching normalise_by: shoulder_ankle_length. Same
        // construction as PlankFixture, because it is the same measurement.
        val dx = ankle.x - shoulder.x
        val dy = ankle.y - shoulder.y
        val len = hypot(dx, dy)
        val nx = -dy / len
        val ny = dx / len
        val sign = if (dx < 0.0) -1.0 else 1.0
        val midX = (shoulder.x + ankle.x) / 2
        val midY = (shoulder.y + ankle.y) / 2
        val hip = Point(
            midX + nx * hipOffset * len * sign,
            midY + ny * hipOffset * len * sign,
            visibility,
        )

        // Not measured by any declared fault, but declared in the landmark
        // list, so a frame that omitted it would not be the frame the
        // specification describes.
        val knee = Point(hip.x + (ankle.x - hip.x) * 0.6, hip.y + (ankle.y - hip.y) * 0.6, visibility)

        val named = mutableMapOf<String, Point>()
        for ((name, point) in listOf(
            "SHOULDER" to shoulder,
            "ELBOW" to elbow,
            "WRIST" to WRIST.copy(visibility = visibility),
            "HIP" to hip,
            "KNEE" to knee,
            "ANKLE" to ankle,
        )) {
            named["LEFT_$name"] = point
            named["RIGHT_$name"] = point
        }
        return Frame.of(timestampMs, named)
    }

    /** The elbow angle a fully extended arm of these proportions produces. */
    val topAngle: Double = run {
        val d = UPPER_ARM + FOREARM - 1e-6
        val cosTheta = (UPPER_ARM * UPPER_ARM + FOREARM * FOREARM - d * d) /
            (2 * UPPER_ARM * FOREARM)
        Math.toDegrees(acos(cosTheta.coerceIn(-1.0, 1.0)))
    }

    /**
     * One repetition: hold the top, descend, hold the bottom, ascend, hold the top.
     *
     * [framesPerLeg] sets the speed, exactly as in [SquatFixture]. 20 frames a
     * leg is about 1.6 seconds a repetition at 30 fps.
     *
     * [hipOffset] applies to every frame of the repetition, including the top.
     * That is deliberate and it is the difference that matters: a sagging body
     * sags continuously, and `PU_HIP_SAG` is scoped to all four phases, so the
     * fault never clears between repetitions the way a squat heel lift does.
     */
    fun repetition(
        startMs: Long,
        bottomAngle: Double,
        framesPerLeg: Int = 20,
        bottomFrames: Int = 10,
        topFrames: Int = 8,
        hipOffset: Double = 0.0,
    ): List<Frame> {
        val out = mutableListOf<Frame>()
        var t = startMs
        val top = 175.0

        fun emit(angle: Double) {
            out += frame(t, angle, hipOffset)
            t += FRAME_MS
        }

        repeat(topFrames) { emit(top) }
        for (i in 1..framesPerLeg) {
            emit(top + (bottomAngle - top) * (i.toDouble() / framesPerLeg))
        }
        repeat(bottomFrames) { emit(bottomAngle) }
        for (i in 1..framesPerLeg) {
            emit(bottomAngle + (top - bottomAngle) * (i.toDouble() / framesPerLeg))
        }
        repeat(topFrames) { emit(top) }

        return out
    }

    fun set(
        repetitions: Int,
        bottomAngle: Double,
        framesPerLeg: Int = 20,
        hipOffset: Double = 0.0,
    ): List<Frame> {
        val out = mutableListOf<Frame>()
        var t = 0L
        repeat(repetitions) {
            val rep = repetition(
                startMs = t,
                bottomAngle = bottomAngle,
                framesPerLeg = framesPerLeg,
                hipOffset = hipOffset,
            )
            out += rep
            t = rep.last().timestampMs + FRAME_MS
        }
        return out
    }
}
