package ai.ratl.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T0: pure functions against the committed golden vectors.
 *
 * Tolerances follow the manifest and the design rule behind it - assert exactly
 * on discrete outputs, tolerate only continuous ones. Frame indices come from an
 * argmax path, so any tolerance there would hide a real divergence rather than
 * absorb numerical noise.
 */
class T0ConformanceTest {

    /**
     * The manifest's 1e-9 tolerance was written for a float64 -> float64
     * comparison. RATL's logSoftmax takes FloatArray because ONNX Runtime emits
     * float32, so feeding the vector's float64 inputs through the real signature
     * quantises them before any RATL arithmetic runs. That quantisation alone
     * accounts for up to 1.32e-6 here (measured per case: 2.1e-7, 1.32e-6,
     * 9.8e-7, 0.0). The tolerance below covers it with headroom while staying
     * orders of magnitude tighter than any genuine arithmetic error would be.
     */
    @Test
    fun `logSoftmax matches the reference within float32 input quantisation`() {
        val json = Vectors.read("t0_log_softmax.json")
        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        assertTrue(cases.isNotEmpty())
        for ((ci, case) in cases.withIndex()) {
            val inp = Vectors.field(case, "input")
            val want = Vectors.field(case, "output")
            val rows = Vectors.elements(inp, inp.indexOf('['))
            val wantRows = Vectors.elements(want, want.indexOf('['))
            val logits = Array(rows.size) { r ->
                Vectors.doubles(rows[r]).let { d -> FloatArray(d.size) { d[it].toFloat() } }
            }
            val got = ForcedAligner.logSoftmax(logits)
            for (r in rows.indices) {
                val expected = Vectors.doubles(wantRows[r])
                for (c in expected.indices) {
                    assertTrue(
                        abs(got[r][c] - expected[c]) <= 1e-5,
                        "case $ci [$r][$c]: got ${got[r][c]} want ${expected[c]}",
                    )
                }
            }
        }
    }

    @Test
    fun `forced alignment reproduces spans exactly`() {
        val json = Vectors.read("t0_forced_align.json")
        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val note = Vectors.field(case, "note")
            val logitsSlice = Vectors.field(case, "logits")
            val rows = Vectors.elements(logitsSlice, logitsSlice.indexOf('['))
            val logits = Array(rows.size) { r ->
                Vectors.doubles(rows[r]).let { d -> FloatArray(d.size) { d[it].toFloat() } }
            }
            val labels = Vectors.ints(Vectors.field(case, "labels"))
            val want = Vectors.pairsOrNull(Vectors.field(case, "spans"))

            val got = ForcedAligner.align(ForcedAligner.logSoftmax(logits), labels, blank = 0)
            assertEquals(want.size, got.size, "[$note] label count")
            for (i in want.indices) {
                val w = want[i]
                val g = got[i]
                if (w == null) {
                    assertEquals(null, g, "[$note] label $i should be null")
                } else {
                    assertTrue(g != null, "[$note] label $i unexpectedly null")
                    assertEquals(w[0], g!!.startFrame, "[$note] label $i start")
                    assertEquals(w[1], g.endFrame, "[$note] label $i end")
                }
            }
        }
    }

    @Test
    fun `M2E edge spike reproduces every case including both edge fallbacks`() {
        val json = Vectors.read("t0_word_bounds.json")
        val fixture = Vectors.field(json, "fixture")
        val spans = Vectors.pairsOrNull(Vectors.field(fixture, "spans"))
            .map { it?.let { p -> p[0] until p[1] } }
        val tok = Vectors.pairsOrNull(Vectors.field(fixture, "tok_spans"))
            .map { it?.let { p -> LabelSpan(p[0], p[1]) } }
        val nTok = Vectors.field(fixture, "n_tok").toInt()
        val totalFrames = Vectors.field(fixture, "T").toInt()

        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        var checked = 0
        for (case in cases) {
            if (Vectors.field(case, "rule") != "M2E_edge_spike") continue
            val edge = Vectors.field(case, "edge")
            if (edge == "missing_separator" || edge == "null_span") continue
            val wi = Vectors.field(case, "word").toInt()
            val boundsField = Vectors.field(case, "bounds")
            val got = BoundaryRule.m2eEdgeSpike(spans, tok, wi, nTok, totalFrames)
            if (boundsField.startsWith("null")) {
                assertEquals(null, got, "word $wi ($edge) should be null")
            } else {
                val want = Vectors.doubles(boundsField)
                assertTrue(got != null, "word $wi ($edge) unexpectedly null")
                assertEquals(want[0], got!!.startFrame, "word $wi ($edge) start")
                assertEquals(want[1], got.endFrame, "word $wi ($edge) end")
            }
            checked++
        }
        assertTrue(checked >= 3, "expected the frozen rule's cases, checked $checked")
    }

    @Test
    fun `windowing geometry reproduces the plan exactly`() {
        val json = Vectors.read("t0_windowing.json")
        assertEquals(Frames.WINDOW_S, Vectors.field(json, "window_s").toDouble())
        assertEquals(Frames.HOP_S, Vectors.field(json, "hop_s").toDouble())
        assertEquals(Frames.MARGIN_S, Vectors.field(json, "margin_s").toDouble())
        assertEquals(Frames.MS_PER_FRAME, Vectors.field(json, "ms_per_frame").toDouble())
        assertEquals(Frames.ENCODER_FRAMES, Vectors.field(json, "enc_frames").toInt())

        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val n = Vectors.field(case, "n_samples").toInt()
            val wantTotal = Vectors.field(case, "total_frames").toInt()
            val windows = Vectors.field(case, "windows")
            val wantWindows = Vectors.elements(windows, windows.indexOf('['))
            val plan = Windowing.plan(n)
            assertEquals(wantWindows.size, plan.size, "window count for n=$n")
            var total = 0
            for (i in plan.indices) {
                val w = wantWindows[i]
                assertEquals(Vectors.field(w, "start").toInt(), plan[i].start, "n=$n w$i start")
                assertEquals(Vectors.field(w, "valid").toInt(), plan[i].validFrames, "n=$n w$i valid")
                assertEquals(Vectors.field(w, "keep_lo").toInt(), plan[i].keepLo, "n=$n w$i lo")
                assertEquals(Vectors.field(w, "keep_hi").toInt(), plan[i].keepHi, "n=$n w$i hi")
                total += plan[i].keepHi - plan[i].keepLo
            }
            assertEquals(wantTotal, total, "total frames for n=$n")
        }
    }

    @Test
    fun `reflect padding matches the T1 vector`() {
        val json = Vectors.read("t1_frontend.json")
        assertEquals(Frames.N_FFT, Vectors.field(json, "n_fft").toInt())
        assertEquals(Frames.REFLECT_PAD, Vectors.field(json, "pad").toInt())

        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val pcmFile = Vectors.field(case, "pcm_file")
            val nPadded = Vectors.field(case, "n_padded").toInt()
            val head = Vectors.doubles(Vectors.field(case, "pad_head"))
            val tail = Vectors.doubles(Vectors.field(case, "pad_tail"))

            val bytes = java.io.File(Vectors.repoRoot, "conformance/vectors/$pcmFile").readBytes()
            val pcm = FloatArray(bytes.size / 2) { i ->
                val lo = bytes[2 * i].toInt() and 0xFF
                val hi = bytes[2 * i + 1].toInt()
                ((hi shl 8) or lo).toShort().toFloat() / 32768.0f
            }
            val prepared = FrontEnd.prepare(pcm)
            assertEquals(nPadded, prepared.size, "$pcmFile padded length")
            for (i in head.indices) {
                assertTrue(
                    abs(prepared[i] - head[i]) <= 1e-6,
                    "$pcmFile head[$i]: got ${prepared[i]} want ${head[i]}",
                )
            }
            for (i in tail.indices) {
                val j = prepared.size - tail.size + i
                assertTrue(
                    abs(prepared[j] - tail[i]) <= 1e-6,
                    "$pcmFile tail[$i]: got ${prepared[j]} want ${tail[i]}",
                )
            }
        }
    }
}
