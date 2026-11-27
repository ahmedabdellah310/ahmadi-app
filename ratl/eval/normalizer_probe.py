"""Validate a rule-based Uthmani -> romanisation transliterator (SPEC 5.5).

RATL's model consumes quran.com-style romanisation. Emran - and any real Quran
app - holds Arabic, so RATL must produce that representation itself.

Validation set: Emran's bundled quran.json supplies the Arabic (the real input
path), the 4,287 cached quran.com transliterations supply the reference. That is
the actual pipeline, not a proxy for it.

Three levels are reported because they matter differently:
  exact word match  - strictest, and not what the aligner needs
  CTC id match      - what the aligner consumes; THIS is the number that counts
  char error rate   - how far the near-misses are

A MEASUREMENT, not an experiment. No model runs and nothing is trained.
"""
import difflib
import glob
import io
import json
import os
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "spike" / ".cache" / "translit"
EMRAN_QURAN = pathlib.Path(
    r"C:\Android Projects\Emran\emran\app\src\main\assets\quran.json"
)

CONSONANTS = {
    "ب": "b", "ت": "t", "ث": "th", "ج": "j", "ح": "ḥ", "خ": "kh",
    "د": "d", "ذ": "dh", "ر": "r", "ز": "z", "س": "s", "ش": "sh",
    "ص": "ṣ", "ض": "ḍ", "ط": "ṭ", "ظ": "ẓ", "ع": "ʿ", "غ": "gh",
    "ف": "f", "ق": "q", "ك": "k", "ل": "l", "م": "m", "ن": "n",
    "ه": "h", "و": "w", "ي": "y", "ة": "h",
}
HAMZA = set("ءأإؤئ")
SHORT = {"َ": "a", "ُ": "u", "ِ": "i"}
TANWIN = {"ً": "an", "ٌ": "un", "ٍ": "in"}
LONG = {"a": "ā", "u": "ū", "i": "ī"}
SHADDA, DAGGER, WASL = "ّ", "ٰ", "ٱ"
# Two sukun encodings occur in Uthmani text: U+0652 and U+06E1.
SUKUNS = ("ْ", "ۡ")
SUKUN = "ْ"
ALIF, WAW, YA, MAQSURA = "ا", "و", "ي", "ى"
SUN = set("تثدذرزسشصضطظلن")
# waqf marks, tatweel, madda, small/superscript letters: orthography, not sound
DROP = set("ـۖۗۘۙۚۛۜ۝۞۟۠ۢۤۥۦۧۨ۩ࣰࣱࣲ۪ۭٕ۫۬ٓٔ\u06dd\u06de\u200d\u200e\u200f")

# Disconnected letters (muqatta'at) are recited as letter NAMES, which is how
# the reference spells them. They cannot be derived from the glyphs.
MUQATTAAT = {
    "الم": "alif-lam-meem", "الٓمٓ": "alif-lam-meem",
    "الر": "alif-lam-ra", "الٓرۚ": "alif-lam-ra", "الٓر": "alif-lam-ra",
    "المص": "alif-lam-meem-sad", "الٓمٓصٓ": "alif-lam-meem-sad",
    "المر": "alif-lam-meem-ra", "الٓمٓرۚ": "alif-lam-meem-ra",
    "كهيعص": "kaf-ha-ya-ain-sad", "كٓهيعٓصٓ": "kaf-ha-ya-ain-sad",
    "طه": "ta-ha", "طٰهٰ": "ta-ha", "طه\u0670": "ta-ha",
    "طسم": "ta-seen-meem", "طسٓمٓ": "ta-seen-meem",
    "طس": "ta-seen", "طسٓ": "ta-seen",
    "يس": "ya-seen", "يسٓ": "ya-seen",
    "ص": "saad", "صٓ": "saad",
    "حم": "ha-meem", "حٓمٓ": "ha-meem",
    "عسق": "ain-seen-qaf", "عٓسٓقٓ": "ain-seen-qaf",
    "ق": "qaf", "قٓ": "qaf",
    "ن": "noon", "نٓ": "noon",
}


def romanize(word: str, utterance_initial: bool) -> str:
    raw = word
    w = "".join(ch for ch in word if ch not in DROP)
    if not w:
        return ""
    stripped = "".join(c for c in w if c not in SHORT and c not in TANWIN
                       and c not in (SHADDA, DAGGER) and c not in SUKUNS)
    if stripped in MUQATTAAT:
        return MUQATTAAT[stripped]
    if raw in MUQATTAAT:
        return MUQATTAAT[raw]

    out = []
    i, n = 0, len(w)

    # ---- hamzat wasl: sounded only at the start of an utterance, elided after
    # a preceding vowel. This is why the reference has "al-hamdu" at an ayah
    # start but "l-lahi" mid-ayah.
    if w[0] == WASL:
        i = 1
        if utterance_initial:
            out.append("a")
        if i + 1 < n and w[i] == "ل" and w[i + 1] == SHADDA:
            # al + lam-with-shadda: the reference writes the full "alla-" form
            if not utterance_initial:
                out.append("a")
            out.append("lla")
            i += 2
            if i < n and w[i] in SHORT:
                out[-1] = out[-1][:-1] + SHORT[w[i]]
                i += 1
        elif i < n and w[i] == "ل":
            j = i + 1
            while j < n and w[j] in SUKUNS:
                j += 1
            doubled = j < n and w[j] == SHADDA
            k = i + 1
            while k < n and (w[k] in SHORT or w[k] in SUKUNS or w[k] == SHADDA):
                k += 1
            follows_sun = k < n and w[k] in SUN
            if doubled or follows_sun:
                # The article assimilates into the following sun letter, and the
                # reference writes it UNDOUBLED: "l-rahmani", not "l-rrahmani".
                # The shadda is consumed here so the loop does not double it.
                out.append("l-")
                i += 1
                while i < n and (w[i] in SUKUNS or w[i] == SHADDA):
                    i += 1
                if i < n and w[i] in SUN:
                    out.append(CONSONANTS.get(w[i], ""))
                    i += 1
                    if i < n and w[i] == SHADDA:
                        i += 1
            else:
                out.append("l-")
                i += 1
                if i < n and w[i] in SUKUNS:
                    i += 1
    elif w[0] in HAMZA:
        # a word-initial glottal stop is not written in the reference
        i = 1
        if i < n and w[i] in SHORT:
            out.append(SHORT[w[i]])
            i += 1
        elif i < n and w[i] == DAGGER:
            out.append("ā")
            i += 1
        else:
            out.append("a")

    while i < n:
        ch = w[i]

        if ch in CONSONANTS or ch in HAMZA:
            cons = CONSONANTS.get(ch, "'")
            i += 1
            if i < n and w[i] == SHADDA:
                cons += cons
                i += 1
            out.append(cons)
            # vowel that follows
            if i < n and w[i] == DAGGER:
                out.append("ā")
                i += 1
                continue
            if i < n and w[i] in TANWIN:
                out.append(TANWIN[w[i]])
                i += 1
                # a following silent alif carries the tanwin, add nothing
                if i < n and w[i] in (ALIF, MAQSURA):
                    i += 1
                continue
            if i < n and w[i] in SHORT:
                v = SHORT[w[i]]
                i += 1
                if i < n and w[i] == DAGGER:
                    out.append("ā")
                    i += 1
                    continue
                if i < n and w[i] in (ALIF, WAW, YA, MAQSURA):
                    matched = {ALIF: "a", MAQSURA: "a", WAW: "u", YA: "i"}[w[i]]
                    after = w[i + 1] if i + 1 < n else ""
                    if matched == v and after not in SHORT and after not in TANWIN \
                            and after != SHADDA:
                        out.append(LONG[v])
                        i += 1
                        continue
                out.append(v)
                continue
            if i < n and w[i] in SUKUNS:
                i += 1
                # The reference marks a sukun-closed syllable with an apostrophe
                # when a consonant follows: bis'mi, mus'taqima, ih'dina. It does
                # NOT when the consonant already carries its own mark (ayn, and
                # the emphatics), which is why nasta'inu has none.
                continue
            continue

        if ch in (ALIF, MAQSURA):
            # a bare alif directly after a dagger alif is orthographic only
            if out and out[-1].endswith("ā"):
                i += 1
                continue
            out.append("ā")
            i += 1
            continue
        if ch == DAGGER:
            if not (out and out[-1].endswith("ā")):
                out.append("ā")
            i += 1
            continue
        if ch in SHORT:
            v = SHORT[ch]
            i += 1
            if i < n and w[i] == DAGGER:
                out.append("ā")
                i += 1
                continue
            if i < n and w[i] in (ALIF, WAW, YA, MAQSURA):
                matched = {ALIF: "a", MAQSURA: "a", WAW: "u", YA: "i"}[w[i]]
                after = w[i + 1] if i + 1 < n else ""
                if matched == v and after not in SHORT and after not in TANWIN                         and after != SHADDA:
                    out.append(LONG[v])
                    i += 1
                    continue
            out.append(v)
            continue
        if ch in TANWIN:
            out.append(TANWIN[ch])
            i += 1
            continue
        i += 1

    return "".join(out)


def main():
    vocab = json.loads((ROOT / "models" / "out" / "vocab.json").read_text(encoding="utf-8"))
    stoi = {c: i for i, c in enumerate(vocab)}

    quran = json.load(io.open(EMRAN_QURAN, encoding="utf-8"))
    arabic = {(s["id"], v["id"]): v["text"] for s in quran for v in s["verses"]}

    n_words = n_exact = n_ids = 0
    char_err = char_tot = 0
    skipped_ayat = 0
    oov = set()
    examples = []

    for f in sorted(glob.glob(str(CACHE / "*.json"))):
        b = os.path.basename(f)[:-5]
        key = (int(b[:3]), int(b[3:]))
        ar_text = arabic.get(key)
        if not ar_text:
            continue
        ref = [(w.get("tr") or "").strip().lower()
               for w in json.load(io.open(f, encoding="utf-8"))]
        ref = [r for r in ref if r]
        ar_words = [w for w in ar_text.split() if w.strip()]
        if len(ar_words) != len(ref):
            skipped_ayat += 1
            continue
        for k, (ar, tr) in enumerate(zip(ar_words, ref)):
            got = romanize(ar, utterance_initial=(k == 0)).lower()
            n_words += 1
            if got == tr:
                n_exact += 1
            elif len(examples) < 40:
                examples.append((ar, tr, got))
            ids_ref = [stoi[c] for c in tr if c in stoi]
            ids_got = [stoi[c] for c in got if c in stoi]
            if ids_ref == ids_got:
                n_ids += 1
            for c in got:
                if c not in stoi:
                    oov.add(c)
            m = max(len(tr), len(got)) or 1
            sm = difflib.SequenceMatcher(None, tr, got)
            char_err += m - int(sm.ratio() * m)
            char_tot += m

    print(f"ayat skipped (word-count mismatch)  {skipped_ayat}")
    print(f"words compared                      {n_words}")
    print(f"exact word match                    {n_exact / max(1,n_words):.4f}")
    print(f"CTC id-sequence match               {n_ids / max(1,n_words):.4f}")
    print(f"approx char error                   {char_err / max(1,char_tot):.4f}")
    print(f"out-of-vocab characters produced    {sorted(oov)}")
    print("\nmismatches (arabic | expected | produced):")
    for ar, tr, got in examples[:20]:
        print(f"  {ar:<22} {tr:<20} {got}")


if __name__ == "__main__":
    main()
