package ai.ratl.core

/**
 * RATL's public error taxonomy. SPEC 4.
 *
 * These identifiers are the public contract and are versioned with the
 * specification. **Adding a value is a breaking change**, so consumers must
 * handle unknown values defensively - `when` blocks over this enum should carry
 * an `else` branch.
 */
enum class WordStatus {
    /** Recited, aligned, above the confidence threshold. */
    CORRECT,

    /** Target word absent from the recitation. */
    OMISSION,

    /**
     * Different content spoken in this word's position.
     *
     * **V1 never emits this** (SPEC 4.1). It remains in the enum because it is
     * part of the public contract and a later version may emit it; suspected
     * substitutions surface as [UNCERTAIN] instead.
     */
    SUBSTITUTION,

    /** Word or phrase recited more than once consecutively. */
    REPETITION,

    /** Extra content between target words, corresponding to no target word. */
    INSERTION,

    /** Word begun then truncated - audio ended, or the reciter broke off. */
    INCOMPLETE,

    /** Abstention. RATL declines to judge rather than accuse. */
    UNCERTAIN,

    /** Streaming only: the target word has not been reached yet. */
    PENDING,
}

/** Session-level events. Out-of-order recitation is an event, not a word status (SPEC 4). */
sealed interface SessionEvent {
    /** Alignment re-anchored after a jump. */
    data class Reanchored(val fromWord: Int, val toWord: Int, val atMs: Double) : SessionEvent

    /** Extra content between target words, with no target index to attach to. */
    data class Insertion(val afterWord: Int, val startMs: Double, val endMs: Double) : SessionEvent
}

/** Per-word assessment. SPEC 5.1. */
data class WordResult(
    val index: Int,
    val qulKey: String,
    val status: WordStatus,
    val confidence: Float,
    val tStartMs: Double?,
    val tEndMs: Double?,
    val observed: String? = null,
)

/** A complete assessment. SPEC 5.1. */
data class Assessment(
    val words: List<WordResult>,
    val events: List<SessionEvent>,
    val audioDurationMs: Double,
    val modelId: String,
    val specVersion: String = SPEC_VERSION,
) {
    companion object {
        const val SPEC_VERSION = "0.4"
    }
}

/**
 * A provisional assessment emitted while streaming.
 *
 * **Previously emitted words may be revised.** Results are provisional until
 * [Session.finish]. A UI must not render these as final verdicts - SPEC 5.3
 * calls this out explicitly, and it is the single easiest way to misuse RATL.
 */
data class PartialAssessment(
    val words: List<WordResult>,
    val events: List<SessionEvent>,
    val cursor: Int,
    val audioDurationMs: Double,
)

/** Strictness presets. SPEC 5.4. */
enum class Strictness { LENIENT, BALANCED, STRICT }

/**
 * Session configuration. SPEC 5.4.
 *
 * Defaults are tuned to minimise false rejects, not to maximise F1 (SPEC 4
 * rule 1): accusing a reciter wrongly is the most serious failure mode RATL has.
 */
data class SessionConfig(
    val confidenceThreshold: Float = 0.5f,
    val allowReanchor: Boolean = true,
    val maxSkipWords: Int = 3,
    val emitPartials: Boolean = true,
    val strictness: Strictness = Strictness.BALANCED,
    /**
     * Minimum new audio, in milliseconds, before a streaming partial is
     * recomputed.
     *
     * Each recompute decodes the whole buffer, so decoding on every push is
     * quadratic in both time and allocation. 500 ms is well inside human
     * reaction time for a progress display and bounds a 15 s recitation to
     * ~30 decodes instead of one per audio chunk. Set to 0 to decode on every
     * push - accurate, and measurably expensive.
     */
    val partialIntervalMs: Int = 500,
) {
    internal val partialIntervalSamples: Int
        get() = partialIntervalMs * Frames.SAMPLE_RATE / 1000
}

/** Model provenance, for reproducible claims. SPEC 5.2. */
data class ModelInfo(
    val modelId: String,
    val vocabularySize: Int,
    val sampleRate: Int = Frames.SAMPLE_RATE,
    val windowSeconds: Double = Frames.WINDOW_S,
    val specVersion: String = Assessment.SPEC_VERSION,
)
