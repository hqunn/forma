package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmenterTest {

    private fun squatSegmenter() = Segmenter(Specs.squat(), emptyMap())

    private fun run(frames: List<Frame>, spec: ExerciseSpec, calibration: Map<String, Double>): List<SegmentUpdate> {
        val segmenter = Segmenter(spec, calibration)
        val signal = spec.repDriver.signal
        val minVis = spec.framing.minVisibility
        return frames.map { f ->
            segmenter.onFrame(Measurements.evaluate(signal, f, minVis), f.timestampMs)
        }
    }

    /**
     * The regression that cost a filming session.
     *
     * A fault scoped to one phase and requiring several consecutive frames
     * in it can only ever fire if that phase actually lasts several frames
     * on a real body. Until 2026-11-04 the stillness threshold was set below
     * the measurement's own noise, so a moving person produced a bottom
     * phase of zero or one frame and `SQ_DEPTH_INSUFFICIENT` never fired:
     * ten deliberately shallow squats on set-20261104-234828 raised nothing.
     *
     * Asserting the run of frames rather than their mere presence is the
     * point. One bottom frame is not enough for a fault that needs three,
     * and a test that only asked whether the phase was ever reached would
     * have passed throughout.
     */
    @Test
    fun `a repetition that never holds still still has a bottom long enough to judge`() {
        val updates = run(SquatFixture.repetition(0, bottomAngle = 95.0), Specs.squat(), emptyMap())

        var run = 0
        var longest = 0
        for (u in updates) {
            run = if (u.phase == "bottom") run + 1 else 0
            longest = maxOf(longest, run)
        }
        assertTrue(
            longest >= 3,
            "the longest unbroken bottom phase was $longest frames; a fault " +
                "needing three consecutive frames there could never fire",
        )
    }

    @Test
    fun `ten repetitions are counted as ten`() {
        val updates = run(SquatFixture.set(10, bottomAngle = 75.0), Specs.squat(), emptyMap())
        assertEquals(10, updates.last().repCount)
    }

    @Test
    fun `a slow repetition and a fast one both count`() {
        val slow = run(SquatFixture.set(5, 75.0, framesPerLeg = 45), Specs.squat(), emptyMap())
        val fast = run(SquatFixture.set(5, 75.0, framesPerLeg = 14), Specs.squat(), emptyMap())
        assertEquals(5, slow.last().repCount)
        assertEquals(5, fast.last().repCount)
    }

    /**
     * TC-07. Dithering at a turning point is the classic defect of repetition
     * counters: the user pauses at the edge of the threshold and the count
     * runs away. Hysteresis is the answer, and this is what tests it.
     */
    @Test
    fun `dithering around the entry bound counts nothing`() {
        val segmenter = squatSegmenter()
        // Entry sits at 175 - 20 = 155, exit at 175 - 12 = 163. The driver
        // crosses entry forty times and never reaches exit.
        var t = 0L
        var last: SegmentUpdate? = null
        repeat(40) { i ->
            val value = if (i % 2 == 0) 152.0 else 158.0
            last = segmenter.onFrame(value, t)
            t += SquatFixture.FRAME_MS
        }
        assertEquals(0, last!!.repCount)
    }

    @Test
    fun `a twitch shorter than min_rep_ms is not a repetition`() {
        val segmenter = squatSegmenter()
        var t = 0L
        var last: SegmentUpdate? = null
        // Down and back up inside 300 ms, against a minimum of 800.
        listOf(176.0, 150.0, 120.0, 150.0, 176.0, 176.0).forEach { v ->
            last = segmenter.onFrame(v, t)
            t += 50
        }
        assertEquals(0, last!!.repCount)
    }

    @Test
    fun `dropped frames hold the count rather than guessing`() {
        val spec = Specs.squat()
        val segmenter = squatSegmenter()
        val signal = spec.repDriver.signal

        // Every third frame unreadable, which is a worse occlusion rate than
        // anything the framing guidance permits.
        var last: SegmentUpdate? = null
        SquatFixture.set(5, bottomAngle = 75.0).forEachIndexed { i, f ->
            val value = if (i % 3 == 2) null else Measurements.evaluate(signal, f, spec.framing.minVisibility)
            last = segmenter.onFrame(value, f.timestampMs)
        }
        assertEquals(5, last!!.repCount, "the repetitions still complete across the gaps")
    }

    @Test
    fun `the phases run standing, descent, bottom, ascent and back`() {
        val updates = run(SquatFixture.repetition(0, 75.0), Specs.squat(), emptyMap())
        val seen = updates.map { it.phase }

        val order = mutableListOf<String>()
        for (p in seen) if (order.lastOrNull() != p) order += p

        assertEquals(listOf("standing", "descent", "bottom", "ascent", "standing"), order)
    }

    @Test
    fun `the bottom phase does not swallow the descent`() {
        val updates = run(SquatFixture.repetition(0, 75.0), Specs.squat(), emptyMap())
        val descentFrames = updates.count { it.phase == "descent" }
        val bottomFrames = updates.count { it.phase == "bottom" }
        assertTrue(descentFrames > 5, "expected a real descent, got $descentFrames frames")
        assertTrue(bottomFrames in 5..20, "expected the bottom to be the hold, got $bottomFrames frames")
    }

    // ------------------------------------------------------- the hold case

    /**
     * The same rep_driver block, segmenting a hold instead of repetitions.
     * No new mechanism: what differs is only whether segments are counted or
     * timed.
     */
    @Test
    fun `a plank is reported as a duration, not as one repetition`() {
        val spec = Specs.plank()
        val frames = PlankFixture.hold(holdMs = 30_000)
        val updates = run(frames, spec, emptyMap())

        val held = updates.last().holdMs
        assertEquals(0, updates.last().repCount)
        assertTrue(held in 29_000..31_000, "expected about 30 s of hold, got ${held} ms")
    }

    @Test
    fun `a hold shorter than min_rep_ms is not recorded`() {
        val frames = PlankFixture.hold(holdMs = 1_500)
        val updates = run(frames, Specs.plank(), emptyMap())
        assertEquals(0L, updates.last().holdMs)
    }

    /**
     * Reported by a user: the plank timer kept running after they walked out
     * of shot. The clock is wall time and the body is not, so a hold measured
     * as the span between entry and exit credits every second the camera saw
     * nothing. Here ten seconds of the thirty are unreadable, and the hold is
     * twenty.
     */
    @Test
    fun `the hold clock stops while the body cannot be seen`() {
        val frames = mutableListOf<Frame>()
        var t = 0L
        val step = PlankFixture.FRAME_MS
        repeat(10) { frames += PlankFixture.frame(t, 0.0, uprightness = 1.0); t += step }
        repeat(5) { i -> frames += PlankFixture.frame(t, 0.0, uprightness = 0.8 - i * 0.2); t += step }
        // Ten seconds held and seen.
        repeat((10_000 / step).toInt()) { frames += PlankFixture.frame(t, 0.0); t += step }
        // Ten seconds held but out of shot: every landmark below the floor.
        repeat((10_000 / step).toInt()) {
            frames += PlankFixture.frame(t, 0.0, visibility = 0.05); t += step
        }
        // Ten more seconds held and seen, then stand.
        repeat((10_000 / step).toInt()) { frames += PlankFixture.frame(t, 0.0); t += step }
        repeat(10) { frames += PlankFixture.frame(t, 0.0, uprightness = 1.0); t += step }

        val held = run(frames, Specs.plank(), emptyMap()).last().holdMs
        assertTrue(
            held in 19_000..21_000,
            "expected about 20 s of visible hold out of 30 s elapsed, got $held ms",
        )
    }
}
