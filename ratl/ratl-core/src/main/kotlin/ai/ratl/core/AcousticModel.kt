package ai.ratl.core

/**
 * Frame-synchronous posterior source. SPEC 5.6.
 *
 * The extension point that keeps the least-portable dependency out of the part
 * that must be portable: RATL Core owns alignment, and acoustic inference sits
 * behind this interface. V1 ships one implementation, backed by ONNX Runtime.
 * An integrator may supply an NPU or server-side source; the SDK never requires
 * one.
 */
interface AcousticModel : AutoCloseable {

    /** Vocabulary size, i.e. the logits' last dimension. */
    val vocabularySize: Int

    /**
     * Run one prepared window.
     *
     * @param preparedPcm output of [FrontEnd.prepare]: [Frames.PADDED_SAMPLES] floats
     * @return logits, `[Frames.ENCODER_FRAMES][vocabularySize]`, unnormalised
     */
    fun logits(preparedPcm: FloatArray): Array<FloatArray>

    override fun close() {}
}
