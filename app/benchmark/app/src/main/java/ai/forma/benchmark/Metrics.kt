package ai.forma.benchmark

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One analysed camera frame. */
data class FrameRow(
    val elapsedMs: Long,
    /** Cost of turning the ImageProxy into a rotated Bitmap, before inference. */
    val prepMs: Long,
    /** Submission to result delivery, which is the latency the app actually sees. */
    val inferMs: Long,
    val poseFound: Boolean,
    val kneeLVis: Float,
    val kneeRVis: Float,
    val kneeLX: Float,
    val kneeLY: Float,
    val kneeRX: Float,
    val kneeRY: Float,
    val thermal: Int,
    val batteryC: Float,
) {
    companion object {
        const val HEADER =
            "elapsed_ms,prep_ms,infer_ms,pose_found,knee_l_vis,knee_r_vis," +
                "knee_l_x,knee_l_y,knee_r_x,knee_r_y,thermal_status,battery_c"
    }

    fun toCsv(): String = listOf(
        elapsedMs.toString(),
        prepMs.toString(),
        inferMs.toString(),
        if (poseFound) "1" else "0",
        f(kneeLVis), f(kneeRVis), f(kneeLX), f(kneeLY), f(kneeRX), f(kneeRY),
        thermal.toString(),
        f(batteryC),
    ).joinToString(",")

    private fun f(v: Float) = String.format(Locale.US, "%.4f", v)
}

/** The per-configuration result: one row of the 4.2 table. */
data class Summary(
    val config: String,
    val available: Boolean,
    val note: String,
    val frames: Int,
    val durationS: Double,
    val fps: Double,
    val prepP50: Long,
    val inferP50: Long,
    val inferP95: Long,
    val totalP50: Long,
    val totalP95: Long,
    val poseRate: Double,
    val kneeDetectRate: Double,
    val kneeJitter: Double,
    val thermalMax: Int,
    val batteryStartC: Float,
    val batteryEndC: Float,
) {
    companion object {
        const val HEADER =
            "config,available,note,frames,duration_s,fps,prep_p50_ms,infer_p50_ms," +
                "infer_p95_ms,total_p50_ms,total_p95_ms,pose_rate,knee_detect_rate," +
                "knee_jitter_norm,thermal_max,battery_start_c,battery_end_c"

        fun unavailable(config: String, reason: String) = Summary(
            config = config, available = false, note = reason, frames = 0,
            durationS = 0.0, fps = 0.0, prepP50 = 0, inferP50 = 0, inferP95 = 0,
            totalP50 = 0, totalP95 = 0, poseRate = 0.0, kneeDetectRate = 0.0,
            kneeJitter = 0.0, thermalMax = 0, batteryStartC = 0f, batteryEndC = 0f,
        )
    }

    fun toCsv(): String = listOf(
        config,
        if (available) "1" else "0",
        "\"" + note.replace("\"", "'") + "\"",
        frames.toString(),
        d(durationS), d(fps),
        prepP50.toString(), inferP50.toString(), inferP95.toString(),
        totalP50.toString(), totalP95.toString(),
        d(poseRate), d(kneeDetectRate), String.format(Locale.US, "%.5f", kneeJitter),
        thermalMax.toString(),
        String.format(Locale.US, "%.1f", batteryStartC),
        String.format(Locale.US, "%.1f", batteryEndC),
    ).joinToString(",")

    private fun d(v: Double) = String.format(Locale.US, "%.2f", v)
}

object Metrics {

    private fun percentile(sorted: List<Long>, p: Double): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /**
     * Mean frame-to-frame displacement of the two knee landmarks, in normalised
     * image units, over consecutive frames where both readings are confident.
     *
     * This is a stability measure, not an accuracy measure. It cannot say the
     * knee is in the right place; it says only whether the reading holds still.
     * With a stationary subject, a large value means the landmark is unusable
     * for a threshold regardless of how fast the model runs.
     *
     * The knee, rather than the heel it was until 2026-11-03, for two reasons.
     * It is the joint the depth and lean thresholds are actually built on, and
     * it is the only distal joint both topologies emit: a probe on a landmark
     * one engine does not have cannot compare two engines.
     */
    private fun kneeJitter(rows: List<FrameRow>, visFloor: Float = 0.5f): Double {
        var sum = 0.0
        var n = 0
        var prev: FrameRow? = null
        for (r in rows) {
            val ok = r.poseFound && r.kneeLVis >= visFloor && r.kneeRVis >= visFloor
            if (!ok) { prev = null; continue }
            val p = prev
            if (p != null) {
                val dl = dist(p.kneeLX, p.kneeLY, r.kneeLX, r.kneeLY)
                val dr = dist(p.kneeRX, p.kneeRY, r.kneeRX, r.kneeRY)
                sum += (dl + dr) / 2.0
                n++
            }
            prev = r
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = (ax - bx).toDouble()
        val dy = (ay - by).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    fun summarise(config: String, rows: List<FrameRow>): Summary {
        if (rows.isEmpty()) return Summary.unavailable(config, "no frames captured")

        val infer = rows.map { it.inferMs }.sorted()
        val prep = rows.map { it.prepMs }.sorted()
        val total = rows.map { it.prepMs + it.inferMs }.sorted()
        val durationS = (rows.last().elapsedMs - rows.first().elapsedMs) / 1000.0
        val visFloor = 0.5f

        return Summary(
            config = config,
            available = true,
            note = "",
            frames = rows.size,
            durationS = durationS,
            fps = if (durationS > 0) rows.size / durationS else 0.0,
            prepP50 = percentile(prep, 0.50),
            inferP50 = percentile(infer, 0.50),
            inferP95 = percentile(infer, 0.95),
            totalP50 = percentile(total, 0.50),
            totalP95 = percentile(total, 0.95),
            poseRate = rows.count { it.poseFound }.toDouble() / rows.size,
            kneeDetectRate = rows.count {
                it.poseFound && it.kneeLVis >= visFloor && it.kneeRVis >= visFloor
            }.toDouble() / rows.size,
            kneeJitter = kneeJitter(rows, visFloor),
            thermalMax = rows.maxOf { it.thermal },
            batteryStartC = rows.first().batteryC,
            batteryEndC = rows.last().batteryC,
        )
    }

    /**
     * Frame rate in fixed wall-clock buckets. This is what answers "what is the
     * frame rate at minute ten", which a single average over a long run hides.
     */
    fun buckets(rows: List<FrameRow>, bucketMs: Long = 30_000): List<Pair<Long, Double>> {
        if (rows.isEmpty()) return emptyList()
        val t0 = rows.first().elapsedMs
        return rows.groupBy { (it.elapsedMs - t0) / bucketMs }
            .toSortedMap()
            .map { (bucket, inBucket) ->
                bucket * bucketMs / 1000 to inBucket.size * 1000.0 / bucketMs
            }
    }
}
