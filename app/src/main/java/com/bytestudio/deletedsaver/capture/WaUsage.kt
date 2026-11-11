package com.bytestudio.deletedsaver.capture

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Tells "WhatsApp removed its notification because you read the chat" apart from
 * "...because the sender deleted the message": if WhatsApp was on screen around that
 * moment, it was a read.
 */
object WaUsage {

    fun hasAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    /** Times between [from] and [to] when you tapped one of [pkg]'s notifications or its buttons (e.g. "Mark as read"). */
    fun notificationTaps(context: Context, pkg: String, from: Long, to: Long): List<Long> {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = ArrayList<Long>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == pkg && e.eventType == UsageEvents.Event.USER_INTERACTION) out += e.timeStamp
        }
        return out
    }

    /** True if [pkg] is in the foreground now or left it within [withinMs]. */
    fun wasOnScreen(context: Context, pkg: String, withinMs: Long = 15_000): Boolean {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 2 * 60 * 60 * 1000, now)
        var lastResumed = 0L
        var lastPaused = 0L
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName != pkg) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> lastResumed = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> lastPaused = e.timeStamp
            }
        }
        val inForeground = lastResumed > lastPaused
        return inForeground || now - lastPaused < withinMs || now - lastResumed < withinMs
    }
}
