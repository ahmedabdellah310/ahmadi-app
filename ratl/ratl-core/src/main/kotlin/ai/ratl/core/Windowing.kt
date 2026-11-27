package ai.ratl.core

/**
 * Overlap-and-discard stitching of encoder logits across long audio.
 *
 * Port of the frozen `run_windows` (models/verify_onnx_g2_e2e.py). 15 s windows
 * on a 10 s hop; the 2.5 s of context at each internal edge is discarded so
 * every retained frame was decoded with full context on both sides. No discard
 * at the very start or the very end, where there is no context to have.
 *
 * Measured contribution is small (+0.0031 on held-out data). It is kept because
 * it is free and positive, not because it was decisive - the record says so
 * plainly and this comment does too.
 */
object Windowing {

    /** One window's plan: where it starts, and which of its frames are kept. */
    data class Window(val start: Int, val validFrames: Int, val keepLo: Int, val keepHi: Int)

    /**
     * The window plan for [nSamples] of audio. Pure arithmetic, no audio needed,
     * which is what makes it directly checkable against the T0 vector.
     */
    fun plan(nSamples: Int): List<Window> {
        val win = Frames.WINDOW_SAMPLES
        val hop = Frames.HOP_SAMPLES
        val margin = Frames.MARGIN_FRAMES
        if (nSamples <= win) {
            val v = Frames.validFrames(nSamples)
            return listOf(Window(0, v, 0, v))
        }
        val out = ArrayList<Window>()
        var start = 0
        while (start < nSamples) {
            val chunkLen = minOf(win, nSamples - start)
            val v = Frames.validFrames(chunkLen)
            val lo = if (start == 0) 0 else margin
            val hi = if (start + win >= nSamples) v else v - margin
            if (hi > lo) out.add(Window(start, v, lo, hi))
            if (start + win >= nSamples) break
            start += hop
        }
        return out
    }

    /**
     * Decode [audio] window by window and stitch the retained frames.
     *
     * @return logits over the whole utterance, `[frames][vocab]`
     */
    fun runWindows(audio: FloatArray, model: AcousticModel): Array<FloatArray> {
        val plan = plan(audio.size)
        val kept = ArrayList<FloatArray>(plan.sumOf { it.keepHi - it.keepLo })
        for (w in plan) {
            val end = minOf(w.start + Frames.WINDOW_SAMPLES, audio.size)
            val chunk = audio.copyOfRange(w.start, end)
            val logits = model.logits(FrontEnd.prepare(chunk))
            for (f in w.keepLo until w.keepHi) kept.add(logits[f])
        }
        return kept.toTypedArray()
    }
}
