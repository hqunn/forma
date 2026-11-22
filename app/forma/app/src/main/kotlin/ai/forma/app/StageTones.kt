package ai.forma.app

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short tones for the stages before the set starts.
 *
 * The interface is on a phone two to four metres away, and 1.3.1 asks the
 * user to stand side on to it. From there a 12sp status line is not
 * readable, so an application that reports framing and calibration only on
 * screen is reporting them to nobody. The benchmark harness hit this first
 * and answered it the same way; the proof of concept did not inherit the
 * answer, and this is it.
 *
 * These are tones rather than speech on purpose. Everything the user is
 * **told** comes from the specification, through the pre-authored bank that
 * 4.4 chose, and is spoken by [CuePlayer]. These say only that something
 * has happened, which is a different job: the user already knows what the
 * sequence is, and what they cannot tell from across the room is where in it
 * they are. Replacing them with spoken guidance is a Chapter 9 decision and
 * is recorded as open.
 *
 * Nothing here plays during a set. The cue path owns the audio from
 * [Stage.SET] onward, and NFR-P1 is measured through it.
 */
class StageTones {

    // May fail if the device cannot allocate the stream. A benchmark that
    // cannot beep is still a benchmark; a crash here would lose the session.
    private val gen: ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    }.getOrNull()

    private fun play(tone: Int, ms: Int) {
        runCatching { gen?.startTone(tone, ms) }
    }

    /** Framing is satisfied. Stop moving; the next instruction is coming. */
    fun framed() = play(ToneGenerator.TONE_PROP_BEEP2, 350)

    /**
     * A calibration measurement has started and the user should now be doing
     * whatever it asked for. The measures run in the order the specification
     * lists them, so counting these is how the user knows which one they are
     * in without reading anything.
     */
    fun holdNow() = play(ToneGenerator.TONE_PROP_BEEP, 500)

    /** The hold was accepted and recorded. */
    fun accepted() = play(ToneGenerator.TONE_PROP_ACK, 300)

    /** The hold was rejected and is about to be repeated. */
    fun rejected() = play(ToneGenerator.TONE_PROP_NACK, 450)

    /** Calibration is complete and the set can start. */
    fun ready() = play(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)

    fun release() {
        runCatching { gen?.release() }
    }
}
