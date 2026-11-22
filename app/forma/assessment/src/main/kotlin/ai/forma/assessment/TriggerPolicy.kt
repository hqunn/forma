package ai.forma.assessment

/** A cue the policy has decided the user should hear. */
data class SpokenCue(
    val code: String,
    val asset: String,
    val text: String,
    val atMs: Long,
)

/**
 * Decides which raised fault, if any, becomes audible.
 *
 * **Silence is a valid output and is the common one.** A system that speaks on
 * every violated constraint is unusable inside a set: the user cannot act on
 * two corrections at once, and a cue they cannot act on teaches them to ignore
 * the next one.
 *
 * Five constraints apply, and a candidate must satisfy all of them:
 *
 * - `confidence_floor`, below which a reading is not trusted enough to speak;
 * - `debounce_ms`, the time a fault must persist before it is worth saying;
 * - `min_gap_ms`, the silence owed after any cue;
 * - `max_cues_per_set`, the budget for a whole set;
 * - `max_consecutive_same_cue`, after which repeating is nagging rather than coaching.
 *
 * When several faults survive, `priority` decides. The others are recorded and
 * not voiced.
 */
class TriggerPolicy(private val spec: ExerciseSpec) {

    private val policy = spec.trigger
    private val rank = policy.priority.withIndex().associate { (i, code) -> code to i }

    private var lastCueAtMs: Long? = null
    private var lastCode: String? = null
    private var consecutiveSame = 0
    private var spokenThisSet = 0

    /** When each code was last seen raised, for the reset cooldown. */
    private val lastRaisedAtMs = mutableMapOf<String, Long>()

    /** All faults suppressed since the last call, with why. Useful for a session record. */
    val suppressed = mutableListOf<Pair<String, String>>()

    fun select(raised: List<RaisedFault>, nowMs: Long, confidence: Double = 1.0): SpokenCue? {
        suppressed.clear()

        // A fault that has been absent for the cooldown is a new episode, so
        // the "already said twice" counter starts again for it. Done before
        // the gates below, because a candidate must not be rejected for
        // repetition it is no longer guilty of.
        for (f in raised) {
            val seen = lastRaisedAtMs[f.code]
            if (f.code == lastCode && seen != null &&
                nowMs - seen >= policy.sameCueResetMs
            ) {
                consecutiveSame = 0
            }
            lastRaisedAtMs[f.code] = nowMs
        }

        if (raised.isEmpty()) return null

        if (confidence < policy.confidenceFloor) {
            raised.forEach { suppressed += it.code to "confidence below floor" }
            return null
        }
        val cap = policy.maxCuesPerSet
        if (cap != null && spokenThisSet >= cap) {
            raised.forEach { suppressed += it.code to "set cue budget exhausted" }
            return null
        }
        lastCueAtMs?.let { last ->
            if (nowMs - last < policy.minGapMs) {
                raised.forEach { suppressed += it.code to "inside minimum gap" }
                return null
            }
        }

        val ordered = raised.sortedBy { rank[it.code] ?: Int.MAX_VALUE }
        for (fault in ordered) {
            if (nowMs - fault.sinceMs < policy.debounceMs) {
                suppressed += fault.code to "below debounce"
                continue
            }
            val sameLimit = policy.maxConsecutiveSameCue
            if (sameLimit != null && fault.code == lastCode && consecutiveSame >= sameLimit) {
                suppressed += fault.code to "already said twice"
                continue
            }
            val cue = spec.cues[fault.code] ?: continue

            consecutiveSame = if (fault.code == lastCode) consecutiveSame + 1 else 1
            lastCode = fault.code
            lastCueAtMs = nowMs
            spokenThisSet++

            ordered.filter { it !== fault }.forEach { suppressed += it.code to "lower priority" }
            return SpokenCue(fault.code, cue.asset, cue.text, nowMs)
        }
        return null
    }

    val cuesSpokenThisSet get() = spokenThisSet

    fun resetSet() {
        lastRaisedAtMs.clear()
        lastCueAtMs = null
        lastCode = null
        consecutiveSame = 0
        spokenThisSet = 0
        suppressed.clear()
    }
}
