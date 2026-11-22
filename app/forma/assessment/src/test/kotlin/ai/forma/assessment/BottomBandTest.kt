package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A repetition that reaches its depth must not be told it did not.
 *
 * The bottom phase is declared as *"driver at local minimum, within 10
 * degrees of it"*, and the segmenter implements the band. Until 2026-11-03 it
 * implemented only half the rest of the sentence: a falling driver was a
 * descent whatever its value, but a rising one was still called the bottom
 * until it had climbed the whole band.
 *
 * A depth fault is scoped to `bottom` and fires *above* a threshold. So a
 * repetition that cleared its threshold by less than the band width raised
 * insufficient depth on the way back up, on frames the user had already left
 * the bottom. The cue is wrong, it is heard on a correct repetition, and it
 * is the opposite of what happened: the user went deep enough and was told to
 * go deeper.
 *
 * Both MVP counting exercises had it. Neither test suite caught it, because
 * the one squat fixture in use passed its threshold by 10.5 degrees against a
 * 10 degree band, which is half a degree away from failing.
 */
class BottomBandTest {

    @Test
    fun `a push-up that reaches depth is not told it fell short`() {
        // Calibrated depth 80, threshold 80 * 1.15 = 92. A repetition to 85
        // passes by 7 degrees, which is inside the 10 degree bottom band.
        val s = AssessmentSession(Specs.pushUp(), emptyMap())
        PushUpFixture.set(6, bottomAngle = 85.0).forEach { s.onFrame(it) }

        assertFalse(
            s.faultLog.any { it.code == "PU_DEPTH_INSUFFICIENT" },
            "a repetition to 85 degrees clears a 92 degree threshold and must raise nothing",
        )
    }

    @Test
    fun `a squat that reaches depth is not told it fell short`() {
        // The criterion is the femur below horizontal, which this fixture's shin
        // model reaches at a knee angle of about 60. A repetition to 50
        // passes by 2.5 degrees, well inside the band. The fixture already in
        // the suite uses 70, which passes by 10.5 and escapes by half a degree.
        val s = AssessmentSession(Specs.squat(), emptyMap())
        SquatFixture.set(6, bottomAngle = 50.0).forEach { s.onFrame(it) }

        assertFalse(
            s.faultLog.any { it.code == "SQ_DEPTH_INSUFFICIENT" },
            "a repetition to 50 degrees puts the hip below the knee and must raise nothing",
        )
    }

    /**
     * The push-up judges the body line and nothing else. A shallow repetition
     * is still a repetition, counted and reported, and the specification
     * records under `excluded_faults` why no cue follows it.
     */
    @Test
    fun `a shallow push-up counts as a repetition and raises nothing`() {
        val s = AssessmentSession(Specs.pushUp(), emptyMap())
        var last: SessionUpdate? = null
        PushUpFixture.set(6, bottomAngle = 110.0).forEach { last = s.onFrame(it) }

        assertEquals(6, last?.repCount, "a shallow repetition still counts")
        assertTrue(
            s.faultLog.isEmpty(),
            "the push-up declares no depth fault, found ${s.faultLog.map { it.code }}",
        )
    }

    @Test
    fun `a squat that does not reach depth is still told so`() {
        val s = AssessmentSession(Specs.squat(), emptyMap())
        SquatFixture.set(6, bottomAngle = 95.0).forEach { s.onFrame(it) }

        assertTrue(
            s.faultLog.any { it.code == "SQ_DEPTH_INSUFFICIENT" },
            "95 degrees leaves the hip well above the knee and must still be caught",
        )
    }

    @Test
    fun `the bottom phase ends when the driver starts rising`() {
        val spec = Specs.pushUp()
        val s = AssessmentSession(spec, emptyMap())

        var sawBottom = false
        var bottomAfterRising = 0
        var previous: Double? = null

        for (frame in PushUpFixture.repetition(0, bottomAngle = 85.0)) {
            val u = s.onFrame(frame)
            val d = u.driver
            if (u.phase == "bottom") sawBottom = true
            if (sawBottom && d != null && previous != null && d - previous!! > 0.05 && u.phase == "bottom") {
                bottomAfterRising++
            }
            previous = d
        }

        assertTrue(sawBottom, "the repetition never reached the bottom phase")
        assertTrue(
            bottomAfterRising == 0,
            "$bottomAfterRising frames were labelled bottom while the driver was already rising",
        )
    }
}
