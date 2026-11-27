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
 * What Emran's target path costs, measured over the whole benchmark.
 *
 * RATL is a *forced* aligner: it is handed the target and finds the best path
 * through it. That makes the spelling of the target load-bearing. The benchmark
 * uses reference transliterations; Emran has none and romanises Uthmani Arabic
 * itself, and the normaliser's own conformance vector records
 * `exact_word_agreement = 0.6354` against those references.
 *
 * SPEC §7.6 already reports what this costs *G2*: 0.8504 through this pipeline
 * against 0.8570 with reference targets - a 0.7 point difference, easy to read
 * as negligible. But G2 measures **boundary timing on words that aligned**. It
 * does not say how many words stop being CORRECT, and CORRECT is the only thing
 * the read-along display reveals. A user does not see boundaries; they see
 * whether the word lit up.
 *
 * This measures that directly: the same studio audio, judged twice.
 */
class TargetPathCostTest {

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

    /** surah:ayah -> Uthmani text, from Emran's bundled corpus. */
    private fun quran(): Map<String, String>? {
        val f = listOf(
            System.getenv("RATL_EMRAN_QURAN_JSON")?.let(::File),
            File(repoRoot, "../emran/app/src/main/assets/quran.json"),
        ).filterNotNull().firstOrNull { it.isFile } ?: return null

        val body = f.readText()
        val out = HashMap<String, String>()
        // Walk surah blocks so verse ids can be paired with their surah id.
        val surahIds = Regex(""""id"\s*:\s*(\d+)\s*,\s*"name"""").findAll(body).map {
            it.groupValues[1].toInt() to it.range.first
        }.toList()
        val verseRe = Regex(""""id"\s*:\s*(\d+)\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        for (m in verseRe.findAll(body)) {
            val surah = surahIds.lastOrNull { it.second < m.range.first }?.first ?: continue
            out["$surah:${m.groupValues[1]}"] = m.groupValues[2]
        }
        return out
    }

    @Test
    fun `measure CORRECT lost to Emran's romanisation`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        val benchDir = File(repoRoot, "eval/bench_pcm")
        if (!modelFile.isFile || !benchDir.isDirectory) {
            println("model or bench not present; skipping")
            return
        }
        val corpus = quran() ?: run {
            println("Emran quran.json not reachable; skipping")
            return
        }

        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val manifest = File(benchDir, "manifest.json").readText()
        val items = Json.elements(manifest, manifest.indexOf('[', Json.indexOfKey(manifest, "items")))

        var clips = 0
        var wordsRef = 0
        var wordsEmran = 0
        var correctRef = 0
        var correctEmran = 0
        var countMismatch = 0
        val examples = mutableListOf<String>()

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            for (item in items) {
                val surah = Json.field(item, "surah")
                val ayah = Json.field(item, "ayah")
                val arabic = corpus["$surah:$ayah"] ?: continue
                val pcmFile = File(benchDir, Json.field(item, "pcm"))
                if (!pcmFile.isFile) continue

                val reference = Json.stringArray(Json.field(item, "words"))
                val emran = Normalizer.romanizeVerse(arabic)
                val audio = readPcm(pcmFile)

                val aRef = engine.assessWords(audio, reference)
                val cRef = aRef.words.count { it.status == WordStatus.CORRECT }

                if (emran.size != reference.size) countMismatch++
                val cEmran = if (emran.isEmpty()) {
                    0
                } else {
                    engine.assessWords(audio, emran).words
                        .count { it.status == WordStatus.CORRECT }
                }

                clips++
                wordsRef += reference.size
                wordsEmran += emran.size
                correctRef += cRef
                correctEmran += cEmran

                if (cEmran < cRef && examples.size < 8) {
                    val diff = reference.zip(emran).filter { it.first != it.second }
                    examples += "$surah:$ayah ref=$cRef/${reference.size} " +
                        "emran=$cEmran/${emran.size}  differing=$diff"
                }
            }
        }

        if (clips == 0) {
            println("no comparable clips")
            return
        }

        fun pct(n: Int, d: Int) = if (d == 0) "n/a" else "%.1f%%".format(100.0 * n / d)
        println()
        println("=== studio audio, two targets, $clips clips ===")
        println("reference targets : $correctRef/$wordsRef CORRECT  ${pct(correctRef, wordsRef)}")
        println("Emran targets     : $correctEmran/$wordsEmran CORRECT  ${pct(correctEmran, wordsEmran)}")
        println("word-count mismatches between the two targets: $countMismatch clips")
        println()
        examples.forEach { println("  $it") }
        println()
        println("This is the ceiling a reciter can reach through Emran, on studio audio.")
    }
}
