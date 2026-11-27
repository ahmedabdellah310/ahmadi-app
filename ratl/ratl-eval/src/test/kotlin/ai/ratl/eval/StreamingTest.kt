package ai.ratl.eval

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.LatticeConfig
import ai.ratl.core.WordStatus
import ai.ratl.text.Vocabulary
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streaming behaviour. SPEC 5.3.
 *
 * The contract being pinned:
 *
 *  1. `finish()` equals `assess()` on the same audio. Streaming is not a
 *     different algorithm - it re-decodes the buffer, so it cannot drift.
 *  2. `pushAudio` accepts arbitrary chunk sizes; buffering is internal.
 *  3. Words the audio has not reached report PENDING, and PENDING appears
 *     nowhere else.
 *  4. Partials are provisional: a word may be revised as more audio arrives.
 *     This test demonstrates that revision happens rather than pretending
 *     partials are stable, because a UI that renders them as final is the
 *     easiest way to misuse RATL.
 *  5. `reset()` clears state.
 */
class StreamingTest {

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

    private fun firstCase(): Triple<FloatArray, List<String>, String> {
        val json = File(repoRoot, "conformance/vectors/t3_assessment.json").readText()
        val cases = Json.elements(json, json.indexOf('[', Json.indexOfKey(json, "cases")))
        // The longest clean clip: a 4-word ayah is too short for a prefix to
        // leave anything unreached, which would make the PENDING test vacuous.
        val case = cases.filter { Json.field(it, "op") == "clean" }
            .maxByOrNull { Json.stringArray(Json.field(it, "words")).size }!!
        val words = Json.stringArray(Json.field(case, "words"))
        val pcm = readPcm(File(repoRoot, "conformance/vectors/${Json.field(case, "pcm_file")}"))
        return Triple(pcm, words, Json.field(case, "id"))
    }

    @Test
    fun `streaming finish equals batch assess`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) return
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val (audio, words, id) = firstCase()

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            val batch = engine.assessWords(audio, words)

            // deliberately awkward chunk sizes: not frame-aligned, not equal
            val session = engine.createSession(words)
            var i = 0
            val sizes = intArrayOf(1000, 4321, 16000, 777, 32000)
            var k = 0
            while (i < audio.size) {
                val n = minOf(sizes[k % sizes.size], audio.size - i)
                session.pushAudio(audio.copyOfRange(i, i + n))
                i += n
                k++
            }
            val streamed = session.finish()

            assertEquals(batch.words.size, streamed.words.size, "[$id] word count")
            for (j in batch.words.indices) {
                assertEquals(
                    batch.words[j].status, streamed.words[j].status,
                    "[$id] status word $j: batch vs streaming",
                )
                assertEquals(
                    batch.words[j].tStartMs, streamed.words[j].tStartMs,
                    "[$id] start word $j",
                )
                assertEquals(batch.words[j].tEndMs, streamed.words[j].tEndMs, "[$id] end word $j")
            }
            assertEquals(batch.audioDurationMs, streamed.audioDurationMs)
            assertEquals(words.size, session.cursor(), "cursor should reach the end")
            println("streaming: ${batch.words.size} words identical to batch")
        }
    }

    @Test
    fun `partials report PENDING ahead of the audio and may be revised`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) return
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val (audio, words, _) = firstCase()

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            val session = engine.createSession(words)
            val quarter = audio.size / 5

            val p1 = session.pushAudio(audio.copyOfRange(0, quarter))
            val pendingEarly = p1.words.count { it.status == WordStatus.PENDING }
            assertTrue(
                pendingEarly > 0,
                "a fifth of the audio should leave later words PENDING, got none " +
                    "(words=${words.size}, statuses=${p1.words.map { it.status }})",
            )

            session.pushAudio(audio.copyOfRange(quarter, audio.size))
            val pFull = session.pushAudio(FloatArray(0))
            val pendingLate = pFull.words.count { it.status == WordStatus.PENDING }
            assertTrue(
                pendingLate <= pendingEarly,
                "PENDING count must not grow as audio arrives ($pendingEarly -> $pendingLate)",
            )

            val finalAssessment = session.finish()
            assertEquals(
                0, finalAssessment.words.count { it.status == WordStatus.PENDING },
                "finish() must never leave PENDING words",
            )
            println("streaming: PENDING $pendingEarly -> $pendingLate -> 0 at finish")
        }
    }

    @Test
    fun `reset clears buffered audio and cursor`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) return
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val (audio, words, _) = firstCase()

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            val session = engine.createSession(words)
            session.pushAudio(audio)
            assertTrue(session.bufferedMs() > 0.0)
            session.reset()
            assertEquals(0.0, session.bufferedMs())
            assertEquals(0, session.cursor())
        }
    }
}
