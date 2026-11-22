package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How often the system is allowed to say the same thing, and when it starts
 * counting again.
 *
 * The two shapes of movement answer this differently, and the policy is
 * declared per exercise rather than fixed in code.
 *
 * A counting exercise paces itself. The fault clears at the top of every
 * repetition, which gives the trainee a natural moment to act, so a limit of
 * two in a row stops the set becoming a lecture. The limit has to reset when
 * the fault genuinely goes away, or a trainee who fixes something and later
 * lets it slip is never told again.
 *
 * A hold paces nothing. The position is held, the fault persists until the
 * trainee changes something, and there is no boundary to pace against, so the
 * plank keeps correcting for as long as the fault is there.
 */
class TriggerResetTest {

    // ---- a counting exercise: twice, then only after a real gap ---------

    private val squat = Specs.squat()
    private val sq = "SQ_FORWARD_LEAN"

    private fun lean(sinceMs: Long) = listOf(RaisedFault(sq, 25.0, 10.0, sinceMs))

    /** Speaks at the first moment every gate allows, and reports when. */
    private fun speakTwice(p: TriggerPolicy, from: Long): Long {
        var t = from
        var said = 0
        while (said < 2 && t < from + 60_000) {
            if (p.select(lean(from), t) != null) said++
            t += 100
        }
        assertEquals(2, said, "the policy should allow two in a row")
        return t
    }

    @Test
    fun `a fault that never clears is said twice and then no more`() {
        val p = TriggerPolicy(squat)
        var t = speakTwice(p, 0L)
        var more = 0
        while (t < 120_000) {
            if (p.select(lean(0L), t) != null) more++
            t += 100
        }
        assertEquals(0, more, "an unbroken fault must not be repeated a third time")
    }

    @Test
    fun `a fault that clears for the cooldown is heard about again`() {
        val p = TriggerPolicy(squat)
        val afterTwo = speakTwice(p, 0L)
        val returnsAt = afterTwo + squat.trigger.sameCueResetMs + 1_000

        // Stepped from the moment it returns, because that is what the live
        // loop does: the policy is asked on every frame the fault is raised,
        // so the gap it observes is the real absence.
        val cue = generateSequence(returnsAt) { it + 100 }
            .take(400)
            .firstNotNullOfOrNull { p.select(lean(returnsAt), it) }

        assertNotNull(cue, "a fault that genuinely went away must be heard about again")
        assertEquals(sq, cue.code)
    }

    @Test
    fun `a flicker shorter than the cooldown does not reset the counter`() {
        val p = TriggerPolicy(squat)
        val afterTwo = speakTwice(p, 0L)
        val returnsAt = afterTwo + squat.trigger.sameCueResetMs / 2

        val cue = generateSequence(returnsAt) { it + 100 }
            .take(400)
            .firstNotNullOfOrNull { p.select(lean(returnsAt), it) }

        assertNull(cue, "a brief gap is not a correction and must not reset the limit")
    }

    // ---- a hold: keep correcting until it is fixed ----------------------

    private val plank = Specs.plank()
    private val pl = "PL_HIP_SAG"

    @Test
    fun `a hold keeps correcting an uncorrected fault`() {
        val p = TriggerPolicy(plank)
        val raised = listOf(RaisedFault(pl, 0.09, 0.02, 0L))

        val times = mutableListOf<Long>()
        var t = 0L
        while (t < 45_000) {
            p.select(raised, t)?.let { times += it.atMs }
            t += 100
        }

        assertTrue(
            times.size > 15,
            "a hold must keep correcting while the fault is there, said ${times.size} times",
        )
        times.zipWithNext { a, b ->
            assertTrue(b - a >= plank.trigger.minGapMs, "only ${b - a} ms apart")
        }
    }

    @Test
    fun `a hold stops the moment the fault clears`() {
        val p = TriggerPolicy(plank)
        val raised = listOf(RaisedFault(pl, 0.09, 0.02, 0L))
        var t = 0L
        while (t < 10_000) { p.select(raised, t); t += 100 }

        // Nothing raised: the evaluator found no violation this frame.
        var afterwards = 0
        while (t < 30_000) {
            if (p.select(emptyList(), t) != null) afterwards++
            t += 100
        }
        assertEquals(0, afterwards, "silence is the correct output once the fault is gone")
    }
}
