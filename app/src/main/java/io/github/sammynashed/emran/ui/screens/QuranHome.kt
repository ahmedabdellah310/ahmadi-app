package io.github.sammynashed.emran.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.data.QuranChapter
import io.github.sammynashed.emran.data.audio.AudioStore
import io.github.sammynashed.emran.data.audio.Reciters
import io.github.sammynashed.emran.data.hadith.Bookmark
import io.github.sammynashed.emran.data.hadith.HadithBookmarks
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.Color
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import io.github.sammynashed.emran.ui.viewmodel.QuranViewModel

/** Arabic for matching: drops diacritics and Quranic marks, and folds the letter variants people type differently. */
internal fun normalizeArabic(s: String): String = buildString(s.length) {
    for (ch in s) {
        val c = ch.code
        if (c in 0x064B..0x065F || c in 0x0610..0x061A || c in 0x06D6..0x06ED || c == 0x0670 || c == 0x0640 || c in 0x08D3..0x08FF) continue
        append(
            when (ch) {
                'ٱ', 'أ', 'إ', 'آ' -> 'ا'
                'ى', 'ی' -> 'ي'
                'ة' -> 'ه'
                'ک' -> 'ك'
                else -> ch
            }
        )
    }
}

/** Where each of the 30 ajzāʾ begins (surah, ayah); checked against the muṣḥaf's page starts. */
private val JUZ_START = listOf(
    1 to 1, 2 to 142, 2 to 253, 3 to 93, 4 to 24, 4 to 148, 5 to 82, 6 to 111, 7 to 88, 8 to 41,
    9 to 93, 11 to 6, 12 to 53, 15 to 1, 17 to 1, 18 to 75, 21 to 1, 23 to 1, 25 to 21, 27 to 56,
    29 to 46, 33 to 31, 36 to 28, 39 to 32, 41 to 47, 46 to 1, 51 to 31, 58 to 1, 67 to 1, 78 to 1,
)

private class Hit(val surah: Int, val ayah: Int, val text: String, val plain: String)

/**
 * The Qur'an tab on one page: search the ayat, carry on where you stopped (with how far the
 * khatmah has come), jump to one of your marks, or open any surah or juzʾ in one tap. How to
 * read (Tilawah or Hifz) is chosen inside the reader, so nothing stands between you and the page.
 */
@Composable
fun QuranHomePage(
    lang: String,
    chapters: List<QuranChapter>,
    resumeChapter: QuranChapter?,
    khatmah: QuranViewModel.Khatmah?,
    onOpen: (QuranChapter, Int?) -> Unit,
    onOpenPlayer: () -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenReciters: () -> Unit = {},
    onMakeVideo: () -> Unit = {},
    onOpenGroupKhatmah: () -> Unit = {},
    /** Per juz 0..1 of my khatmah, and the way into its garden. */
    juzRead: List<Float> = emptyList(),
    onOpenMyKhatmah: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var browse by rememberSaveable { mutableStateOf<Int?>(null) } // null = the page itself, 0 = all surahs, 1 = all ajzāʾ
    androidx.activity.compose.BackHandler(enabled = browse != null) { browse = null }

    val bookmarks by HadithBookmarks.all.collectAsState()
    LaunchedEffect(Unit) { HadithBookmarks.ensureLoaded(context) }
    val marks = remember(bookmarks) { bookmarks.filterIsInstance<Bookmark.AyahRef>().sortedByDescending { it.savedAt } }

    val audioChanges by AudioStore.changes.collectAsState()
    val withAudio = remember(audioChanges) { AudioStore.installedSurahs(context, Reciters.chosen(context).id).toSet() }

    // Every ayah, stripped for matching, built once in the background for the search.
    val index by produceState<List<Hit>>(emptyList()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val repo = io.github.sammynashed.emran.data.repository.QuranRepository(context.applicationContext)
                repo.getChapters().flatMap { ch -> repo.getSurahVerses(ch.id).verses.map { Hit(ch.id, it.id, it.text, normalizeArabic(it.text)) } }
            }.getOrDefault(emptyList())
        }
    }
    val hits = remember(query, index) {
        val q = normalizeArabic(query.trim())
        val ref = Regex("^(\\d{1,3})\\s*[:/.\\s]\\s*(\\d{1,3})$").find(query.trim())
        when {
            ref != null -> index.filter { it.surah == ref.groupValues[1].toInt() && it.ayah == ref.groupValues[2].toInt() }
            q.length >= 2 && q.any { it.code in 0x0600..0x06FF } -> index.filter { it.plain.contains(q) }.take(80)
            else -> emptyList()
        }
    }
    fun open(ch: QuranChapter, ayah: Int?) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onOpen(ch, ayah) }
    fun chapter(id: Int) = chapters.firstOrNull { it.id == id }

    val tabNow = browse
    if (tabNow != null) {
        // The full list lives one tap away, so the page itself never has to scroll. Search, then a draggable scrollbar.
        var bq by rememberSaveable(tabNow) { mutableStateOf("") }
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LaunchedEffect(bq, tabNow) { gridState.scrollToItem(0) }
        val nq = normalizeArabic(bq.trim()).lowercase()
        val surahs = remember(nq, chapters) {
            if (nq.isEmpty()) chapters else chapters.filter {
                it.id.toString() == nq || normalizeArabic(it.name).contains(nq) || it.transliteration.lowercase().replace("-", " ").contains(nq.replace("-", " "))
            }
        }
        val juz = remember(nq, chapters) {
            (1..JUZ_START.size).filter { i ->
                if (nq.isEmpty()) return@filter true
                val ch = chapter(JUZ_START[i - 1].first)
                i.toString() == nq || normalizeArabic("الجزء $i").contains(nq) || "juz $i".contains(nq) ||
                    (ch != null && (normalizeArabic(ch.name).contains(nq) || ch.transliteration.lowercase().contains(nq)))
            }
        }
        Column(modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            io.github.sammynashed.emran.ui.design.ScreenHeader(
                title = if (tabNow == 0) (if (ar) "السور" else "Surahs") else (if (ar) "الأجزاء" else "Ajzāʾ"),
                onBack = { browse = null },
            )
            QuranSearchField(
                bq, { bq = it },
                if (tabNow == 0) (if (ar) "ابحث عن سورة" else "Search surahs") else (if (ar) "ابحث عن جزء" else "Search ajzāʾ"),
            )
            Spacer(Modifier.height(Space.md))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(), state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 20.dp, bottom = 124.dp),
                ) {
                    if (tabNow == 0) {
                        if (surahs.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { EmptyNote(if (ar) "لا توجد سورة بهذا الاسم" else "No surah matches") }
                        items(surahs, key = { "s${it.id}" }) { ch ->
                            IndexCell(
                                num = ch.id, onClick = { open(ch, null) },
                                title = {
                                    Text(
                                        if (ar) ch.name else ch.transliteration,
                                        color = sky.ink, fontSize = if (ar) 22.sp else 16.sp, lineHeight = if (ar) 28.sp else 22.sp, maxLines = 1, softWrap = false,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        fontFamily = if (ar) SurahNameFontFamily else null,
                                        style = TextStyle(lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)),
                                    )
                                },
                                line = {
                                    Text(surahMetaLabel(ch.type, ch.total_verses, lang), color = sky.inkSoft, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                },
                            )
                        }
                    } else {
                        if (juz.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { EmptyNote(if (ar) "لا يوجد جزء بهذا الرقم" else "No juzʾ matches") }
                        items(juz, key = { "j$it" }) { n ->
                            val (s0, a0) = JUZ_START[n - 1]
                            val ch = chapter(s0)
                            IndexCell(
                                num = null, onClick = { if (ch != null) open(ch, if (a0 > 1) a0 else null) },
                                title = { Text(if (ar) "الجزء $n" else "Juzʾ $n", color = sky.ink, fontSize = 18.sp, lineHeight = 26.sp, maxLines = 1, softWrap = false) },
                                line = {
                                    Text(
                                        if (ar) "${ch?.name ?: s0} · $a0" else "${ch?.transliteration ?: s0} $s0:$a0",
                                        color = sky.inkSoft, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, softWrap = false,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                QuranScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 124.dp))
            }
        }
    } else Column(modifier.fillMaxSize().padding(horizontal = Space.screen).padding(top = Space.xl)) {
        Text(if (ar) "القرآن الكريم" else "Qur'an", color = sky.ink, fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(Space.md))
        // Search the words of the Qur'an, or go straight to 2:255.
        QuranSearchField(query, { query = it }, if (ar) "ابحث في الآيات، أو اكتب 2:255" else "Search the ayat, or type 2:255")
        Spacer(Modifier.height(Space.lg))

        if (query.isNotBlank()) {
            val hitState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = hitState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 20.dp, bottom = 124.dp)) {
                if (hits.isEmpty()) item {
                    Text(
                        if (ar) "لا توجد آية بهذه الكلمات." else "No ayah has these words. Try typing them in Arabic.",
                        color = sky.inkSoft, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                }
                items(hits, key = { "hit-${it.surah}:${it.ayah}" }) { hit ->
                    val ch = chapter(hit.surah) ?: return@items
                    GlassPanel(Modifier.fillMaxWidth().clickable { open(ch, hit.ayah) }, cornerRadius = Radius.row, tint = 0.8f, lens = false) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(hit.text, color = sky.ink, fontSize = 22.sp, lineHeight = 36.sp, maxLines = 2, fontFamily = MushafFontFamily, modifier = Modifier.fillMaxWidth(), bypassNumerals = true)
                            Spacer(Modifier.height(8.dp))
                            Text(if (ar) "سورة ${ch.name} · الآية ${hit.ayah}" else "${ch.transliteration} · ${hit.surah}:${hit.ayah}", color = sky.inkSoft, fontSize = 13.sp)
                        }
                    }
                }
            }
            QuranScrollbar(hitState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(bottom = 124.dp))
            }
        } else {
            // Everything on one screen. It scrolls only when a huge system font pushes it past the glass bar.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Carry on: the one rich moment. The khatmah is a thin line, the disc the way in.
                val target = resumeChapter ?: chapter(1)
                if (target != null) {
                    GlassPanel(Modifier.fillMaxWidth().clickable { open(target, null) }, cornerRadius = Radius.panel) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (resumeChapter != null) (if (ar) "تابع القراءة" else "Continue reading") else (if (ar) "ابدأ القراءة" else "Start reading"),
                                        color = sky.inkSoft, fontSize = 14.sp,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (ar) "سورة ${target.name}" else target.transliteration,
                                        color = sky.ink, fontSize = 32.sp, lineHeight = 44.sp, fontFamily = if (ar) SurahNameFontFamily else null, maxLines = 1,
                                    )
                                }
                                Box(Modifier.size(56.dp).clip(CircleShape).background(sky.ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.PlayArrow, if (ar) "متابعة" else "Continue", tint = sky.ink, modifier = Modifier.size(28.dp))
                                }
                            }
                            if (khatmah != null) {
                                // The khatmah as 30 small segments, one per juz, each filled as far as it has been read.
                                // Tapping it opens my khatmah's garden.
                                Spacer(Modifier.height(18.dp))
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onOpenMyKhatmah() }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        (0 until 30).forEach { j ->
                                            val f = juzRead.getOrNull(j) ?: 0f
                                            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(sky.ink.copy(alpha = 0.14f))) {
                                                if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(sky.ink.copy(alpha = if (f >= 0.999f) 1f else 0.6f)))
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    val doneJuz = juzRead.count { it >= 0.999f }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (ar) "ختمتي: ${khatmah.pages} من ${khatmah.totalPages} صفحة" + (if (doneJuz > 0) " · أُتمّ $doneJuz من ٣٠ جزءاً" else "")
                                            else "My khatmah: ${khatmah.pages} of ${khatmah.totalPages} pages" + (if (doneJuz > 0) " · $doneJuz of 30 juz" else ""),
                                            color = sky.inkSoft, fontSize = 13.sp, modifier = Modifier.weight(1f),
                                        )
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = sky.inkSoft, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Your marks ("I stopped here"), newest first. All of them are in Saved.
                if (marks.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(marks, key = { it.key }) { m ->
                        val ch = chapter(m.surah) ?: return@items
                        GlassPanel(Modifier.clickable { open(ch, m.ayah) }, cornerRadius = Radius.control, tint = 0.9f, lens = false) {
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bookmark, null, tint = sky.ink, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (ar) "${ch.name} · ${m.ayah}" else "${ch.transliteration} ${m.surah}:${m.ayah}", color = sky.ink, fontSize = 15.sp, maxLines = 1)
                            }
                        }
                    }
                }

                // The four ways in besides reading. A dot on Listen while something recites.
                val now by io.github.sammynashed.emran.data.audio.QuranAudio.state.collectAsState()
                val reciting = now?.playing == true
                val doors = listOf(
                    Triple(Icons.Outlined.Headphones, if (ar) "استمع" else "Listen", onOpenPlayer),
                    Triple(Icons.Outlined.GraphicEq, if (ar) "القرّاء" else "Reciters", onOpenReciters),
                    Triple(Icons.Outlined.BookmarkBorder, if (ar) "المحفوظات" else "Saved", onOpenSaved),
                    Triple(androidx.compose.material.icons.Icons.Outlined.Movie, if (ar) "فيديو" else "Video", onMakeVideo),
                    // Group khatmah: friends finish the Qur'an together, a juz each (its own garden screens).
                )
                GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row, tint = 0.9f, lens = false) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
                        doors.forEachIndexed { i, (icon, label, go) ->
                            val source = remember { MutableInteractionSource() }
                            val pressed by source.collectIsPressedAsState()
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                    .alpha(if (pressed) 0.6f else 1f)
                                    .clickable(interactionSource = source, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); go() }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(Modifier.size(52.dp).clip(CircleShape).background(sky.ink.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                    Icon(icon, null, tint = sky.ink, modifier = Modifier.size(26.dp))
                                    if (i == 0 && reciting) {
                                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(12.dp).background(sky.orb, CircleShape).border(1.5.dp, sky.ink.copy(alpha = 0.55f), CircleShape))
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(label, color = sky.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }

                // Browse: every surah and every juzʾ, one tap away in their own list.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        Triple(0, if (ar) "السور" else "Surahs", if (ar) "١١٤ سورة" else "114 surahs"),
                        Triple(1, if (ar) "الأجزاء" else "Ajzāʾ", if (ar) "٣٠ جزءًا" else "30 parts"),
                    ).forEach { (i, label, count) ->
                        GlassPanel(Modifier.weight(1f).clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); browse = i }, cornerRadius = Radius.row, tint = 0.9f, lens = false) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
                                Text(label, color = sky.ink, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Spacer(Modifier.height(4.dp))
                                Text(count, color = sky.inkSoft, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(108.dp))
            }
        }
    }
}

/** One quiet list row: a muted number, the title and its line, an optional mark, and a hairline under it. */
@Composable
private fun ListRow(num: Int, onClick: () -> Unit, trailing: @Composable () -> Unit = {}, text: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val sky = LocalSky.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                num.toString(), color = sky.inkSoft, fontSize = 15.sp, fontFamily = io.github.sammynashed.emran.ui.theme.InterFontFamily,
                style = TextStyle(fontFeatureSettings = "tnum"), bypassNumerals = true, maxLines = 1, softWrap = false, modifier = Modifier.width(36.dp),
            )
            Column(Modifier.weight(1f), content = text)
            trailing()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(sky.ink.copy(alpha = 0.08f)))
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, color = LocalSky.current.inkSoft, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
}

/** The one search field of the Qur'an tab: the same quiet glass pill on the page and inside each list. */
@Composable
internal fun QuranSearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val sky = LocalSky.current
    val ar = io.github.sammynashed.emran.ui.localization.LocalAppLanguage.current == "ar"
    GlassPanel(modifier.fillMaxWidth(), cornerRadius = Radius.row, tint = 0.9f, lens = false) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = sky.inkSoft, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = sky.inkSoft, fontSize = 16.sp)
                BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true,
                    textStyle = TextStyle(color = sky.ink, fontSize = 17.sp), cursorBrush = SolidColor(sky.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Icon(Icons.Filled.Close, if (ar) "مسح" else "Clear", tint = sky.inkSoft, modifier = Modifier.size(22.dp).clickable { onChange("") })
            }
        }
    }
}

/** A thin scrollbar on the edge of a list: shows where you are, and dragging it jumps there. */
@Composable
private fun QuranScrollbar(state: androidx.compose.foundation.lazy.LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val first = info.visibleItemsInfo.firstOrNull()?.size?.coerceAtLeast(1) ?: 1
    ScrollbarCore(
        total = info.totalItemsCount, visible = info.visibleItemsInfo.size,
        pos = state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset.toFloat() / first,
        jump = { state.scrollToItem(it) }, modifier = modifier,
    )
}

@Composable
private fun QuranScrollbar(state: androidx.compose.foundation.lazy.grid.LazyGridState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    ScrollbarCore(
        total = info.totalItemsCount, visible = info.visibleItemsInfo.size,
        pos = state.firstVisibleItemIndex.toFloat(),
        jump = { state.scrollToItem(it) }, modifier = modifier,
    )
}

@Composable
private fun ScrollbarCore(total: Int, visible: Int, pos: Float, jump: suspend (Int) -> Unit, modifier: Modifier) {
    val sky = LocalSky.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    if (total == 0 || visible >= total) return
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.width(24.dp)) {
        val thumbH = maxOf(maxHeight * (visible.toFloat() / total), 48.dp)
        val prog = (pos / (total - visible).coerceAtLeast(1)).coerceIn(0f, 1f)
        val thumbY = (maxHeight - thumbH) * prog
        Box(
            Modifier.fillMaxSize().pointerInput(total, visible) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    val f = (change.position.y / size.height).coerceIn(0f, 1f)
                    scope.launch { jump((f * (total - visible)).toInt()) }
                }
            },
        ) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = thumbY)
                    .padding(end = 4.dp).width(if (dragging) 8.dp else 4.dp).height(thumbH).clip(CircleShape)
                    .background(sky.ink.copy(alpha = if (dragging) 0.7f else 0.35f)),
            )
        }
    }
}

/** One cell of the two-column index: a muted number beside the name and a quiet line under it. */
@Composable
private fun IndexCell(num: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, title: @Composable () -> Unit, line: @Composable () -> Unit) {
    val sky = LocalSky.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The same frost the panes carry (deep navy by night, soft white by day), a step denser than the sky behind it,
    // so a tile reads as a surface and not as a patch of cloud.
    val frost = androidx.compose.ui.graphics.lerp(Color(0xFF0A1630).copy(alpha = 0.58f), Color.White.copy(alpha = 0.62f), sky.darkInk)
    Row(
        modifier.alpha(if (pressed) 0.7f else 1f).clip(RoundedCornerShape(Radius.control)).background(frost)
            .border(1.dp, sky.ink.copy(alpha = 0.10f), RoundedCornerShape(Radius.control))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (num != null) Text(
            num.toString(), color = sky.inkSoft, fontSize = 13.sp, fontFamily = io.github.sammynashed.emran.ui.theme.InterFontFamily,
            style = TextStyle(fontFeatureSettings = "tnum"), bypassNumerals = true, maxLines = 1, softWrap = false, modifier = Modifier.width(36.dp),
        )
        Column(Modifier.weight(1f)) { title(); line() }
    }
}
