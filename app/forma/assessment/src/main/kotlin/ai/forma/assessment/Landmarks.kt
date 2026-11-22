package ai.forma.assessment

/**
 * COCO-17 landmark names and their indices, and the frame type the
 * assessment layer consumes.
 *
 * Specification files name landmarks symbolically so that an exercise file
 * never contains a bare integer (SCHEMA.md). Resolution happens here, once,
 * which is what lets the topology change underneath every specification
 * without any of them being edited.
 *
 * **This was BlazePose-33 until 2026-11-04.** The engine measurement on that
 * date put MoveNet Lightning on the GPU delegate at 27 ms p95 against a 65 ms
 * requirement, where no MediaPipe configuration came under it, and MoveNet
 * emits COCO-17. Every measurement in the three MVP specifications is
 * expressible in this set; the four foot landmarks that are not in it were
 * used only by `SQ_HEEL_LIFT`, which was removed on 2026-11-03 for unrelated
 * reasons. A specification naming one of them now fails to load, which is the
 * intended behaviour: an unresolvable landmark must not become a silent null.
 */
object Landmarks {

    /** The number of slots a frame carries. COCO-17, in the order the model emits. */
    const val COUNT = 17

    val indexByName: Map<String, Int> = mapOf(
        "NOSE" to 0,
        "LEFT_EYE" to 1, "RIGHT_EYE" to 2,
        "LEFT_EAR" to 3, "RIGHT_EAR" to 4,
        "LEFT_SHOULDER" to 5, "RIGHT_SHOULDER" to 6,
        "LEFT_ELBOW" to 7, "RIGHT_ELBOW" to 8,
        "LEFT_WRIST" to 9, "RIGHT_WRIST" to 10,
        "LEFT_HIP" to 11, "RIGHT_HIP" to 12,
        "LEFT_KNEE" to 13, "RIGHT_KNEE" to 14,
        "LEFT_ANKLE" to 15, "RIGHT_ANKLE" to 16,
    )

    fun isKnown(name: String) = indexByName.containsKey(name)

    fun indexOf(name: String): Int =
        indexByName[name] ?: throw IllegalArgumentException("unknown landmark: $name")

    /**
     * The opposite-side counterpart of a landmark.
     *
     * Used by the `mean_of_sides` aggregate: a specification names only the
     * left-hand set and the evaluator substitutes the mirror for the second
     * evaluation (SCHEMA.md). A name with no side, such as NOSE, mirrors to
     * itself.
     */
    fun mirror(name: String): String = when {
        name.startsWith("LEFT_") -> "RIGHT_" + name.removePrefix("LEFT_")
        name.startsWith("RIGHT_") -> "LEFT_" + name.removePrefix("RIGHT_")
        else -> name
    }
}

/**
 * One landmark in normalised image coordinates.
 *
 * x and y are fractions of frame width and height. **y increases downward**,
 * so a point higher up the body has the smaller y and a `y_delta` measured
 * upward is negative.
 *
 * The two fractions do not share a scale. x is divided by frame width and y
 * by frame height, so on a 720 by 1280 frame an x of 0.1 and a y of 0.1 are
 * different distances. A ratio of one against the other is therefore not
 * dimensionless. This is what broke SQ_HEEL_LIFT, removed 2026-11-03, and
 * `signed_offset_from_line` and `dist` have the same exposure untested.
 *
 * `visibility` is whatever the engine reports as its per-landmark confidence.
 * Under MoveNet that is the model's keypoint score, which is not the same
 * quantity BlazePose called visibility and does not sit in the same part of
 * the range: on 2026-11-04 the knee scored a median of 0.76 under MoveNet
 * against 0.99 under BlazePose on the same pose. Thresholds written against
 * one engine's numbers are not transferable to the other's by inspection.
 */
data class Point(val x: Double, val y: Double, val visibility: Double)

/**
 * One frame of pose output.
 *
 * `timestampMs` is a monotonic reading, not wall time. The segmenter and the
 * trigger policy both measure elapsed intervals from it, and a clock that can
 * step backwards would corrupt both.
 */
class Frame(val timestampMs: Long, private val points: List<Point?>) {

    fun point(name: String): Point? = points.getOrNull(Landmarks.indexOf(name))

    companion object {
        /** Builds a frame from named landmarks. Used by fixtures and tests. */
        fun of(timestampMs: Long, named: Map<String, Point>): Frame {
            val slots = arrayOfNulls<Point>(Landmarks.COUNT)
            for ((name, p) in named) slots[Landmarks.indexOf(name)] = p
            return Frame(timestampMs, slots.toList())
        }
    }
}
