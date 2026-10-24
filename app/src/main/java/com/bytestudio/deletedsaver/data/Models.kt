package com.bytestudio.deletedsaver.data

const val PKG_WHATSAPP = "com.whatsapp"
const val PKG_BUSINESS = "com.whatsapp.w4b"

object Retention {
    /** WhatsApp allows "Delete for everyone" for ~2 days 12 hours. Keep a 2h buffer on top. */
    const val WINDOW_MS = (60L + 2L) * 60 * 60 * 1000
    const val DAY_MS = 24L * 60 * 60 * 1000
}

data class Msg(
    val id: Long = 0,
    val pkg: String,
    val chat: String,
    val sender: String,
    val text: String,
    /** Time WhatsApp says the message was sent. */
    val ts: Long,
    val capturedAt: Long,
    val deletedAt: Long? = null,
    val isGroup: Boolean = false,
    /** Internal bookkeeping rows, never shown in the UI. */
    val hidden: Boolean = false,
)

data class ChatSummary(
    val pkg: String,
    val chat: String,
    val lastTs: Long,
    val lastText: String,
    val total: Int,
    val deleted: Int,
    val isGroup: Boolean,
)

enum class MediaKind { IMAGE, VIDEO, AUDIO, DOC }

data class MediaItem(
    val id: Long = 0,
    val pkg: String,
    /** Path relative to the WhatsApp folder, e.g. "accounts/1001/Media/WhatsApp Images/IMG-...jpg". */
    val relPath: String,
    val kind: MediaKind,
    val size: Long,
    val localPath: String,
    val capturedAt: Long,
    val deletedAt: Long? = null,
    /** When WhatsApp wrote the original file (≈ when the message arrived). */
    val srcMtime: Long = 0,
) {
    val name: String get() = relPath.substringAfterLast('/')
}

/** WhatsApp's text for media messages starts with an emoji: "📷 Photo", "🎥 Video", "🎤 Voice message"... */
fun mediaKindOf(text: String): MediaKind? = when {
    text.startsWith("📷") -> MediaKind.IMAGE
    text.startsWith("🎥") || text.startsWith("📹") || text.startsWith("👾") -> MediaKind.VIDEO
    text.startsWith("🎤") || text.startsWith("🎵") -> MediaKind.AUDIO
    text.startsWith("📄") -> MediaKind.DOC
    else -> null
}

data class RawEntry(val id: Long, val pkg: String, val at: Long, val dump: String)

fun appLabel(pkg: String) = if (pkg == PKG_BUSINESS) "Business" else "WhatsApp"
