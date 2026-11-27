package io.github.sammynashed.emran.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.github.sammynashed.emran.R
import io.github.sammynashed.emran.data.PrayerNames
import io.github.sammynashed.emran.data.PrayerTimeCalculator
import io.github.sammynashed.emran.data.TimeFormat
import io.github.sammynashed.emran.data.local.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Prayer times in the notification panel" (a friend's idea, 2026-11): a quiet notification that stays up once the
 * user turns it on, like a live widget: the next prayer with a countdown that ticks by itself (a native Chronometer, so
 * nothing runs and nothing is redrawn between prayers), or all the prayers of the day.
 *
 * Content: [Content.ALL] (default: the whole day with the next prayer highlighted, visible even while the notification is
 * collapsed) or [Content.NEXT] (just the next prayer).
 * One look only: the system's own ("clean"), so it sits naturally with the rest of the panel.
 * It redraws only when the next prayer passes, via one alarm, and after a reboot, an update or a settings change.
 * Swiping it away just puts it back while the feature is on: it is a status, not a message.
 */
object PrayerStatus {
    enum class Content { NEXT, ALL }
    private const val PREFS = "emran_settings"
    private const val CHANNEL = "emran_prayer_status_v2"
    private const val OLD_CHANNEL = "emran_prayer_status_v1"
    private const val NOTIF_ID = 7301
    private const val REQ_ALARM = 7302
    private const val REQ_OPEN = 7303
    private const val REQ_DISMISS = 7304
    const val ACTION_REFRESH = "io.github.sammynashed.emran.PRAYER_STATUS_REFRESH"
    const val ACTION_DISMISSED = "io.github.sammynashed.emran.PRAYER_STATUS_DISMISSED"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(c: Context) = prefs(c).getBoolean("status_notif_on", false)
    fun content(c: Context) = if (prefs(c).getString("status_notif_content", "all") == "next") Content.NEXT else Content.ALL

    fun setOn(c: Context, on: Boolean) { prefs(c).edit().putBoolean("status_notif_on", on).apply(); refreshAsync(c) }
    fun setContent(c: Context, v: Content) { prefs(c).edit().putString("status_notif_content", if (v == Content.ALL) "all" else "next").apply(); refreshAsync(c) }

    /** The day as the notification needs it: six times (epoch ms, in the order of [NAMES]) and which one is next. */
    class Snapshot(val times: List<Long>, val next: Int, val zone: ZoneId, val location: String)

    val NAMES = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")

    /** Today's times with the user's own corrections (the same ones the adhan alarms use); null when they cannot be worked out. */
    fun snapshot(c: Context, now: Long = System.currentTimeMillis()): Snapshot? = runCatching {
        val p = prefs(c)
        val lat = p.getFloat("latitude", 31.4165f).toDouble()
        val lon = p.getFloat("longitude", 31.8133f).toDouble()
        val zoneId = p.getString("zone_id", null) ?: PrayerTimeCalculator.DEFAULT_ZONE_ID
        val zone = PrayerTimeCalculator.safeZone(zoneId)
        val method = runCatching { PrayerTimeCalculator.CalculationMethod.valueOf(p.getString("calculation_method", "MWL") ?: "MWL") }.getOrDefault(PrayerTimeCalculator.CalculationMethod.MWL)
        val school = runCatching { PrayerTimeCalculator.AsrSchool.valueOf(p.getString("asr_school", "SHAFII") ?: "SHAFII") }.getOrDefault(PrayerTimeCalculator.AsrSchool.SHAFII)
        val rule = runCatching { PrayerTimeCalculator.HighLatitudeRule.valueOf(p.getString("high_latitude_rule", "AUTOMATIC") ?: "AUTOMATIC") }.getOrDefault(PrayerTimeCalculator.HighLatitudeRule.AUTOMATIC)
        val offsets = runBlocking { AppDatabase.getDatabase(c).prayerDao().getAllOffsets().first() }.associate { it.prayerKey to it.offsetMinutes }
        fun day(d: LocalDate): List<Long> {
            val t = PrayerTimeCalculator.calculateTimes(
                date = d, latitude = lat, longitude = lon, zoneId = zoneId, method = method, school = school, highLatitudeRule = rule,
                adjustments = PrayerTimeCalculator.Adjustments(
                    fajr = offsets["FAJR"] ?: 0, sunrise = offsets["SUNRISE"] ?: 0, dhuhr = offsets["DHUHR"] ?: 0,
                    asr = offsets["ASR"] ?: 0, maghrib = offsets["MAGHRIB"] ?: 0, isha = offsets["ISHA"] ?: 0,
                ),
            )
            return listOf(t.fajr, t.sunrise, t.dhuhr, t.asr, t.maghrib, t.isha).map { it.atZone(zone).toInstant().toEpochMilli() }
        }
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        var times = day(today)
        var next = times.indexOfFirst { it > now }
        if (next < 0) { times = day(today.plusDays(1)); next = 0 }   // after Isha: tomorrow's day, Fajr is next
        Snapshot(times, next, zone, p.getString("location_name", "") ?: "")
    }.getOrNull()

    private fun refreshAsync(c: Context) {
        val app = c.applicationContext
        Thread { runCatching { refresh(app) } }.start()
    }

    /** Posts (or removes) the notification and arms the one alarm for the next change. Blocking: call off the main thread. */
    fun refresh(context: Context) {
        val c = context.applicationContext
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!isOn(c) || !prefs(c).getBoolean("onboarding_completed", false)) {
            nm.cancel(NOTIF_ID); cancelAlarm(c); return
        }
        val snap = snapshot(c) ?: return
        post(c, nm, snap)
        arm(c, snap.times[snap.next])
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, REQ_ALARM, Intent(c, PrayerStatusReceiver::class.java).setAction(ACTION_REFRESH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelAlarm(c: Context) = (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(c))

    private fun arm(c: Context, nextAt: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = nextAt + 1_500L
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
        }
    }

    private fun post(c: Context, nm: NotificationManager, s: Snapshot) {
        val ar = (prefs(c).getString("current_language", "en") ?: "en") == "ar"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Normal importance, not low: low-importance notifications are filed under "Silent", below everything else.
            // It still makes no sound and never vibrates or pops up; it simply sits at the top of the list.
            nm.deleteNotificationChannel(OLD_CHANNEL)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, if (ar) "مواقيت الصلاة" else "Prayer times", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = if (ar) "مواقيت الصلاة في شريط الإشعارات، دون صوت" else "Prayer times in the notification panel, no sound"
                    setSound(null, null); enableVibration(false); enableLights(false); setShowBadge(false)
                },
            )
        }
        val content = content(c)
        val remaining = (s.times[s.next] - System.currentTimeMillis()).coerceAtLeast(0)
        val use24 = TimeFormat.is24(c)
        fun clock(i: Int) = TimeFormat.full(Instant.ofEpochMilli(s.times[i]).atZone(s.zone).toLocalDateTime(), ar, use24)
        val nextName = PrayerNames.name(NAMES[s.next], ar)
        val title = "$nextName · ${clock(s.next)}"
        val sub = if (ar) "الصلاة القادمة" + (if (s.location.isNotBlank()) " · ${s.location}" else "")
        else "Next prayer" + (if (s.location.isNotBlank()) " · ${s.location}" else "")

        val open = PendingIntent.getActivity(
            c, REQ_OPEN, Intent(c, io.github.sammynashed.emran.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_emran)
            .setContentTitle(title)
            .setContentText(sub)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Its own group: Android bundles an app's ungrouped notifications (adhan, adhkar) together,
            // and the live times must never fold into that stack.
            .setGroup("emran_prayer_status").setSortKey("0")
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            // A swipe puts it straight back (Android lets people clear "ongoing" notifications now); only the switch removes it.
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    c, REQ_DISMISS, Intent(c, PrayerStatusReceiver::class.java).setAction(ACTION_DISMISSED),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        fun fill(v: RemoteViews) {
            v.setTextViewText(R.id.status_title, if (content == Content.ALL) title else title)
            v.setChronometerCountDown(R.id.status_timer, true)
            v.setChronometer(R.id.status_timer, SystemClock.elapsedRealtime() + remaining, null, true)
        }
        b.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        if (content == Content.ALL) {
            // The whole day goes in the collapsed view too, so it is always shown "open", never hidden behind a pull-down.
            val accent = 0xFFFF9A3D.toInt()
            val v = RemoteViews(c.packageName, R.layout.notif_status_all).also(::fill)
            val nameIds = intArrayOf(R.id.p0_name, R.id.p1_name, R.id.p2_name, R.id.p3_name, R.id.p4_name, R.id.p5_name)
            val timeIds = intArrayOf(R.id.p0_time, R.id.p1_time, R.id.p2_time, R.id.p3_time, R.id.p4_time, R.id.p5_time)
            for (i in NAMES.indices) {
                v.setTextViewText(nameIds[i], PrayerNames.name(NAMES[i], ar))
                v.setTextViewText(timeIds[i], clock(i).replace(Regex("\\s+(AM|PM|ص|م)$"), ""))
                if (i == s.next) { v.setTextColor(nameIds[i], accent); v.setTextColor(timeIds[i], accent) }
            }
            b.setCustomContentView(v)   // no separate expanded view: nothing to expand
        } else {
            val v = RemoteViews(c.packageName, R.layout.notif_status_next).also(::fill)
            v.setTextViewText(R.id.status_sub, sub)
            b.setCustomContentView(v)
        }
        // NO_CLEAR on top of ONGOING: neither a swipe nor "Clear all" removes it; only the switch in Settings does.
        val n = b.build()
        n.flags = n.flags or android.app.Notification.FLAG_NO_CLEAR
        nm.notify(NOTIF_ID, n)
    }
}

/** Redraws the prayer-times notification: when the next prayer passes, after a swipe, and by the schedule worker. */
class PrayerStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.d("PrayerStatus", "receiver: ${intent.action}")
        val pending = goAsync()
        Thread {
            try { PrayerStatus.refresh(context) } catch (e: Exception) { android.util.Log.e("PrayerStatus", "refresh failed", e) } finally { pending.finish() }
        }.start()
    }
}
