package ai.ratl.eval

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.LatticeConfig
import ai.ratl.core.WordStatus
import ai.ratl.text.Vocabulary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a session reports before anything has been recited into it.
 *
 * A read-along UI reveals a word when RATL calls it CORRECT. That is only safe
 * if a session which has heard nothing reports nothing. If near-silence is
 * enough to satisfy the aligner - and forced alignment is *given* the target,
 * so it is always looking for a way through it - then every consumer that
 * reveals on CORRECT will hand the reciter the whole ayah the moment it starts
 * listening, and no amount of guarding in the UI can prevent it.
 *
 * Reported symptom this exists to explain: moving to the next ayah in
 * continuous mode unblurred it instantly, before a word was recited.
 */
class SilenceRevealTest {

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

    private fun words(): List<String> {
        val json = File(repoRoot, "conformance/vectors/t3_assessment.json").readText()
        val cases = Json.elements(json, json.indexOf('[', Json.indexOfKey(json, "cases")))
        val case = cases.filter { Json.field(it, "op") == "clean" }
            .maxByOrNull { Json.stringArray(Json.field(it, "words")).size }!!
        return Json.stringArray(Json.field(case, "words"))
    }

    private fun model(): Pair<File, Vocabulary>? {
        val m = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!m.isFile) return null
        return m to Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
    }

    /**
     * Digital silence, then room tone. Real microphones never deliver zeros, so
     * a test that only tries perfect silence would miss the case that actually
     * happens on a phone between two ayat.
     */
    @Test
    fun `a session that has heard nothing must not report CORRECT`() {
        val (modelFile, vocabulary) = model() ?: run {
            println("model not present; skipping")
            return
        }
        val target = words()
        val rng = java.util.Random(7)

        val cases = linkedMapOf(
            "digital silence 2s" to FloatArray(32_000),
            "room tone 2s (-60 dBFS)" to FloatArray(32_000) {
                (rng.nextGaussian() * 0.001).toFloat()
            },
            "room tone 2s (-45 dBFS)" to FloatArray(32_000) {
                (rng.nextGaussian() * 0.006).toFloat()
            },
        )

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        val offenders = mutableListOf<String>()
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            println("target = ${target.size} words: $target")
            for ((name, pcm) in cases) {
                val session = engine.createSession(target)
                session.pushAudio(pcm)
                val partial = session.pushAudio(FloatArray(1600))
                val statuses = partial.words.map { it.status }
                val correct = partial.words.filter { it.status == WordStatus.CORRECT }
                val confident = correct.filter { it.confidence >= 0.65f }

                println("--- $name")
                println("    statuses = $statuses")
                println("    CORRECT = ${correct.size}/${target.size}, of which >=0.65 conf = ${confident.size}")
                if (correct.isNotEmpty()) {
                    println("    confidences = ${correct.map { "%.2f".format(it.confidence) }}")
                    offenders += "$name -> ${correct.size} CORRECT"
                }
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "silence produced CORRECT words, so revealing on CORRECT leaks the ayah: $offenders",
        )
    }

    /**
     * The handover case, end to end: a real recitation, then a fresh session
     * for a different ayah fed only silence - exactly what continuous mode does
     * when it moves on.
     */
    @Test
    fun `a fresh session after a real one starts clean`() {
        val (modelFile, vocabulary) = model() ?: run {
            println("model not present; skipping")
            return
        }
        val target = words()
        val real = readPcm(File(repoRoot, "conformance/vectors/pcm_Husary_64kbps_001001.s16"))

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            val first = engine.createSession(target)
            first.pushAudio(real)
            val done = first.finish()
            println("first session settled: ${done.words.count { it.status == WordStatus.CORRECT }}/${target.size} CORRECT")

            val second = engine.createSession(target)
            val partial = second.pushAudio(FloatArray(32_000))
            val correct = partial.words.count { it.status == WordStatus.CORRECT }
            println("second session on silence: $correct/${target.size} CORRECT")
            println("    statuses = ${partial.words.map { it.status }}")

            assertTrue(
                correct == 0,
                "a new session reported $correct CORRECT words from silence - " +
                    "the previous session's state is leaking, or alignment " +
                    "satisfies the target without audio",
            )
        }
    }
}
