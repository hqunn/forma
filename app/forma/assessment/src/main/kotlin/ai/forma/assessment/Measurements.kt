package ai.forma.assessment

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The measurement vocabulary.
 *
 * This list is the expressiveness ceiling 4.6 accepts. An exercise needing
 * a measurement not on it cannot be expressed, and extending the list is a
 * code change - which is the ceiling behaving as documented rather than
 * failing. Three functions were added while authoring the three MVP
 * specifications, and 4.6.1 of the report accounts for them.
 *
 * All coordinates are normalised image coordinates, so a raw distance varies
 * with the user's distance from the camera and must be normalised either by
 * `normalise_by` or by use inside a ratio.
 */
/**
 * One side of a measurement, with the parts its final number was built from.
 *
 * [value] is what the live path uses and what a fault is thresholded against;
 * it is null when the side could not be measured. The rest exists so that a
 * fault can be explained after the event: [raw] before normalisation, [scale]
 * the body-scale denominator it was divided by, and [visibility] for every
 * landmark the side touched, the normaliser's landmarks included.
 *
 * [visibility] is the field that settles whether `min_visibility` is doing
 * the job it is relied on to do. The occluded side of a sagittal subject is
 * meant to be filtered out by it, and whether it actually is has never been
 * measured on hardware.
 */
data class SideBreakdown(
    val value: Double?,
    val raw: Double?,
    val scale: Double?,
    val visibility: Map<String, Double>,
)

/**
 * A measurement decomposed into the sides that produced it.
 *
 * The two sides are named for how the specification wrote them rather than
 * for the body: [declared] uses the landmarks as the file names them and
 * [mirrored] uses their opposites, which for a file written with `LEFT_` args
 * means [declared] is the left. The landmark names in each side's visibility
 * map say which is which without having to know that.
 *
 * [mirrored] is null when the measurement does not declare `mean_of_sides`.
 * That is a different thing from a mirrored side that could not be measured,
 * which is a [SideBreakdown] whose value is null.
 */
data class MeasureBreakdown(
    val combined: Double?,
    val declared: SideBreakdown,
    val mirrored: SideBreakdown?,
)

object Measurements {

    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * Evaluates a measurement against one frame.
     *
     * Returns null when the measurement is unavailable, which happens when a
     * landmark it needs is below the visibility floor. Null is not zero and is
     * not a passing value: the caller treats it as "no reading this frame".
     */
    fun evaluate(m: Measure, frame: Frame, minVisibility: Double): Double? {
        if (!m.isMeanOfSides) return oneSide(m, frame, minVisibility, mirror = false)
        return combine(
            oneSide(m, frame, minVisibility, mirror = false),
            oneSide(m, frame, minVisibility, mirror = true),
        )
    }

    /**
     * The number [evaluate] produces, with the parts it was built from.
     *
     * A `mean_of_sides` measurement in a sagittal view averages the side the
     * camera can see with the side it cannot, and the single value [evaluate]
     * returns says nothing about which contributed what. A fault that fires
     * on such a number cannot be explained from the number alone, which is
     * the position the 2026-09-18 squat set left this project in.
     *
     * This shares [sideParts] with the live path rather than recomputing, so
     * a breakdown that disagrees with the value the fault actually fired on
     * is not expressible.
     */
    fun explain(m: Measure, frame: Frame, minVisibility: Double): MeasureBreakdown {
        val declared = sideParts(m, frame, minVisibility, mirror = false)
        if (!m.isMeanOfSides) return MeasureBreakdown(declared.value, declared, null)
        val mirrored = sideParts(m, frame, minVisibility, mirror = true)
        return MeasureBreakdown(combine(declared.value, mirrored.value), declared, mirrored)
    }

    /**
     * How the two sides of a `mean_of_sides` measurement become one number.
     *
     * One side occluded is the normal case in a sagittal view, so a single
     * reading is used rather than discarding the frame.
     */
    private fun combine(left: Double?, right: Double?): Double? = when {
        left != null && right != null -> (left + right) / 2.0
        else -> left ?: right
    }

    private fun oneSide(m: Measure, frame: Frame, minVis: Double, mirror: Boolean): Double? =
        sideParts(m, frame, minVis, mirror).value

    private fun sideParts(m: Measure, frame: Frame, minVis: Double, mirror: Boolean): SideBreakdown {
        val names = if (mirror) m.args.map(Landmarks::mirror) else m.args
        val scaleNames = m.normaliseBy?.let { Normalisers.landmarksFor(it, mirror) } ?: emptyList()
        val visibility = (names + scaleNames).associateWith { frame.point(it)?.visibility ?: 0.0 }
        val unavailable = SideBreakdown(null, null, null, visibility)

        val p = names.map { frame.point(it) ?: return unavailable }
        if (p.any { it.visibility < minVis }) return unavailable

        val raw = when (m.fn) {
            "angle" -> {
                require3(m, p.size)
                angle(p[0], p[1], p[2])
            }
            "angle_from_vertical" -> {
                require2(m, p.size)
                angleFromVertical(p[0], p[1])
            }
            "y_delta" -> {
                require2(m, p.size)
                p[0].y - p[1].y
            }
            "dist" -> {
                require2(m, p.size)
                dist(p[0], p[1])
            }
            "ratio" -> {
                requireN(m, p.size, 4)
                val denominator = dist(p[2], p[3])
                if (denominator == 0.0) return unavailable
                dist(p[0], p[1]) / denominator
            }
            "mean_y" -> {
                if (p.isEmpty()) throw IllegalArgumentException("mean_y needs at least one landmark")
                p.sumOf { it.y } / p.size
            }
            "signed_offset_from_line" -> {
                require3(m, p.size)
                signedOffsetFromLine(p[0], p[1], p[2])
            }
            "signed_angle_between_segments" -> {
                requireN(m, p.size, 4)
                angleFromVertical(p[0], p[1]) - angleFromVertical(p[2], p[3])
            }
            else -> throw IllegalArgumentException("unknown measurement function '${m.fn}'")
        }

        val scale = m.normaliseBy ?: return SideBreakdown(raw, raw, null, visibility)
        val denominator = Normalisers.value(scale, frame, mirror, minVis)
            ?: return SideBreakdown(null, raw, null, visibility)
        if (denominator == 0.0) return SideBreakdown(null, raw, denominator, visibility)
        return SideBreakdown(raw / denominator, raw, denominator, visibility)
    }

    private fun require2(m: Measure, n: Int) = requireN(m, n, 2)
    private fun require3(m: Measure, n: Int) = requireN(m, n, 3)
    private fun requireN(m: Measure, n: Int, want: Int) {
        if (n != want) throw IllegalArgumentException("${m.fn} takes $want landmarks, given $n")
    }

    // ------------------------------------------------------------- primitives

    fun dist(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)

    /** Interior angle at [b], in degrees. */
    fun angle(a: Point, b: Point, c: Point): Double {
        val v1x = a.x - b.x
        val v1y = a.y - b.y
        val v2x = c.x - b.x
        val v2y = c.y - b.y
        val n1 = hypot(v1x, v1y)
        val n2 = hypot(v2x, v2y)
        if (n1 == 0.0 || n2 == 0.0) return 0.0
        val cos = ((v1x * v2x + v1y * v2y) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return acos(cos) * RAD_TO_DEG
    }

    /**
     * The angle of segment a-b from image vertical, in degrees, unsigned.
     *
     * 0 when the segment is vertical, 90 when horizontal. This is what makes
     * the same rep_driver block work for a plank: the shoulder-to-hip segment
     * reads near 90 when the user is in position and near 0 when standing.
     */
    fun angleFromVertical(a: Point, b: Point): Double =
        atan2(abs(b.x - a.x), abs(b.y - a.y)) * RAD_TO_DEG

    /**
     * Perpendicular distance of [p] from the line through [a] and [b],
     * **signed**: positive when p lies on the larger-y side, meaning below
     * the line.
     *
     * The sign is the whole reason this function exists. Hip sag and hip pike
     * are opposite deviations of the same body line and take opposite cues,
     * but the interior angle at the hip falls below 180 degrees for both and
     * cannot distinguish them.
     *
     * The raw cross product's sign depends on the direction a to b, so a
     * subject facing the other way would invert it and a sag would be cued as
     * a pike. The sign is therefore normalised against the x direction of the
     * line, which is well defined for the near-horizontal body line of a
     * push-up or a plank.
     */
    fun signedOffsetFromLine(p: Point, a: Point, b: Point): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len == 0.0) return 0.0
        val cross = dx * (p.y - a.y) - dy * (p.x - a.x)
        val signed = cross / len
        return if (dx < 0.0) -signed else signed
    }
}

/**
 * Named body scales a measurement can be normalised by.
 *
 * Each is a distance over landmarks, computed per frame rather than once,
 * because the subject moves relative to the camera during a set.
 */
object Normalisers {

    /** Which landmarks a named scale needs, so rule 1 can check they are declared. */
    fun landmarksFor(name: String, mirror: Boolean): List<String> {
        val side = if (mirror) "RIGHT" else "LEFT"
        return when (name) {
            // `foot_length` was here until 2026-11-04 and is gone with the
            // landmarks it needed. COCO-17 has no heel and no foot index, and
            // a normaliser that cannot be computed is worse than one that
            // does not exist: it would return null on every frame and the
            // fault using it would simply never fire.
            "shoulder_width" -> listOf("LEFT_SHOULDER", "RIGHT_SHOULDER")
            "hip_width" -> listOf("LEFT_HIP", "RIGHT_HIP")
            "torso_length" -> listOf("LEFT_SHOULDER", "RIGHT_SHOULDER", "LEFT_HIP", "RIGHT_HIP")
            "shoulder_ankle_length" -> listOf("${side}_SHOULDER", "${side}_ANKLE")
            else -> throw IllegalArgumentException("unknown normaliser '$name'")
        }
    }

    fun value(name: String, frame: Frame, mirror: Boolean, minVis: Double): Double? {
        val needed = landmarksFor(name, mirror).map { frame.point(it) ?: return null }
        if (needed.any { it.visibility < minVis }) return null

        return when (name) {
            "shoulder_width", "hip_width", "shoulder_ankle_length" ->
                Measurements.dist(needed[0], needed[1])

            "torso_length" -> {
                val shoulder = midpoint(needed[0], needed[1])
                val hip = midpoint(needed[2], needed[3])
                Measurements.dist(shoulder, hip)
            }

            else -> throw IllegalArgumentException("unknown normaliser '$name'")
        }
    }

    private fun midpoint(a: Point, b: Point) =
        Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0, minOf(a.visibility, b.visibility))
}
