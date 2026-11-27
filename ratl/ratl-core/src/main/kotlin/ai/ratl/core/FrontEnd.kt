package ai.ratl.core

/**
 * Everything RATL does to audio before the model sees it.
 *
 * The log-mel front end lives INSIDE the ONNX graph (a DFT expressed as a
 * convolution), so there is no hand-ported DSP here and no filterbank to drift.
 * What remains is exactly two steps: pad the chunk out to the 15 s window, then
 * reflect-pad by n_fft/2 for the STFT's centring.
 *
 * Reproduces the reference `reflect_pad` (models/mel_path_spike2.py):
 * head is a[p], a[p-1] .. a[1] and tail is a[n-2], a[n-3] .. a[n-1-p], i.e. a
 * mirror that does NOT repeat the edge sample.
 */
object FrontEnd {

    /**
     * Zero-pad [chunk] to the window length, then reflect-pad for the STFT.
     *
     * @return an array of [Frames.PADDED_SAMPLES] floats, ready for the model
     */
    fun prepare(chunk: FloatArray): FloatArray {
        val win = Frames.WINDOW_SAMPLES
        val padded = if (chunk.size >= win) {
            chunk.copyOf(win)
        } else {
            FloatArray(win).also { chunk.copyInto(it) }
        }
        return reflectPad(padded)
    }

    /** Mirror-pad by [Frames.REFLECT_PAD] on each side, excluding the edge sample. */
    fun reflectPad(a: FloatArray): FloatArray {
        val p = Frames.REFLECT_PAD
        val n = a.size
        require(n > p) { "signal shorter than the reflect pad ($n <= $p)" }
        val out = FloatArray(n + 2 * p)
        for (i in 0 until p) out[i] = a[p - i]                    // a[p]..a[1]
        a.copyInto(out, p)
        for (i in 0 until p) out[p + n + i] = a[n - 2 - i]        // a[n-2]..a[n-1-p]
        return out
    }
}
