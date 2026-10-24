package com.bytestudio.deletedsaver.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.data.Msg
import com.bytestudio.deletedsaver.data.Retention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Receives every WhatsApp notification. Saves message text, detects
 * "This message was deleted" updates, and kicks off media scans.
 */
class WaNotificationListener : NotificationListenerService() {

    // One message at a time so dedup/deletion matching never races.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var loopJob: Job? = null
    private var scanJob: Job? = null
    private val app get() = application as App

    override fun onListenerConnected() {
        loopJob?.cancel()
        loopJob = scope.launch {
            runCatching { activeNotifications?.forEach(::handle) }
            var tick = 0
            while (isActive) {
                runCatching { MediaBackup.scan(app) }
                if (tick++ % 6 == 0) runCatching { Cleanup.run(app) }
                delay(10.minutes)
            }
        }
    }

    override fun onListenerDisconnected() {
        loopJob?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Some ROMs register the listener twice and deliver every event twice (seen on
     * OnePlus / Android 16). Remember recent events so each is handled once.
     */
    private val recentEvents = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 300
    }

    private fun firstTime(event: String): Boolean = synchronized(recentEvents) {
        recentEvents.put(event, Unit) == null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in app.prefs.packages()) return
        if (!firstTime("post|${sbn.key}|${sbn.postTime}|${sbn.notification.`when`}")) return
        scope.launch { runCatching { handle(sbn) } }
        scheduleScan()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (sbn.packageName !in app.prefs.packages()) return
        if (!firstTime("remove|${sbn.key}|${sbn.postTime}")) return
        scope.launch { runCatching { handleRemoved(sbn, reason) } }
        scheduleScan() // a deleted photo's file disappears around now too
    }

    /**
     * Newer WhatsApp versions don't post "This message was deleted"; they silently cancel the
     * notification. A cancel by WhatsApp itself while WhatsApp wasn't on screen = deleted.
     */
    private fun handleRemoved(sbn: StatusBarNotification, reason: Int) {
        val db = app.db
        db.addRaw(sbn.packageName, "REMOVED reason=$reason\n" + NotificationParser.dump(sbn))
        // WhatsApp cancels the group summary (APP_CANCEL); Android then removes the message
        // notification under it with GROUP_SUMMARY_CANCELED. Anything else = you swiped/tapped it.
        if (reason != REASON_APP_CANCEL && reason != REASON_GROUP_SUMMARY_CANCELED) return
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        // Android strips the message list from removed notifications, so use what we saw when it
        // was posted. Without that (e.g. after a reboot), match the title + last line of text.
        val seen = lastSeen.remove(sbn.key)
        val access = WaUsage.hasAccess(app)
        val onScreen = access && WaUsage.wasOnScreen(app, sbn.packageName)
        db.addRaw(
            sbn.packageName,
            "DECISION usageAccess=$access whatsappOnScreen=$onScreen remembered=${seen?.size ?: 0} " +
                "-> ${if (access && !onScreen) "check for deletion" else "treat as read"}",
        )
        if (!access || onScreen) return // can't tell, or you opened the chat

        val now = System.currentTimeMillis()
        if (seen != null) {
            seen.forEach { markSilentlyDeleted(sbn.packageName, it, now) }
            return
        }
        val extras = sbn.notification.extras
        val chat = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: return
        val row = db.findLatest(sbn.packageName, chat, text, now - Retention.WINDOW_MS) ?: return
        db.markMessageDeleted(row.id, now)
        if (!row.hidden) Alerts.messageDeleted(app, row)
    }

    /** Messages last seen in each notification, to spot one vanishing from a repost. */
    private val lastSeen = HashMap<String, List<NotificationParser.Parsed>>()

    /**
     * Deleting one of several unread messages makes WhatsApp repost the notification without it.
     * A message missing from the repost is deleted if something older is still there (WhatsApp
     * trims history from the front) or if nothing new arrived (so it wasn't trimmed at all).
     */
    private fun detectDroppedMessages(sbn: StatusBarNotification, now: List<NotificationParser.Parsed>) {
        val before = lastSeen.put(sbn.key, now) ?: return
        if (!WaUsage.hasAccess(app) || WaUsage.wasOnScreen(app, sbn.packageName)) return
        val nowTs = now.map { it.ts }
        val nothingNew = (nowTs.maxOrNull() ?: 0) <= (before.maxOfOrNull { it.ts } ?: 0)
        val time = System.currentTimeMillis()
        for (p in before) {
            if (now.any { it.ts == p.ts && it.sender == p.sender }) continue
            if (nothingNew || nowTs.any { it < p.ts }) markSilentlyDeleted(sbn.packageName, p, time)
        }
    }

    private fun markSilentlyDeleted(pkg: String, p: NotificationParser.Parsed, now: Long) {
        if (NotificationParser.isDeletedMarker(p.text)) return
        val row = app.db.findByTs(pkg, p.chat, p.sender, p.ts) ?: return
        if (row.deletedAt != null) return
        app.db.markMessageDeleted(row.id, now)
        if (!row.hidden) Alerts.messageDeleted(app, row)
    }

    /** Media downloads a moment after the notification; scan shortly after, then again a bit later. */
    private fun scheduleScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(4.seconds)
            runCatching { MediaBackup.scan(app) }
            delay(30.seconds)
            runCatching { MediaBackup.scan(app) }
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName !in app.prefs.packages()) return
        val db = app.db
        db.addRaw(sbn.packageName, NotificationParser.dump(sbn))

        val result = NotificationParser.parse(sbn) ?: return
        val now = System.currentTimeMillis()
        if (result.hiddenContent) {
            app.prefs.lastHiddenAt = now
            return
        }
        if (result.messages.isNotEmpty()) app.prefs.lastReadableAt = now
        detectDroppedMessages(sbn, result.messages)

        val tsInNotification = result.messages.map { it.ts }.toSet()
        for (p in result.messages) {
            if (NotificationParser.isDeletedMarker(p.text)) {
                handleDeletion(sbn.packageName, p, tsInNotification, now)
            } else {
                db.insertMessage(
                    Msg(
                        pkg = sbn.packageName, chat = p.chat, sender = p.sender, text = p.text,
                        ts = p.ts, capturedAt = now, isGroup = p.isGroup,
                    )
                )
            }
        }
    }

    private fun handleDeletion(pkg: String, p: NotificationParser.Parsed, tsInNotification: Set<Long>, now: Long) {
        val db = app.db

        // WhatsApp normally swaps the text in place and keeps the original timestamp.
        val exact = db.findByTs(pkg, p.chat, p.sender, p.ts)
        if (exact != null) {
            if (exact.deletedAt == null) {
                db.markMessageDeleted(exact.id, now)
                if (!exact.hidden) Alerts.messageDeleted(app, exact) // hidden = user already removed it
            }
            return
        }

        // A hidden marker at this timestamp stops later reposts from matching another message.
        val marker = Msg(
            pkg = pkg, chat = p.chat, sender = p.sender, text = p.text, ts = p.ts,
            capturedAt = now, deletedAt = now, isGroup = p.isGroup,
        )

        val guess = db.findFallback(pkg, p.chat, p.sender, tsInNotification, now - Retention.WINDOW_MS)
        if (guess != null) {
            db.markMessageDeleted(guess.id, now)
            db.insertMessage(marker.copy(hidden = true))
            Alerts.messageDeleted(app, guess)
            return
        }

        // Deleted before we ever saw the original: still record that it happened.
        if (db.insertMessage(marker.copy(text = NOT_CAPTURED))) {
            Alerts.messageDeleted(app, marker.copy(text = NOT_CAPTURED))
        }
    }

    companion object {
        const val NOT_CAPTURED = "(deleted before it could be saved)"
    }
}
