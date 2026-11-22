package ai.forma.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The adapter is the one join between two coordinate conventions, and every
 * way of getting it wrong produces output that looks reasonable. These tests
 * run on the JVM with no device, which is the point of keeping the mapping
 * free of engine types.
 *
 * The engine changed on 2026-11-04 and these tests changed with it. What the
 * old ones guarded, a rotation applied to the landmarks after inference, no
 * longer exists: MoveNet is not rotation invariant, so the turn is applied to
 * the image before inference and the coordinates come back upright. What
 * replaced it is the letterbox, which is the same kind of hazard in a new
 * place, and it is pure arithmetic so it can still be tested here.
 */
class FrameAdapterTest {

    private fun readings(
        count: Int = FrameAdapter.EXPECTED_LANDMARKS,
        visibility: Float = 0.9f,
    ) = List(count) { i -> FrameAdapter.Reading(0.05f * i, 0.04f * i, visibility) }

    /** The target device's analysis frame, portrait, and MoveNet's input square. */
    private val box = FrameAdapter.letterbox(720, 1280, 192)

    @Test
    fun `a full set of landmarks becomes a frame`() {
        val frame = FrameAdapter.fromReadings(1234L, readings())
        assertNotNull(frame)
        assertEquals(1234L, frame.timestampMs)
        assertNotNull(frame.point("LEFT_KNEE"))
        assertNotNull(frame.point("RIGHT_ANKLE"))
    }

    @Test
    fun `a short landmark list is not a pose`() {
        // MoveNet emits seventeen or the result is malformed. A truncated
        // list reaching the assessment layer would be measured as a body with
        // missing parts rather than rejected.
        assertNull(FrameAdapter.fromReadings(1L, readings(count = 16)))
        assertNull(FrameAdapter.fromReadings(1L, emptyList()))
    }

    @Test
    fun `an empty room is not a pose even though the model still answers`() {
        // The regression that distinguishes this engine from the last one.
        // MoveNet returns seventeen points whatever is in front of the
        // camera, so a frame of furniture arrives looking exactly like a
        // frame of a person except for the scores.
        val nobody = readings(visibility = 0.05f)
        assertNull(FrameAdapter.fromReadings(1L, nobody))

        val somebody = nobody.toMutableList()
        somebody[0] = FrameAdapter.Reading(0.5f, 0.5f, 0.42f)
        assertNotNull(FrameAdapter.fromReadings(1L, somebody))
    }

    @Test
    fun `the letterbox fits the long side and centres the short one`() {
        // 720 by 1280 into 192: the height is the long side, so it sets the
        // scale and the width is padded. Squashing instead of padding would
        // distort every angle the thresholds are built on.
        assertEquals(0.15f, box.scale, 1e-6f)
        assertEquals(42f, box.padX, 1e-4f)
        assertEquals(0f, box.padY, 1e-4f)
    }

    @Test
    fun `unpad returns the centre of the image to the centre of the image`() {
        val centre = FrameAdapter.unpad(floatArrayOf(0.5f, 0.5f, 0.9f), box)
        assertEquals(0.5f, centre.x, 1e-4f)
        assertEquals(0.5f, centre.y, 1e-4f)
    }

    @Test
    fun `unpad reads row then column, not x then y`() {
        // The transposition that looks like a working skeleton. MoveNet
        // orders each keypoint as row, column, score. Read in the obvious
        // order it flips the body about its diagonal, which on a roughly
        // symmetric standing pose still produces a plausible stick figure
        // and plausible-looking angles.
        val r = FrameAdapter.unpad(floatArrayOf(0.25f, 0.75f, 0.9f), box)
        assertEquals(0.9444f, r.x, 1e-3f)
        assertEquals(0.2500f, r.y, 1e-3f)
        assertTrue(r.x > r.y, "row 0.25 column 0.75 must land right of centre and high")
    }

    @Test
    fun `a keypoint outside the frame is clamped rather than passed through`() {
        // The model places a hidden joint wherever it guesses. A coordinate
        // outside the unit interval would break any normaliser that divides
        // by a distance; the score is what the assessment layer uses to
        // refuse the reading, and it is carried through untouched.
        val low = FrameAdapter.unpad(floatArrayOf(-0.4f, -0.4f, 0.11f), box)
        assertEquals(0f, low.x, 1e-6f)
        assertEquals(0f, low.y, 1e-6f)
        assertEquals(0.11f, low.visibility, 1e-6f)

        val high = FrameAdapter.unpad(floatArrayOf(1.4f, 1.4f, 0.11f), box)
        assertEquals(1f, high.x, 1e-6f)
        assertEquals(1f, high.y, 1e-6f)
    }

    @Test
    fun `y is not flipped, so a point higher up the body keeps the smaller y`() {
        // y increases downward in both the model's output and in Point. A
        // flip here would silently invert the sign of every y_delta.
        val high = FrameAdapter.unpad(floatArrayOf(0.20f, 0.5f, 0.9f), box)
        val low = FrameAdapter.unpad(floatArrayOf(0.80f, 0.5f, 0.9f), box)
        assertTrue(high.y < low.y, "got ${high.y} and ${low.y}")
    }

    @Test
    fun `visibility is carried through unchanged`() {
        // The assessment layer declines to evaluate a landmark below
        // min_visibility, which is how the system stays quiet about a body it
        // cannot see. Losing or defaulting this value would make it speak.
        val list = readings(visibility = 0.42f)
        val frame = FrameAdapter.fromReadings(0L, list)
        assertNotNull(frame)
        assertEquals(0.42, frame.point("LEFT_HIP")!!.visibility, 1e-6)
    }
}
