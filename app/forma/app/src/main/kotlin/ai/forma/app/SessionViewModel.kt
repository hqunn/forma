package ai.forma.app

import ai.forma.assessment.AssessmentSession
import ai.forma.assessment.CueSpec
import ai.forma.assessment.ExerciseSpec
import ai.forma.assessment.Frame
import ai.forma.assessment.GuidanceLoader
import ai.forma.assessment.SideBreakdown
import ai.forma.assessment.SpecLoader
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Stage {
    /** No exercise chosen. */
    IDLE,

    /**
     * Chosen. The camera is live, the framing is reported, and the trainee
     * starts when they are ready.
     *
     * There used to be a FRAMING stage in front of this one that held the
     * start control disabled until the framing check passed. It is gone:
     * framing is now advice rather than a gate. See [SetupOverlay] for why
     * the check was close to worthless where it sat.
     */
    READY,

    /** Live. This is the only stage with a deadline. */
    SET,

    SUMMARY,
}

/**
 * One cue, timed.
 *
 * This is the record NFR-P1 is measured from, and it is kept per cue rather
 * than averaged because the requirement is written against the user's
 * experience of a single correction, not against a mean.
 *
 * The three readings are all on the same monotonic clock: frames are
 * submitted with `SystemClock.uptimeMillis()`, MediaPipe returns that reading
 * with the result, and the assessment layer timestamps its decisions with it.
 */
data class CueLatency(
    val code: String,
    /** When the fault reached `min_frames` and became a candidate. */
    val confirmedMs: Long,
    /** The frame at which the trigger policy chose to speak it. */
    val selectedMs: Long,
    /** When `SoundPool.play` was called. */
    val dispatchedMs: Long,
) {
    /** Policy cost: debounce, minimum gap and priority resolution. */
    val policyMs get() = selectedMs - confirmedMs

    /** Decision to audio dispatch: the lookup and the play call. */
    val dispatchMs get() = dispatchedMs - selectedMs

    /**
     * Confirmation to audio dispatch.
     *
     * **This is not the whole of NFR-P1.** Two terms sit outside it: the
     * `min_frames` confirmation window before [confirmedMs], which 4.1
     * allocates 169 ms to and which is measurable from the frame interval,
     * and the platform's own path from `play()` to the first audible sample,
     * which is not observable from inside the application. A figure taken
     * from here is a lower bound and Chapter 11.5 says so.
     */
    val totalMs get() = dispatchedMs - confirmedMs
}

data class AppState(
    val stage: Stage = Stage.IDLE,
    val exerciseName: String = "-",
    val message: String = "Choose an exercise.",
    /** The calibration instruction currently being shown, if any. */
    val instruction: String? = null,
    val framingOk: Boolean = false,
    val confidence: Double = 0.0,
    val phase: String = "-",
    val repCount: Int = 0,
    val holdMs: Long = 0,
    val driver: Double? = null,
    val lastCueText: String? = null,
    val cuesSpoken: Int = 0,
    /**
     * Completed repetitions in which at least one fault was raised,
     * spoken or not. Shown beside the cue count because the two answer
     * different questions and the gap between them is the rate limiter
     * doing its job rather than a detection failure.
     */
    val repsWithFault: Int = 0,
    val bankReady: Boolean = false,
    val poseRate: Double = -1.0,
    /** How long the set ran, in seconds. Zero until the set has ended. */
    val durationS: Double = 0.0,
    val fps: Double = 0.0,
    val latencies: List<CueLatency> = emptyList(),
    val error: String? = null,
)

/**
 * Wires the camera to the assessment layer and the assessment layer to the
 * speaker, and owns everything with a lifecycle.
 *
 * The division of labour is the one Chapter 6 argues for. Every decision
 * about the movement is made in `:assessment`, which has no Android
 * dependency and is tested without a device. This class contributes no
 * judgement about form at all: it converts, it sequences, and it times.
 */
class SessionViewModel : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var spec: ExerciseSpec? = null
    private var engine: PoseEngine? = null
    private var player: CuePlayer? = null

    // The user is across the room, side on, and on the target device facing
    // the back of the phone, so nothing before the set is readable and most of
    // it is not even visible. Every stage change is spoken. The tones are kept
    // only as what is heard if a line will not play.
    private val tones = StageTones()
    private var narrator: Narrator? = null
    private var guidance: Map<String, CueSpec> = emptyMap()

    // Held so that a finished set can be written out. The summary panel is a
    // display, not a record, and the button beside it clears the display.
    private var appContext: Context? = null
    private var session: AssessmentSession? = null

    /** Whether the ready line has been spoken for this attempt. */

    private val latencies = mutableListOf<CueLatency>()

    // Live counters for the status panel. Pose rate is the same quantity the
    // benchmark shows, and it is here for the same reason: it is the only
    // on-device evidence that the camera is reaching the model usefully.
    private var framesSeen = 0
    private var posesSeen = 0
    private var firstFrameMs = 0L

    // When the set began, on both clocks. The wall clock is what the record
    // prints; the monotonic one is what it measures the length with, because
    // a clock the user can change is not a stopwatch.
    private var setStartedAt = ""
    private var setStartedMs = 0L
    private var setDurationS = 0.0

    /**
     * Loads a specification, decodes its cue bank and starts the estimator.
     *
     * The specification is read from the assets, where the build placed the
     * file from `docs/specs` directly. No copy of it exists in this module.
     */
    /**
     * The specification currently loaded, for the screens that show where a
     * fault's definition came from. Read-only: the interface renders it and
     * never judges with it.
     */
    val activeSpec: ExerciseSpec? get() = spec

    /**
     * Play one cue so the trainee can check they will hear it.
     *
     * Their headphones, their volume and their silent switch are three things
     * this product depends on entirely and cannot see. Finding out mid-set
     * that the phone was muted costs them the set.
     *
     * Self-contained: it loads and releases its own player rather than
     * touching the session's, so it is safe to use before a set and between
     * them.
     */
    fun playTestCue(context: Context, asset: String) {
        viewModelScope.launch {
            val p = CuePlayer(context.applicationContext)
            try {
                p.preload(listOf(asset))
                // SoundPool decodes off the calling thread, so the sample is
                // not playable the instant preload returns.
                repeat(40) {
                    if (p.isReady) return@repeat
                    delay(50)
                }
                p.play(asset)
                delay(2000)
            } finally {
                p.release()
            }
        }
    }

    fun begin(context: Context, specAsset: String) {
        teardown()
        try {
            appContext = context.applicationContext
            val text = context.assets.open(specAsset).bufferedReader().use { it.readText() }
            val loaded = SpecLoader.load(text)
            spec = loaded

            // Both halves of the bank are decoded before anything starts. The
            // guidance half is needed within a second or two of this call, while
            // the fault half is not needed until the first repetition, so the
            // guidance assets are listed first and reach SoundPool first.
            val guidanceText = context.assets.open(GUIDANCE_ASSET)
                .bufferedReader().use { it.readText() }
            guidance = GuidanceLoader.load(guidanceText)

            // The framing line used to be preloaded here. It is not spoken
            // any more, so loading it cost 315 KB of SoundPool for a clip
            // nothing could play.
            val spoken = guidance.values.map { it.asset }

            val cuePlayer = CuePlayer(context)
            cuePlayer.preload(spoken + loaded.cues.values.map { it.asset })
            player = cuePlayer
            narrator = Narrator(cuePlayer, tones)

            engine = PoseEngine(
                context = context.applicationContext,
                onResult = ::onResult,
                onError = { msg -> _state.value = _state.value.copy(error = msg) },
            )

            latencies.clear()
            resetCounters()

            _state.value = AppState(
                stage = Stage.READY,
                exerciseName = loaded.displayName,
                message = loaded.framing.guidance,
                bankReady = cuePlayer.isReady,
            )
        } catch (e: Exception) {
            _state.value = AppState(
                stage = Stage.IDLE,
                error = e.message ?: e::class.java.simpleName,
                message = "Could not start.",
            )
        }
    }

    /** Called from the camera analyser thread for every delivered frame. */
    fun onFrame(bitmap: Bitmap, rotationDegrees: Int) {
        engine?.submit(bitmap, SystemClock.uptimeMillis(), rotationDegrees)
    }

    private fun onResult(readings: List<FrameAdapter.Reading>, timestampMs: Long) {
        val s = spec ?: return
        val frame = FrameAdapter.fromReadings(timestampMs, readings)
        countFrame(frame != null, timestampMs)

        if (frame == null) {
            _state.value = _state.value.copy(
                framingOk = false,
                confidence = 0.0,
                poseRate = poseRate(),
                fps = fps(),
            )
            return
        }

        when (_state.value.stage) {
            Stage.READY -> onReady(s, frame)
            Stage.SET -> onSetFrame(frame)
            else -> _state.value = _state.value.copy(poseRate = poseRate(), fps = fps())
        }
    }

    // ---- stages ----------------------------------------------------------


    /**
     * Before the set: report the framing on screen, and say nothing.
     *
     * The set-up instruction used to be spoken, on a repeat timer, while the
     * trainee was still standing at the phone with the guide open in front
     * of them. It was the product reading its own screen aloud. The order of
     * operations is now press start, then get into position, so the only
     * line worth speaking before a set is the one confirming it has begun,
     * and [startSet] handles that.
     */
    private fun onReady(s: ExerciseSpec, frame: Frame) {
        val probe = session ?: AssessmentSession(s).also { session = it }
        val ok = probe.framingSatisfied(frame)

        _state.value = _state.value.copy(
            framingOk = ok,
            confidence = probe.framingConfidence(frame),
            bankReady = player?.isReady ?: false,
            poseRate = poseRate(),
            fps = fps(),
            message = if (ok) {
                "Ready when you are."
            } else {
                s.framing.guidance
            },
        )
    }

    private fun onSetFrame(frame: Frame) {
        val live = session ?: return
        val update = live.onFrame(frame)

        var cueText = _state.value.lastCueText
        update.cue?.let { cue ->
            val dispatchedMs = player?.play(cue.asset)
            cueText = cue.text
            if (dispatchedMs != null) {
                val confirmedMs = update.raised.firstOrNull { it.code == cue.code }?.sinceMs
                    ?: cue.atMs
                latencies += CueLatency(
                    code = cue.code,
                    confirmedMs = confirmedMs,
                    selectedMs = cue.atMs,
                    dispatchedMs = dispatchedMs,
                )
            }
        }

        _state.value = _state.value.copy(
            phase = update.phase,
            repCount = update.repCount,
            holdMs = update.holdMs,
            driver = update.driver,
            lastCueText = cueText,
            cuesSpoken = live.cuesSpokenThisSet,
            repsWithFault = live.repLog.count { it.raised.isNotEmpty() },
            confidence = live.framingConfidence(frame),
            poseRate = poseRate(),
            fps = fps(),
            latencies = latencies.toList(),
        )
    }

    // ---- control ---------------------------------------------------------

    fun startSet() {
        val s = spec ?: return
        session = AssessmentSession(s).also { it.startNewSet() }
        latencies.clear()
        resetCounters()
        setStartedAt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        setStartedMs = SystemClock.uptimeMillis()
        setDurationS = 0.0
        // The one line spoken before a repetition, and it is now the only
        // one. The trainee presses this and walks away from the phone, so
        // what they need is confirmation that it heard them.
        narrator?.say(guidance[GuidanceLoader.READY]?.asset) { tones.ready() }
        _state.value = _state.value.copy(
            stage = Stage.SET,
            message = "Go.",
            repCount = 0,
            holdMs = 0,
            cuesSpoken = 0,
            repsWithFault = 0,
            lastCueText = null,
            latencies = emptyList(),
        )
    }

    fun endSet() {
        setDurationS =
            if (setStartedMs > 0L) (SystemClock.uptimeMillis() - setStartedMs) / 1000.0
            else 0.0
        _state.value = _state.value.copy(
            stage = Stage.SUMMARY,
            message = "Set finished.",
            latencies = latencies.toList(),
            durationS = setDurationS,
        )
        writeSetRecord()
    }

    /**
     * Writes what the set measured, next to the benchmark's own output.
     *
     * The summary panel shows these figures and the button under it clears
     * them, which made the only record of a set a photograph of a phone. The
     * cue latencies are the first NFR-P1 evidence this project has, and a
     * measurement that survives only until someone taps Done is not evidence.
     *
     * Deliberately not fatal. A set that was performed is not undone by a
     * record that could not be written, and the panel still has the numbers.
     */
    /**
     * A number, or `null`, with enough places to see a threshold being
     * crossed. Six, because a normalised y delta is a small number and
     * rounding one to three places loses the margin the fault fired by.
     */
    private fun num(v: Double?): String =
        if (v == null) "null" else String.format(Locale.US, "%.6f", v)

    private fun side(s: SideBreakdown?): String {
        if (s == null) return "null"
        val vis = s.visibility.entries.joinToString(", ") { e ->
            "\"" + e.key + "\": " + String.format(Locale.US, "%.3f", e.value)
        }
        return "{\"value\": " + num(s.value) +
            ", \"raw\": " + num(s.raw) +
            ", \"scale\": " + num(s.scale) +
            ", \"visibility\": {" + vis + "}}"
    }

    private fun writeSetRecord() {
        val ctx = appContext ?: return
        val st = _state.value
        try {
            val dir = File(ctx.getExternalFilesDir(null), "sets")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

            val rows = latencies.joinToString(",\n") { l ->
                "    {\"code\": \"" + l.code + "\"" +
                    ", \"confirmed_ms\": " + l.confirmedMs +
                    ", \"selected_ms\": " + l.selectedMs +
                    ", \"dispatched_ms\": " + l.dispatchedMs +
                    ", \"policy_ms\": " + l.policyMs +
                    ", \"dispatch_ms\": " + l.dispatchMs +
                    ", \"total_ms\": " + l.totalMs + "}"
            }
            val faults = (session?.faultLog ?: emptyList()).joinToString(",\n") { o ->
                "    {\"code\": \"" + o.code + "\"" +
                    ", \"rep\": " + o.rep +
                    ", \"phase\": \"" + o.phase + "\"" +
                    ", \"since_ms\": " + o.sinceMs +
                    ", \"value\": " + num(o.value) +
                    ", \"threshold\": " + num(o.threshold) +
                    ", \"declared\": " + side(o.breakdown.declared) +
                    ", \"mirrored\": " + side(o.breakdown.mirrored) + "}"
            }

            val repRows = (session?.repLog ?: emptyList()).joinToString(",\n") { r ->
                val ext = r.extremes.entries.joinToString(", ") { e ->
                    "\"" + e.key + "\": " + num(e.value)
                }
                val raised = r.raised.joinToString(", ") { "\"" + it + "\"" }
                "    {\"rep\": " + r.rep +
                    ", \"raised\": [" + raised + "]" +
                    ", \"extremes\": {" + ext + "}}"
            }
            val repsWithFault = (session?.repLog ?: emptyList()).count { it.raised.isNotEmpty() }
            val suppressed = (session?.suppressedCounts ?: emptyMap()).entries
                .joinToString(", ") { "\"" + it.key + "\": " + it.value }

            // How far each continuous fault's measurement actually travelled.
            // A hold has no repetitions, so without this a set that raised
            // nothing carries no evidence of why it raised nothing.
            val ranges = (session?.measuredRange ?: emptyMap()).entries.joinToString(",\n") { e ->
                val code = e.key
                "    {\"code\": \"" + code + "\"" +
                    ", \"min\": " + num(e.value[0]) +
                    ", \"max\": " + num(e.value[1]) +
                    ", \"threshold\": " + num(session?.thresholdFor(code) ?: 0.0) +
                    ", \"measured_frames\": " + (session?.measuredFrames?.get(code) ?: 0) +
                    ", \"unmeasured_frames\": " + (session?.unmeasuredFrames?.get(code) ?: 0) + "}"
            }

            File(dir, "set-" + stamp + ".json").writeText(
                "{\n" +
                    "  \"written\": \"" + stamp + "\",\n" +
                    "  \"started_at\": \"" + setStartedAt + "\",\n" +
                    "  \"ended_at\": \"" + stamp + "\",\n" +
                    "  \"duration_s\": " +
                    String.format(Locale.US, "%.1f", setDurationS) + ",\n" +
                    "  \"exercise\": \"" + st.exerciseName.replace('"', '\'') + "\",\n" +
                    "  \"spec_id\": \"" + (spec?.id ?: "") + "\",\n" +
                    "  \"spec_version\": \"" + (spec?.version ?: "") + "\",\n" +
                    "  \"reps\": " + st.repCount + ",\n" +
                    "  \"hold_s\": " +
                    String.format(Locale.US, "%.1f", st.holdMs / 1000.0) + ",\n" +
                    "  \"reps_with_fault\": " + repsWithFault + ",\n" +
                    "  \"cues_spoken\": " + st.cuesSpoken + ",\n" +
                    "  \"suppressed\": {" + suppressed + "},\n" +
                    "  \"measured\": [\n" + ranges + "\n  ],\n" +
                    "  \"pose_rate\": " + String.format(Locale.US, "%.4f", st.poseRate) + ",\n" +
                    "  \"fps\": " + String.format(Locale.US, "%.2f", st.fps) + ",\n" +
                    "  \"note\": \"total_ms is confirmation to audio dispatch. The min_frames " +
                    "window before it and the platform path from play() to the first audible " +
                    "sample after it are both outside these figures. 11.5 governs.\",\n" +
                    "  \"faults_note\": \"Every fault raised, spoken or not, one entry per " +
                    "fault per repetition. 'declared' is the measurement over the landmarks " +
                    "the specification names and 'mirrored' is the same measurement over " +
                    "their opposites; a mean_of_sides fault fires on the average of the two. " +
                    "'mirrored' is null when the measurement is not mean_of_sides. A side " +
                    "whose value is null was below min_visibility and did not contribute.\",\n" +
                    "  \"reps_note\": \"One row per completed repetition. " +
                    "'extremes' is how far each fault's measurement went in " +
                    "the violating direction during the phases that fault is " +
                    "scoped to, whether or not it fired. A repetition that " +
                    "raised nothing and whose extreme is on the passing side " +
                    "was performed correctly; one that raised nothing and " +
                    "whose extreme is on the violating side was missed. " +
                    "Recall is computed against this, not against 'cues'.\",\n" +
                    "  \"reps_detail\": [\n" + repRows + "\n  ],\n" +
                    "  \"faults\": [\n" + faults + "\n  ],\n" +
                    "  \"cues\": [\n" + rows + "\n  ]\n" +
                    "}\n"
            )
        } catch (_: Exception) {
            // See above. Losing the file is bad; losing the set is worse.
        }
    }

    fun reset() {
        teardown()
        _state.value = AppState()
    }

    private fun teardown() {
        engine?.close()
        engine = null
        player?.release()
        player = null
        narrator = null
        guidance = emptyMap()
        appContext = null
        session = null
        spec = null
    }

    override fun onCleared() {
        tones.release()
        super.onCleared()
        teardown()
    }

    // ---- counters --------------------------------------------------------

    private fun countFrame(found: Boolean, timestampMs: Long) {
        if (framesSeen == 0) firstFrameMs = timestampMs
        framesSeen++
        if (found) posesSeen++
    }

    companion object {
        /**
         * The shared spoken lines, packaged from docs/specs like every other
         * specification. Named here rather than passed in because there is
         * exactly one of them and no caller could sensibly choose another.
         */
        private const val GUIDANCE_ASSET = "guidance.json"
    }

    private fun resetCounters() {
        framesSeen = 0
        posesSeen = 0
        firstFrameMs = 0L
    }

    private fun poseRate(): Double =
        if (framesSeen > 0) posesSeen.toDouble() / framesSeen else -1.0

    private fun fps(): Double {
        val elapsed = (SystemClock.uptimeMillis() - firstFrameMs) / 1000.0
        return if (elapsed > 0.5) framesSeen / elapsed else 0.0
    }

}
