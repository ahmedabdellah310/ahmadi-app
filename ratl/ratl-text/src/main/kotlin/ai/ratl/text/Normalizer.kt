package ai.ratl.text

/**
 * Uthmani Arabic to the model's romanised representation. SPEC 5.5.
 *
 * RATL's acoustic model was trained on quran.com-style romanisation, but every
 * real Quran application holds Arabic text. Without this, RATL is only usable by
 * a caller that happens to already have transliterations - which, as the first
 * integration showed, is nobody.
 *
 * ## What this is and is not
 *
 * It is a **rule-based approximation**, not a reproduction of quran.com's
 * strings. Measured against 4,287 cached reference ayat (33,932 words):
 *
 * | | |
 * |---|---|
 * | exact word agreement | 0.6354 |
 * | character error rate | 0.0788 |
 * | **G2 through this pipeline** | **0.8504** (reference targets: 0.8570) |
 *
 * Exact agreement is not the requirement - forced alignment tolerates a good
 * deal of spelling difference - which is why the character rate and G2 matter
 * more than the word rate.
 *
 * Some of the residual is not derivable by rule at all. The reference writes
 * `mus'taqima` but `nasta'inu` for the identical consonant-sukun-consonant
 * pattern; emitting that apostrophe by rule was measured and made agreement
 * *worse* (0.6242 vs 0.5446), so it is not emitted.
 *
 * ## Orthography handled
 *
 * - short vowels, tanwin, and long vowels formed by a matching vowel + alif/waw/ya
 * - **shadda** - consonant doubling
 * - **sukun** - in both encodings that occur in Uthmani text, U+0652 and U+06E1
 * - **dagger / superscript alif** - a long `a`
 * - **hamzat wasl** - sounded at the start of an utterance, elided after a
 *   preceding vowel, which is why the reference has `al-hamdu` at an ayah start
 *   but `l-lahi` mid-ayah
 * - the definite article, including assimilation to sun letters
 * - **waqf marks, tatweel, madda and small/superscript letters** - discarded as
 *   orthography rather than sound
 * - **muqatta'at** - the disconnected letters are recited as letter names and
 *   cannot be derived from the glyphs, so they are tabulated
 */
object Normalizer {

    private val CONSONANTS = mapOf(
        'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "ḥ", 'خ' to "kh",
        'د' to "d", 'ذ' to "dh", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh",
        'ص' to "ṣ", 'ض' to "ḍ", 'ط' to "ṭ", 'ظ' to "ẓ", 'ع' to "ʿ", 'غ' to "gh",
        'ف' to "f", 'ق' to "q", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ه' to "h", 'و' to "w", 'ي' to "y", 'ة' to "h",
    )
    private val HAMZA = setOf('ء', 'أ', 'إ', 'ؤ', 'ئ')
    private val SHORT = mapOf('َ' to "a", 'ُ' to "u", 'ِ' to "i")
    private val TANWIN = mapOf('ً' to "an", 'ٌ' to "un", 'ٍ' to "in")
    private val LONG = mapOf("a" to "ā", "u" to "ū", "i" to "ī")

    private const val SHADDA = 'ّ'
    private const val DAGGER = 'ٰ'
    private const val WASL = 'ٱ'
    private const val ALIF = 'ا'
    private const val WAW = 'و'
    private const val YA = 'ي'
    private const val MAQSURA = 'ى'

    /** Two sukun encodings occur in Uthmani text. Missing U+06E1 silently
     *  disables every sukun rule, which is exactly what happened once. */
    private val SUKUNS = setOf('ْ', 'ۡ')

    private val SUN = "تثدذرزسشصضطظلن".toSet()

    /** Waqf marks, tatweel, madda, small and superscript letters. */
    private val DROP = buildSet {
        add('ـ')
        addAll(('ۖ'..'۠').toList())
        addAll(('ۢ'..'۩').toList())
        addAll(listOf('۪', '۫', '۬', 'ۭ'))
        addAll(listOf('ٓ', 'ٔ', 'ٕ'))
        addAll(listOf('ࣰ', 'ࣱ', 'ࣲ'))
        addAll(listOf('‌', '‍', '‎', '‏'))
        remove('ۡ')   // sukun, not decoration
    }

    private val MUQATTAAT = mapOf(
        "الم" to "alif-lam-meem", "الر" to "alif-lam-ra",
        "المص" to "alif-lam-meem-sad", "المر" to "alif-lam-meem-ra",
        "كهيعص" to "kaf-ha-ya-ain-sad", "طه" to "ta-ha",
        "طسم" to "ta-seen-meem", "طس" to "ta-seen", "يس" to "ya-seen",
        "ص" to "saad", "حم" to "ha-meem", "عسق" to "ain-seen-qaf",
        "ق" to "qaf", "ن" to "noon",
    )

    /**
     * Split [verse] into words and romanise each.
     *
     * @param verse Uthmani text of a single ayah
     */
    fun romanizeVerse(verse: String): List<String> {
        val words = verse.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.mapIndexed { i, w -> romanize(w, utteranceInitial = i == 0) }
            .filter { it.isNotEmpty() }
    }

    /**
     * Romanise one word.
     *
     * @param utteranceInitial true for the first word of the recitation, which
     *   is what decides whether a leading hamzat wasl is sounded
     */
    fun romanize(word: String, utteranceInitial: Boolean): String {
        val w = word.filter { it !in DROP }
        if (w.isEmpty()) return ""

        val bare = w.filter { it !in SHORT && it !in TANWIN && it != SHADDA && it != DAGGER && it !in SUKUNS }
        MUQATTAAT[bare]?.let { return it }

        val out = StringBuilder()
        var i = 0
        val n = w.length

        if (w[0] == WASL) {
            i = 1
            if (utteranceInitial) out.append("a")
            if (i + 1 < n && w[i] == 'ل' && w[i + 1] == SHADDA) {
                // article + lam-with-shadda: the reference writes the full form
                if (!utteranceInitial) out.append("a")
                out.append("lla")
                i += 2
                if (i < n && w[i] in SHORT) {
                    out.setLength(out.length - 1)
                    out.append(SHORT[w[i]])
                    i++
                }
            } else if (i < n && w[i] == 'ل') {
                var j = i + 1
                while (j < n && w[j] in SUKUNS) j++
                val doubled = j < n && w[j] == SHADDA
                var k = i + 1
                while (k < n && (w[k] in SHORT || w[k] in SUKUNS || w[k] == SHADDA)) k++
                val followsSun = k < n && w[k] in SUN
                out.append("l-")
                i++
                if (doubled || followsSun) {
                    // assimilates to the sun letter, written UNDOUBLED
                    while (i < n && (w[i] in SUKUNS || w[i] == SHADDA)) i++
                    if (i < n && w[i] in SUN) {
                        out.append(CONSONANTS[w[i]] ?: "")
                        i++
                        if (i < n && w[i] == SHADDA) i++
                    }
                } else {
                    if (i < n && w[i] in SUKUNS) i++
                }
            }
        } else if (w[0] in HAMZA) {
            // a word-initial glottal stop is not written in the reference
            i = 1
            when {
                i < n && w[i] in SHORT -> { out.append(SHORT[w[i]]); i++ }
                i < n && w[i] == DAGGER -> { out.append("ā"); i++ }
                else -> out.append("a")
            }
        }

        while (i < n) {
            val ch = w[i]
            if (ch in CONSONANTS || ch in HAMZA) {
                var cons = CONSONANTS[ch] ?: "'"
                i++
                if (i < n && w[i] == SHADDA) { cons += cons; i++ }
                out.append(cons)
                when {
                    i < n && w[i] == DAGGER -> { out.append("ā"); i++ }
                    i < n && w[i] in TANWIN -> {
                        out.append(TANWIN[w[i]]); i++
                        if (i < n && (w[i] == ALIF || w[i] == MAQSURA)) i++
                    }
                    i < n && w[i] in SHORT -> {
                        val v = SHORT.getValue(w[i]); i++
                        if (i < n && w[i] == DAGGER) { out.append("ā"); i++ }
                        else if (!appendLong(w, i, v, out).also { if (it) i++ }) out.append(v)
                    }
                    i < n && w[i] in SUKUNS -> i++
                }
                continue
            }
            when {
                ch == ALIF || ch == MAQSURA -> {
                    if (!out.endsWith("ā")) out.append("ā")
                    i++
                }
                ch == DAGGER -> { if (!out.endsWith("ā")) out.append("ā"); i++ }
                ch in SHORT -> {
                    val v = SHORT.getValue(ch); i++
                    if (i < n && w[i] == DAGGER) { out.append("ā"); i++ }
                    else if (!appendLong(w, i, v, out).also { if (it) i++ }) out.append(v)
                }
                ch in TANWIN -> { out.append(TANWIN[ch]); i++ }
                else -> i++
            }
        }
        return out.toString()
    }

    /** A short vowel followed by its matching bare alif/waw/ya is long. */
    private fun appendLong(w: String, i: Int, v: String, out: StringBuilder): Boolean {
        if (i >= w.length) return false
        val matched = when (w[i]) {
            ALIF, MAQSURA -> "a"
            WAW -> "u"
            YA -> "i"
            else -> return false
        }
        if (matched != v) return false
        val after = if (i + 1 < w.length) w[i + 1] else null
        if (after != null && (after in SHORT || after in TANWIN || after == SHADDA)) return false
        out.append(LONG.getValue(v))
        return true
    }
}
