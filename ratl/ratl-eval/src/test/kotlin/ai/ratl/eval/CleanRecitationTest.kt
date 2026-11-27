package ai.ratl.eval

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.LatticeConfig
import ai.ratl.core.WordStatus
import ai.ratl.text.Vocabulary
import java.io.File
import kotlin.test.Test

/**
 * How much of a *correct* recitation actually comes back CORRECT.
 *
 * This exists to answer a question a user cannot answer for themselves: when
 * only one or two words of an ayah light up, is the recitation poor or is the
 * engine? Studio recitation with the matching target is the ceiling - whatever
 * fraction lights up here is the best any reciter can hope for, and no phone in
 * a room will beat it.
 *
 * It also measures the same audio through the read-along display's own rules,
 * which are stricter than the engine's verdict: a confidence floor and a held
 * back frontier word. If those rules cost most of the ceiling, that is the UI's
 * fault and not the reciter's.
 */
class CleanRecitationTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "conformance/manifest.json").exists() }

    /** Must match RecitationModeScreen's REVEAL_CONFIDENCE. */
    private val revealConfidence = 0.65f

    private fun readPcm(f: File): FloatArray {
        val b = f.readBytes()
        return FloatArray(b.size / 2) { i ->
            val lo = b[2 * i].toInt() and 0xFF
            val hi = b[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort().toFloat() / 32768.0f
        }
    }

    @Test
    fun `measure how much of a clean recitation the display would reveal`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) {
            println("model not present; skipping")
            return
        }
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())

        val json = File(repoRoot, "conformance/vectors/t3_assessment.json").readText()
        val cases = Json.elements(json, json.indexOf('[', Json.indexOfKey(json, "cases")))
            .filter { Json.field(it, "op") == "clean" }

        println("clean cases: ${cases.size}")

        var totalWords = 0
        var totalCorrect = 0
        var totalConfident = 0
        var totalUncertain = 0
        var totalOmission = 0

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            for (case in cases) {
                val id = Json.field(case, "id")
                val words = Json.stringArray(Json.field(case, "words"))
                val pcmFile = File(repoRoot, "conformance/vectors/${Json.field(case, "pcm_file")}")
                if (!pcmFile.isFile) continue
                val audio = readPcm(pcmFile)

                val a = engine.assessWords(audio, words)
                val correct = a.words.count { it.status == WordStatus.CORRECT }
                val confident = a.words.count {
                    it.status == WordStatus.CORRECT && it.confidence >= revealConfidence
                }
                val uncertain = a.words.count { it.status == WordStatus.UNCERTAIN }
                val omission = a.words.count { it.status == WordStatus.OMISSION }

                totalWords += words.size
                totalCorrect += correct
                totalConfident += confident
                totalUncertain += uncertain
                totalOmission += omission

                println(
                    "  $id  ${words.size}w  CORRECT=$correct  " +
                        ">=${revealConfidence}=$confident  UNCERTAIN=$uncertain  OMISSION=$omission",
                )
            }
        }

        if (totalWords == 0) {
            println("no clean cases with audio present")
            return
        }

        fun pct(n: Int) = "%.1f%%".format(100.0 * n / totalWords)
        println()
        println("=== studio recitation, matching target, batch assessment ===")
        println("words                        : $totalWords")
        println("CORRECT (engine verdict)     : $totalCorrect  ${pct(totalCorrect)}")
        println("CORRECT and >= $revealConfidence conf  : $totalConfident  ${pct(totalConfident)}")
        println("UNCERTAIN (abstained)        : $totalUncertain  ${pct(totalUncertain)}")
        println("OMISSION (called missing)    : $totalOmission  ${pct(totalOmission)}")
        println()
        println("The second line is the ceiling for what the read-along reveals.")
    }
}
