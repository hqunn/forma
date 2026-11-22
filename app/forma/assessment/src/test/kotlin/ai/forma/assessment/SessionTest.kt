package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole assessment layer, end to end, on a JVM with no device attached.
 *
 * This is Chapter 11 tier T1. The method is deterministic, so a landmark
 * sequence is a complete replayable fixture and the rules can be exercised
 * without a camera, a phone or a person.
 */
class SessionTest {

    private fun session() = AssessmentSession(Specs.squat(), emptyMap())

    private fun run(frames: List<Frame>, session: AssessmentSession = session()): List<SessionUpdate> =
        frames.map { session.onFrame(it) }

    @Test
    fun `a clean set raises nothing and says nothing`() {
        val s = session()
        val updates = run(SquatFixture.set(8, bottomAngle = 50.0), s)

        assertEquals(8, updates.last().repCount)
        assertTrue(s.faultLog.isEmpty(), "raised: ${s.faultLog.map { it.code }}")
        assertTrue(updates.all { it.cue == null })
    }

    @Test
    fun `a shallow set raises insufficient depth`() {
        val s = session()
        // Calibrated depth is 70 degrees and the threshold is 70 x 1.15 = 80.5.
        run(SquatFixture.set(5, bottomAngle = 95.0), s)

        assertEquals(List(5) { "SQ_DEPTH_INSUFFICIENT" }, s.faultLog.map { it.code })
    }

    @Test
    fun `the tolerance below horizontal is where the specification says it is`() {
        // Pins the threshold, which is a chosen number and not the criterion.
        // The criterion is femur below horizontal, which is zero; the
        // threshold sits 0.0209 under it so that the system never speaks
        // about a difference smaller than its own repeatability, measured at
        // a standard deviation of 0.0107 over ten repetitions on
        // 2026-11-05. In this fixture's geometry the thigh reaches
        // horizontal near a knee angle of 60 and crosses the threshold near
        // 68, so these two cases straddle it by a couple of degrees.
        val inside = session()
        run(SquatFixture.set(3, bottomAngle = 64.0), inside)   // about -0.0105
        assertTrue(
            inside.repLog.none { "SQ_DEPTH_INSUFFICIENT" in it.raised },
            "a repetition inside the tolerance must not be called shallow",
        )

        val outside = session()
        run(SquatFixture.set(3, bottomAngle = 74.0), outside)  // about -0.0364
        assertEquals(
            3, outside.repLog.count { "SQ_DEPTH_INSUFFICIENT" in it.raised },
            "a repetition beyond the tolerance must be caught",
        )
    }

    @Test
    fun `the same depth is judged the same however fast the repetition is`() {
        // The requirement in one test. A range-of-motion fault must depend on
        // the range and on nothing else. Before 2026-11-04 it depended on how
        // many frames the user spent near the turning point, so the same
        // shallow squat performed smoothly and performed with a pause got
        // different answers, and on set-20261105-000329 ten shallow squats
        // raised on six.
        for (bottomFrames in listOf(0, 1, 3, 10, 30)) {
            val s = session()
            run(
                SquatFixture.set(
                    3,
                    bottomAngle = 95.0,
                    // 20 frames a leg keeps even the shortest of these
                    // above min_rep_ms, so what varies is the pause at the
                    // bottom and nothing else. The segment opens partway
                    // down and closes partway up, so the engaged stretch is
                    // shorter than the frames emitted.
                    framesPerLeg = 20,
                    bottomFrames = bottomFrames,
                ),
                s,
            )
            assertEquals(
                3, s.repLog.count { "SQ_DEPTH_INSUFFICIENT" in it.raised },
                "with " + bottomFrames + " frames at the bottom, only " +
                    s.repLog.count { c -> "SQ_DEPTH_INSUFFICIENT" in c.raised } +
                    " of " + s.repLog.size + " shallow repetitions were caught",
            )
        }
    }

    @Test
    fun `a deep repetition is judged the same however fast it is`() {
        for (bottomFrames in listOf(0, 1, 10)) {
            val s = session()
            run(
                SquatFixture.set(3, bottomAngle = 50.0, bottomFrames = bottomFrames),
                s,
            )
            assertTrue(
                s.repLog.none { "SQ_DEPTH_INSUFFICIENT" in it.raised },
                "a deep repetition was called shallow at " + bottomFrames + " bottom frames",
            )
        }
    }

    @Test
    fun `every repetition records how close each fault came, raised or not`() {
        // The distinction a raise count cannot make. A repetition with no
        // raise is either correct or missed, and only the extreme the
        // measurement reached says which. Recall is computed against this.
        val s = session()
        run(SquatFixture.set(4, bottomAngle = 95.0), s)

        assertEquals(4, s.repLog.size)
        for (r in s.repLog) {
            val worst = r.extremes["SQ_DEPTH_INSUFFICIENT"]
            assertTrue(worst != null, "rep ${'$'}{r.rep} measured nothing at the bottom")
            assertTrue(worst!! < 0.0, "rep ${'$'}{r.rep} should be shallow, got ${'$'}worst")
            assertTrue("SQ_DEPTH_INSUFFICIENT" in r.raised, "rep ${'$'}{r.rep} did not raise")
        }
    }

    @Test
    fun `a correct repetition records a passing extreme and no raise`() {
        val s = session()
        run(SquatFixture.set(3, bottomAngle = 50.0), s)

        assertEquals(3, s.repLog.size)
        for (r in s.repLog) {
            assertTrue(r.raised.isEmpty(), "rep ${'$'}{r.rep} raised ${'$'}{r.raised}")
            val worst = r.extremes["SQ_DEPTH_INSUFFICIENT"]
            assertTrue(worst != null && worst >= 0.0, "rep ${'$'}{r.rep} extreme was ${'$'}worst")
        }
    }

    @Test
    fun `a squat that takes the thigh past horizontal raises nothing`() {
        // The criterion is the hip at or below the knee. This fixture's shin
        // pitches forward as the knee bends, so the thigh reaches horizontal
        // at a knee angle near 60 rather than at 90: the published standard
        // is deeper than the one a knee angle alone would suggest, and that
        // is a property of the criterion rather than of this fixture.
        val s = session()
        run(SquatFixture.set(5, bottomAngle = 50.0), s)
        assertTrue(s.faultLog.none { it.code == "SQ_DEPTH_INSUFFICIENT" })
    }

    @Test
    fun `a squat stopping above horizontal is raised, however consistent the user is`() {
        // What the calibrated threshold could not do. Before 2026-11-04 the
        // reference was whatever depth the user demonstrated, so a user who
        // only ever squatted to 95 degrees had 95 degrees as their standard
        // and was never told. The standard no longer moves with the user.
        val s = session()
        run(SquatFixture.set(5, bottomAngle = 95.0), s)
        assertTrue(s.faultLog.any { it.code == "SQ_DEPTH_INSUFFICIENT" })
    }

    @Test
    fun `a torso pitched past the tibia is raised, and one parallel with it is not`() {
        val leaning = session()
        run(SquatFixture.set(4, bottomAngle = 50.0, leanExcessDeg = 25.0), leaning)
        assertTrue(leaning.faultLog.any { it.code == "SQ_FORWARD_LEAN" })

        val upright = session()
        run(SquatFixture.set(4, bottomAngle = 50.0, leanExcessDeg = 0.0), upright)
        assertTrue(upright.faultLog.none { it.code == "SQ_FORWARD_LEAN" })
    }

    /**
     * The criterion is relative by construction, which is the property that
     * made it better than the calibrated rule it replaced. A user with poor
     * ankle mobility pitches the tibia further forward, and the torso follows
     * it without that being a fault.
     */
    @Test
    fun `forward lean does not fire on depth alone`() {
        for (depth in listOf(110.0, 90.0, 70.0, 55.0)) {
            val s = session()
            run(SquatFixture.set(3, bottomAngle = depth, leanExcessDeg = 0.0), s)
            assertTrue(
                s.faultLog.none { it.code == "SQ_FORWARD_LEAN" },
                "a correct squat to $depth degrees was cued for leaning",
            )
        }
    }

    @Test
    fun `an occluded subject raises nothing`() {
        val s = session()
        val frames = SquatFixture.set(3, bottomAngle = 95.0).map { f ->
            // Rebuild every landmark below the visibility floor.
            Frame.of(
                f.timestampMs,
                Landmarks.indexByName.keys.mapNotNull { name ->
                    f.point(name)?.let { name to it.copy(visibility = 0.2) }
                }.toMap(),
            )
        }
        run(frames, s)
        assertTrue(s.faultLog.isEmpty())
    }

    @Test
    fun `one fault held for a whole repetition is one occurrence, not forty`() {
        val s = session()
        run(SquatFixture.set(3, bottomAngle = 95.0), s)
        assertEquals(3, s.faultLog.size, "one per repetition")
    }

    // --------------------------------------------------------- what is said

    /**
     * TC-08, the half that can be tested without a device: two faults present
     * together never produce two cues at once, and whichever one wins does so
     * at every tempo rather than only at the tempo somebody happened to try.
     *
     * **The two faults are now depth and forward lean**, SQ_HEEL_LIFT having
     * been excluded on 2026-11-03. That changes what this test demonstrates,
     * and the change is the interesting part. Depth is scoped to `bottom`
     * alone; lean is scoped to `descent` and `bottom`, so lean is raised
     * first and holds the older clock at the trigger. `priority` lists depth
     * above lean. Phase scoping therefore decides the order here, and
     * `priority` does not.
     *
     * That is not a regression. `priority` breaks ties among faults raised in
     * the same frame, and SCHEMA.md says so where the field is defined. This
     * test pins the behaviour so that a change to either field is visible
     * rather than silent.
     *
     * The scan over tempos is deliberate: one tempo would not distinguish
     * the ordering holding from the ordering happening to hold once.
     */
    @Test
    fun `two faults at once produce at most one cue, and the order is stable`() {
        val s = session()
        val updates = run(
            SquatFixture.set(6, bottomAngle = 95.0, framesPerLeg = 55, leanExcessDeg = 15.0),
            s,
        )
        val spoken = updates.mapNotNull { it.cue }

        assertTrue(spoken.isNotEmpty(), "a slow set with two real faults should produce a cue")
        assertEquals(spoken.size, spoken.map { it.atMs }.distinct().size, "two cues shared an instant")
        assertTrue(
            s.faultLog.map { it.code }.toSet() == setOf("SQ_DEPTH_INSUFFICIENT", "SQ_FORWARD_LEAN"),
            "both faults should be detected, got ${s.faultLog.map { it.code }.toSet()}",
        )

        val first = spoken.first().code
        assertEquals(
            "SQ_FORWARD_LEAN",
            first,
            "lean holds the older clock because its phase scope opens earlier",
        )

        for (fpl in listOf(15, 20, 30, 40, 55, 70)) {
            val t = session()
            val atThisTempo = run(
                SquatFixture.set(6, bottomAngle = 95.0, framesPerLeg = fpl, leanExcessDeg = 15.0),
                t,
            ).mapNotNull { it.cue }.firstOrNull()
            if (atThisTempo != null) {
                assertEquals(
                    first,
                    atThisTempo.code,
                    "at $fpl frames a leg the first cue was ${atThisTempo.code}, not $first",
                )
            }
        }
    }

    @Test
    fun `the set cue budget is never exceeded`() {
        val s = session()
        val updates = run(
            SquatFixture.set(30, bottomAngle = 95.0, framesPerLeg = 55, leanExcessDeg = 15.0),
            s,
        )
        val spoken = updates.mapNotNull { it.cue }
        assertTrue(spoken.size <= Specs.squat().trigger.maxCuesPerSet!!, "spoke ${spoken.size} times")
    }

    @Test
    fun `the same cue is never spoken more than twice in a row`() {
        val s = session()
        val updates = run(SquatFixture.set(30, bottomAngle = 95.0, framesPerLeg = 55), s)
        val codes = updates.mapNotNull { it.cue }.map { it.code }

        var run = 0
        var previous: String? = null
        for (c in codes) {
            run = if (c == previous) run + 1 else 1
            previous = c
            assertTrue(run <= Specs.squat().trigger.maxConsecutiveSameCue!!, "said '$c' $run times in a row")
        }
    }

    @Test
    fun `cues respect the minimum gap`() {
        val s = session()
        val updates = run(
            SquatFixture.set(20, bottomAngle = 95.0, framesPerLeg = 55, leanExcessDeg = 15.0),
            s,
        )
        val times = updates.mapNotNull { it.cue }.map { it.atMs }
        times.zipWithNext { a, b ->
            assertTrue(b - a >= Specs.squat().trigger.minGapMs, "only ${b - a} ms between cues")
        }
    }

    /**
     * A finding, recorded as a test because it is a property of the current
     * specification rather than a defect in the code.
     *
     * `debounce_ms` was 1500 for the squat: a fault had to persist a second
     * and a half before it was worth saying. A fault can only persist for as
     * long as the phases it is scoped to. DebounceTempoTest measures where
     * those two facts meet, on SQ_FORWARD_LEAN since 2026-11-03:
     * This test previously asserted the opposite. At `debounce_ms` 1500 a
     * repetition had to last 3.6 seconds before a heel lift was ever spoken,
     * and an ordinary squat is half that, so every real fault was detected,
     * logged, and never said out loud. The debounce was never the problem:
     * it exists so the user is not cued on a transient, and `min_frames` and
     * `min_gap_ms` are untouched. 1500 ms was simply the wrong number for a
     * movement whose faults cannot last that long, chosen before anything
     * could run it. It is 400 ms as of 2026-11-03.
     */
    @Test
    fun `at an ordinary tempo the squat speaks`() {
        val s = session()
        val updates = run(
            SquatFixture.set(10, bottomAngle = 95.0, framesPerLeg = 20, leanExcessDeg = 15.0),
            s,
        )
        assertTrue(s.faultLog.isNotEmpty(), "the faults are detected")
        assertTrue(
            updates.any { it.cue != null },
            "and at least one is spoken: 400 ms fits inside the phases a squat fault can occupy",
        )
    }

    @Test
    fun `starting a new set clears the count, the log and the cue budget`() {
        val s = session()
        run(SquatFixture.set(4, bottomAngle = 95.0), s)
        assertTrue(s.faultLog.isNotEmpty())

        s.startNewSet()
        val updates = run(SquatFixture.set(2, bottomAngle = 50.0), s)

        assertEquals(2, updates.last().repCount)
        assertTrue(s.faultLog.isEmpty())
        assertEquals(0, s.cuesSpokenThisSet)
    }

    @Test
    fun `a session cannot start without the calibration its thresholds need`() {
        // No MVP specification needs calibration any more, so this is checked
        // against the fixture that still does. The guard matters for any
        // exercise that reintroduces a per-user reference: a missing value
        // must stop the set rather than default to something.
        val e = kotlin.test.assertFailsWith<IllegalArgumentException> {
            AssessmentSession(Specs.calibrated(), calibration = emptyMap())
        }
        assertTrue(e.message!!.contains("max_squat_depth_knee_angle"))
    }

    @Test
    fun `no MVP specification requires a calibration value`() {
        // The user demonstrates nothing before training. This is the
        // assertion behind that claim, and it fails the moment a
        // specification reintroduces a relative threshold.
        for (spec in Specs.all()) {
            assertTrue(
                spec.requiredCalibrationIds.isEmpty(),
                "${'$'}{spec.id} still needs ${'$'}{spec.requiredCalibrationIds}",
            )
        }
    }

    // ------------------------------------------------- the other exercises

    @Test
    fun `the same evaluator runs a plank with no code change`() {
        val spec = Specs.plank()
        val s = AssessmentSession(spec, emptyMap())
        val updates = PlankFixture.hold(holdMs = 40_000, hipOffset = 0.0).map { s.onFrame(it) }

        assertTrue(updates.last().holdMs > 38_000)
        assertTrue(s.faultLog.isEmpty())
    }

    @Test
    fun `a sagging plank raises sag and a piking one raises pike`() {
        val sagging = AssessmentSession(Specs.plank(), emptyMap())
        PlankFixture.hold(holdMs = 20_000, hipOffset = 0.12).forEach { sagging.onFrame(it) }
        assertEquals(listOf("PL_HIP_SAG"), sagging.faultLog.map { it.code }.distinct())

        val piking = AssessmentSession(Specs.plank(), emptyMap())
        PlankFixture.hold(holdMs = 20_000, hipOffset = -0.12).forEach { piking.onFrame(it) }
        assertEquals(listOf("PL_HIP_PIKE"), piking.faultLog.map { it.code }.distinct())
    }

    @Test
    fun `the push-up specification runs without new evaluator code`() {
        val s = AssessmentSession(Specs.pushUp(), emptyMap())
        // Three frames of a plausible top position: the point is that loading
        // and evaluating a third exercise needs nothing new, which is the
        // claim 4.6 makes.
        repeat(5) { i ->
            s.onFrame(
                Frame.of(
                    i * 33L,
                    mapOf(
                        "LEFT_SHOULDER" to Point(0.25, 0.60, 0.9), "RIGHT_SHOULDER" to Point(0.25, 0.60, 0.9),
                        "LEFT_ELBOW" to Point(0.25, 0.72, 0.9), "RIGHT_ELBOW" to Point(0.25, 0.72, 0.9),
                        "LEFT_WRIST" to Point(0.25, 0.84, 0.9), "RIGHT_WRIST" to Point(0.25, 0.84, 0.9),
                        "LEFT_HIP" to Point(0.50, 0.60, 0.9), "RIGHT_HIP" to Point(0.50, 0.60, 0.9),
                        "LEFT_KNEE" to Point(0.63, 0.60, 0.9), "RIGHT_KNEE" to Point(0.63, 0.60, 0.9),
                        "LEFT_ANKLE" to Point(0.75, 0.60, 0.9), "RIGHT_ANKLE" to Point(0.75, 0.60, 0.9),
                    ),
                )
            )
        }
        assertEquals("push-up", s.spec.id)
    }
}
