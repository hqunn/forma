package ai.forma.app

import android.os.SystemClock

/**
 * Speaks the stages before a set.
 *
 * Chapter 1.3.1 puts the user two to four metres from the phone and side on
 * to it, and 4.1 points the rear cameras at them, which puts the display
 * face the other way. Before a set, speech is not an addition to the
 * interface. It is the whole of it, and this class is the only thing standing
 * between a stage change and a user who has no idea it happened.
 *
 * **One line per transition.** [CuePlayer] is a [android.media.SoundPool],
 * which mixes rather than queues: a second line started while the first is
 * speaking produces two voices, not a sequence. Every caller therefore says
 * exactly one thing per stage change, and the lines are written so that one is
 * enough. Nothing here is called from [Stage.SET] onward, where the cue path
 * owns the channel and NFR-P1 is measured through it.
 */
class Narrator(
    private val player: CuePlayer,
    private val tones: StageTones,
) {

    private var lastAtMs = 0L

    // When the voice goes quiet again. A caller that judges the user has to
    // know this: the squat depth instruction runs 3304 ms and the hold it
    // asks for is 1500 ms, so a stage that starts scoring when the line
    // starts is scoring a user who has not yet heard what to do.
    private var quietFromMs = 0L

    /**
     * Says [asset] once.
     *
     * Returns false when nothing was spoken, which happens in two different
     * situations that are deliberately handled differently. Before the bank
     * has finished decoding there is nothing to play and nothing to report:
     * the clock is left alone so that the caller tries again on the next
     * frame and the line arrives a moment late rather than never. After the
     * bank is ready, a line that will not play is a defect in the bank, and
     * [fallback] exists so that a user is not left in silence by it.
     */
    fun say(asset: String?, fallback: (() -> Unit)? = null): Boolean {
        if (asset != null) {
            if (player.play(asset) != null) {
                lastAtMs = SystemClock.uptimeMillis()
                quietFromMs = lastAtMs + player.durationMs(asset)
                return true
            }
            if (!player.isReady) return false
        }
        lastAtMs = SystemClock.uptimeMillis()
        // A tone is short enough that nothing needs to wait for it.
        quietFromMs = lastAtMs
        fallback?.invoke()
        return false
    }

    /**
     * True while a line is still being spoken.
     *
     * Exists because a spoken instruction takes longer to say than the hold
     * it asks for takes to perform, so a stage that judges the user from the
     * moment it starts talking judges them before they have been told
     * anything. Derived from the generated bank manifest, which records the
     * length of every line it synthesised.
     */
    val isSpeaking: Boolean get() = SystemClock.uptimeMillis() < quietFromMs

    /**
     * True when nothing has been spoken for [ms].
     *
     * Only framing uses this. It is the one stage with no event to trigger on:
     * the user is walking backwards and the application has nothing to say
     * except the same sentence again, so it says it on a timer until the
     * framing is satisfied. Every other stage speaks on a transition.
     */
    fun silentFor(ms: Long): Boolean = SystemClock.uptimeMillis() - lastAtMs >= ms

    /** Lets the next line speak immediately, whatever the clock says. */
    fun reset() {
        lastAtMs = 0L
        quietFromMs = 0L
    }

    companion object {
        /**
         * How often the framing line repeats while framing is unsatisfied.
         *
         * The longest framing line in the bank is 7327 ms, measured at
         * generation time, so this leaves about 4.7 seconds of quiet to move
         * in. Shorter and the application talks over the user's attempt to do
         * what it asked.
         */
        const val FRAMING_REPEAT_MS = 12_000L
    }
}
