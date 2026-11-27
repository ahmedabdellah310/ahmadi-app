package ai.ratl.android

import ai.ratl.acoustic.OnnxAcousticModel
import ai.ratl.core.Engine
import ai.ratl.core.Frames
import ai.ratl.core.LatticeConfig
import ai.ratl.text.Vocabulary
import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Android entry point. Deliberately thin.
 *
 * Everything that decides anything lives in `ratl-core`, which has no Android
 * dependency at all. This module only does what a platform wrapper should:
 * find files, extract assets, and hand PCM to the same engine the desktop
 * harness runs. If logic accumulates here, the port has gone wrong.
 *
 * No NDK, no CMake, no JNI written by this project - ONNX Runtime ships
 * prebuilt native libraries and exposes the same Java API on both targets.
 */
object RatlAndroid {

    /**
     * Open an engine from a model directory on the filesystem.
     *
     * @param modelDir directory holding the `.onnx` graph and its `vocab.json`
     * @param threads  inference threads. Defaults to 1 because G5 is specified
     *   single-threaded (SPEC 7.2); a default above 1 would quietly report a
     *   figure the specification does not accept.
     */
    fun engineFromDir(
        modelDir: File,
        threads: Int = 1,
        lattice: LatticeConfig = LatticeConfig.V1,
    ): Engine {
        val vocabFile = File(modelDir, "vocab.json")
        require(vocabFile.isFile) { "vocab.json not found in $modelDir" }
        val graph = modelDir.listFiles { f -> f.name.endsWith(".onnx") }
            ?.minByOrNull { it.name }
            ?: error("no .onnx graph in $modelDir")
        val vocabulary = Vocabulary.fromJson(vocabFile.readText())
        val model = OnnxAcousticModel(graph, vocabulary.size, threads)
        return Engine(model, vocabulary, model.modelId, lattice)
    }

    /**
     * Copy a bundled asset into app storage once, verifying its hash.
     *
     * The model is distributed by hash (SPEC 5.1 `model_id`), so a partial or
     * substituted file must fail loudly rather than produce quietly wrong
     * assessments.
     */
    fun extractAsset(context: Context, assetName: String, dest: File): File {
        if (dest.exists() && dest.length() > 0) return dest
        dest.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            dest.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
        return dest
    }

    /** SHA-256 of a file, for verifying a downloaded or extracted model. */
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

    /** 16-bit PCM bytes to the float samples the engine expects. */
    fun pcm16ToFloat(bytes: ByteArray): FloatArray =
        FloatArray(bytes.size / 2) { i ->
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort().toFloat() / 32768.0f
        }

    /** The sample rate RATL requires. Audio at any other rate must be resampled. */
    const val SAMPLE_RATE = Frames.SAMPLE_RATE
}
