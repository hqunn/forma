package ai.forma.app

import ai.forma.assessment.Frame
import ai.forma.assessment.Landmarks
import ai.forma.assessment.Point

/**
 * The join between the pose estimator and the assessment layer.
 *
 * This is the only file in the application that holds an estimator's output
 * convention and an assessment type at the same time, and it exists so that
 * nothing else has to. The assessment layer consumes [Frame], which is
 * [Landmarks.COUNT] nullable [Point]s and a timestamp, and knows nothing
 * about where they came from. Changing the engine rewrites this file and
 * `PoseEngine`, and no others; that claim was tested on 2026-11-04, when the
 * engine changed from BlazePose to MoveNet.
 *
 * Four conventions have to line up here, and each is wrong in a way that
 * produces plausible output rather than an error:
 *
 * 1. **y increases downward** in both the model's normalised output and in
 *    [Point]. No flip. A `y_delta` measured upward is therefore negative,
 *    and a flip here would silently invert the sign of every such fault.
 * 2. **The model emits (y, x), not (x, y).** MoveNet's output tensor orders
 *    each keypoint as row then column then score. Reading it in the obvious
 *    order transposes the whole skeleton, which on a roughly symmetric
 *    standing pose still looks like a person.
 * 3. **An absent or low confidence reads as unmeasurable, not as pass.** The
 *    assessment layer declines to evaluate a landmark below `min_visibility`
 *    rather than letting the fault through, so the safe default is the one
 *    that makes the system go quiet.
 * 4. **The timestamp is the frame's own**, not the time the result arrived.
 *    Using the delivery time instead would fold inference latency, which
 *    varies frame to frame, into the intervals the segmenter measures
 *    movement with.
 *
 * Rotation is **not** handled here. MoveNet is not rotation invariant, so the
 * turn has to be applied to the image before inference, and the coordinates
 * that come back are already upright. The adapter that preceded this one
 * rotated the landmarks afterwards, because MediaPipe reported them in the
 * frame it was handed; that correction has no counterpart here and keeping a
 * dead one would be worse than removing it.
 */
object FrameAdapter {

    /** MoveNet emits COCO-17. Fewer means the result is malformed. */
    const val EXPECTED_LANDMARKS = Landmarks.COUNT

    /** One landmark as the estimator reports it, in upright normalised space. */
    data class Reading(val x: Float, val y: Float, val visibility: Float)

    /**
     * How an upright frame of [width] by [height] is fitted into a square of
     * [side] pixels: scaled to fit, centred, the remainder left black.
     *
     * Letterboxed rather than squashed. Stretching a 9:16 frame into a square
     * would distort every angle the thresholds are built on, and the
     * distortion would then be read as the model being inaccurate.
     */
    data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val width: Int,
        val height: Int,
        val side: Int,
    )

    fun letterbox(width: Int, height: Int, side: Int): Letterbox {
        val scale = side.toFloat() / maxOf(width, height)
        return Letterbox(
            scale = scale,
            padX = (side - width * scale) / 2f,
            padY = (side - height * scale) / 2f,
            width = width,
            height = height,
            side = side,
        )
    }

    /**
     * One keypoint from the padded square back into normalised image space.
     *
     * [kp] is the model's triple in its own order: row, column, score. The
     * inverse of [letterbox]: undo the centring, undo the scale, divide by
     * the upright frame's own dimensions.
     *
     * Clamped to the unit interval. A keypoint the model places outside the
     * image is a guess about a body part it cannot see, and the confidence
     * it carries is what the assessment layer uses to refuse it; a
     * coordinate outside the frame would also break any normaliser that
     * divides by a distance.
     */
    fun unpad(kp: FloatArray, box: Letterbox): Reading {
        val px = kp[1] * box.side
        val py = kp[0] * box.side
        return Reading(
            x = ((px - box.padX) / box.scale / box.width).coerceIn(0f, 1f),
            y = ((py - box.padY) / box.scale / box.height).coerceIn(0f, 1f),
            visibility = kp[2],
        )
    }

    /**
     * Builds a [Frame] from normalised readings, or null if there is no
     * usable pose.
     *
     * Null is returned rather than an empty frame because the two mean
     * different things downstream. An empty frame would be measured, found
     * unreadable, and would clear every fault's confirmation counter as
     * though the user had corrected themselves. No frame at all is simply a
     * gap, which is what a missing pose actually is.
     *
     * **MoveNet has no notion of an empty room.** It returns seventeen points
     * with a score each whatever is in front of the camera, so unlike the
     * engine before it, the absence of a subject cannot be read from the
     * absence of a result. [PRESENCE_FLOOR] is the gate, and it is an
     * engineering choice rather than a sourced number: it only has to
     * separate a person from furniture, because `min_visibility` in the
     * specification is what decides whether an individual landmark may be
     * measured.
     */
    fun fromReadings(timestampMs: Long, readings: List<Reading>): Frame? {
        if (readings.size < EXPECTED_LANDMARKS) return null
        if (readings.none { it.visibility >= PRESENCE_FLOOR }) return null
        return Frame(
            timestampMs,
            readings.map { Point(it.x.toDouble(), it.y.toDouble(), it.visibility.toDouble()) },
        )
    }

    /** Below this on every keypoint, there is nobody in front of the camera. */
    const val PRESENCE_FLOOR = 0.3f
}
