package ai.forma.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecLoaderTest {

    // ------------------------------------------------- the real files load

    @Test
    fun `all three MVP specifications load`() {
        val specs = Specs.all()
        assertEquals(listOf("bodyweight-squat", "push-up", "plank"), specs.map { it.id })
    }

    @Test
    fun `squat declares two faults, two exclusions and calibrates nothing`() {
        val s = Specs.squat()
        assertEquals(
            listOf("SQ_DEPTH_INSUFFICIENT", "SQ_FORWARD_LEAN"),
            s.faults.map { it.code },
        )
        assertEquals(listOf("SQ_KNEE_VALGUS", "SQ_HEEL_LIFT"), s.excludedFaults.map { it.code })
        assertNull(s.calibration, "every squat threshold became absolute on 2026-11-04")
        assertTrue(s.requiredCalibrationIds.isEmpty())
    }

    @Test
    fun `plank reports a duration and needs no calibration`() {
        val s = Specs.plank()
        assertTrue(s.repDriver.reports.isDuration)
        assertEquals("holding", s.repDriver.reports.phase)
        assertNull(s.calibration, "a plank's thresholds are all absolute, so it calibrates nothing")
        assertTrue(s.requiredCalibrationIds.isEmpty())
    }

    @Test
    fun `an excluded fault keeps its citation, so the exclusion cannot be misread as missing evidence`() {
        val valgus = Specs.squat().excludedFaults.single { it.code == "SQ_KNEE_VALGUS" }
        assertNotNull(valgus.taxonomySource)
        assertTrue(valgus.taxonomySource!!.contains("Cook"))
    }

    /**
     * The claim in Chapter 7.1 and 11.5, as an assertion rather than a
     * sentence. If a specification is edited so that the count changes, this
     * fails and the report is corrected with it.
     */
    @Test
    fun `five of the six declared faults carry a taxonomy source`() {
        val faults = Specs.all().flatMap { it.faults }
        assertEquals(6, faults.size, "the MVP declares six faults across three exercises")

        val sourced = faults.filter { it.provenance.taxonomySourced }
        assertEquals(5, sourced.size, "sourced: ${sourced.map { it.code }}")

        assertEquals(
            listOf("PU_HIP_PIKE"),
            faults.filterNot { it.provenance.taxonomySourced }.map { it.code },
        )
    }

    /**
     * Each exercise carries the same number of live faults, and each records
     * the ones it does not implement. An absence with no reason beside it is
     * indistinguishable from an oversight, so the count of exclusions is
     * asserted as well as the count of faults.
     */
    @Test
    fun `every exercise declares two faults and records what it excludes`() {
        for (spec in Specs.all()) {
            assertEquals(2, spec.faults.size, "${spec.id} live faults")
            assertTrue(
                spec.excludedFaults.isNotEmpty(),
                "${spec.id} records no excluded fault",
            )
        }
    }

    /**
     * Also from 11.5, and the cleanest finding of the taxonomy search: every
     * source located establishes *that* a deviation is a fault and gives an
     * observable criterion, and not one gives the quantity at which it
     * begins. Nothing may therefore be reported as a headline result yet.
     */
    @Test
    fun `no fault has a threshold source, so none is headline-reportable`() {
        val faults = Specs.all().flatMap { it.faults }
        assertTrue(faults.all { it.provenance.thresholdSource == null })
        assertTrue(faults.none { it.provenance.reportableAsHeadline })
    }

    // ------------------------------------------- the eleven rules reject

    private fun rejects(rule: Int, mutate: (String) -> String) {
        // Normalised, because every mutation below matches on \n. A checkout
        // under core.autocrlf delivers \r\n, the mutations stop matching, and
        // the rule silently stops being tested. .gitattributes pins the file
        // too; this is the half that survives someone else's git config.
        val original = Specs.text("bodyweight-squat.json").replace("\r\n", "\n")
        val mutated = mutate(original)
        assertTrue(mutated != original, "the mutation for rule $rule did not change the file")
        val e = assertFailsWith<SpecValidationException> { SpecLoader.load(mutated) }
        assertEquals(rule, e.rule, "wrong rule fired: ${e.message}")
    }

    /**
     * The five rules that govern calibration and relative thresholds, which
     * none of the three MVP files exercises any more. They are mutated
     * against [Specs.calibratedText] instead, because a rule that no live
     * file can break is still a rule the loader enforces and still one a
     * future exercise will meet.
     */
    private fun rejectsCalibrated(rule: Int, mutate: (String) -> String) {
        // No line-ending normalisation here, unlike the loader above:
        // this fixture is a raw string in the test source, so it cannot
        // arrive with CRLF whatever the checkout did.
        val original = Specs.calibratedText()
        val mutated = mutate(original)
        assertTrue(mutated != original, "the mutation for rule $rule did not change the fixture")
        val e = assertFailsWith<SpecValidationException> { SpecLoader.load(mutated) }
        assertEquals(rule, e.rule, "wrong rule fired: ${e.message}")
    }

    @Test
    fun `rule 1 rejects a landmark the landmarks block does not declare`() =
        rejects(1) { it.replace("\"LEFT_KNEE\", \"RIGHT_KNEE\",\n", "") }

    @Test
    fun `rule 2 rejects a fault with no cue, which would fire silently`() =
        rejects(2) { it.replace(Regex("\"SQ_DEPTH_INSUFFICIENT\":\\s+\\{ \"en\".*\\},\n *"), "") }

    @Test
    fun `rule 3 rejects a fault scoped to an undeclared phase`() =
        rejects(3) { it.replace("\"phases\": [\"descent\", \"bottom\"],", "\"phases\": [\"descent\", \"launch\"],") }

    @Test
    fun `rule 4 rejects a threshold relative to a calibration that does not exist`() =
        rejectsCalibrated(4) { it.replace("\"calibration_id\": \"max_squat_depth_knee_angle\"", "\"calibration_id\": \"imaginary\"") }

    @Test
    fun `rule 5 rejects an unknown provenance status`() =
        rejects(5) { it.replaceFirst("\"status\": \"taxonomy_confirmed\"", "\"status\": \"probably_fine\"") }

    @Test
    fun `rule 5 rejects a fault claiming a source it does not carry`() =
        rejects(5) { it.replaceFirst("\"status\": \"taxonomy_confirmed\"", "\"status\": \"confirmed\"") }

    /**
     * The fault screen is built entirely out of these two fields. A fault
     * without them does not fail at load today unless the loader says so;
     * it reaches the trainee as a card with a heading and no text.
     */
    @Test
    fun `rule 5 rejects a fault with no plain source`() =
        rejectsCalibrated(5) {
            it.replaceFirst("\"plain_source\"", "\"plain_source_was_here\"")
        }

    @Test
    fun `rule 5 rejects a fault with blank coaching`() =
        rejectsCalibrated(5) {
            it.replaceFirst(
                "\"fix\": \"A fixture. Sit back further and go slower.\"",
                "\"fix\": \"   \"",
            )
        }

    @Test
    fun `rule 6 rejects hysteresis bounds that cross`() =
        rejectsCalibrated(6) {
            it.replace(
                "\"descent_enter\": { \"type\": \"relative\", \"calibration_id\": \"standing_knee_angle\", \"offset\": -20.0 }",
                "\"descent_enter\": { \"type\": \"relative\", \"calibration_id\": \"standing_knee_angle\", \"offset\": -5.0 }",
            )
        }

    @Test
    fun `rule 7 rejects a fault missing from the priority order`() =
        rejects(7) { it.replace(",\n      \"SQ_FORWARD_LEAN\"\n", "\n") }

    @Test
    fun `rule 8 rejects a calibration that is not safe unsupervised`() =
        rejectsCalibrated(8) { it.replace("\"safe_unsupervised\": true", "\"safe_unsupervised\": false") }

    @Test
    fun `rule 9 rejects a relative threshold carrying both factor and offset`() =
        rejectsCalibrated(9) { it.replace("\"calibration_id\": \"max_squat_depth_knee_angle\", \"factor\": 1.15", "\"calibration_id\": \"max_squat_depth_knee_angle\", \"factor\": 1.15, \"offset\": 2.0") }

    @Test
    fun `rule 10 rejects a during that names no earlier measure`() =
        rejectsCalibrated(10) { it.replace("\"id\": \"standing_knee_angle\",", "\"id\": \"standing_knee_angle\", \"during\": \"nothing\",") }

    @Test
    fun `rule 11 rejects an excluded fault that is still wired up`() =
        rejects(11) {
            it.replace(
                "\"SQ_FORWARD_LEAN\":       { \"en\"",
                "\"SQ_KNEE_VALGUS\": { \"en\": { \"asset\": \"x.wav\", \"text\": \"Knees out.\" } },\n    \"SQ_FORWARD_LEAN\":       { \"en\"",
            )
        }
}
