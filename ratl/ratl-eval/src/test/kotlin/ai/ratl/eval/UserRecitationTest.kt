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
 * Replays a real recitation captured on a phone through the same engine.
 *
 * Everything measured so far used studio audio from professional reciters, and
 * scored 92-98% CORRECT. A user reciting into a phone sees one or two words
 * light up. Those two facts cannot both describe the same system, so something
 * between them is losing the words - and from the phone alone there is no way
 * to tell capture from assessment from streaming.
 *
 * This isolates all three: same audio, judged in batch and streamed, against
 * both the reference target and the one Emran actually builds.
 *
 * Opt-in via `RATL_USER_PCM` (raw 16 kHz mono little-endian s16) and
 * `RATL_USER_WORDS` (space separated), since the audio is not in the repo.
 */
class UserRecitationTest {

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

    private fun report(label: String, words: List<String>, a: ai.ratl.core.Assessment) {
        val correct = a.words.count { it.status == WordStatus.CORRECT }
        println("  $label: $correct/${words.size} CORRECT")
        a.words.forEachIndexed { i, w ->
            println(
                "      ${words.getOrNull(i)}  ${w.status}  conf=%.2f  ".format(w.confidence) +
                    "t=${w.tStartMs?.toInt()}..${w.tEndMs?.toInt()}ms",
            )
        }
    }

    @Test
    fun `replay a phone recitation`() {
        val pcmPath = System.getenv("RATL_USER_PCM") ?: run {
            println("RATL_USER_PCM not set; skipping")
            return
        }
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) {
            println("model not present; skipping")
            return
        }
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val audio = readPcm(File(pcmPath))
        println("audio: %.2f s".format(audio.size / 16000.0))

        val arabic = System.getenv("RATL_USER_ARABIC") ?: "بِسۡمِ ٱللَّهِ ٱلرَّحۡمَٰنِ ٱلرَّحِيمِ"
        val emran = Normalizer.romanizeVerse(arabic)
        val reference = (System.getenv("RATL_USER_WORDS")
            ?: "bis'mi l-lahi l-raḥmāni l-raḥīmi").trim().split(Regex("\\s+"))

        println("emran target    : $emran")
        println("reference target: $reference")

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            println("\n--- batch assessment (the whole clip at once) ---")
            report("emran    ", emran, engine.assessWords(audio, emran))
            report("reference", reference, engine.assessWords(audio, reference))

            // The app streams in ~250 ms chunks and reveals from partials, so
            // the streamed result is what a reciter actually sees.
            println("\n--- streamed in 250 ms chunks, as the app does ---")
            val session = engine.createSession(emran)
            var i = 0
            val chunk = 4000
            var lastCorrect = 0
            while (i < audio.size) {
                val n = minOf(chunk, audio.size - i)
                val p = session.pushAudio(audio.copyOfRange(i, i + n))
                i += n
                val c = p.words.count { it.status == WordStatus.CORRECT }
                if (c != lastCorrect) {
                    println("    at %.2fs: %d CORRECT  %s".format(i / 16000.0, c, p.words.map { it.status }))
                    lastCorrect = c
                }
            }
            report("streamed ", emran, session.finish())

            // And with only the speech, in case trailing silence is the problem.
            val trimmed = audio.copyOfRange(0, minOf(audio.size, 16000 * 5))
            println("\n--- first 5 s only (speech, minimal trailing silence) ---")
            report("emran    ", emran, engine.assessWords(trimmed, emran))
        }
    }
}
