package io.github.sammynashed.emran.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

// Mushaf editions: each edition is a font plus the full text, downloaded on the user's device from the
// publisher's own distribution (nothing is bundled or re-hosted by this app). The publisher's end-user
// licence (stored inside every font file) allows free use, copying and distribution and forbids
// selling or modifying; the font file is kept unmodified.
private class Edition(val id: String, val ar: String, val en: String, val zip: String)

private val EDITIONS = listOf(
    Edition("hafs", "حفص عن عاصم", "Hafs from Asim", "hafs-3.0"),
    Edition("warsh", "ورش عن نافع", "Warsh from Nafi", "warsh-3.0"),
    Edition("qalun", "قالون عن نافع", "Qalun from Nafi", "qalun-3.0"),
    Edition("duri", "الدوري عن أبي عمرو", "Al-Duri from Abu Amr", "duri-2.0"),
    Edition("susi", "السوسي عن أبي عمرو", "Al-Susi from Abu Amr", "susi-3.0"),
    Edition("shubah", "شعبة عن عاصم", "Shu'ba from Asim", "shubah-3.0"),
    Edition("bazzi", "البزي عن ابن كثير", "Al-Bazzi from Ibn Kathir", "bazzi-3.0"),
)

private fun edDir(c: android.content.Context, e: Edition) = File(c.filesDir, "mushaf/${e.id}")
private fun edReady(c: android.content.Context, e: Edition) = File(edDir(c, e), "pages.txt").exists() && File(edDir(c, e), "font.ttf").exists()
private fun edSize(c: android.content.Context, e: Edition) = edDir(c, e).listFiles()?.sumOf { it.length() } ?: 0L

// Older Android builds may not know the publisher's certificate authority yet. The authority's own
// public root (checked against its published fingerprint) is added next to the system roots; every
// other check, including the host name, stays on.
private fun trustWithExtraRoot(c: android.content.Context): javax.net.ssl.SSLSocketFactory {
    fun tm(ks: java.security.KeyStore?): javax.net.ssl.X509TrustManager {
        val f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()); f.init(ks)
        return f.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
    }
    val system = tm(null)
    val extraKs = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()); extraKs.load(null)
    c.assets.open("ca_extra_root.pem").use {
        extraKs.setCertificateEntry("extra-root", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it))
    }
    val extra = tm(extraKs)
    val both = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            try { system.checkServerTrusted(chain, authType) } catch (e: java.security.cert.CertificateException) { extra.checkServerTrusted(chain, authType) }
        }
        override fun getAcceptedIssuers() = system.acceptedIssuers + extra.acceptedIssuers
    }
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS"); ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(both), null)
    return ctx.socketFactory
}

private fun downloadEdition(c: android.content.Context, e: Edition, onProgress: (Float) -> Unit) {
    val dir = edDir(c, e); dir.mkdirs()
    val part = File(dir, "dl.part")
    val url = URL("https://qurancomplex.gov.sa/files/fonts/${e.zip}.zip")
    var have = if (part.exists()) part.length() else 0L
    var conn = url.openConnection() as HttpURLConnection
    (conn as? javax.net.ssl.HttpsURLConnection)?.sslSocketFactory = trustWithExtraRoot(c)
    conn.connectTimeout = 20000; conn.readTimeout = 30000
    if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
    val code = conn.responseCode
    if (code == 200) have = 0L
    else if (code != 206) throw RuntimeException("HTTP $code")
    val total = have + conn.contentLengthLong
    conn.inputStream.use { ins ->
        java.io.FileOutputStream(part, have > 0).use { out ->
            val buf = ByteArray(32768); var done = have
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n
                if (total > 0) onProgress(done.toFloat() / total * 0.8f)
            }
        }
    }
    // unpack: the font stays as shipped; the Word file is read once into plain pages and discarded
    val zip = ZipFile(part)
    val docx = File(dir, "tmp.docx")
    for (en in zip.entries()) {
        val n = en.name.lowercase()
        if (n.endsWith(".ttf")) zip.getInputStream(en).use { i -> File(dir, "font.ttf").outputStream().use { o -> i.copyTo(o) } }
        else if (n.endsWith(".docx")) zip.getInputStream(en).use { i -> docx.outputStream().use { o -> i.copyTo(o) } }
    }
    zip.close()
    val dz = ZipFile(docx)
    val parser = android.util.Xml.newPullParser()
    parser.setInput(dz.getInputStream(dz.getEntry("word/document.xml")), "UTF-8")
    val outFile = File(dir, "pages.txt.tmp")
    outFile.bufferedWriter(Charsets.UTF_8).use { w ->
        var inT = false; var ev = parser.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (ev) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "t" -> inT = true
                    "br" -> if (parser.getAttributeValue(null, "w:type") == "page" || parser.getAttributeValue(null, "type") == "page") w.write("\u000c")
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inT) w.write(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) { "t" -> inT = false; "p" -> w.write("\n") }
            }
            ev = parser.next()
        }
    }
    dz.close(); docx.delete(); part.delete()
    onProgress(1f)
    outFile.renameTo(File(dir, "pages.txt"))
}

private fun fmt(b: Long) = if (b >= 1048576) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"

@Composable
fun MushafEditionsScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var msg by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val openEd = EDITIONS.firstOrNull { it.id == open }
    if (openEd != null) { EditionReader(ar, openEd) { open = null }; return }
    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            ScreenHeader(
                title = if (ar) "المصاحف والروايات" else "Mushaf editions",
                subtitle = if (ar) "تُنزَّل على جهازك وتعمل بدون إنترنت" else "Downloaded to your phone, works offline",
                onBack = onBack,
            )
            if (msg.isNotEmpty()) Text(msg, color = sky.inkSoft, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 124.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(EDITIONS, key = { it.id }) { e ->
                    val ready = remember(tick) { edReady(ctx, e) }
                    val p = progress[e.id]
                    GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row, lens = false) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(if (ar) e.ar else e.en, color = sky.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    p != null -> (if (ar) "جارٍ التنزيل… " else "Downloading… ") + "${(p * 100).toInt()}%"
                                    ready -> (if (ar) "منزَّل · " else "Downloaded · ") + fmt(edSize(ctx, e))
                                    else -> if (ar) "غير منزَّل · حوالي ١ ميجابايت" else "Not downloaded · about 1 MB"
                                },
                                color = sky.inkSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (ready) {
                                    GlassButton(if (ar) "اقرأ" else "Read", { open = e.id }, true, hPad = 18.dp)
                                    GlassButton(if (ar) "احذف" else "Delete", { edDir(ctx, e).deleteRecursively(); tick++ }, false, hPad = 18.dp)
                                } else if (p == null) {
                                    GlassButton(if (ar) "نزّل" else "Download", {
                                        msg = ""; progress = progress + (e.id to 0f)
                                        scope.launch {
                                            try { withContext(Dispatchers.IO) { downloadEdition(ctx, e) { f -> progress = progress + (e.id to f) } } }
                                            catch (t: Throwable) { android.util.Log.e("MUSHAF", "download failed", t); msg = (if (ar) "تعذّر التنزيل، تأكد من الإنترنت وحاول مرة أخرى (يُستأنف من حيث وقف)" else "Download failed. Check the internet and retry (it resumes)") }
                                            progress = progress - e.id; tick++
                                        }
                                    }, true, hPad = 18.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditionReader(ar: Boolean, e: Edition, onBack: () -> Unit) {
    val sky = LocalSky.current
    val ctx = LocalContext.current
    var pages by remember { mutableStateOf<List<String>>(emptyList()) }
    var size by remember { mutableStateOf(26) }
    val family = remember { FontFamily(Font(File(edDir(ctx, e), "font.ttf"))) }
    LaunchedEffect(Unit) {
        pages = withContext(Dispatchers.IO) { File(edDir(ctx, e), "pages.txt").readText().split('\u000c').map { it.trim() }.filter { it.isNotEmpty() } }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            ScreenHeader(title = if (ar) e.ar else e.en, subtitle = if (ar) "${pages.size} صفحة" else "${pages.size} pages", onBack = onBack)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("A+", { if (size < 44) size += 3 }, false, hPad = 16.dp)
                GlassButton("A-", { if (size > 18) size -= 3 }, false, hPad = 16.dp)
            }
            if (pages.isNotEmpty()) {
                val st = rememberPagerState { pages.size }
                HorizontalPager(st, Modifier.weight(1f)) { i ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 124.dp)) {
                        GlassPanel(Modifier.fillMaxWidth(), cornerRadius = Radius.row, lens = false) {
                            Text(
                                pages[i], color = sky.ink, fontFamily = family, fontSize = size.sp, lineHeight = (size * 2.0).sp,
                                textAlign = TextAlign.Center, bypassNumerals = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                        Text("${i + 1}", color = sky.inkSoft, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

