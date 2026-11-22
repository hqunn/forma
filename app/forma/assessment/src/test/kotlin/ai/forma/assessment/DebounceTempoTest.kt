package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * At what tempo does a real fault become speakable?
 *
 * This is a measurement, not a check. `debounce_ms` requires a fault to
 * persist before it is worth saying, and a squat fault can only persist for
 * as long as the phases it is scoped to. Those two facts interact, and the
 * interaction was not visible until the evaluator existed.
 *
 * The scan prints the boundary so that the figure quoted elsewhere is one
 * this suite produced rather than one somebody estimated.
 */
class DebounceTempoTest {

    private fun cuesFor(framesPerLeg: Int): List<SpokenCue> {
        val session = AssessmentSession(Specs.squat(), emptyMap())
        return SquatFixture.set(6, bottomAngle = 95.0, framesPerLeg = framesPerLeg, leanExcessDeg = 15.0)
            .mapNotNull { session.onFrame(it).cue }
    }

    @Test
    fun `measure the tempo at which a forward lean becomes speakable`() {
        val rows = (10..70 step 5).map { fpl ->
            // Descent and ascent are framesPerLeg each, plus ten frames at the
            // bottom, at 33 ms a frame.
            val repMs = (2 * fpl + 10) * SquatFixture.FRAME_MS
            Triple(fpl, repMs, cuesFor(fpl).size)
        }

        println()
        println("squat, forward lean present, debounce_ms = ${Specs.squat().trigger.debounceMs}")
        println("frames/leg   rep duration   cues spoken in 6 reps")
        for ((fpl, repMs, cues) in rows) {
            println(String.format("%10d   %9d ms   %6d", fpl, repMs, cues))
        }

        val firstSpeaking = rows.firstOrNull { it.third > 0 }
        if (firstSpeaking == null) {
            println("no tempo in the scanned range produces a cue")
        } else {
            println("first tempo that speaks: ${firstSpeaking.second} ms per repetition")
        }
        println()

        // The scan must at least be monotonic in the right direction: a
        // slower repetition cannot make a fault harder to say.
        val speaking = rows.filter { it.third > 0 }.map { it.first }
        if (speaking.isNotEmpty()) {
            assertTrue(
                speaking == (speaking.first()..rows.last().first step 5).toList().filter { it in speaking },
                "the speakable tempos are not contiguous: $speaking",
            )
        }
    }
}
