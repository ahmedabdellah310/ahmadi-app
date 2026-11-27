package io.github.sammynashed.emran.recitation

import ai.ratl.core.SessionConfig
import ai.ratl.android.RatlAndroid
import ai.ratl.core.Assessment
import ai.ratl.core.Engine
import ai.ratl.core.Session
import ai.ratl.core.WordStatus
import android.content.Context
import io.github.sammynashed.emran.data.packs.Packs
import java.io.File

/**
 * Emran's integration of RATL, the on-device Quran recitation assessment engine.
 *
 * ## The model is an optional download (the "hifz" pack)
 *
 * The ~31 MB model and (in the Telegram build) the onnxruntime native library are not in the APK: Emran
 * downloads them once, when someone first opens Hifz mode ([Packs]), and keeps them in the app's private
 * storage, where updates never touch them. Hosted on Cloudflare because GitHub's download servers are
 * unreachable from Syria. Recitation audio never leaves the phone.
 */
class RecitationAssessor(private val context: Context) {

    private var engine: Engine? = null

    private val modelDir: File get() = Packs.hifzModelDir(context).apply { mkdirs() }
    private val vocabFile: File get() = File(modelDir, "vocab.json")

    /** True once the Hifz pack (model, and the native library where it is not in the APK) is on the phone. */
    val isInstalled: Boolean
        get() = Packs.isInstalled(context, "hifz")

    val isLoaded: Boolean get() = engine != null

    /** The small vocabulary file rides inside the APK; the engine wants it next to the model. */
    @Synchronized
    private fun prepareVocab() {
        if (vocabFile.isFile && vocabFile.length() > 0) return
        modelDir.mkdirs()
        context.assets.open("ratl/vocab.json").use { input -> vocabFile.outputStream().use { out -> input.copyTo(out) } }
    }

    /**
     * Where to keep the audio of Hifz attempts, or null (the normal case).
     *
     * Opt-in by a marker file `hifz-debug` in the app's external files folder,
     * so nothing a reciter says is ever stored unless a developer asked for it.
     * It exists to tune the engine against real phone-mic recitation.
     */
    fun debugAudioDir(): File? {
        val base = context.getExternalFilesDir(null) ?: return null
        if (!File(base, "hifz-debug").exists()) return null
        return File(base, "hifz-audio").apply { mkdirs() }
    }

    /** Load the engine. Call off the main thread - it reads ~31 MB. */
    fun load(): Engine {
        engine?.let { return it }
        check(isInstalled && Packs.prepareNative(context)) { "The Hifz check is not downloaded yet." }
        prepareVocab()
        // RATL defaults to ONE thread because its G5 benchmark is specified
        // single-threaded (SPEC 7.2) - a rule about what figure may be
        // reported, not a limit of the engine. On a phone one thread decodes
        // slower than the reciter speaks, so words lagged ~2 s behind. The
        // answer is the same model on more cores; the big cores are 4 of 8.
        return RatlAndroid.engineFromDir(modelDir, threads = INFERENCE_THREADS)
            .also { engine = it }
    }

    fun assess(pcm16kMono: FloatArray, targetWords: List<String>): Assessment =
        load().assessWords(pcm16kMono, targetWords)

    fun startSession(targetWords: List<String>): Session =
        load().createSession(targetWords, SessionConfig(partialIntervalMs = PARTIAL_INTERVAL_MS))

    fun release() {
        engine?.close()
        engine = null
    }

    /**
     * How Emran presents a word.
     *
     * RATL abstains rather than accuse, and the UI must not undo that:
     * `UNCERTAIN` is "unclear", never a mistake. V1 never emits `SUBSTITUTION`,
     * and if a later version does, it is shown the same neutral way rather than
     * asserting a verdict on someone's recitation.
     */
    fun display(status: WordStatus): Display = when (status) {
        WordStatus.CORRECT -> Display.MATCHED
        WordStatus.OMISSION -> Display.MISSED
        WordStatus.REPETITION -> Display.REPEATED
        WordStatus.INCOMPLETE -> Display.UNFINISHED
        WordStatus.INSERTION -> Display.EXTRA
        WordStatus.SUBSTITUTION, WordStatus.UNCERTAIN -> Display.UNCLEAR
        WordStatus.PENDING -> Display.NOT_REACHED
        else -> Display.UNCLEAR
    }

    enum class Display { MATCHED, MISSED, REPEATED, UNFINISHED, EXTRA, UNCLEAR, NOT_REACHED }

    companion object {
        const val MODEL_NAME = "ratl-align-v1-e2e-fp32.onnx"
        const val MODEL_SHA256 =
            "95a01016aa1383bf60d28edfe5eaf6baecb65b71280cc5085b67ce05545d2798"

        /** The model inside the APK. */
// (model is the "hifz" pack now; nothing is bundled)

        /** RATL requires exactly this rate. */
        const val SAMPLE_RATE = 16_000

        /** Inference threads; see [load]. */
        private const val INFERENCE_THREADS = 4

        /**
         * How much new audio before the display is refreshed. RATL's default
         * is 500 ms; halved so a word appears sooner. It is only a floor - the
         * recorder hands the engine everything queued, so a slow decode never
         * drops audio, it just refreshes less often.
         */
        private const val PARTIAL_INTERVAL_MS = 100
    }
}
