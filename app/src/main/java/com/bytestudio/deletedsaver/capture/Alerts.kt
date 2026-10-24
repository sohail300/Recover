package com.bytestudio.deletedsaver.capture

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.R
import com.bytestudio.deletedsaver.data.MediaItem
import com.bytestudio.deletedsaver.data.MediaKind
import com.bytestudio.deletedsaver.data.Msg
import com.bytestudio.deletedsaver.data.appLabel
import com.bytestudio.deletedsaver.ui.MainActivity

object Alerts {
    private const val CHANNEL = "deleted"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_MEDIA = "media"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Deleted messages", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun messageDeleted(app: App, msg: Msg) {
        val who = if (msg.isGroup) "${msg.sender} in ${msg.chat}" else msg.sender
        val intent = Intent(app, MainActivity::class.java)
            .putExtra(EXTRA_PKG, msg.pkg)
            .putExtra(EXTRA_CHAT, msg.chat)
        post(app, "$who deleted a message", msg.text, intent, appLabel(msg.pkg))
    }

    fun mediaDeleted(app: App, items: List<MediaItem>) {
        val intent = Intent(app, MainActivity::class.java).putExtra(EXTRA_MEDIA, true)
        if (items.size == 1) {
            val kind = when (items[0].kind) {
                MediaKind.IMAGE -> "photo"; MediaKind.VIDEO -> "video"
                MediaKind.AUDIO -> "voice note / audio"; MediaKind.DOC -> "document"
            }
            post(app, "A $kind was deleted", items[0].name, intent, appLabel(items[0].pkg))
        } else {
            post(app, "${items.size} media files were deleted", "Saved copies are in the Media tab", intent, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(app: App, title: String, text: String, intent: Intent, sub: String?) {
        if (!app.prefs.alerts) return
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pi = PendingIntent.getActivity(
            app, id, intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(sub)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}
