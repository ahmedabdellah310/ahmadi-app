package ai.ratl.eval

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.LatticeConfig
import ai.ratl.core.WordStatus
import ai.ratl.text.Normalizer
import ai.ratl.text.Vocabulary
import java.io.File
import kotlin.test.Test

/**
 * The same audio, judged against two different targets.
 *
 * The conformance vectors carry reference transliterations. Emran has none - it
 * romanises Uthmani Arabic itself with [Normalizer.romanizeVerse] - and the
 * normaliser's own vector records `exact_word_agreement = 0.6354` against those
 * references. Roughly a third of target words therefore differ in spelling from
 * what the vectors use.
 *
 * Forced alignment is handed the target, so a target word spelled differently
 * from what the acoustic model expects is a word the audio cannot match. If
 * that is what is happening, a perfectly recited ayah will still light up only
 * partially, and no amount of reciting better will fix it.
 *
 * This measures the cost of that path directly rather than inferring it.
 */
class EmranTargetPathTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "conformance/manifest.json").exists() }

    private fun readPcm(f: File): FloatArray {
        val b = f.readBytes()
        return FloatArray(b.size / 2) { i ->
            val lo = b[2 * i].toInt() and 0xFF
            val hi = b[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort().toFloat() / 32768.0f
        }
    }

    /** Al-Fatiha 1:1, from Emran's own bundled text. */
    private fun arabicFatiha(): String? {
        val candidates = listOf(
            System.getenv("RATL_EMRAN_QURAN_JSON")?.let(::File),
            File(repoRoot, "../emran/app/src/main/assets/quran.json"),
        )
        val f = candidates.filterNotNull().firstOrNull { it.isFile } ?: return null
        val m = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(f.readText())
        return m?.groupValues?.get(1)
    }

    @Test
    fun `compare reference transliteration against Emran's romanisation`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) {
            println("model not present; skipping")
            return
        }
        val pcm = File(repoRoot, "conformance/vectors/pcm_Husary_64kbps_001001.s16")
        if (!pcm.isFile) {
            println("Husary 1:1 audio not present; skipping")
            return
        }
        val arabic = arabicFatiha() ?: run {
            println("Emran quran.json not reachable; skipping")
            return
        }

        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val audio = readPcm(pcm)

        // What the vectors use for this ayah.
        val reference = listOf("bis'mi", "l-lahi", "l-raḥmāni", "l-raḥīmi")
        // What Emran actually sends.
        val emran = Normalizer.romanizeVerse(arabic)

        println("arabic   : $arabic")
        println("reference: $reference")
        println("emran    : $emran")
        println("identical: ${reference == emran}")

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            for ((label, target) in listOf("reference" to reference, "emran" to emran)) {
                if (target.isEmpty()) {
                    println("$label: empty target")
                    continue
                }
                val a = engine.assessWords(audio, target)
                val correct = a.words.count { it.status == WordStatus.CORRECT }
                println(
                    "$label: $correct/${target.size} CORRECT  " +
                        "statuses=${a.words.map { it.status }}  " +
                        "conf=${a.words.map { "%.2f".format(it.confidence) }}",
                )
            }
        }
    }
}
