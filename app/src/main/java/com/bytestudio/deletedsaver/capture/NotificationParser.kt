package com.bytestudio.deletedsaver.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

object NotificationParser {

    data class Parsed(
        val chat: String,
        val sender: String,
        val text: String,
        val ts: Long,
        val isGroup: Boolean,
    )

    /** [hiddenContent] = WhatsApp posted something but the text was masked (app lock / locker). */
    class Result(val messages: List<Parsed>, val hiddenContent: Boolean)

    /** Placeholders WhatsApp shows in place of a revoked message. Add your language here if needed. */
    private val deletedMarkers = listOf(
        "this message was deleted",
        "se eliminó este mensaje",
        "esta mensagem foi apagada",
        "diese nachricht wurde gelöscht",
        "ce message a été supprimé",
    )

    private val countSuffix = Regex("""\s*[(:]\s*\d+\s+(new\s+)?messages?\)?\s*$""", RegexOption.IGNORE_CASE)
    private val maskedText = Regex("""^(\d+\s+)?new messages?$|^\d+ messages? from \d+ chats?$""", RegexOption.IGNORE_CASE)

    fun isDeletedMarker(text: String): Boolean {
        val t = text.trim().removePrefix("🚫").trim().lowercase()
        return deletedMarkers.any { t.startsWith(it) }
    }

    fun parse(sbn: StatusBarNotification): Result? {
        val n = sbn.notification
        // Summary ("5 messages from 3 chats") and ongoing (calls, backup, WhatsApp Web) notifications.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.let(::clean)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val isGroup = style.isGroupConversation
            val convo = style.conversationTitle?.toString()?.let(::clean)
            val out = style.messages.mapNotNull { m ->
                val body = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val sender = m.person?.name?.toString()?.let(::clean) ?: title ?: return@mapNotNull null
                val chat = if (isGroup) convo ?: title ?: sender else sender
                Parsed(chat, sender, body, m.timestamp, isGroup)
            }
            val masked = out.isNotEmpty() && out.all { maskedText.matches(it.text.trim()) }
            return if (masked) Result(emptyList(), true) else Result(out, false)
        }

        // Chat messages always use MessagingStyle. Anything else is WhatsApp housekeeping
        // (backup progress, "Downloading document", "Couldn't complete backup"...), unless
        // it's the masked "1 new message" an app locker leaves behind.
        if (text != null && maskedText.matches(text.trim())) return Result(emptyList(), hiddenContent = true)
        return null
    }

    private fun clean(s: String) = s.replace(countSuffix, "").trim()

    /** Human-readable dump of every extra, for the notification inspector. */
    fun dump(sbn: StatusBarNotification): String = buildString {
        val n = sbn.notification
        appendLine("pkg=${sbn.packageName} id=${sbn.id} tag=${sbn.tag}")
        appendLine("flags=0x${Integer.toHexString(n.flags)} category=${n.category} group=${n.group}")
        dumpBundle(n.extras, "")
    }

    private fun StringBuilder.dumpBundle(b: Bundle, indent: String) {
        for (key in b.keySet().sorted()) {
            @Suppress("DEPRECATION")
            when (val v = b.get(key)) {
                is Bundle -> { appendLine("$indent$key:"); dumpBundle(v, "$indent  ") }
                is Array<*> -> {
                    appendLine("$indent$key: [${v.size}]")
                    v.forEach { item ->
                        if (item is Bundle) { appendLine("$indent  -"); dumpBundle(item, "$indent    ") }
                        else appendLine("$indent  - ${item.toString().take(200)}")
                    }
                }
                else -> appendLine("$indent$key=${v.toString().take(300)}")
            }
        }
    }
}
