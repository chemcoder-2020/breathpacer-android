package dev.breathwork.pacer

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Daily session reminders, one per breathing slot.
 *
 * Exact alarms need a special permission screen on Android 12+ and die under
 * Doze; inexact repeating alarms fire "around" the slot time and survive
 * unopened for months, which is exactly what a breathing schedule wants.
 * Tapping the notification opens the app with that slot pre-selected.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            Reminders.rescheduleAll(app)
            return
        }
        val slotId = intent.getStringExtra(EXTRA_SLOT) ?: return
        Reminders.notify(app, slotId)
    }

    companion object {
        const val EXTRA_SLOT = "dev.breathwork.pacer.SLOT"
        const val REQUEST_BASE = 4100
    }
}

object Reminders {
    const val CHANNEL_ID = "breath_sessions"
    private const val PREFS = "breathpacer"
    private const val KEY_PREFIX = "reminder_"
    private const val KEY_MASTER = "reminders_on"

    fun prefsOf(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isMasterOn(ctx: Context): Boolean =
        prefsOf(ctx).getBoolean(KEY_MASTER, false)

    fun setMaster(ctx: Context, on: Boolean) {
        prefsOf(ctx).edit().putBoolean(KEY_MASTER, on).apply()
        if (on) rescheduleAll(ctx) else cancelAll(ctx)
    }

    fun isOn(ctx: Context, slotId: String): Boolean =
        isMasterOn(ctx) && prefsOf(ctx).getBoolean(KEY_PREFIX + slotId, defaultOn(slotId))

    fun defaultOn(slotId: String): Boolean = true

    fun setSlot(ctx: Context, slotId: String, on: Boolean) {
        prefsOf(ctx).edit().putBoolean(KEY_PREFIX + slotId, on).apply()
        if (on && isMasterOn(ctx)) schedule(ctx, slotId) else cancel(ctx, slotId)
    }

    fun rescheduleAll(ctx: Context) {
        if (!isMasterOn(ctx)) return
        for (slot in Patterns.SLOTS) {
            if (prefsOf(ctx).getBoolean(KEY_PREFIX + slot.id, defaultOn(slot.id))) {
                schedule(ctx, slot.id)
            } else {
                cancel(ctx, slot.id)
            }
        }
    }

    fun schedule(ctx: Context, slotId: String) {
        val (hour, minute) = Schedules.timeFor(slotId)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = System.currentTimeMillis().let { now ->
            Schedules.nextTriggerForSlot(now, slotId)
        }
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            at,
            AlarmManager.INTERVAL_DAY,
            pending(ctx, slotId),
        )
        // Keep the stored copy honest even if nothing was showing.
        prefsOf(ctx).edit().putBoolean(KEY_PREFIX + slotId, true).apply()
        @Suppress("UNUSED_VARIABLE") val unused = Pair(hour, minute)
    }

    fun cancel(ctx: Context, slotId: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, slotId))
    }

    fun cancelAll(ctx: Context) {
        for (slot in Patterns.SLOTS) cancel(ctx, slot.id)
    }

    private fun pending(ctx: Context, slotId: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_SLOT, slotId)
        }
        val slotIndex = Patterns.SLOTS.indexOfFirst { it.id == slotId }.coerceAtLeast(0)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, ReminderReceiver.REQUEST_BASE + slotIndex, i, flags)
    }

    fun notify(ctx: Context, slotId: String) {
        val slot = Patterns.SLOTS.firstOrNull { it.id == slotId } ?: return
        ensureChannel(ctx)
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SLOT, slotId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val content = PendingIntent.getActivity(
            ctx, ReminderReceiver.REQUEST_BASE + 100 + Patterns.SLOTS.indexOf(slot), open, flags)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val note = android.app.Notification.Builder(ctx, CHANNEL_ID).apply {
            setContentTitle("${slot.time} — ${slot.name}")
            setContentText("Time for your breathing session. Tap to open the pacer.")
            setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            setContentIntent(content)
            setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Nothing version-specific here; the guard keeps lint quiet on old APIs.
            }
        }.build()
        // One stable id per slot: tomorrow's reminder replaces today's.
        nm.notify(ReminderReceiver.REQUEST_BASE + Patterns.SLOTS.indexOf(slot), note)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Breathing sessions", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Daily reminders for each breathing slot"
        })
    }
}
