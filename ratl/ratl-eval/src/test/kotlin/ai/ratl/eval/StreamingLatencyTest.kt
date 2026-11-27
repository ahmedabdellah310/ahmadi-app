package ai.ratl.eval

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.LatticeConfig
import ai.ratl.text.Vocabulary
import java.io.File
import kotlin.test.Test

/**
 * How far behind a reciter the display falls, as a session gets longer.
 *
 * `Session.pushAudio` decodes the whole accumulated buffer every time it
 * recomputes. That is deliberate - it is what makes streaming and batch agree
 * exactly - but it means the cost of each update grows with everything recited
 * so far. A reciter who pauses between ayat never notices, because the session
 * is handed on and the buffer starts again. A reciter who takes a whole surah in
 * one breath keeps one session alive for minutes.
 *
 * This measures the shape of that curve, so the fix can be aimed at the real
 * number rather than at an intuition.
 */
class StreamingLatencyTest {

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

    @Test
    fun `decode cost against buffer length`() {
        val modelFile = File(repoRoot, "models/out/ratl-align-v1-e2e-fp32.onnx")
        if (!modelFile.isFile) {
            println("model not present; skipping")
            return
        }
        val vocabulary = Vocabulary.fromJson(File(repoRoot, "models/out/vocab.json").readText())
        val clip = readPcm(File(repoRoot, "conformance/vectors/pcm_Husary_64kbps_001001.s16"))

        // A long recitation, built by repeating real audio. The words do not
        // matter here - decode cost depends on buffer length, not on content.
        val target = List(24) { "bis'mi" }

        val model = OnnxAcousticModel(modelFile, vocabulary.size)
        Engine(model, vocabulary, model.modelId, LatticeConfig.V1).use { engine ->
            val session = engine.createSession(target)
            var pushed = 0
            println("  seconds |  decode ms")
            println("  --------+-----------")
            repeat(24) { i ->
                val t0 = System.nanoTime()
                session.pushAudio(clip)
                val ms = (System.nanoTime() - t0) / 1_000_000
                pushed += clip.size
                val secs = pushed / 16000
                if (i % 2 == 1 || i < 4) {
                    println("  %7d | %9d".format(secs, ms))
                }
            }
        }
        println()
        println("Each row is one update while reciting. The cost is the delay")
        println("between saying a word and seeing it, on top of the engine's own")
        println("half-second partial interval.")
    }
}
