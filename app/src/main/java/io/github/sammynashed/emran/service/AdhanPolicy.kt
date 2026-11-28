package io.github.sammynashed.emran.service

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import java.io.File
import java.time.LocalDateTime

/**
 * Decides HOW an adhan reaches the user, so the phone's own settings win by default:
 * silent, vibrate and Do Not Disturb are respected, and the adhan follows the notification volume.
 * The only thing that overrides all of that is the user's own "Wake me for Fajr" switch.
 */
object AdhanPolicy {

    /** What the user picked for one prayer. On/off is the bell on the Prayers screen (`notif_<Prayer>`), not this. */
    enum class Mode { ADHAN, VIBRATE }

    /** What actually happens right now, once the phone's state is taken into account. */
    enum class Play { WAKE, ADHAN, VIBRATE, SILENT }

    /** Days are a bitmask, Monday = bit 0; times are minutes after midnight; end < start runs past midnight. */
    data class QuietWindow(val days: Int, val startMin: Int, val endMin: Int)

    private const val PREFS = "emran_settings"
    private const val KEY_LOUDNESS = "adhan_loudness"
    private const val KEY_FIXED = "adhan_fixed_level"
    private const val KEY_WAKE = "adhan_wake_fajr"
    private const val KEY_WAKE_LOUDNESS = "adhan_wake_loudness"
    private const val KEY_FULL = "adhan_full"
    private const val KEY_FAJR_SOUND = "fajr_sound"
    private const val KEY_QUIET = "adhan_quiet_windows"

    const val DEFAULT_LOUDNESS = 45
    const val DEFAULT_WAKE_LOUDNESS = 40

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context, prayer: String): Mode =
        if (prefs(context).getString("adhan_mode_$prayer", null) == "VIBRATE") Mode.VIBRATE else Mode.ADHAN

    fun setMode(context: Context, prayer: String, mode: Mode) {
        prefs(context).edit().putString("adhan_mode_$prayer", mode.name).commit()
    }

    /** 20..100: the adhan's own loudness, as a share of whatever the phone's volume already is. */
    fun loudness(context: Context): Int = prefs(context).getInt(KEY_LOUDNESS, DEFAULT_LOUDNESS).coerceIn(20, 100)

    fun setLoudness(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LOUDNESS, value.coerceIn(20, 100)).apply()
    }

    /**
     * "Fixed level" (a friend's idea, 2026-11): the adhan plays at [loudness] percent of the phone's MAXIMUM volume whatever the
     * volume is set to, so it is always heard. Off (the default) = it follows the notification volume and the cap only lowers it.
     * Silent, vibrate and Do Not Disturb are respected either way.
     */
    fun fixedLevel(context: Context): Boolean = prefs(context).getBoolean(KEY_FIXED, false)

    fun setFixedLevel(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FIXED, on).apply()
    }

    /** The wake-up adhan has its own cap, separate from the everyday one: loud enough to wake you, never "maximum". */
    fun wakeLoudness(context: Context): Int = prefs(context).getInt(KEY_WAKE_LOUDNESS, DEFAULT_WAKE_LOUDNESS).coerceIn(10, 100)

    fun setWakeLoudness(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_WAKE_LOUDNESS, value.coerceIn(10, 100)).apply()
    }

    fun wakeForFajr(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE, false)

    fun setWakeForFajr(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE, on).commit()
    }

    fun quietWindows(context: Context): List<QuietWindow> {
        val raw = prefs(context).getString(KEY_QUIET, "") ?: ""
        return raw.split(';').mapNotNull {
            val p = it.split(':')
            if (p.size != 3) return@mapNotNull null
            val d = p[0].toIntOrNull() ?: return@mapNotNull null
            val s = p[1].toIntOrNull() ?: return@mapNotNull null
            val e = p[2].toIntOrNull() ?: return@mapNotNull null
            QuietWindow(d and 0x7F, s.coerceIn(0, 1439), e.coerceIn(0, 1439))
        }
    }

    fun setQuietWindows(context: Context, windows: List<QuietWindow>) {
        prefs(context).edit().putString(KEY_QUIET, windows.joinToString(";") { "${it.days}:${it.startMin}:${it.endMin}" }).commit()
    }

    fun isQuietNow(context: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val minute = now.hour * 60 + now.minute
        val today = now.dayOfWeek.value - 1
        val yesterday = (today + 6) % 7
        return quietWindows(context).any { w ->
            if (w.startMin <= w.endMin) {
                (w.days shr today and 1) == 1 && minute >= w.startMin && minute < w.endMin
            } else {
                // Overnight: the evening part belongs to the day it started on, the morning part to the day after.
                ((w.days shr today and 1) == 1 && minute >= w.startMin) ||
                    ((w.days shr yesterday and 1) == 1 && minute < w.endMin)
            }
        }
    }

    /** Whether Android would let our channel through Do Not Disturb (the wake switch needs it). */
    fun canBypassDnd(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    fun resolve(context: Context, prayer: String, now: LocalDateTime = LocalDateTime.now()): Play {
        // The user asked to be woken for Fajr: that is the one thing allowed to ignore silent, DND and quiet hours.
        if (prayer == "Fajr" && wakeForFajr(context)) return Play.WAKE

        val mode = mode(context, prayer)
        if (mode == Mode.VIBRATE) return Play.VIBRATE
        // Personal build: the adhan is an alarm. Silent mode, vibrate, Do Not Disturb (priority) and quiet hours
        // do not stop it; it plays on the alarm stream. (Sunrise is only a reminder and keeps the old rules.)
        if (prayer != "Sunrise") return Play.ADHAN
        if (isQuietNow(context, now)) return Play.VIBRATE

        val ringer = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode
        if (ringer == AudioManager.RINGER_MODE_SILENT) return Play.SILENT
        if (ringer == AudioManager.RINGER_MODE_VIBRATE) return Play.VIBRATE

        val filter = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return Play.SILENT
        }
        return Play.ADHAN
    }

    /** "The whole adhan" instead of its opening — the opening is the takbirat and the two testimonies of faith ("أشهد أن لا إله إلا الله" twice), then it stops — for every prayer (a friend asked for it, 2026-11). Off by default. */
    /** "Stop the adhan with a volume button" (on by default): any press of a volume key stops it at once, even with the screen off. */
    fun volumeStops(context: Context): Boolean = prefs(context).getBoolean("adhan_volume_stops", true)

    fun setVolumeStops(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("adhan_volume_stops", on).commit()
    }

    /** A plain-language report of everything that decides whether the adhan is heard, for the "test now" button. */
    fun diagnose(context: Context, ar: Boolean): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val l = mutableListOf<String>()
        fun add(ok: Boolean, okAr: String, okEn: String, badAr: String, badEn: String) { l += (if (ok) "✓ " else "✗ ") + (if (ar) (if (ok) okAr else badAr) else (if (ok) okEn else badEn)) }
        val ringer = am.ringerMode
        add(ringer == AudioManager.RINGER_MODE_NORMAL, "الهاتف على الوضع العادي", "Phone ringer is normal", "الهاتف على الصامت أو الاهتزاز: الأذان سيعمل رغم ذلك على قناة المنبّه", "Phone is on silent or vibrate: the adhan still plays on the alarm channel")
        val f = nm.currentInterruptionFilter
        add(f == NotificationManager.INTERRUPTION_FILTER_ALL || f == NotificationManager.INTERRUPTION_FILTER_UNKNOWN, "عدم الإزعاج مغلق", "Do Not Disturb is off", "عدم الإزعاج مفعّل: يمر المنبّه إلا في وضع «الصمت التام» (Total silence) فلا يمكن تجاوزه، أو إن لم تسمح للتطبيق بتجاوز عدم الإزعاج", "Do Not Disturb is on: alarms get through, except in Total silence mode, which nothing can bypass, or if the app is not allowed to override DND")
        
        add(true, "الأذان يرفع صوت المنبّه تلقائيًا وقت الأذان", "The adhan raises the alarm volume by itself when it plays", "", "")
        add(nm.areNotificationsEnabled(), "الإشعارات مسموحة", "Notifications allowed", "الإشعارات غير مسموحة للتطبيق", "Notifications are blocked for the app")
        val exact = android.os.Build.VERSION.SDK_INT < 31 || (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()
        add(exact, "المنبهات الدقيقة مسموحة", "Exact alarms allowed", "المنبهات الدقيقة غير مسموحة", "Exact alarms not allowed")
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        add(pm.isIgnoringBatteryOptimizations(context.packageName), "البطارية غير مقيّدة", "Battery unrestricted", "البطارية تقيّد التطبيق: اختر غير مقيّد", "Battery restricts the app: choose unrestricted")
        add(isMasterOn(context), "الأذان مفعّل", "Adhan switched on", "الأذان مغلق من الإعدادات", "Adhan is switched off in settings")
        run {
            val am2 = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val next = am2.nextAlarmClock
            val fmt = java.text.SimpleDateFormat("EEE d MMM HH:mm", if (ar) java.util.Locale("ar") else java.util.Locale.ENGLISH)
            add(next != null, "أقرب منبّه مسجّل: " + (next?.let { fmt.format(java.util.Date(it.triggerTime)) } ?: ""), "Next alarm armed: " + (next?.let { fmt.format(java.util.Date(it.triggerTime)) } ?: ""), "لا يوجد منبّه مسجّل: افتح التطبيق مرة لتسجيل الأذان", "No alarm is armed: open the app once to arm the adhan")
            val last = prefs(context).getString("last_fire_log", null)
            l += if (ar) "آخر مرة أيقظ فيها النظام التطبيق: " + (last ?: "لم يحدث بعد") else "Last time the system woke the app: " + (last ?: "never yet")
        }
        return l.joinToString("\n")
    }

    private fun isMasterOn(context: Context): Boolean = prefs(context).getBoolean("notifications_enabled_global", true)

    /** Speak "اقتربت صلاة ..." aloud when the pre-adhan notice appears (on by default). */
    fun voiceNotice(context: Context): Boolean = prefs(context).getBoolean("voice_notice", true)

    fun setVoiceNotice(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("voice_notice", on).commit()
    }

    fun fullAdhan(context: Context): Boolean = prefs(context).getBoolean(KEY_FULL, false)

    fun setFullAdhan(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL, on).commit()
    }

    /** "The screen after the adhan" (on by default): when an adhan ends on its own, a full screen shows its text and the dua after it. */
    private const val KEY_POST_ADHAN = "post_adhan_screen"

    fun showPostAdhanScreen(context: Context): Boolean = prefs(context).getBoolean(KEY_POST_ADHAN, true)

    fun setShowPostAdhanScreen(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_POST_ADHAN, on).commit()
    }

    private fun raw(context: Context, name: String): Uri? {
        val res = context.resources.getIdentifier(name, "raw", context.packageName)
        return if (res != 0) Uri.parse("android.resource://${context.packageName}/$res") else null
    }

    /**
     * The whole adhan of [voiceKey] for [prayer], or null when this voice has no full recording.
     * Fajr is ALWAYS the voice the user picked (his rule, 2026-11-07: never a stranger's recording):
     * its own Fajr recording with "الصلاة خير من النوم" when one exists from the same muezzin,
     * otherwise that same muezzin's everyday adhan.
     */
    fun fullAdhanUri(context: Context, voiceKey: String, prayer: String): Uri? = when (prayer) {
        "Fajr" -> fullFajrAdhan(context, voiceKey) ?: raw(context, "${voiceKey}_full")
        "Dhuhr", "Asr", "Maghrib", "Isha" -> raw(context, "${voiceKey}_full")
        else -> null // Sunrise is a reminder that Fajr's time is over, never an adhan
    }

    /**
     * Voices with a "الصلاة خير من النوم" clip (`res/raw/<voice>_tathweeb`), cut from that muezzin's own
     * Fajr adhan. Only these are offered for "Fajr sound" (his rule: show only what exists).
     */
    val TATHWEEB_VOICES = listOf("mishary_alafasy")

    /** The voice whose "الصلاة خير من النوم" plays at Fajr instead of the adhan, or null for the adhan. */
    fun fajrTathweebVoice(context: Context): String? =
        prefs(context).getString(KEY_FAJR_SOUND, null)?.takeIf { it in TATHWEEB_VOICES && raw(context, "${it}_tathweeb") != null }

    fun setFajrTathweebVoice(context: Context, voice: String?) {
        prefs(context).edit().putString(KEY_FAJR_SOUND, voice.orEmpty()).commit()
    }

    fun tathweebUri(context: Context, voice: String): Uri? = raw(context, "${voice}_tathweeb")

    /**
     * The full Fajr adhan (ending with "الصلاة خير من النوم") for a voice, if it is on the phone:
     * either bundled as `res/raw/<voice>_fajr_full` or downloaded to `files/adhan_full/<voice>.ogg`.
     */
    fun fullFajrAdhan(context: Context, voiceKey: String): Uri? {
        val file = File(context.filesDir, "adhan_full/$voiceKey.ogg")
        if (file.isFile && file.length() > 0) return Uri.fromFile(file)
        return raw(context, "${voiceKey}_fajr_full")
    }
}
