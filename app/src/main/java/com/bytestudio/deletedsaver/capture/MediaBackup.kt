package com.bytestudio.deletedsaver.capture

import android.os.Environment
import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.data.MediaItem
import com.bytestudio.deletedsaver.data.MediaKind
import com.bytestudio.deletedsaver.data.PKG_BUSINESS
import com.bytestudio.deletedsaver.data.Retention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copies freshly received WhatsApp media into app storage. If WhatsApp later removes
 * the original while it is still inside the delete-for-everyone window, the copy is
 * flagged as deleted and kept.
 */
object MediaBackup {

    private val mutex = Mutex()

    /**
     * Top-level Media folders that hold received chat media, matched by suffix so both
     * "WhatsApp Images" and "WhatsApp Business Images" qualify. Everything else
     * (stickers, wallpapers, AI/ad media, bug reports...) is ignored.
     */
    private val mediaDirSuffixes = listOf(
        "images", "video", "video notes", "audio", "voice notes", "documents", "animated gifs",
    )

    /** Subfolders holding your own outgoing media. */
    private val skipSubDirs = setOf("sent", "private")

    private fun shouldEnter(dir: File, root: File): Boolean {
        val name = dir.name.lowercase()
        return when {
            dir == root -> true
            name.startsWith(".") -> false
            dir.parentFile == root -> mediaDirSuffixes.any { name.endsWith(it) }
            else -> name !in skipSubDirs
        }
    }

    fun hasAccess() = Environment.isExternalStorageManager()

    private fun roots(pkg: String): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val name = if (pkg == PKG_BUSINESS) "WhatsApp Business" else "WhatsApp"
        return listOf(
            File(sd, "Android/media/$pkg/$name"),
            File(sd, name), // pre-2021 location
        ).filter { it.isDirectory && it.canRead() }
    }

    /** "Media" folders inside a WhatsApp folder: the classic one plus one per account (accounts/<id>/Media). */
    private fun mediaDirs(base: File): List<File> =
        (listOf(File(base, "Media")) + (File(base, "accounts").listFiles()?.map { File(it, "Media") } ?: emptyList()))
            .filter { it.isDirectory && it.canRead() }

    suspend fun scan(app: App) {
        if (!hasAccess()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                for (pkg in app.prefs.packages()) scanPackage(app, pkg)
            }
        }
    }

    private fun scanPackage(app: App, pkg: String) {
        val roots = roots(pkg)
        if (roots.isEmpty()) return // folder missing or unreadable: don't treat files as deleted
        val db = app.db
        val now = System.currentTimeMillis()
        val since = now - Retention.WINDOW_MS

        // 1) Back up new files. Paths are stored relative to the WhatsApp folder so accounts don't collide.
        for (base in roots) for (root in mediaDirs(base)) {
            root.walkTopDown()
                .maxDepth(4)
                .onEnter { shouldEnter(it, root) }
                .filter { f ->
                    f.parentFile != root && // loose files directly in Media/
                    f.isFile && !f.name.startsWith(".") && f.length() > 0 &&
                        f.lastModified() >= since &&
                        now - f.lastModified() > 3_000 // still being written otherwise
                }
                .forEach { f ->
                    val rel = f.relativeTo(base).invariantSeparatorsPath
                    if (db.hasMedia(pkg, rel)) return@forEach
                    if (isMixedFolder(f, root) && !db.hasIncomingMedia(
                            pkg, kindOf(f), f.lastModified() - 10 * 60_000, f.lastModified() + 30_000,
                        )
                    ) return@forEach // your own recording, or not announced yet (retried next scan)
                    runCatching {
                        val dest = File(app.filesDir, "media/$pkg/$rel")
                        dest.parentFile?.mkdirs()
                        f.copyTo(dest, overwrite = true)
                        db.insertMedia(
                            MediaItem(
                                pkg = pkg, relPath = rel, kind = kindOf(f), size = f.length(),
                                localPath = dest.absolutePath, capturedAt = now, srcMtime = f.lastModified(),
                            )
                        )
                    }
                }
        }

        // 2) Originals that vanished inside the window = deleted for everyone.
        val gone = db.liveMedia(pkg, since).filter { item ->
            roots.none { File(it, item.relPath).exists() }
        }
        if (gone.isNotEmpty()) {
            db.markMediaDeleted(gone.map { it.id }, now)
            Alerts.mediaDeleted(app, gone)
        }
    }

    /**
     * Voice notes and video notes have no "Sent" subfolder: your own recordings sit next to
     * received ones. Files there only count if a matching incoming message was notified.
     */
    private fun isMixedFolder(f: File, mediaRoot: File): Boolean {
        val top = f.relativeTo(mediaRoot).invariantSeparatorsPath.substringBefore('/').lowercase()
        return top.endsWith("voice notes") || top.endsWith("video notes")
    }

    private fun kindOf(f: File): MediaKind = when (f.extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "gif" -> MediaKind.IMAGE
        "mp4", "3gp", "mkv", "mov", "webm" -> MediaKind.VIDEO
        "opus", "ogg", "m4a", "mp3", "aac", "amr", "wav" -> MediaKind.AUDIO
        else -> MediaKind.DOC
    }
}
