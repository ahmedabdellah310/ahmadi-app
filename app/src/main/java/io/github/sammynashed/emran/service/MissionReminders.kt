package io.github.sammynashed.emran.service

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One daily mission the user set for himself: a title, an optional reminder time and a streak. */
class Mission(val id: Int, val title: String, val minute: Int, val doneDate: String, val lastDate: String, val streak: Int)

/**
 * The missions list and its daily reminders. Each mission with a reminder time is one exact alarm for its next
 * occurrence; when it fires the receiver posts the reminder and arms the next day. The prayer re-arm job, boot and
 * opening the app re-arm these too, like the adhkar reminders.
 */
object MissionReminders {
    private const val PREFS = "missions"
    private const val CHANNEL = "emran_missions"
    private const val EXTRA_ID = "MISSION_ID"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(c: Context): List<Mission> {
        val raw = prefs(c).getString("items", "[]") ?: "[]"
        val a = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Mission(o.getInt("id"), o.getString("t"), o.optInt("m", -1), o.optString("done"), o.optString("last"), o.optInt("streak", 0))
        }
    }

    private fun store(c: Context, list: List<Mission>, next: Int? = null) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("t", it.title).put("m", it.minute).put("done", it.doneDate).put("last", it.lastDate).put("streak", it.streak))
        }
        val e = prefs(c).edit().putString("items", a.toString())
        if (next != null) e.putInt("next", next)
        e.commit()
    }

    fun add(c: Context, title: String, minute: Int) {
        val n = prefs(c).getInt("next", 1)
        store(c, load(c) + Mission(n, title.trim(), minute, "", "", 0), n + 1)
        scheduleAll(c)
    }

    fun remove(c: Context, id: Int) {
        store(c, load(c).filter { it.id != id }); cancel(c, id)
    }

    fun setMinute(c: Context, id: Int, minute: Int) {
        store(c, load(c).map { if (it.id == id) Mission(it.id, it.title, minute, it.doneDate, it.lastDate, it.streak) else it })
        scheduleAll(c)
    }

    fun toggleToday(c: Context, id: Int) {
        val today = LocalDate.now().toString()
        val yesterday = LocalDate.now().minusDays(1).toString()
        store(c, load(c).map {
            if (it.id != id) it
            else if (it.doneDate == today) Mission(it.id, it.title, it.minute, "", it.lastDate.takeIf { l -> l != today } ?: "", maxOf(0, it.streak - 1))
            else Mission(it.id, it.title, it.minute, today, today, if (it.lastDate == yesterday) it.streak + 1 else 1)
        })
    }

    /** The streak as shown today: it breaks if nothing was done yesterday or today. */
    fun liveStreak(m: Mission): Int {
        val today = LocalDate.now().toString(); val y = LocalDate.now().minusDays(1).toString()
        return if (m.lastDate == today || m.lastDate == y) m.streak else 0
    }

    private fun pending(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 8000 + id, Intent(c, MissionReminderReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancel(c: Context, id: Int) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(c, id))
    }

    fun nextTrigger(minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val at = LocalTime.of(minute / 60, minute % 60)
        val today = LocalDate.now(java.time.Clock.fixed(java.time.Instant.ofEpochMilli(now), zone))
        val t = today.atTime(at).atZone(zone).toInstant().toEpochMilli()
        return if (t > now) t else today.plusDays(1).atTime(at).atZone(zone).toInstant().toEpochMilli()
    }

    fun scheduleAll(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (m in load(app)) {
            val pi = pending(app, m.id)
            am.cancel(pi)
            if (m.minute < 0) continue
            val at = nextTrigger(m.minute, System.currentTimeMillis())
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Log.d("MissionReminders", "mission ${m.id} armed for ${java.time.Instant.ofEpochMilli(at)}")
            } catch (e: Exception) { Log.e("MissionReminders", "could not arm ${m.id}", e) }
        }
    }

    internal fun notify(context: Context, id: Int) {
        val m = load(context).firstOrNull { it.id == id } ?: return
        if (m.doneDate == LocalDate.now().toString()) return // already done today: stay quiet
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ar = (context.getSharedPreferences("emran_settings", Context.MODE_PRIVATE).getString("current_language", "en") ?: "en") == "ar"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, if (ar) "المهام" else "Missions", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, io.github.sammynashed.emran.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("ROUTE", "missions")
        }
        val content = PendingIntent.getActivity(context, 8000 + id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(io.github.sammynashed.emran.R.drawable.ic_stat_emran)
            .setContentTitle(if (ar) "مهمتك اليوم" else "Today's mission")
            .setContentText(m.title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(content).build()
        try { nm.notify(8000 + id, n) } catch (e: SecurityException) { Log.w("MissionReminders", "notifications not allowed", e) }
    }
}

class MissionReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("MISSION_ID", -1)
        if (id >= 0) MissionReminders.notify(context, id)
        MissionReminders.scheduleAll(context)
    }
}
