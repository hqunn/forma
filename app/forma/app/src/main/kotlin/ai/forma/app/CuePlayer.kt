package ai.forma.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Plays the pre-authored cue bank.
 *
 * This is the far end of 4.4, and it is deliberately the
 * dullest file in the application. A fault code is a map key, the value is a
 * sound already decoded and resident in memory, and the only work at fault
 * time is a lookup and a call to [SoundPool.play]. There is no synthesis, no
 * file read, no decode and no network call anywhere in the live path, which
 * is how 4.1's audio allocation is met by construction rather than by tuning.
 *
 * Everything expensive happens in [preload], before the set begins.
 */
class CuePlayer(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        // The trigger policy already guarantees one cue at a time, through
        // min_gap_ms and max_cues_per_set. Two streams are allowed so that a
        // policy change cannot truncate a cue mid-word as a side effect.
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Speech on the media stream. It is what the user turns up
                // when they start a set, and routing a form correction to
                // the notification stream would let Do Not Disturb silence
                // the only output this product has.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val soundIdByAsset = mutableMapOf<String, Int>()
    private val loaded = mutableSetOf<Int>()

    // How long each line takes to say. SoundPool will not tell us, and the
    // caller needs it: a stage that is judged while its own instruction is
    // still being spoken judges a user who has not yet heard it.
    private val durationByAsset = mutableMapOf<String, Long>()

    @Volatile
    private var failures = 0

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                synchronized(loaded) { loaded.add(sampleId) }
            } else {
                failures++
                Log.e(TAG, "sample $sampleId failed to load, status $status")
            }
        }
    }

    /**
     * Decodes every cue the loaded specifications name, before the set starts.
     *
     * Loading is asynchronous, so [isReady] must be true before a set is
     * allowed to begin. Starting a set with a half-loaded bank would produce
     * silence on exactly the faults whose files happened to be last in the
     * queue, which would look like a rule that does not fire rather than a
     * cue that did not load.
     */
    fun preload(assets: Collection<String>) {
        readDurations()
        for (asset in assets.distinct()) {
            if (soundIdByAsset.containsKey(asset)) continue
            try {
                // openFd requires the asset to be stored uncompressed. The
                // `noCompress` list in build.gradle.kts includes wav for this
                // reason; without it this throws at run time and every cue is
                // silent.
                context.assets.openFd(asset).use { fd ->
                    soundIdByAsset[asset] = pool.load(fd, 1)
                }
            } catch (e: Exception) {
                failures++
                Log.e(TAG, "could not open cue asset '$asset'", e)
            }
        }
    }

    /**
     * Reads the generated bank manifest, which records the length of every
     * line it synthesised.
     *
     * Deliberately read from the manifest rather than computed from the file,
     * because the generator is what knows the format, and a wrong constant
     * here would be silent. A line missing from the manifest returns
     * [UNKNOWN_DURATION_MS], which is long enough to cover any line in the
     * bank, so an absent entry makes the product wait rather than interrupt.
     */
    private fun readDurations() {
        if (durationByAsset.isNotEmpty()) return
        try {
            val text = context.assets.open(MANIFEST).bufferedReader().use { it.readText() }
            val cues = JSONObject(text).getJSONArray("cues")
            for (i in 0 until cues.length()) {
                val o = cues.getJSONObject(i)
                durationByAsset[o.getString("asset")] = o.optLong("duration_ms", UNKNOWN_DURATION_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not read '" + MANIFEST + "'; every line will be treated as long", e)
        }
    }

    /** How long [asset] takes to say, erring long when it is not known. */
    fun durationMs(asset: String): Long =
        durationByAsset[asset] ?: UNKNOWN_DURATION_MS

    val expected get() = soundIdByAsset.size
    val ready get() = synchronized(loaded) { loaded.size }
    val failedCount get() = failures

    /** True once every cue the specifications name is decoded and resident. */
    val isReady: Boolean
        get() = soundIdByAsset.isNotEmpty() && ready == expected

    /**
     * Starts a cue and returns the monotonic reading taken at dispatch, or
     * null if the asset is not loaded.
     *
     * **What this timestamp is, and what it is not.** It is the instant
     * [SoundPool.play] was called. It is not the instant the first audible
     * sample leaves the speaker: the mixer, the audio HAL and the output
     * buffer all sit between the two and none of them is observable from
     * here. The cue bank's own leading silence was measured and trimmed to a
     * uniform 7 ms at generation time, so that term is known; the platform
     * term is not, and NFR-P1 measurements taken with this clock are a lower
     * bound. Chapter 11.5 records it as such rather than letting a figure
     * that excludes it be read as end to end.
     */
    fun play(asset: String): Long? {
        val id = soundIdByAsset[asset] ?: return null
        synchronized(loaded) { if (id !in loaded) return null }
        val atMs = SystemClock.uptimeMillis()
        pool.play(id, 1f, 1f, 1, 0, 1f)
        return atMs
    }

    fun release() {
        pool.release()
        soundIdByAsset.clear()
        synchronized(loaded) { loaded.clear() }
    }

    companion object {
        private const val TAG = "CuePlayer"
        private const val MANIFEST = "manifest.json"

        /**
         * Assumed length of a line the manifest does not describe. The
         * longest in the bank is 7327 ms, so this cannot under-wait.
         */
        const val UNKNOWN_DURATION_MS = 8000L
    }
}
