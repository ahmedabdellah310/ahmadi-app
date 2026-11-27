"""RATL reference error-tolerant lattice - Python reference implementation.

This is the reference that defines the production WordResult contract. It is
research/eval tooling (SPEC 6.2: no production RATL implementation is written in
Python); RATL Core in Kotlin must reproduce its output, and the conformance
vectors are what make that checkable.

WHAT IT IS
----------
The frozen CTC forced-alignment lattice, plus four error arcs:

    skip    bypass word i (and one adjacent separator)     -> OMISSION
    garbage self-looping "any token" state at each gap     -> INSERTION / SUBSTITUTION
    repeat  re-enter word i after leaving it               -> REPETITION
    (truncation needs no arc - it is read off the terminal state)

THE INVARIANT
-------------
With lam_skip = lam_ins = lam_rep = -inf every added arc and every garbage state
is unreachable, so the reachable sub-lattice is exactly the standard blank-
augmented CTC lattice with [stay, s-1, s-2] transitions. Edge ordering is chosen
so ties break identically to g2.ctc_forced_align. The Viterbi result is then
bit-identical to the frozen aligner, and G2 = 0.8570 is preserved by
construction rather than by re-measurement. conformance_lattice.py asserts this.

The frozen g2 module is imported unmodified and never patched.

WHY lam_skip IS THE OMISSION THRESHOLD
--------------------------------------
Viterbi takes the skip arc for word i exactly when

    score(path without word i) + lam_skip > score(path with word i)

i.e. exactly when LLR_i < -lam_skip. The arc penalty and the omission decision
threshold are the same number, so there is no second free parameter hiding here.
"""
import importlib.util
import json
import pathlib
import sys
from dataclasses import dataclass, field, asdict

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
SPIKE = ROOT / "spike"
sys.path.insert(0, str(SPIKE))


def _load(name, fn, base=SPIKE):
    sp = importlib.util.spec_from_file_location(name, base / fn)
    m = importlib.util.module_from_spec(sp)
    sys.modules[name] = m
    sp.loader.exec_module(m)
    return m


g2 = _load("g2", "05_g2_alignment.py")
e1 = _load("e1", "13_eval_exp1.py")
d3 = _load("d3", "18_decode_extent.py")
m1 = _load("m1", "20_m1_select.py")

NEG = -1e30
BLANK = 0

# ---- the eight SPEC 4 statuses. PENDING is streaming-only (M2.2).
CORRECT = "CORRECT"
OMISSION = "OMISSION"
SUBSTITUTION = "SUBSTITUTION"
REPETITION = "REPETITION"
INSERTION = "INSERTION"
INCOMPLETE = "INCOMPLETE"
UNCERTAIN = "UNCERTAIN"
PENDING = "PENDING"


@dataclass
class LatticeConfig:
    """All decision parameters. Selected on the seen-reciter tier only."""
    lam_skip: float = -np.inf     # omission arc penalty == -tau_omit
    lam_ins: float = -np.inf      # garbage entry penalty
    lam_rep: float = -np.inf      # repetition arc penalty
    garb_pen: float = 0.0         # per-frame garbage penalty (>= 0)
    tau_uncertain: float = -np.inf  # LLR/token below this -> UNCERTAIN
    lam_trunc: float = -np.inf    # terminate mid-word (INCOMPLETE)
    tau_sub_z: float = np.inf     # RECITER-NORMALISED substitution outlier score
    # ---- abstention bands (SPEC 4 rule 1: decline to judge rather than accuse)
    # Evidence between the abstain band and the accuse threshold yields
    # UNCERTAIN. Set the abstain bound equal to the accuse bound to disable.
    tau_sub_z_abstain: float = np.inf   # z above this but below tau_sub_z -> UNCERTAIN
    tau_omit_abstain: float = -np.inf   # absent word with llr above this -> UNCERTAIN
    min_ins_frames: int = 5       # ignore garbage runs shorter than this (100 ms)
    conf_scale: float = 10.0      # logistic scale for confidence

    def frozen_equivalent(self):
        return (self.lam_skip == -np.inf and self.lam_ins == -np.inf
                and self.lam_rep == -np.inf and self.lam_trunc == -np.inf)


@dataclass
class WordResult:
    """SPEC 5.1 WordResult. This dataclass IS the frozen output contract."""
    index: int
    qul_key: str
    status: str
    confidence: float
    t_start_ms: float | None
    t_end_ms: float | None
    observed: str | None = None


@dataclass
class Assessment:
    """SPEC 5.1 Assessment."""
    words: list = field(default_factory=list)
    events: list = field(default_factory=list)
    audio_duration_ms: float = 0.0
    model_id: str = ""
    spec_version: str = "0.3"


# --------------------------------------------------------------------- lattice
def build_states(ids, word_spans):
    """Return (ext, can_skip, n_ctc, gap_of_word, S).

    States 0..n_ctc-1 are the standard blank-augmented CTC states.
    States n_ctc..S-1 are one garbage state per gap (M+1 gaps for M words).
    """
    L = len(ids)
    n_ctc = 2 * L + 1
    ext = np.full(n_ctc, BLANK, dtype=np.int64)
    ext[1::2] = ids
    can_skip = np.zeros(n_ctc, dtype=bool)
    if n_ctc > 2:
        can_skip[2:] = (ext[2:] != BLANK) & (ext[2:] != ext[:-2])
    n_words = len(word_spans)
    return ext, can_skip, n_ctc, n_words + 1, n_ctc + n_words + 1


def skip_arc_states(word_spans, wi, L):
    """Blank states a skip of word wi jumps between.

    Also consumes one adjacent separator so the remaining target has the same
    separator structure that build_tokens_v2 would produce for the shorter word
    list - which is what the validated LLR rescoring compares against.
    """
    sp = word_spans[wi]
    if sp is None:
        return None
    lo, hi = sp
    if wi > 0:
        return 2 * (lo - 1), 2 * hi          # separator + word
    if hi < L:
        return 2 * lo, 2 * (hi + 1)          # word + following separator
    return 2 * lo, 2 * hi


def build_edges(ids, word_spans, cfg):
    """Incoming-edge table (src, cost) padded to a fixed fan-in.

    Edge ORDER matters: [stay, s-1, s-2] come first for every CTC state so that
    argmax ties break exactly as g2.ctc_forced_align's argmax over [a0,a1,a2].
    """
    L = len(ids)
    ext, can_skip, n_ctc, n_gaps, S = build_states(ids, word_spans)
    inc = [[] for _ in range(S)]

    for s in range(n_ctc):                    # base CTC, in g2's order
        inc[s].append((s, 0.0))
        if s >= 1:
            inc[s].append((s - 1, 0.0))
        if s >= 2 and can_skip[s]:
            inc[s].append((s - 2, 0.0))

    gap_state = {}                            # gap g -> garbage state id
    for g in range(n_gaps):
        gs = n_ctc + g
        gap_state[g] = gs
        inc[gs].append((gs, 0.0))             # self loop

    # garbage attaches at the blank preceding word g (final blank for g == M)
    gap_blank = {}
    for g in range(n_gaps):
        if g < len(word_spans) and word_spans[g] is not None:
            b = 2 * (word_spans[g][0] - 1) if g > 0 else 2 * word_spans[g][0]
        else:
            b = n_ctc - 1
        gap_blank[g] = int(b)
        gs = gap_state[g]
        inc[gs].append((gap_blank[g], cfg.lam_ins))    # enter garbage
        inc[gap_blank[g]].append((gs, 0.0))            # leave garbage

    skip_edges, rep_edges = {}, {}
    for wi in range(len(word_spans)):
        arc = skip_arc_states(word_spans, wi, L)
        if arc is None:
            continue
        a, b = arc
        if 0 <= a < n_ctc and 0 <= b < n_ctc:
            inc[b].append((a, cfg.lam_skip))
            skip_edges[wi] = (a, b)
            sp = word_spans[wi]
            inc[2 * sp[0]].append((2 * sp[1], cfg.lam_rep))   # repeat: after -> before
            rep_edges[wi] = (2 * sp[1], 2 * sp[0])

    K = max(len(e) for e in inc)
    src = np.zeros((S, K), dtype=np.int32)
    cost = np.full((S, K), NEG, dtype=np.float64)
    for s, es in enumerate(inc):
        for k, (f, c) in enumerate(es):
            src[s, k] = f
            cost[s, k] = NEG if not np.isfinite(c) else c
    return {"ext": ext, "n_ctc": n_ctc, "S": S, "src": src, "cost": cost,
            "gap_state": gap_state, "gap_blank": gap_blank,
            "skip_edges": skip_edges, "rep_edges": rep_edges, "n_gaps": n_gaps}


def viterbi(logp, ids, word_spans, cfg):
    """Viterbi over the extended lattice. Returns (path, total, tables)."""
    tb = build_edges(ids, word_spans, cfg)
    ext, n_ctc, S = tb["ext"], tb["n_ctc"], tb["S"]
    T = logp.shape[0]

    emit = np.empty((T, S), dtype=np.float64)
    emit[:, :n_ctc] = logp[:, ext]
    if S > n_ctc:
        emit[:, n_ctc:] = (logp.max(axis=1) - cfg.garb_pen)[:, None]

    alpha = np.full(S, NEG, dtype=np.float64)
    alpha[0] = emit[0, 0]
    if n_ctc > 1:
        alpha[1] = emit[0, 1]
    for g, gs in tb["gap_state"].items():          # a leading insertion
        if np.isfinite(cfg.lam_ins):
            alpha[gs] = cfg.lam_ins + emit[0, gs]

    src, cost = tb["src"], tb["cost"]
    back = np.zeros((T, S), dtype=np.int32)
    ar = np.arange(S)
    for t in range(1, T):
        cand = alpha[src] + cost
        arg = cand.argmax(axis=1)
        best = cand[ar, arg]
        back[t] = src[ar, arg]
        alpha = best + emit[t]

    # Terminal states, in a FIXED order so ties break deterministically and
    # identically to the frozen aligner's `S-1 if a[S-1] >= a[S-2] else S-2`.
    ends = [n_ctc - 1, n_ctc - 2, tb["gap_state"][tb["n_gaps"] - 1]]
    ends = [e for e in ends if 0 <= e < S]
    vals = [alpha[e] for e in ends]
    if np.isfinite(cfg.lam_trunc):
        # TRUNCATION ARC: the recitation may stop anywhere, leaving the current
        # word only partly consumed. Paying lam_trunc buys the right to end in
        # any earlier CTC state. With lam_trunc = -inf this is unreachable and
        # the terminal set is exactly the frozen one.
        for e in range(n_ctc - 2):
            ends.append(e)
            vals.append(alpha[e] + cfg.lam_trunc)
    s = ends[int(np.argmax(vals))]
    total = float(vals[int(np.argmax(vals))])

    path = np.zeros(T, dtype=np.int32)
    for t in range(T - 1, -1, -1):
        path[t] = s
        s = int(back[t, s])
    return path, total, tb


def frozen_spans(path, ids, n_ctc):
    """Per-label (start, end) frames - the frozen aligner's output shape."""
    spans = []
    for l in range(len(ids)):
        occ = np.nonzero(path == 2 * l + 1)[0]
        spans.append((int(occ[0]), int(occ[-1])) if occ.size else None)
    return spans


# ------------------------------------------------------------------- decisions
def arc_traversals(path, edges):
    """Count traversals of specific (src, dst) lattice arcs in the decoded path.

    Repetition must be read off the REPEAT ARC, not off runs of label occupancy:
    CTC interleaves blank states between the characters of a single word, so
    label-occupancy runs count characters, not repetitions.
    """
    out = {}
    for wi, (src, dst) in edges.items():
        n = 0
        for t in range(1, len(path)):
            if int(path[t - 1]) == src and int(path[t]) == dst:
                n += 1
        out[wi] = n
    return out


def _runs(mask):
    """Contiguous True runs as (start, end_exclusive)."""
    out, i, n = [], 0, len(mask)
    while i < n:
        if mask[i]:
            j = i
            while j + 1 < n and mask[j + 1]:
                j += 1
            out.append((i, j + 1))
            i = j + 1
        else:
            i += 1
    return out


def llr_per_word(logp, ids, word_spans, words, stoi, wi):
    """Leave-one-word-out log-likelihood ratio, per token. Validated in llr_probe."""
    base = LatticeConfig()
    _, s_full, _ = viterbi(logp, ids, word_spans, base)
    kept = [w for j, w in enumerate(words) if j != wi]
    ids_wo, spans_wo, _ = e1.build_tokens_v2(kept, stoi)
    if not ids_wo:
        return None
    _, s_wo, _ = viterbi(logp, ids_wo, spans_wo, base)
    sp = word_spans[wi]
    n_tok = max(1, sp[1] - sp[0])
    return (s_full - s_wo) / n_tok


def llr_all(logp, words, stoi):
    """LLR per token for every word. M+1 Viterbi passes, not 2M.

    Same quantity validated in llr_probe.py: positive means the audio is better
    explained WITH the word than without it.
    """
    base = LatticeConfig()
    ids, word_spans, _ = e1.build_tokens_v2(words, stoi)
    if not ids:
        return {}
    _, s_full, _ = viterbi(logp, ids, word_spans, base)
    out = {}
    for wi, sp in enumerate(word_spans):
        if sp is None:
            continue
        kept = [w for j, w in enumerate(words) if j != wi]
        ids_wo, spans_wo, _ = e1.build_tokens_v2(kept, stoi)
        if not ids_wo:
            continue
        _, s_wo, _ = viterbi(logp, ids_wo, spans_wo, base)
        out[wi] = {"total": s_full - s_wo,
                   "per_token": (s_full - s_wo) / max(1, sp[1] - sp[0])}
    return out


def compute_signals(logp, words, stoi, cfg):
    """Run the lattice once and record every raw signal, BEFORE any threshold.

    Separated from classification so the reference implementation and the
    threshold sweep share one source of truth: the sweep caches these signals
    and calls classify(), it does not reimplement the decision rules.
    """
    ids, word_spans, _ = e1.build_tokens_v2(words, stoi)
    if not ids:
        return None
    T = logp.shape[0]
    path, _, tb = viterbi(logp, ids, word_spans, cfg)
    n_ctc = tb["n_ctc"]
    lab_spans = frozen_spans(path, ids, n_ctc)
    reps = arc_traversals(path, tb["rep_edges"])

    gset = set(tb["gap_state"].values())
    inv = {v: k for k, v in tb["gap_state"].items()}
    isg = np.array([int(x) in gset for x in path])
    garb = {}
    for a, b in _runs(isg):
        if b - a >= cfg.min_ins_frames:
            garb.setdefault(inv[int(path[a])], []).append([int(a), int(b)])

    terminal = int(path[-1])
    cut_word = -1
    if terminal < n_ctc - 2 and np.isfinite(cfg.lam_trunc):
        tl = (terminal - 1) // 2 if terminal % 2 == 1 else terminal // 2
        for wj, spj in enumerate(word_spans):
            if spj is not None and spj[0] <= tl < spj[1]:
                cut_word = wj
                break

    raw_gap, bounds, present = {}, {}, {}
    for wi, sp in enumerate(word_spans):
        if sp is None:
            continue
        lo, hi = sp
        wmask = np.zeros(T, dtype=bool)
        for l in range(lo, hi):
            wmask |= (path == 2 * l + 1)
        rr = _runs(wmask)
        present[wi] = bool(rr)
        if rr:
            a, b = rr[0][0], rr[-1][1]
            seg = path[a:b]
            labs = np.array([tb["ext"][x] if x < n_ctc else 0 for x in seg])
            raw_gap[wi] = float(np.mean(logp[np.arange(a, b)].max(axis=1))
                                - np.mean(logp[np.arange(a, b), labs]))
            bd = d3.word_bounds("M2E_edge_spike", word_spans, lab_spans, wi,
                                len(ids), T)
            if bd is not None:
                bounds[wi] = [bd[0] * m1.MS_PER_FRAME, bd[1] * m1.MS_PER_FRAME]

    # reciter-normalised substitution outlier score (unit-free, leave-one-out)
    sub_z = {}
    if len(raw_gap) >= 3:
        for wi, v in raw_gap.items():
            oth = np.array([x for k, x in raw_gap.items() if k != wi])
            med = float(np.median(oth))
            mad = float(np.median(np.abs(oth - med))) * 1.4826
            if mad < 1e-6:
                mad = float(np.std(oth)) or 1e-6
            sub_z[wi] = (v - med) / mad

    return {"T": int(T), "n_words": len(word_spans), "cut_word": cut_word,
            "garbage": garb, "present": present, "n_rep": {k: int(v) for k, v in reps.items()},
            "raw_gap": raw_gap, "sub_z": sub_z, "bounds": bounds}


def classify(sig, wi, cfg, llr=None):
    """Map recorded signals to one WordStatus. THE decision rule.

    Order is significant and deterministic:
      1. absent            -> OMISSION, or SUBSTITUTION if something was said there
      2. repeat arc taken  -> REPETITION
      3. after the cut     -> OMISSION   (recitation ended before this word)
      4. at the cut        -> INCOMPLETE
      5. acoustic outlier  -> SUBSTITUTION  (reciter-normalised)
      6. low confidence    -> UNCERTAIN
      7. otherwise         -> CORRECT
    """
    if not sig["present"].get(wi, False):
        # The word was skipped. If the evidence for absence is only marginal,
        # decline to judge rather than accuse the reciter.
        if llr is not None and llr > cfg.tau_omit_abstain:
            return UNCERTAIN
        said = sig["garbage"].get(wi) or sig["garbage"].get(wi + 1)
        return SUBSTITUTION if said else OMISSION
    if sig["n_rep"].get(wi, 0) >= 1 and np.isfinite(cfg.lam_rep):
        return REPETITION
    cw = sig["cut_word"]
    if cw >= 0 and wi > cw:
        return OMISSION
    if cw >= 0 and wi == cw:
        return INCOMPLETE
    z = sig["sub_z"].get(wi)
    if z is not None and z > cfg.tau_sub_z:
        return SUBSTITUTION
    # Abstention must be reachable for the class that dominates the false-reject
    # rate. Previously SUBSTITUTION was decided first and returned immediately,
    # so no substitution candidate could ever abstain and UNCERTAIN stayed at
    # exactly 0% no matter how the band was set.
    if z is not None and z > cfg.tau_sub_z_abstain:
        return UNCERTAIN
    if llr is not None and llr < cfg.tau_uncertain:
        return UNCERTAIN
    return CORRECT


def assess(logp, words, stoi, cfg, qul_prefix="", model_id="", llr_cache=None):
    """Full SPEC 5 Assessment for one utterance."""
    sig = compute_signals(logp, words, stoi, cfg)
    if sig is None:
        return Assessment(model_id=model_id)
    results = []
    for wi in range(sig["n_words"]):
        rec = None if llr_cache is None else llr_cache.get(wi)
        llr = None if rec is None else rec["per_token"]
        conf = 0.0 if llr is None else float(1.0 / (1.0 + np.exp(-llr / cfg.conf_scale)))
        status = classify(sig, wi, cfg, llr)
        b = sig["bounds"].get(wi)
        results.append(WordResult(wi, f"{qul_prefix}{wi + 1}", status, round(conf, 4),
                                  None if b is None else round(b[0], 1),
                                  None if b is None else round(b[1], 1)))
    events = []
    for g, runs in sig["garbage"].items():
        if g < len(results) and results[g].status in (OMISSION, SUBSTITUTION):
            continue
        for a, b in runs:
            events.append({"type": "INSERTION", "after_word": g - 1,
                           "t_start_ms": round(a * m1.MS_PER_FRAME, 1),
                           "t_end_ms": round(b * m1.MS_PER_FRAME, 1)})
    return Assessment(words=results, events=events,
                      audio_duration_ms=round(sig["T"] * m1.MS_PER_FRAME, 1),
                      model_id=model_id)


def assessment_to_json(a):
    return {"words": [asdict(w) for w in a.words], "events": a.events,
            "audio_duration_ms": a.audio_duration_ms,
            "model_id": a.model_id, "spec_version": a.spec_version}
