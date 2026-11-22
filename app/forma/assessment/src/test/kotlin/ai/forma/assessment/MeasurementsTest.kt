package ai.forma.assessment

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasurementsTest {

    private fun p(x: Double, y: Double, v: Double = 0.9) = Point(x, y, v)
    private fun close(expected: Double, actual: Double?, tol: Double = 1e-6) {
        assertTrue(actual != null && abs(expected - actual) < tol, "expected $expected, got $actual")
    }

    @Test
    fun `angle is the interior angle at the middle landmark`() {
        close(90.0, Measurements.angle(p(0.0, 0.0), p(0.0, 1.0), p(1.0, 1.0)), 1e-9)
        close(180.0, Measurements.angle(p(0.0, 0.0), p(0.0, 1.0), p(0.0, 2.0)), 1e-9)
    }

    @Test
    fun `angle from vertical reads zero upright and ninety horizontal`() {
        close(0.0, Measurements.angleFromVertical(p(0.0, 0.0), p(0.0, 1.0)), 1e-9)
        close(90.0, Measurements.angleFromVertical(p(0.0, 0.0), p(1.0, 0.0)), 1e-9)
        close(45.0, Measurements.angleFromVertical(p(0.0, 0.0), p(1.0, 1.0)), 1e-9)
    }

    /**
     * This is the property the push-up and plank faults depend on. The raw
     * cross product changes sign with the direction of the line, so without
     * normalisation a subject facing the other way would have a sag cued as a
     * pike.
     */
    @Test
    fun `signed offset from line is positive below the line whichever way the body faces`() {
        val below = p(0.5, 0.6)
        val facingRight = Measurements.signedOffsetFromLine(below, p(0.0, 0.5), p(1.0, 0.5))
        val facingLeft = Measurements.signedOffsetFromLine(below, p(1.0, 0.5), p(0.0, 0.5))

        close(0.1, facingRight)
        close(0.1, facingLeft)

        val above = p(0.5, 0.4)
        close(-0.1, Measurements.signedOffsetFromLine(above, p(0.0, 0.5), p(1.0, 0.5)))
        close(-0.1, Measurements.signedOffsetFromLine(above, p(1.0, 0.5), p(0.0, 0.5)))
    }

    @Test
    fun `the forward lean measure is the torso angle minus the tibia angle`() {
        // Torso 40 degrees from vertical, tibia 25. The FMS criterion is
        // satisfied when the torso is parallel with the tibia or more
        // upright, so a positive difference is a lean past the shin.
        val frame = Frame.of(
            0, mapOf(
                "LEFT_SHOULDER" to p(0.0, 0.0),
                "LEFT_HIP" to p(sinDeg(40.0), cosDeg(40.0)),
                "LEFT_KNEE" to p(0.0, 0.0),
                "LEFT_ANKLE" to p(sinDeg(25.0), cosDeg(25.0)),
            )
        )
        val m = Measure("signed_angle_between_segments", listOf("LEFT_SHOULDER", "LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"))
        close(15.0, Measurements.evaluate(m, frame, 0.6), 1e-9)
    }

    @Test
    fun `a rise is a negative normalised y delta because y increases downward`() {
        // The convention, tested on landmarks the engine actually emits. It
        // used to be tested on a lifted heel against the foot index; COCO-17
        // has neither, and the property under test was never about the foot.
        val m = Measure(
            "y_delta",
            listOf("LEFT_KNEE", "LEFT_ANKLE"),
            normaliseBy = "shoulder_ankle_length",
        )
        fun frame(kneeY: Double) = Frame.of(
            0, mapOf(
                "LEFT_SHOULDER" to p(0.50, 0.30),
                "LEFT_ANKLE" to p(0.50, 0.90),
                "LEFT_KNEE" to p(0.50, kneeY),
            )
        )

        // Scale is the shoulder-to-ankle distance, 0.60.
        val level = Measurements.evaluate(m, frame(0.90), 0.6)!!   // delta 0
        val raised = Measurements.evaluate(m, frame(0.84), 0.6)!!  // delta -0.06, so -0.10

        assertTrue(level > -0.05, "a knee level with the ankle must not trip it, got $level")
        assertTrue(raised < -0.05, "a knee above the ankle must trip it, got $raised")
    }

    @Test
    fun `mean of sides averages the two sides and falls back to one when the other is occluded`() {
        val m = Measure("y_delta", listOf("LEFT_HIP", "LEFT_KNEE"), aggregate = "mean_of_sides")

        val both = Frame.of(
            0, mapOf(
                "LEFT_HIP" to p(0.4, 0.50), "LEFT_KNEE" to p(0.4, 0.70),
                "RIGHT_HIP" to p(0.4, 0.60), "RIGHT_KNEE" to p(0.4, 0.70),
            )
        )
        close(-0.15, Measurements.evaluate(m, both, 0.6), 1e-9)

        val rightOccluded = Frame.of(
            0, mapOf(
                "LEFT_HIP" to p(0.4, 0.50), "LEFT_KNEE" to p(0.4, 0.70),
                "RIGHT_HIP" to p(0.4, 0.60, 0.1), "RIGHT_KNEE" to p(0.4, 0.70, 0.1),
            )
        )
        close(-0.20, Measurements.evaluate(m, rightOccluded, 0.6), 1e-9)
    }

    @Test
    fun `a measurement below the visibility floor is unavailable, not zero`() {
        val m = Measure("angle", listOf("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"))
        val frame = Frame.of(
            0, mapOf(
                "LEFT_HIP" to p(0.4, 0.5, 0.2),
                "LEFT_KNEE" to p(0.4, 0.7),
                "LEFT_ANKLE" to p(0.4, 0.9),
            )
        )
        assertNull(Measurements.evaluate(m, frame, 0.6))
    }

    @Test
    fun `every function the three specifications use is implemented`() {
        val used = Specs.all().flatMap { spec ->
            spec.faults.map { it.measure.fn } +
                spec.repDriver.signal.fn +
                (spec.calibration?.measures?.map { it.signal.fn } ?: emptyList())
        }.toSet()

        val frame = squareFrame()
        for (fn in used) {
            val args = when (fn) {
                "angle", "signed_offset_from_line" -> listOf("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE")
                "angle_from_vertical", "y_delta", "dist" -> listOf("LEFT_HIP", "LEFT_KNEE")
                "signed_angle_between_segments" -> listOf("LEFT_SHOULDER", "LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE")
                "mean_y" -> listOf("LEFT_HIP", "LEFT_KNEE")
                "ratio" -> listOf("LEFT_HIP", "LEFT_KNEE", "LEFT_KNEE", "LEFT_ANKLE")
                else -> error("test does not know how to call '$fn'")
            }
            val value = Measurements.evaluate(Measure(fn, args), frame, 0.6)
            assertTrue(value != null && value.isFinite(), "'$fn' returned $value")
        }
        assertEquals(5, used.size, "functions in use: $used")
    }

    /**
     * The invariant the whole diagnostic rests on. A breakdown that disagreed
     * with the value the fault actually fired on would be worse than no
     * breakdown, because it would be believed.
     */
    @Test
    fun `explain reports exactly the number evaluate returns`() {
        val frame = squareFrame()
        val cases = listOf(
            Measure("angle", listOf("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE")),
            Measure("angle", listOf("LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"), aggregate = "mean_of_sides"),
            Measure("y_delta", listOf("LEFT_HIP", "LEFT_KNEE")),
            Measure("y_delta", listOf("LEFT_HIP", "LEFT_KNEE"), aggregate = "mean_of_sides"),
            Measure("dist", listOf("LEFT_HIP", "LEFT_KNEE")),
            Measure(
                "signed_angle_between_segments",
                listOf("LEFT_SHOULDER", "LEFT_HIP", "LEFT_KNEE", "LEFT_ANKLE"),
                aggregate = "mean_of_sides",
            ),
        )
        for (m in cases) {
            assertEquals(
                Measurements.evaluate(m, frame, 0.6),
                Measurements.explain(m, frame, 0.6).combined,
                "'${m.fn}' aggregate=${m.aggregate} disagreed",
            )
        }
    }

    /**
     * The 2026-09-18 squat set spoke "Keep your heels down" on repetitions
     * the student says were performed with his heels on the floor. He stood
     * side on, so one foot is behind the other and the engine infers it
     * rather than measuring it.
     *
     * This is that scenario: a flat near foot, a tilted far foot, and a mean
     * that crosses the threshold on the strength of the side the camera
     * cannot see. The test does not claim this is what happened on the
     * device. It fixes what the record must show if it did.
     */
    @Test
    fun `a mean of sides fault records the side the camera cannot see`() {
        val frame = Frame.of(
            0, mapOf(
                "LEFT_SHOULDER" to p(0.50, 0.30),
                "RIGHT_SHOULDER" to p(0.50, 0.30),
                "LEFT_ANKLE" to p(0.50, 0.90),
                "RIGHT_ANKLE" to p(0.50, 0.90),
                // Near knee level with the ankle.
                "LEFT_KNEE" to p(0.50, 0.90),
                // Far knee, inferred rather than observed, reading as raised.
                "RIGHT_KNEE" to p(0.50, 0.78),
            )
        )
        val rise = Measure(
            "y_delta",
            listOf("LEFT_KNEE", "LEFT_ANKLE"),
            normaliseBy = "shoulder_ankle_length",
            aggregate = "mean_of_sides",
        )

        val b = Measurements.explain(rise, frame, 0.6)
        close(0.0, b.declared.value)
        assertTrue(b.mirrored!!.value!! < -0.15, "the far side reads raised, got ${b.mirrored.value}")

        // The fault fires, and the near side contributed nothing to it.
        assertTrue(b.combined!! < -0.05, "the mean crosses the threshold, got ${b.combined}")
        assertTrue(
            b.declared.value!! > -0.05,
            "on the near side alone it would not fire, got ${b.declared.value}",
        )

        // Both sides' visibility is on the record, which is what decides
        // whether min_visibility should have excluded the far one. The
        // normaliser's own landmarks are in there too, because a scale that
        // could not be measured is as good a reason for a null as the
        // measurement itself.
        assertEquals(
            setOf("LEFT_KNEE", "LEFT_ANKLE", "LEFT_SHOULDER"),
            b.declared.visibility.keys,
        )
        assertEquals(
            setOf("RIGHT_KNEE", "RIGHT_ANKLE", "RIGHT_SHOULDER"),
            b.mirrored.visibility.keys,
        )
    }

    @Test
    fun `a side below the visibility floor reports its visibility and no value`() {
        val frame = Frame.of(
            0, mapOf(
                "LEFT_SHOULDER" to p(0.50, 0.30),
                "RIGHT_SHOULDER" to p(0.50, 0.30, v = 0.30),
                "LEFT_ANKLE" to p(0.50, 0.90),
                "RIGHT_ANKLE" to p(0.50, 0.90, v = 0.30),
                "LEFT_KNEE" to p(0.50, 0.90),
                "RIGHT_KNEE" to p(0.50, 0.78, v = 0.30),
            )
        )
        val rise = Measure(
            "y_delta",
            listOf("LEFT_KNEE", "LEFT_ANKLE"),
            normaliseBy = "shoulder_ankle_length",
            aggregate = "mean_of_sides",
        )

        val b = Measurements.explain(rise, frame, 0.6)
        assertNull(b.mirrored!!.value, "below the floor the side must not contribute")
        close(0.30, b.mirrored.visibility["RIGHT_KNEE"])
        // Falling back to the one measured side is the live behaviour, and
        // here it is the correct one: the fault does not fire.
        close(0.0, b.combined)
    }

    private fun squareFrame() = Frame.of(
        0, mapOf(
            "LEFT_SHOULDER" to p(0.40, 0.30),
            "LEFT_HIP" to p(0.42, 0.52),
            "LEFT_KNEE" to p(0.44, 0.72),
            "LEFT_ANKLE" to p(0.46, 0.90),
        )
    )

    private fun sinDeg(d: Double) = kotlin.math.sin(Math.toRadians(d))
    private fun cosDeg(d: Double) = kotlin.math.cos(Math.toRadians(d))
}
