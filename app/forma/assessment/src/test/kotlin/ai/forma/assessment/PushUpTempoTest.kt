package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does the push-up have the squat's mute bug, and does it have the squat's
 * priority inversion?
 *
 * Both questions are open for the same reason: `push-up.json` was authored
 * with `debounce_ms` 1500 and nothing had ever run it. The squat carried the
 * same value and was mute. Whether the push-up is too cannot be reasoned from
 * the number alone, because `debounce_ms` interacts with the phases a fault is
 * scoped to, and the push-up scopes its faults very differently:
 *
 * | Fault | Phases | `min_frames` |
 * |---|---|---|
 * | `PU_HIP_SAG` | all four | 5 |
 * | `PU_HIP_PIKE` | all four | 5 |
 * | `PU_DEPTH_INSUFFICIENT` | `bottom` only | 2 |
 *
 * A fault scoped to all four phases never clears between repetitions, so its
 * debounce clock runs continuously. One scoped to `bottom` restarts every
 * repetition. That difference is the whole question, and this scan answers it
 * by running it rather than by arguing it.
 */
class PushUpTempoTest {

    private val cal: Map<String, Double> = emptyMap()

    /** The shipped specification with one trigger value replaced, for counterfactuals. */
    private fun withDebounce(ms: Int): ExerciseSpec =
        SpecLoader.load(
            Specs.text("push-up.json").replace("\r\n", "\n")
                .replace("\"debounce_ms\": 1500", "\"debounce_ms\": $ms")
        )

    private fun run(spec: ExerciseSpec, frames: List<Frame>): Pair<AssessmentSession, List<SpokenCue>> {
        val s = AssessmentSession(spec, cal)
        return s to frames.mapNotNull { s.onFrame(it).cue }
    }

    // 85 is a deep repetition and 110 a shallow one. Both are repetitions and
    // both are counted; the push-up judges the body line, not the range.
    private val deep = 85.0
    private val shallow = 110.0

    @Test
    fun `the fixture produces the faults it claims to produce`() {
        val spec = Specs.pushUp()

        val clean = run(spec, PushUpFixture.set(6, bottomAngle = deep, hipOffset = 0.0))
        assertTrue(
            clean.first.faultLog.isEmpty(),
            "a deep push-up with a straight body raises nothing, found ${clean.first.faultLog.map { it.code }}",
        )

        val shallowSet = run(spec, PushUpFixture.set(6, bottomAngle = shallow, hipOffset = 0.0))
        assertTrue(
            shallowSet.first.faultLog.isEmpty(),
            "a shallow push-up with a straight body raises nothing, found ${shallowSet.first.faultLog.map { it.code }}",
        )

        val sagging = run(spec, PushUpFixture.set(6, bottomAngle = deep, hipOffset = 0.10))
        assertTrue(
            sagging.first.faultLog.any { it.code == "PU_HIP_SAG" },
            "a sagging push-up raises sag, found ${sagging.first.faultLog.map { it.code }}",
        )

        val piking = run(spec, PushUpFixture.set(6, bottomAngle = deep, hipOffset = -0.10))
        assertTrue(
            piking.first.faultLog.any { it.code == "PU_HIP_PIKE" },
            "a piking push-up raises pike, found ${piking.first.faultLog.map { it.code }}",
        )
    }

    @Test
    fun `scan the tempo at which each push-up fault becomes speakable`() {
        val spec = Specs.pushUp()
        val debounce = spec.trigger.debounceMs

        println()
        println("push-up, debounce_ms = $debounce, 6 repetitions per row")
        println("frames/leg  rep duration   depth-only   sag-only   both (first cue)")

        var depthEverSpeaks = false
        var sagEverSpeaks = false

        for (fpl in 10..70 step 5) {
            // Top hold and bottom hold are 8 and 10 frames, plus two legs.
            val repMs = (2 * fpl + 18) * PushUpFixture.FRAME_MS

            val depthOnly = run(spec, PushUpFixture.set(6, shallow, fpl, hipOffset = 0.0)).second
            val sagOnly = run(spec, PushUpFixture.set(6, deep, fpl, hipOffset = 0.10)).second
            val both = run(spec, PushUpFixture.set(6, shallow, fpl, hipOffset = 0.10)).second

            if (depthOnly.isNotEmpty()) depthEverSpeaks = true
            if (sagOnly.isNotEmpty()) sagEverSpeaks = true

            println(
                String.format(
                    "%10d  %9d ms   %10d %10d   %s",
                    fpl, repMs, depthOnly.size, sagOnly.size,
                    both.firstOrNull()?.code ?: "(silent)",
                )
            )
        }

        println()
        println("depth ever spoken in the scanned range: $depthEverSpeaks")
        println("sag   ever spoken in the scanned range: $sagEverSpeaks")
        println()

        // The scan is a measurement and must not assert a conclusion. The one
        // thing it can assert is that the fixture reached the evaluator at all.
        assertTrue(
            depthEverSpeaks || sagEverSpeaks,
            "no push-up fault is speakable at any tempo in the scanned range, which would mean the " +
                "fixture never raised anything rather than that the debounce is wrong",
        )
    }

    /**
     * The scan above varies the speed of the descent and the ascent, which is
     * the wrong variable for `PU_DEPTH_INSUFFICIENT`. That fault is scoped to
     * `bottom` alone, so the only thing its debounce clock can accumulate in
     * is the bottom hold, and the fixture holds the bottom for a fixed ten
     * frames however slowly the legs are performed.
     *
     * This scan varies the right one: how long the user pauses at the bottom.
     * It gives the number the previous scan could not, which is the pause a
     * push-up would need before the system is willing to say the repetition
     * was shallow.
     */
    @Test
    fun `how long a push-up must pause at the bottom before depth is speakable`() {
        println()
        println("push-up, shallow repetitions, 4 reps per row")
        println("bottom hold   debounce 1500   debounce 400")
        for (bottomFrames in listOf(5, 10, 20, 30, 45, 60, 75, 90)) {
            val holdMs = bottomFrames * PushUpFixture.FRAME_MS
            fun cues(ms: Int): Int {
                val spec = withDebounce(ms)
                val s = AssessmentSession(spec, cal)
                var t = 0L
                val frames = mutableListOf<Frame>()
                repeat(4) {
                    val rep = PushUpFixture.repetition(t, shallow, 20, bottomFrames = bottomFrames)
                    frames += rep
                    t = rep.last().timestampMs + PushUpFixture.FRAME_MS
                }
                return frames.count { s.onFrame(it).cue != null }
            }
            println(String.format("%8d ms   %13d   %12d", holdMs, cues(1500), cues(400)))
        }
        println()

        // The other direction: hold the bottom for an ordinary length of time
        // and find the debounce at which the fault becomes speakable at all.
        println("push-up, shallow repetitions, bottom held 330 ms, 4 reps")
        println("debounce_ms   cues spoken")
        for (ms in listOf(400, 300, 250, 200, 150, 100, 50)) {
            val spec = withDebounce(ms)
            val session = AssessmentSession(spec, cal)
            var t = 0L
            val frames = mutableListOf<Frame>()
            repeat(4) {
                val rep = PushUpFixture.repetition(t, shallow, 20, bottomFrames = 10)
                frames += rep
                t = rep.last().timestampMs + PushUpFixture.FRAME_MS
            }
            println(String.format("%11d   %11d", ms, frames.count { session.onFrame(it).cue != null }))
        }
        println()
    }

    @Test
    fun `what a shorter debounce would change for the push-up`() {
        val ordinary = 20 // frames per leg, about 1.9 s a repetition

        println()
        println("push-up, both faults present, 6 repetitions at $ordinary frames a leg")
        println("debounce_ms   cues spoken   first cue")
        for (ms in listOf(1500, 1000, 600, 400, 250, 200)) {
            val cues = run(withDebounce(ms), PushUpFixture.set(6, shallow, ordinary, hipOffset = 0.10)).second
            println(
                String.format(
                    "%11d   %11d   %s", ms, cues.size, cues.firstOrNull()?.code ?: "(silent)",
                )
            )
        }
        println()

        // Lowering a suppression threshold cannot make the system say less.
        val atShipped = run(withDebounce(1500), PushUpFixture.set(6, shallow, ordinary, hipOffset = 0.10)).second
        val atShort = run(withDebounce(400), PushUpFixture.set(6, shallow, ordinary, hipOffset = 0.10)).second
        assertTrue(
            atShort.size >= atShipped.size,
            "a shorter debounce spoke fewer cues (${atShort.size}) than a longer one (${atShipped.size})",
        )
    }
}
