package ai.ratl.core

/**
 * The frozen frame geometry (SPEC 2.1, 7.6). These are not tunable.
 *
 * A 15 s window on a 10 s hop, with the 2.5 s of context on each side discarded
 * so every kept frame had full context. 50 encoder frames per second, so one
 * frame is 20 ms. The latency offset is 0 ms: every non-zero offset tested was
 * worse on held-out data.
 */
object Frames {
    const val SAMPLE_RATE = 16_000
    const val WINDOW_S = 15.0
    const val HOP_S = 10.0
    const val MARGIN_S = (WINDOW_S - HOP_S) / 2.0
    const val FPS = 50.0
    const val MS_PER_FRAME = 1000.0 / FPS
    const val ENCODER_FRAMES = 750
    const val OFFSET_MS = 0.0

    const val WINDOW_SAMPLES = (WINDOW_S * SAMPLE_RATE).toInt()
    const val HOP_SAMPLES = (HOP_S * SAMPLE_RATE).toInt()
    const val MARGIN_FRAMES = (MARGIN_S * FPS).toInt()

    /** n_fft/2 reflect padding consumed by the in-graph STFT front end. */
    const val N_FFT = 400
    const val REFLECT_PAD = N_FFT / 2
    const val PADDED_SAMPLES = WINDOW_SAMPLES + N_FFT

    /** Encoder frames that carry real audio for a chunk of [samples] length. */
    fun validFrames(samples: Int): Int {
        val byDuration = (samples.toDouble() / SAMPLE_RATE * FPS).toInt()
        return maxOf(1, minOf(byDuration, ENCODER_FRAMES))
    }

    fun frameToMs(frame: Double): Double = frame * MS_PER_FRAME + OFFSET_MS
}
