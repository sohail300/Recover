package com.bytestudio.deletedsaver.capture

import com.bytestudio.deletedsaver.App
import com.bytestudio.deletedsaver.data.Retention
import java.io.File

object Cleanup {
    /** Removes items the sender never deleted (window passed) and old deleted items. */
    fun run(app: App) {
        val now = System.currentTimeMillis()
        val days = app.prefs.keepDeletedDays
        val files = app.db.purge(
            liveBefore = now - Retention.WINDOW_MS,
            deletedBefore = if (days > 0) now - days * Retention.DAY_MS else null,
        )
        files.forEach { runCatching { File(it).delete() } }
    }
}
