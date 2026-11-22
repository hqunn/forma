package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shipped guidance file, loaded the way the application loads it.
 *
 * [SpecLoader] has been held against the real exercise files since the
 * specifications existed; this holds [GuidanceLoader] against the real
 * guidance file for the same reason. Without it, the loader's required list
 * and the file it validates can disagree, and the first thing that reports
 * the disagreement is the handset.
 */
class GuidanceLoaderTest {

    @Test
    fun `the shipped guidance file loads`() {
        Specs.guidance()
    }

    @Test
    fun `every required code resolves to a line`() {
        val byCode = Specs.guidance()
        for (code in GuidanceLoader.required) {
            assertTrue(code in byCode, "guidance.json has no line for $code")
        }
    }

    @Test
    fun `every required code names an asset in the cue bank`() {
        val bank = java.io.File("../cues")
            .listFiles { f -> f.name.endsWith(".wav") }
            .orEmpty()
            .map { it.name }
            .toSet()
        assertTrue(bank.isNotEmpty(), "no cue bank found beside the module")
        for (code in GuidanceLoader.required) {
            val asset = Specs.guidance().getValue(code).asset
            assertTrue(asset in bank, "$code names '$asset', which is not in the cue bank")
        }
    }
}
