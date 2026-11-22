package ai.forma.assessment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses the spoken lines that do not belong to any one exercise.
 *
 * The lines that do belong to an exercise stay in that exercise's
 * specification and are read by [SpecLoader]. What is left is the line that
 * reads the same whatever is being trained, and which would otherwise have to
 * be written out once per exercise and kept in step by hand.
 *
 * This fails loudly on a missing line for the same reason [SpecLoader] fails
 * loudly on a missing provenance entry. The user is two to four metres from
 * the phone and facing away from it; a line that is quietly absent is not a
 * degraded interface, it is no interface, and the only symptom is a person
 * standing still waiting to be told something.
 */
object GuidanceLoader {

    const val READY = "READY"

    /**
     * Every code the application asks for by name.
     *
     * A code belongs here only while something reads it. The loader fails
     * loudly on a missing line, so a code that stays here after its caller is
     * gone rejects a guidance file that is correct.
     */
    val required = listOf(READY)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(text: String, lang: String = "en"): Map<String, CueSpec> {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw SpecValidationException(0, "guidance is not a JSON object: ${e.message}")
        }

        val lines = root["lines"]?.jsonObject
            ?: throw SpecValidationException(0, "guidance has no 'lines' object")

        val byCode = buildMap {
            for ((code, node) in lines) {
                val forLang = node.jsonObject[lang]?.jsonObject ?: continue
                val asset = forLang["asset"]?.jsonPrimitive?.content ?: continue
                val say = forLang["text"]?.jsonPrimitive?.content ?: continue
                put(code, CueSpec(asset, say))
            }
        }

        val missing = required.filterNot { it in byCode }
        if (missing.isNotEmpty()) {
            throw SpecValidationException(
                0,
                "guidance has no '$lang' line for " + missing.joinToString(", "),
            )
        }
        return byCode
    }
}
