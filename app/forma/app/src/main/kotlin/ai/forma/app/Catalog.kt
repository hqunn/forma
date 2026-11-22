package ai.forma.app

import android.content.Context
import androidx.annotation.DrawableRes
import ai.forma.assessment.ExerciseSpec
import ai.forma.assessment.SpecLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the exercise list and the settings screen show.
 *
 * Every field is read from the specification file rather than written into
 * the application, which is the claim those two screens are making. If a
 * fault is added to a file, the count on the list changes with no code
 * touched here.
 */
data class ExerciseEntry(
    val asset: String,
    val spec: ExerciseSpec,
) {
    val displayName get() = spec.displayName
    val version get() = spec.version
    val faultCount get() = spec.faults.size
    val isHold get() = spec.repDriver.reports.type == "duration"
    val kind get() = if (isHold) "Hold" else "Repetitions"

    /**
     * The photograph shown before the set.
     *
     * Keyed by the specification's id, and the only place in the client that
     * has to know an exercise by name. A picture cannot be generated from a
     * measurement rule, so this is where the data-driven claim stops: a new
     * specification without one here gets the squat's, which is wrong but
     * not broken, and the alternative is a blank space.
     *
     * These replaced line drawings. The drawings were built to show the line
     * each rule measures against, which is a good thing for a diagram to do
     * and not what somebody about to train wants to look at.
     */
    @get:DrawableRes
    val figure: Int
        get() = when (spec.id) {
            "push-up" -> R.drawable.fig_pushup
            "plank" -> R.drawable.fig_plank
            else -> R.drawable.fig_squat
        }

    /**
     * A second photograph, where one position does not describe the movement.
     *
     * The push-up is the case: the top and the bottom are different shapes
     * and the fault is about the line held between them, so a single frame
     * of either one teaches half of it. The squat and the plank each have
     * one position worth showing.
     */
    @get:DrawableRes
    val figureSecond: Int?
        get() = if (spec.id == "push-up") R.drawable.fig_pushup_b else null

    /**
     * Who took the photograph, where it came from and under what licence.
     *
     * Null where none is owed, which is the push-up: that frame is from the
     * project's own recordings. The plank is CC BY and the credit is a
     * condition of using it at all, so it is rendered with the picture
     * rather than filed somewhere a reader would have to go looking.
     */
    val figureCredit: String?
        get() = when (spec.id) {
            "push-up" -> null
            "plank" -> "Photo: nenadstojkovicart, Flickr (CC BY 2.0)"
            else -> "Photo: Matt Moloney, StockSnap (CC0)"
        }

    /**
     * What this exercise watches for, in the trainee's words.
     *
     * Built from the cue each fault speaks rather than from a table here, so
     * it cannot fall out of step with what they will actually hear. A version
     * number told them nothing; the correction they might get tells them what
     * the app is for.
     */
    val checks: String
        get() {
            val lines = spec.faults.mapNotNull { spec.cues[it.code]?.text?.trimEnd('.') }
            return if (lines.isEmpty()) "No corrections yet"
            else "Listens for: " + lines.joinToString("  ·  ")
        }
}

/**
 * Whether the trainee still wants the guide before a set.
 *
 * Per exercise, not per app: someone who knows the squat may still want the
 * plank explained. Nothing else is stored here, and the default is to show
 * it, so a cleared install starts by explaining itself.
 */
object GuidePrefs {
    private const val FILE = "forma"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hidden(context: Context, specId: String): Boolean =
        prefs(context).getBoolean("guide_hidden_" + specId, false)

    fun setHidden(context: Context, specId: String, hidden: Boolean) {
        prefs(context).edit().putBoolean("guide_hidden_" + specId, hidden).apply()
    }
}

object Catalog {
    /**
     * The assets to offer. This list is the one place an exercise is named,
     * and it names a file rather than a movement: nothing here knows what a
     * squat is.
     */
    private val ASSETS = listOf(
        "bodyweight-squat.json",
        "push-up.json",
        "plank.json",
    )

    private var cache: List<ExerciseEntry>? = null

    fun load(context: Context): List<ExerciseEntry> {
        cache?.let { return it }
        val out = ASSETS.mapNotNull { asset ->
            try {
                val text = context.assets.open(asset).bufferedReader().use { it.readText() }
                ExerciseEntry(asset, SpecLoader.load(text))
            } catch (_: Exception) {
                // A file that will not load is not offered. It would fail at
                // the same point a moment later with less explanation.
                null
            }
        }
        cache = out
        return out
    }
}

/** One completed set, as it was written to disk when the set ended. */
data class SetRecord(
    val file: String,
    val written: String,
    val exercise: String,
    val specId: String,
    val specVersion: String,
    val reps: Int,
    val holdS: Double,
    val repsWithFault: Int,
    val cuesSpoken: Int,
    val poseRate: Double,
    /** How long the set ran. Zero on a record written before this release. */
    val durationS: Double,
    /** Fault code to the number of repetitions it was raised on. */
    val faultCounts: Map<String, Int>,
    /** Fault code to the number of times it was actually spoken. */
    val cueCounts: Map<String, Int>,
) {
    val isHold get() = holdS > 0.0 && reps == 0

    /** `20261105-091500` to something a person reads. */
    val day get() = if (written.length >= 8) written.substring(0, 8) else written
    val clock
        get() = if (written.length >= 13) {
            written.substring(9, 11) + ":" + written.substring(11, 13)
        } else ""

    val result get() = if (isHold) fmt1(holdS) + " s held" else "$reps reps"

    /** `92.4` seconds as `1:32`, and empty where it was never recorded. */
    val clockLength: String
        get() {
            if (durationS <= 0.0) return ""
            val t = durationS.toInt()
            return "%d:%02d".format(t / 60, t % 60)
        }
}

private fun fmt1(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

/**
 * The session history, read back from the files the client wrote.
 *
 * There is no database. A set is one JSON file in the application's own
 * external files directory, which is what 5.3 describes and what the pull
 * script in Appendix A collects. Reading them back is enough for a history
 * list, and it keeps one representation of a set rather than two.
 */
object SessionStore {

    fun all(context: Context): List<SetRecord> {
        val dir = File(context.getExternalFilesDir(null), "sets")
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.name }.mapNotNull { parse(it) }
    }

    fun latest(context: Context): SetRecord? = all(context).firstOrNull()

    private fun parse(f: File): SetRecord? = try {
        val o = JSONObject(f.readText())

        val faultCounts = mutableMapOf<String, Int>()
        val reps = o.optJSONArray("reps_detail") ?: JSONArray()
        for (i in 0 until reps.length()) {
            val raised = reps.getJSONObject(i).optJSONArray("raised") ?: continue
            for (j in 0 until raised.length()) {
                val code = raised.getString(j)
                faultCounts[code] = (faultCounts[code] ?: 0) + 1
            }
        }

        val cueCounts = mutableMapOf<String, Int>()
        val cues = o.optJSONArray("cues") ?: JSONArray()
        for (i in 0 until cues.length()) {
            val code = cues.getJSONObject(i).optString("code")
            if (code.isNotEmpty()) cueCounts[code] = (cueCounts[code] ?: 0) + 1
        }

        SetRecord(
            file = f.name,
            written = o.optString("written"),
            exercise = o.optString("exercise", "-"),
            specId = o.optString("spec_id"),
            specVersion = o.optString("spec_version"),
            reps = o.optInt("reps"),
            holdS = o.optDouble("hold_s", 0.0),
            repsWithFault = o.optInt("reps_with_fault"),
            cuesSpoken = o.optInt("cues_spoken"),
            poseRate = o.optDouble("pose_rate", -1.0),
            durationS = o.optDouble("duration_s", 0.0),
            faultCounts = faultCounts,
            cueCounts = cueCounts,
        )
    } catch (_: Exception) {
        // A record this version cannot read is skipped rather than guessed
        // at. The file stays on disk.
        null
    }
}
