package io.github.sammynashed.emran.ui.screens

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.sammynashed.emran.ui.design.GlassButton
import io.github.sammynashed.emran.ui.design.GlassIconButton
import io.github.sammynashed.emran.ui.design.GlassPanel
import io.github.sammynashed.emran.ui.design.LocalSky
import io.github.sammynashed.emran.ui.design.Radius
import io.github.sammynashed.emran.ui.design.ScreenHeader
import io.github.sammynashed.emran.ui.design.Space
import io.github.sammynashed.emran.ui.localization.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Downloads a JSON list from mp3quran.net's API and keeps the last good copy, so the list still opens offline. */
private suspend fun cachedApi(context: Context, key: String, url: String, refresh: Boolean): String? = withContext(Dispatchers.IO) {
    val prefs = context.getSharedPreferences("mp3quran_cache", Context.MODE_PRIVATE)
    val old = prefs.getString(key, null)
    if (old != null && !refresh) return@withContext old
    try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        if (c.responseCode != 200) return@withContext old
        val t = c.inputStream.bufferedReader().use { it.readText() }
        JSONObject(t) // must parse
        prefs.edit().putString(key, t).putLong("${key}_at", System.currentTimeMillis()).apply()
        t
    } catch (e: Exception) { old }
}

/** One radio stream for the whole app, so it keeps playing when you leave the screen. */
object RadioPlayer {
    @Volatile private var mp: MediaPlayer? = null
    @Volatile var current: String? = null
    @Volatile var state: String = "" // "" | loading | playing | error

    fun toggle(url: String, onChange: () -> Unit) {
        if (current == url && state != "error") { stop(); onChange(); return }
        stop()
        current = url; state = "loading"; onChange()
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            m.setDataSource(url)
            m.setOnPreparedListener { if (mp === it) { it.start(); state = "playing"; onChange() } }
            m.setOnErrorListener { _, _, _ -> if (mp === m) { state = "error"; onChange() }; true }
            mp = m
            m.prepareAsync()
        } catch (e: Exception) { state = "error"; onChange() }
    }

    fun stop() {
        runCatching { mp?.stop() }; runCatching { mp?.release() }
        mp = null; current = null; state = ""
    }
}

@Composable
private fun SearchBox(value: String, onChange: (String) -> Unit, label: String) {
    val sky = LocalSky.current
    TextField(
        value = value, onValueChange = { onChange(it.take(40)) },
        label = { Text(label, color = sky.inkSoft, fontSize = 13.sp) }, singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = sky.ink.copy(alpha = 0.20f), unfocusedContainerColor = sky.ink.copy(alpha = 0.16f),
            focusedTextColor = sky.ink, unfocusedTextColor = sky.ink, cursorColor = sky.ink,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
    )
}

private class Radio(val name: String, val url: String)

@Composable
fun RadioScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var radios by remember { mutableStateOf<List<Radio>?>(null) }
    var tick by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    suspend fun load(refresh: Boolean) {
        val raw = cachedApi(ctx, "radios", "https://www.mp3quran.net/api/v3/radios?language=${if (ar) "ar" else "eng"}", refresh)
        if (raw == null) { note = if (ar) "تحتاج إنترنت لأول تحميل للقائمة" else "Needs internet the first time"; radios = emptyList(); return }
        val a = JSONObject(raw).getJSONArray("radios")
        radios = (0 until a.length()).map { Radio(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("url")) }.filter { it.url.startsWith("https") }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load(false) }
    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            ScreenHeader(
                title = if (ar) "الإذاعات" else "Radio",
                subtitle = (radios?.size?.let { if (ar) "$it إذاعة قرآنية" else "$it Quran stations" }) ?: if (ar) "جارٍ التحميل…" else "Loading…",
                onBack = onBack,
            )
            SearchBox(query, { query = it }, if (ar) "ابحث عن إذاعة" else "Search stations")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note.ifEmpty { if (ar) "البث مباشر ويحتاج إنترنت" else "Live streams need internet" }, color = sky.inkSoft, fontSize = 12.sp, modifier = Modifier.weight(1f))
                GlassButton(if (ar) "حدّث" else "Refresh", { scope.launch { load(true) } }, false, hPad = 14.dp)
            }
            val shown = (radios ?: emptyList()).filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 124.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(shown, key = { it.url }) { r ->
                    tick.let {
                        val on = RadioPlayer.current == r.url
                        GlassPanel(Modifier.fillMaxWidth().clickable { RadioPlayer.toggle(r.url) { tick++ } }, cornerRadius = Radius.row, lens = false) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                GlassIconButton(
                                    icon = if (on && RadioPlayer.state == "playing") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = null, onClick = { RadioPlayer.toggle(r.url) { tick++ } },
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, color = sky.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    if (on) Text(
                                        when (RadioPlayer.state) { "loading" -> if (ar) "جارٍ الاتصال…" else "Connecting…"; "playing" -> if (ar) "يعمل الآن" else "Playing"; else -> if (ar) "تعذّر التشغيل" else "Could not play" },
                                        color = sky.inkSoft, fontSize = 12.sp,
                                    )
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
fun VideosScreen(lang: String, onBack: () -> Unit) {
    val ar = lang == "ar"
    val sky = LocalSky.current
    var cat by remember { mutableStateOf(0) }
    var playId by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalLayoutDirection provides if (ar) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().padding(horizontal = Space.screen)) {
            ScreenHeader(
                title = if (ar) "مرئيات" else "Videos",
                subtitle = if (ar) "محتوى إسلامي · يُبثّ من الإنترنت ولا يُحفظ" else "Islamic content · streamed, never saved",
                onBack = onBack,
            )
            if (playId != null) {
                val id = playId!!
                AndroidView(
                    factory = { c ->
                        android.webkit.WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean { android.util.Log.d("VID", "console: " + m.message()); return true }
                            }
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(v: android.webkit.WebView?, u: String?) { android.util.Log.d("VID", "finished " + u) }
                                override fun onReceivedError(v: android.webkit.WebView?, r: android.webkit.WebResourceRequest?, e: android.webkit.WebResourceError?) { android.util.Log.d("VID", "error " + r?.url + " " + e?.description) }
                            }
                            setBackgroundColor(android.graphics.Color.BLACK)
                            val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head><body><iframe " +
                                "src='https://www.youtube.com/embed/$id?playsinline=1&rel=0&autoplay=1' " +
                                "allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe></body></html>"
                            loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
                        }
                    },
                    update = { },
                    onRelease = { it.loadUrl("about:blank"); it.destroy() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 240.dp).testTag("video_player"),
                )
                val vctx = LocalContext.current
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton(if (ar) "إغلاق" else "Close", { playId = null }, false, Modifier.weight(1f), hPad = 12.dp)
                    GlassButton(if (ar) "لا يعمل؟ افتحه خارج التطبيق" else "Not playing? Open outside", {
                        runCatching { vctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, false, Modifier.weight(2f), hPad = 12.dp)
                }
            }
            androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(VIDEO_CATS.size) { n ->
                    GlassButton(if (ar) VIDEO_CATS[n].ar else VIDEO_CATS[n].en, { cat = n }, cat == n, hPad = 14.dp)
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 124.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(VIDEO_CATS[cat].items, key = { it.first }) { v ->
                    GlassPanel(Modifier.fillMaxWidth().clickable { playId = v.first }, cornerRadius = Radius.row, lens = false) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            GlassIconButton(icon = Icons.Filled.PlayArrow, contentDescription = null, onClick = { playId = v.first })
                            Spacer(Modifier.width(12.dp))
                            Text(v.second, color = sky.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
