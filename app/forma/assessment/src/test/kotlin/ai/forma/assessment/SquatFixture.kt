package ai.forma.assessment

import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic squat frames.
 *
 * A fixture, not a recording. Its job is to exercise the rules against
 * geometry whose answer is known in advance, which is what a recorded clip
 * cannot give: on a real clip the ground truth is a human judgement, and
 * Chapter 11.4 is about how to obtain one honestly. These frames establish
 * that the rules compute what they claim to compute, nothing more.
 *
 * Sagittal view, subject facing +x. Normalised image coordinates, y downward.
 */
object SquatFixture {

    const val FRAME_MS = 33L // ~30 fps

    /**
     * How far above the lowest point the driver still is at the edges of the
     * turn, in degrees. Shapes the bottom of a repetition as a parabola
     * rather than a flat line, so the frame-to-frame delta passes through
     * small values without ever reaching zero.
     */
    private const val TURN_DEPTH = 4.0

    private const val SHIN = 0.20
    private const val THIGH = 0.20
    private const val TORSO = 0.25

    private val ANKLE = Point(0.50, 0.90, 0.95)

    /**
     * One frame at a given knee angle.
     *
     * @param kneeAngleDeg interior angle at the knee: 180 standing, less when squatting.
     * @param leanExcessDeg how far the torso is pitched past the tibia. 0 satisfies
     *   the FMS criterion of "upper torso parallel with tibia or toward vertical".
     * @param heelLifted raises the heel above the foot index.
     */
    fun frame(
        timestampMs: Long,
        kneeAngleDeg: Double,
        leanExcessDeg: Double = 0.0,
        heelLifted: Boolean = false,
        visibility: Double = 0.95,
    ): Frame {
        // The tibia pitches forward as the user descends. This is a property
        // of the movement, not a fault, which is precisely why the FMS
        // criterion compares the torso against it rather than against vertical.
        val tibiaDeg = (180.0 - kneeAngleDeg) * 0.25
        val tibia = Math.toRadians(tibiaDeg)

        val knee = Point(
            ANKLE.x + SHIN * sin(tibia),
            ANKLE.y - SHIN * cos(tibia),
            visibility,
        )

        // Rotate the knee-to-ankle direction by the knee angle to place the hip.
        val ux = -sin(tibia)
        val uy = cos(tibia)
        val theta = Math.toRadians(kneeAngleDeg)
        val hip = Point(
            knee.x + THIGH * (ux * cos(theta) - uy * sin(theta)),
            knee.y + THIGH * (ux * sin(theta) + uy * cos(theta)),
            visibility,
        )

        val torsoDeg = tibiaDeg + leanExcessDeg
        val torso = Math.toRadians(torsoDeg)
        val shoulder = Point(
            hip.x + TORSO * sin(torso),
            hip.y - TORSO * cos(torso),
            visibility,
        )

        // The heel and foot index were emitted here until 2026-11-04. COCO-17
        // has neither, and nothing has measured them since SQ_HEEL_LIFT was
        // removed on 2026-11-03.
        val named = mutableMapOf<String, Point>()
        for ((name, point) in listOf(
            "SHOULDER" to shoulder,
            "HIP" to hip,
            "KNEE" to knee,
            "ANKLE" to ANKLE.copy(visibility = visibility),
        )) {
            named["LEFT_$name"] = point
            named["RIGHT_$name"] = point
        }
        return Frame.of(timestampMs, named)
    }

    /**
     * One repetition: stand, descend, hold the bottom, ascend, stand.
     *
     * [framesPerLeg] sets the speed. 20 frames a leg is about 1.4 seconds a
     * repetition at 30 fps, which is an ordinary tempo; 45 is a slow one.
     */
    fun repetition(
        startMs: Long,
        bottomAngle: Double,
        framesPerLeg: Int = 20,
        bottomFrames: Int = 10,
        standFrames: Int = 8,
        leanExcessDeg: Double = 0.0,
        heelLiftedAtBottom: Boolean = false,
        standingAngle: Double = 176.0,
    ): List<Frame> {
        val out = mutableListOf<Frame>()
        var t = startMs

        fun emit(angle: Double, lifted: Boolean, lean: Double) {
            out += frame(t, angle, lean, lifted)
            t += FRAME_MS
        }

        repeat(standFrames) { emit(standingAngle, false, 0.0) }

        for (i in 1..framesPerLeg) {
            val f = i.toDouble() / framesPerLeg
            emit(standingAngle + (bottomAngle - standingAngle) * f, heelLiftedAtBottom && f > 0.8, leanExcessDeg * f)
        }
        // A smooth turn through the lowest point, not a pause at a constant
        // angle. Holding the angle exactly still made the frame-to-frame
        // delta zero, which satisfied the segmenter's stillness test no
        // matter how that test was set, and hid a bug that stopped the
        // bottom phase resolving on a real body for every run up to
        // 2026-11-04. The driver now eases in and out of the extreme the way
        // a person does: slowest at the bottom, never motionless.
        for (i in 0 until bottomFrames) {
            val u = (i + 1).toDouble() / (bottomFrames + 1)
            val wobble = TURN_DEPTH * (2.0 * u - 1.0) * (2.0 * u - 1.0)
            emit(bottomAngle + wobble, heelLiftedAtBottom, leanExcessDeg)
        }
        for (i in 1..framesPerLeg) {
            val f = i.toDouble() / framesPerLeg
            // The heel stays up for most of the ascent and settles near the
            // top, which is how the fault actually presents.
            emit(bottomAngle + (standingAngle - bottomAngle) * f, heelLiftedAtBottom && f < 0.85, leanExcessDeg * (1 - f))
        }
        repeat(standFrames) { emit(standingAngle, false, 0.0) }

        return out
    }

    fun set(
        repetitions: Int,
        bottomAngle: Double,
        framesPerLeg: Int = 20,
        bottomFrames: Int = 10,
        leanExcessDeg: Double = 0.0,
        heelLiftedAtBottom: Boolean = false,
    ): List<Frame> {
        val out = mutableListOf<Frame>()
        var t = 0L
        repeat(repetitions) {
            val rep = repetition(
                startMs = t,
                bottomAngle = bottomAngle,
                framesPerLeg = framesPerLeg,
                bottomFrames = bottomFrames,
                leanExcessDeg = leanExcessDeg,
                heelLiftedAtBottom = heelLiftedAtBottom,
            )
            out += rep
            t = rep.last().timestampMs + FRAME_MS
        }
        return out
    }
}

/** Plank frames: the subject lies horizontal, with the hips off the line or on it. */
object PlankFixture {

    const val FRAME_MS = 33L

    /**
     * @param hipOffset how far the hips sit off the shoulder-to-ankle line, in
     *   normalised units. Positive is below the line, which is a sag.
     * @param uprightness 0 is a held plank, 1 is standing.
     */
    fun frame(timestampMs: Long, hipOffset: Double, uprightness: Double = 0.0, visibility: Double = 0.95): Frame {
        val shoulder = Point(0.25, 0.60, visibility)
        val ankle = Point(
            0.25 + 0.50 * (1 - uprightness),
            0.60 + 0.50 * uprightness,
            visibility,
        )
        val midX = (shoulder.x + ankle.x) / 2
        val midY = (shoulder.y + ankle.y) / 2

        // Offset perpendicular to the body line, scaled by its length so the
        // value matches the specification's normalise_by: shoulder_ankle_length.
        val dx = ankle.x - shoulder.x
        val dy = ankle.y - shoulder.y
        val len = kotlin.math.hypot(dx, dy)
        val nx = -dy / len
        val ny = dx / len
        val sign = if (dx < 0.0) -1.0 else 1.0
        val hip = Point(midX + nx * hipOffset * len * sign, midY + ny * hipOffset * len * sign, visibility)

        val named = mutableMapOf<String, Point>()
        for ((name, point) in listOf("SHOULDER" to shoulder, "HIP" to hip, "ANKLE" to ankle)) {
            named["LEFT_$name"] = point
            named["RIGHT_$name"] = point
        }
        return Frame.of(timestampMs, named)
    }

    /** Stand, drop into a plank, hold for [holdMs], stand back up. */
    fun hold(holdMs: Long, hipOffset: Double = 0.0): List<Frame> {
        val out = mutableListOf<Frame>()
        var t = 0L
        repeat(10) { out += frame(t, 0.0, uprightness = 1.0); t += FRAME_MS }
        repeat(5) { i -> out += frame(t, hipOffset, uprightness = 0.8 - i * 0.2); t += FRAME_MS }
        val holdFrames = (holdMs / FRAME_MS).toInt()
        repeat(holdFrames) { out += frame(t, hipOffset, uprightness = 0.0); t += FRAME_MS }
        repeat(10) { out += frame(t, 0.0, uprightness = 1.0); t += FRAME_MS }
        return out
    }
}
