package ai.ratl.acoustic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.ratl.core.AcousticModel
import ai.ratl.core.Frames
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * The V1 [AcousticModel]: the frozen end-to-end ONNX graph on ONNX Runtime.
 *
 * The graph takes reflect-padded PCM and returns CTC logits - the log-mel front
 * end lives inside it (a DFT expressed as a convolution), so nothing here does
 * DSP and there is no filterbank to drift out of sync with the weights.
 *
 * The same ONNX Runtime Java API backs desktop (`onnxruntime`) and Android
 * (`onnxruntime-android`), which is what lets one Kotlin core serve both.
 *
 * Single-threaded by default: G5 is specified as a single-thread figure
 * (SPEC 7.2), so the default must not quietly use more.
 */
class OnnxAcousticModel(
    modelFile: File,
    vocabularySize: Int,
    threads: Int = 1,
) : AcousticModel {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val outputName: String

    /** SHA-256 of the model file, surfaced as `Assessment.model_id` (SPEC 5.1). */
    val modelId: String = sha256(modelFile)

    override val vocabularySize: Int = vocabularySize

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(threads)
        }
        session = env.createSession(modelFile.absolutePath, opts)
        inputName = session.inputNames.first()
        outputName = session.outputNames.first()
    }

    override fun logits(preparedPcm: FloatArray): Array<FloatArray> {
        require(preparedPcm.size == Frames.PADDED_SAMPLES) {
            "expected ${Frames.PADDED_SAMPLES} prepared samples, got ${preparedPcm.size}"
        }
        val shape = longArrayOf(1, preparedPcm.size.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(preparedPcm), shape).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val out = result[outputName].get().value as Array<Array<FloatArray>>
                return out[0]
            }
        }
    }

    @Volatile
    private var closed = false

    /** Idempotent, as Closeable requires: double-close must not throw. */
    override fun close() {
        if (closed) return
        closed = true
        session.close()
    }

    private companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
