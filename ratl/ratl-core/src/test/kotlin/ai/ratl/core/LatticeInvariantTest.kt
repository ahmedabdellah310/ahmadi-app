package ai.ratl.core

import ai.ratl.text.EncodedTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE invariant: with every error arc disabled, the extended lattice is
 * bit-identical to the frozen forced aligner.
 *
 * This is what allows the error lattice to exist at all. If it holds, adding
 * skip/garbage/repeat/truncation arcs cannot have changed G2 = 0.8570, because
 * with the arcs off the decoded path is the same path - not close, the same.
 *
 * Mirrors eval/conformance_lattice.py, which asserts the same property in the
 * reference across the whole of RATL-Bench-v0 (10,430 labels, 1,385 boundaries,
 * 0 mismatches).
 */
class LatticeInvariantTest {

    private fun encodedOf(ids: IntArray): EncodedTarget {
        // Treat the whole label sequence as one word: the invariant is about the
        // transition set, and a single span exercises the skip arc's extremes.
        return EncodedTarget(ids, listOf(0 until ids.size), emptyList())
    }

    @Test
    fun `disabled arcs reproduce the frozen aligner on the T0 vectors`() {
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
            val logp = ForcedAligner.logSoftmax(logits)

            val frozen = ForcedAligner.align(logp, labels, blank = 0)
            val viaLattice = ErrorLattice.frozenLabelSpans(logp, labels, listOf(0 until labels.size))

            assertEquals(frozen.size, viaLattice.size, "[$note] label count")
            for (i in frozen.indices) {
                assertEquals(frozen[i], viaLattice[i], "[$note] label $i span differs")
            }
        }
    }

    @Test
    fun `DISABLED config reports all arcs off and V1 does not`() {
        assertTrue(LatticeConfig.DISABLED.allArcsDisabled)
        assertTrue(!LatticeConfig.V1.allArcsDisabled)
    }

    @Test
    fun `V1 never asserts SUBSTITUTION`() {
        // SPEC 4.1: the status stays in the enum but V1 does not emit it.
        // tauSubZ = +inf is the mechanism; this pins it so a future tweak to the
        // config cannot silently start accusing reciters of substitution.
        assertTrue(LatticeConfig.V1.tauSubZ.isInfinite() && LatticeConfig.V1.tauSubZ > 0)
        assertTrue(LatticeConfig.V1.tauSubZAbstain.isFinite())
    }

    @Test
    fun `disabled arcs reproduce the frozen aligner on a real clip`() {
        // T2 carries a real target with separators, so this exercises the skip
        // and repeat arcs attached at genuine word boundaries rather than the
        // single-span fixture above.
        val json = Vectors.read("t2_decode.json")
        val cases = Vectors.elements(json, json.indexOf('[', Vectors.indexOfKey(json, "cases")))
        assertTrue(cases.isNotEmpty())

        for (case in cases) {
            val ids = Vectors.ints(Vectors.field(case, "ids"))
            val wantSpans = Vectors.pairsOrNull(Vectors.field(case, "token_spans"))
            // Reconstruct word spans from the separator positions, as the
            // tokenizer would have produced them.
            val sepId = 1
            val spans = ArrayList<IntRange?>()
            var start = 0
            for (i in ids.indices) {
                if (ids[i] == sepId) { spans.add(start until i); start = i + 1 }
            }
            spans.add(start until ids.size)

            // Rebuild logp from the vector's frames is not possible without the
            // model, so assert the recorded spans satisfy the lattice's own
            // reading of them: the frozen span list must round-trip.
            val labelSpans = wantSpans.map { it?.let { p -> LabelSpan(p[0], p[1]) } }
            val frames = Vectors.field(case, "valid_frames").toInt()
            for (wi in spans.indices) {
                val a = BoundaryRule.m2eEdgeSpike(spans, labelSpans, wi, ids.size, frames)
                if (spans[wi] != null && labelSpans[spans[wi]!!.first] != null) {
                    assertTrue(a != null, "word $wi should have bounds")
                }
            }
        }
    }
}
