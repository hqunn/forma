package ai.forma.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

enum class Phase { IDLE, WARMUP, FRAMING, MEASURE, COOLDOWN, WRITING, DONE, ERROR }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val configLabel: String = "-",
    val configIndex: Int = 0,
    val configTotal: Int = 0,
    val phaseRemainingS: Int = 0,
    val frames: Int = 0,
    val liveFps: Double = 0.0,
    /** Fraction of frames so far in this phase with a pose found. -1 before any. */
    val poseRate: Double = -1.0,
    val lastInferMs: Long = 0,
    val thermal: Int = 0,
    val batteryC: Float = 0f,
    val message: String = "Ready.",
    val outputPath: String? = null,
)

/**
 * Audible phase markers.
 *
 * The product framing puts the rear camera on the subject, so the screen faces
 * away and the phase cannot be read from where the subject has to stand.
 * Without a cue the subject must stand through the cooldowns as well, and the
 * resulting fatigue would land on whichever configurations run last - an
 * artefact that would be read as a property of the model rather than of the
 * person. Each tone fires once, at a phase boundary, on the audio thread.
 */
private class PhaseTones {

    private val tone = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    }.getOrNull()

    /** Warm-up has begun: five seconds to get into position. */
    fun getReady() = play(ToneGenerator.TONE_PROP_BEEP2, 400)

    /**
     * Not in shot. Repeats while the run waits for the subject.
     *
     * Deliberately short and unlike every other marker here: it is the only
     * one that asks for an action rather than reporting a change, and it is
     * the only one the subject hears more than once.
     */
    fun notInFrame() = play(ToneGenerator.TONE_PROP_ACK, 150)

    /** Recording, and the subject is in shot. Stand still. */
    fun holdStill() = play(ToneGenerator.TONE_PROP_BEEP, 700)

    /** Cooling down. Nothing is recorded, so step out if you want to. */
    fun relax() = play(ToneGenerator.TONE_PROP_NACK, 500)

    /** The run is over. */
    fun done() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1200)

    private fun play(type: Int, ms: Int) {
        runCatching { tone?.startTone(type, ms) }
    }

    fun close() {
        runCatching { tone?.release() }
    }
}

class BenchmarkViewModel : ViewModel() {

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    @Volatile private var engine: PoseEngine? = null
    @Volatile private var recording = false

    // Counted for the status panel, not for the CSV, which derives pose_rate
    // from the frame rows. On screen it is the only way to tell from the
    // handset alone whether the camera is reaching the model the right way up:
    // reading it out of the CSV needs adb and therefore the build machine.
    // The rotation patch in app/benchmark/patches is validated by this number.
    @Volatile private var poseSeen = 0
    @Volatile private var poseTotal = 0

    // The last moment each requirement was *not* met, so that "has been
    // satisfied continuously for N ms" is a subtraction rather than a
    // history. Separate because they call for different corrections: no pose
    // at all means step into shot, a pose without knees means the feet are
    // cut off or too dim, and telling a user the wrong one wastes the run.

    @Volatile private var lastResultAtMs = 0L
    @Volatile private var lastPoseMissAtMs = 0L
    @Volatile private var lastKneeMissAtMs = 0L

    private val tones = PhaseTones()
    private val rows = ConcurrentLinkedQueue<FrameRow>()
    private val prepByTimestamp = ConcurrentHashMap<Long, Long>()
    private var runStartUptime = 0L
    private var job: Job? = null

    // ---- camera side -----------------------------------------------------

    /**
     * Called from the CameraX analyzer thread for every delivered frame.
     * [prepMs] is the cost of producing [bitmap] from the ImageProxy, which is
     * part of the per-frame budget and is reported separately so the budget in
     * Chapter 4 can be split between preparation and inference.
     *
     * [rotationDegrees] travels with the frame instead of being applied to it,
     * so preparation no longer carries a software rotate. 4.2 F11 has the
     * measurement that prompted the change.
     */
    fun onFrame(bitmap: Bitmap, prepMs: Long, rotationDegrees: Int) {
        val e = engine ?: return
        val ts = SystemClock.uptimeMillis()
        prepByTimestamp[ts] = prepMs
        val accepted = runCatching { e.submit(bitmap, ts, rotationDegrees) }.getOrDefault(false)
        if (!accepted) prepByTimestamp.remove(ts)
    }

    private fun onReading(context: Context, r: PoseReading) {
        val ts = r.timestampMs
        val prep = prepByTimestamp.remove(ts) ?: 0L
        val inferMs = SystemClock.uptimeMillis() - ts
        val found = r.found

        val row = FrameRow(
            elapsedMs = SystemClock.uptimeMillis() - runStartUptime,
            prepMs = prep,
            inferMs = inferMs,
            poseFound = found,
            kneeLVis = r.leftVis,
            kneeRVis = r.rightVis,
            kneeLX = r.leftX,
            kneeLY = r.leftY,
            kneeRX = r.rightX,
            kneeRY = r.rightY,
            thermal = thermalStatus(context),
            batteryC = batteryTemp(context),
        )

        if (recording) rows.add(row)

        lastResultAtMs = SystemClock.uptimeMillis()
        if (!found) lastPoseMissAtMs = lastResultAtMs
        if (!found || row.kneeLVis < KNEE_VIS || row.kneeRVis < KNEE_VIS) {
            lastKneeMissAtMs = lastResultAtMs
        }

        poseTotal++
        if (found) poseSeen++

        val first = rows.peek()
        val n = rows.size
        val elapsed = if (first != null) (row.elapsedMs - first.elapsedMs) / 1000.0 else 0.0
        _state.value = _state.value.copy(
            frames = n,
            liveFps = if (elapsed > 0.5) n / elapsed else 0.0,
            poseRate = if (poseTotal > 0) poseSeen.toDouble() / poseTotal else -1.0,
            lastInferMs = inferMs,
            thermal = row.thermal,
            batteryC = row.batteryC,
        )
    }

    // ---- device sensors --------------------------------------------------

    private fun thermalStatus(context: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            runCatching { pm.currentThermalStatus }.getOrDefault(-1)
        } else -1

    private fun batteryTemp(context: Context): Float {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return if (tenths < 0) -1f else tenths / 10f
    }

    // ---- runs ------------------------------------------------------------

    /**
     * Mode A. Sixty seconds per configuration, warm-up discarded, with a
     * cooldown gate between configurations so that configuration six is not
     * measured on a device heated by configurations one to five. Produces the
     * latency and frame-rate columns of the 4.2 table.
     */
    fun runSweep(context: Context) {
        val ctx = context.applicationContext
        job?.cancel()
        job = viewModelScope.launch {
            val dir = newRunDir(ctx, "sweep")
            val summaries = mutableListOf<Summary>()

            // Stopping after the configurations in question is a reasonable
            // thing to do - six of these take upwards of ten minutes of
            // standing still - so the summary is written on the way out as
            // well as at the end.
            try {
                ALL_CONFIGS.forEachIndexed { index, config ->
                    _state.value = _state.value.copy(
                        configLabel = config.label,
                        configIndex = index + 1,
                        configTotal = ALL_CONFIGS.size,
                    )
                    val summary = measureOne(ctx, config, WARMUP_MS, SWEEP_MEASURE_MS, dir)
                    summaries.add(summary)
                    if (index < ALL_CONFIGS.lastIndex) cooldown(ctx)
                }
                _state.value = _state.value.copy(phase = Phase.WRITING, message = "Writing CSV.")
            } finally {
                writeSummary(dir, summaries)
            }

            val ran = summaries.count { it.available }
            finish(dir, "Sweep complete. " + ran + " of " + summaries.size + " configurations ran.")
        }
    }

    /**
     * One configuration, for the same sixty seconds Mode A would give it.
     *
     * 4.2 is Accepted and the variant is settled, so re-running six
     * configurations to check one of them re-argues a decision instead of
     * testing a build. Everything that is not the ADR table wants this:
     * confirming a patch, reproducing a baseline, answering one question.
     */
    fun runSingle(context: Context, config: Config) {
        val ctx = context.applicationContext
        job?.cancel()
        job = viewModelScope.launch {
            val dir = newRunDir(ctx, "single-" + config.label)
            _state.value = _state.value.copy(
                configLabel = config.label, configIndex = 1, configTotal = 1,
            )
            val summaries = mutableListOf<Summary>()
            try {
                summaries.add(measureOne(ctx, config, WARMUP_MS, SWEEP_MEASURE_MS, dir))
                _state.value = _state.value.copy(phase = Phase.WRITING, message = "Writing CSV.")
            } finally {
                writeSummary(dir, summaries)
            }
            finish(dir, "Measured " + config.label + " for 60 seconds.")
        }
    }

    /**
     * Mode B. One configuration held for ten minutes without interruption.
     * This is the thermal question, and it is the one a short run cannot answer:
     * the number that matters is the frame rate in the last bucket, not the mean.
     */
    fun runEndurance(context: Context, config: Config) {
        val ctx = context.applicationContext
        job?.cancel()
        job = viewModelScope.launch {
            val dir = newRunDir(ctx, "endurance-" + config.label)
            _state.value = _state.value.copy(
                configLabel = config.label, configIndex = 1, configTotal = 1,
            )
            val summary = measureOne(ctx, config, WARMUP_MS, ENDURANCE_MEASURE_MS, dir)
            val captured = rows.toList()

            _state.value = _state.value.copy(phase = Phase.WRITING, message = "Writing CSV.")
            withContext(Dispatchers.IO) {
                File(dir, "summary.csv").writeText(
                    listOf(Summary.HEADER, summary.toCsv()).joinToString("\n")
                )
                val lines = mutableListOf("elapsed_s,fps")
                Metrics.buckets(captured).forEach { (sec, fps) ->
                    lines.add(sec.toString() + "," + String.format(Locale.US, "%.2f", fps))
                }
                File(dir, "buckets.csv").writeText(lines.joinToString("\n"))
            }
            finish(dir, "Endurance run complete.")
        }
    }

    private suspend fun measureOne(
        context: Context,
        config: Config,
        warmupMs: Long,
        measureMs: Long,
        dir: File,
    ): Summary {
        rows.clear()
        poseSeen = 0
        poseTotal = 0
        prepByTimestamp.clear()
        recording = false
        runStartUptime = SystemClock.uptimeMillis()

        val errors = ConcurrentLinkedQueue<String>()
        val created = runCatching {
            createEngine(
                context = context,
                config = config,
                onReading = { r -> onReading(context, r) },
                onError = { msg -> errors.add(msg) },
            )
        }

        val e = created.getOrNull()
        if (e == null) {
            val reason = created.exceptionOrNull()?.message ?: "could not create engine"
            _state.value = _state.value.copy(message = config.label + " unavailable: " + reason)
            return Summary.unavailable(config.label, reason)
        }

        engine = e
        var framed = true
        try {
            tones.getReady()
            countdown(Phase.WARMUP, warmupMs, "Warming up " + config.label)

            // Nothing is recorded until there is something to record.
            framed = waitForSubject(config.label)

            rows.clear()
            poseSeen = 0
            poseTotal = 0
            runStartUptime = SystemClock.uptimeMillis()
            recording = true
            tones.holdStill()
            countdown(Phase.MEASURE, measureMs, "Measuring " + config.label)
        } finally {
            recording = false
            engine = null
            e.close()
            // Written here rather than after, so that a configuration stopped
            // part way through is a short measurement instead of a lost one.
            // A stop during warm-up or framing has captured nothing and
            // writes nothing, which is why the empty run directories of
            // 2026-11-04 were empty.
            writeFrames(dir, config, rows.toList())
        }

        val captured = rows.toList()
        val s = Metrics.summarise(config.label, captured)
        val note = when {
            errors.isNotEmpty() -> errors.first()
            !framed -> "framing gate timed out; measured without a confirmed subject"
            else -> ""
        }
        return s.copy(note = note)
    }

    /**
     * Writes the frames of one configuration, including when the run is being
     * stopped. `NonCancellable` because the caller is a `finally` on a job
     * that may already be cancelled, and a suspending write there would
     * otherwise be dropped on the floor.
     */
    private suspend fun writeFrames(dir: File, config: Config, captured: List<FrameRow>) {
        if (captured.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            File(dir, "frames-" + config.label + ".csv").writeText(
                (listOf(FrameRow.HEADER) + captured.map { it.toCsv() }).joinToString("\n")
            )
        }
    }

    /**
     * Writes the sweep summary for the configurations that finished.
     *
     * Every other column of `Summary` is a function of the frames CSV and can
     * be recomputed from it. `note` cannot, and since the framing gate it is
     * where a configuration records that it measured without a confirmed
     * subject. Losing it on a stop would mean keeping the numbers and throwing
     * away the one field that says whether to trust them.
     */
    private suspend fun writeSummary(dir: File, summaries: List<Summary>) {
        if (summaries.isEmpty()) return
        withContext(NonCancellable + Dispatchers.IO) {
            File(dir, "summary.csv").writeText(
                (listOf(Summary.HEADER) + summaries.map { it.toCsv() }).joinToString("\n")
            )
        }
    }

    /**
     * Holds the run until the subject is in shot, with both knees.
     *
     * The subject stands where the rear camera can see them, which puts the
     * display on the far side of the phone. Every reading that would tell
     * them they are not in frame is on a screen they cannot see, and on
     * 2026-11-04 that cost three of four configurations: one recorded a full
     * sixty seconds of an empty room at a pose rate of 0.0066, and the
     * subject had no way to know until the files were pulled the next day.
     *
     * Knees are part of the condition rather than a pose alone, because the
     * knee landmarks are what the geometry comparison in 4.2 rests on and
     * a pose whose feet are out of shot cannot supply them. A run of that
     * kind looks healthy on `pose_rate` and is worthless.
     *
     * Capped for the same reason the cooldown is: an uncapped wait turns an
     * unattended sweep into a hang. If the cap is reached the run proceeds
     * and the summary's note column says the subject was never confirmed, so
     * the resulting row can never be read as a clean measurement.
     *
     * @return true if the subject was confirmed, false if the cap was reached.
     */
    private suspend fun waitForSubject(label: String): Boolean {
        val now0 = SystemClock.uptimeMillis()
        lastPoseMissAtMs = now0
        lastKneeMissAtMs = now0
        val end = now0 + FRAMING_MAX_MS
        var lastTickMs = 0L

        while (SystemClock.uptimeMillis() < end) {
            val now = SystemClock.uptimeMillis()
            val live = now - lastResultAtMs < STALE_MS
            val hasPose = live && now - lastPoseMissAtMs >= STEADY_MS
            val hasKnees = live && now - lastKneeMissAtMs >= STEADY_MS

            if (hasPose && hasKnees) return true

            if (now - lastTickMs >= TICK_MS) {
                tones.notInFrame()
                lastTickMs = now
            }
            _state.value = _state.value.copy(
                phase = Phase.FRAMING,
                message = when {
                    !live -> "Waiting for the camera - " + label
                    !hasPose -> "Step into shot - " + label
                    else -> "Feet need to be in shot - " + label
                },
                phaseRemainingS = ((end - now) / 1000).toInt(),
            )
            delay(200)
        }
        return false
    }

    private suspend fun countdown(phase: Phase, durationMs: Long, message: String) {
        val end = SystemClock.uptimeMillis() + durationMs
        while (SystemClock.uptimeMillis() < end) {
            _state.value = _state.value.copy(
                phase = phase,
                message = message,
                phaseRemainingS = ((end - SystemClock.uptimeMillis()) / 1000).toInt(),
            )
            delay(250)
        }
    }

    /**
     * Wait for the device to come back down to a light thermal state. Capped,
     * because an uncapped wait would silently turn a twelve-minute sweep into
     * an hour; if the cap is hit the run continues, and the thermal column on
     * the following configuration records that it started warm.
     */
    private suspend fun cooldown(context: Context) {
        tones.relax()
        val end = SystemClock.uptimeMillis() + COOLDOWN_MAX_MS
        while (SystemClock.uptimeMillis() < end) {
            val t = thermalStatus(context)
            if (t in 0..THERMAL_LIGHT) break
            _state.value = _state.value.copy(
                phase = Phase.COOLDOWN,
                message = "Cooling down, thermal status " + t,
                phaseRemainingS = ((end - SystemClock.uptimeMillis()) / 1000).toInt(),
            )
            delay(2000)
        }
        // A floor even when the device reports cool, so consecutive runs are alike.
        countdown(Phase.COOLDOWN, COOLDOWN_MIN_MS, "Cooling down")
    }

    private fun finish(dir: File, message: String) {
        tones.done()
        _state.value = _state.value.copy(
            phase = Phase.DONE,
            message = message,
            outputPath = dir.absolutePath,
            phaseRemainingS = 0,
        )
    }

    private fun newRunDir(context: Context, tag: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), tag + "-" + stamp)
        dir.mkdirs()
        return dir
    }

    fun cancel() {
        job?.cancel()
        recording = false
        engine?.close()
        engine = null
        _state.value = _state.value.copy(phase = Phase.IDLE, message = "Cancelled.")
    }

    override fun onCleared() {
        super.onCleared()
        engine?.close()
        tones.close()
    }

    companion object {
        const val WARMUP_MS = 5_000L
        const val SWEEP_MEASURE_MS = 60_000L
        const val ENDURANCE_MEASURE_MS = 600_000L
        const val COOLDOWN_MIN_MS = 20_000L
        const val COOLDOWN_MAX_MS = 180_000L
        const val THERMAL_LIGHT = 1

        /** Matches the visibility floor Metrics uses, so the gate and the
         *  figure it protects agree about what a visible knee is. */
        const val KNEE_VIS = 0.5f

        /** How long the subject must be continuously in shot before the
         *  measurement starts. Long enough that walking through the frame
         *  does not open the gate. */
        const val STEADY_MS = 1_500L

        /** Beyond this with no result at all, the camera is the problem
         *  rather than the subject, and the message says so. */
        const val STALE_MS = 1_000L

        /** How often the "not in shot" marker repeats while waiting. */
        const val TICK_MS = 1_500L

        /** Cap on the wait. Reaching it is recorded in the summary note. */
        const val FRAMING_MAX_MS = 120_000L
    }
}
