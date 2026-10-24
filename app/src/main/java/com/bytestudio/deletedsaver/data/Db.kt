package com.bytestudio.deletedsaver.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

class Db(context: Context) : SQLiteOpenHelper(context, "saver.db", null, 3) {

    private val _changes = MutableStateFlow(0L)

    /** Bumped on every write so the UI can re-query. */
    val changes: StateFlow<Long> = _changes

    private fun changed() = _changes.update { it + 1 }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL, chat TEXT NOT NULL, sender TEXT NOT NULL, text TEXT NOT NULL,
                ts INTEGER NOT NULL, captured_at INTEGER NOT NULL, deleted_at INTEGER,
                is_group INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0,
                UNIQUE(pkg, chat, sender, ts, text))"""
        )
        db.execSQL("CREATE INDEX idx_msg_chat ON messages(pkg, chat, ts)")
        db.execSQL(
            """CREATE TABLE media (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL, rel_path TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL,
                local_path TEXT NOT NULL, captured_at INTEGER NOT NULL, deleted_at INTEGER,
                src_mtime INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0,
                UNIQUE(pkg, rel_path))"""
        )
        db.execSQL(
            "CREATE TABLE raw_log (id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT NOT NULL, at INTEGER NOT NULL, dump TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE media ADD COLUMN src_mtime INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 3) db.execSQL("ALTER TABLE media ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
    }

    // ---- user deletes ----
    // Rows are hidden rather than removed: WhatsApp keeps reposting unread messages and the
    // original media file stays on disk, so a removed row would just be captured again.
    // Hidden rows are purged once the delete-for-everyone window has passed.

    fun hideMessage(id: Long) {
        writableDatabase.execSQL("UPDATE messages SET hidden=1 WHERE id=?", arrayOf<Any>(id))
        changed()
    }

    fun hideChat(pkg: String, chat: String) {
        writableDatabase.execSQL("UPDATE messages SET hidden=1 WHERE pkg=? AND chat=?", arrayOf<Any>(pkg, chat))
        changed()
    }

    fun hideMedia(id: Long) {
        writableDatabase.execSQL("UPDATE media SET hidden=1 WHERE id=?", arrayOf<Any>(id))
        changed()
    }

    // ---- messages ----

    /** Returns true if the row was new. */
    fun insertMessage(m: Msg): Boolean {
        val values = ContentValues().apply {
            put("pkg", m.pkg); put("chat", m.chat); put("sender", m.sender); put("text", m.text)
            put("ts", m.ts); put("captured_at", m.capturedAt); put("deleted_at", m.deletedAt)
            put("is_group", if (m.isGroup) 1 else 0); put("hidden", if (m.hidden) 1 else 0)
        }
        val id = writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (id != -1L) changed()
        return id != -1L
    }

    /** Any row (deleted or not, hidden or not) sent at about [ts] by [sender] in [chat]. */
    fun findByTs(pkg: String, chat: String, sender: String, ts: Long, toleranceMs: Long = 1500): Msg? =
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE pkg=? AND chat=? AND sender=? AND ts BETWEEN ? AND ? ORDER BY deleted_at IS NULL",
            arrayOf(pkg, chat, sender, (ts - toleranceMs).toString(), (ts + toleranceMs).toString()),
        ).use { c -> c.toMsgs().minByOrNull { abs(it.ts - ts) } }

    /** Latest live message from [sender] that is no longer in the notification (best guess for a deletion). */
    fun findFallback(pkg: String, chat: String, sender: String, excludeTs: Set<Long>, since: Long): Msg? =
        readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE pkg=? AND chat=? AND sender=? AND deleted_at IS NULL AND hidden=0 AND captured_at>=? ORDER BY ts DESC LIMIT 50",
            arrayOf(pkg, chat, sender, since.toString()),
        ).use { c -> c.toMsgs().firstOrNull { m -> excludeTs.none { abs(it - m.ts) < 1500 } } }

    /** Newest live message in [chat] with exactly [text] (used when only the notification's last line is known). */
    fun findLatest(pkg: String, chat: String, text: String, since: Long): Msg? = readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE pkg=? AND chat=? AND text=? AND deleted_at IS NULL AND captured_at>=? ORDER BY ts DESC LIMIT 1",
        arrayOf(pkg, chat, text, since.toString()),
    ).use { it.toMsgs().firstOrNull() }

    /** Whether a received media message of [kind] was captured between [from] and [to]. */
    fun hasIncomingMedia(pkg: String, kind: MediaKind, from: Long, to: Long): Boolean = readableDatabase.rawQuery(
        "SELECT text FROM messages WHERE pkg=? AND captured_at BETWEEN ? AND ?",
        arrayOf(pkg, from.toString(), to.toString()),
    ).use { c ->
        var found = false
        while (!found && c.moveToNext()) found = mediaKindOf(c.getString(0)) == kind
        found
    }

    fun markMessageDeleted(id: Long, at: Long) {
        writableDatabase.execSQL("UPDATE messages SET deleted_at=? WHERE id=?", arrayOf<Any>(at, id))
        changed()
    }

    fun chats(deletedOnly: Boolean): List<ChatSummary> = readableDatabase.rawQuery(
        """SELECT pkg, chat, MAX(ts) AS last_ts, COUNT(*) AS total,
              SUM(deleted_at IS NOT NULL) AS deleted, MAX(is_group) AS grp,
              (SELECT text FROM messages m2 WHERE m2.pkg=m.pkg AND m2.chat=m.chat AND m2.hidden=0
               ORDER BY ts DESC LIMIT 1) AS last_text
           FROM messages m WHERE hidden=0
           GROUP BY pkg, chat ${if (deletedOnly) "HAVING deleted > 0" else ""}
           ORDER BY last_ts DESC""",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                ChatSummary(
                    pkg = c.str("pkg"), chat = c.str("chat"), lastTs = c.long("last_ts"),
                    lastText = c.str("last_text"), total = c.int("total"), deleted = c.int("deleted"),
                    isGroup = c.int("grp") == 1,
                )
            )
        }
    }

    fun messages(pkg: String, chat: String): List<Msg> = readableDatabase.rawQuery(
        "SELECT * FROM messages WHERE pkg=? AND chat=? AND hidden=0 ORDER BY ts",
        arrayOf(pkg, chat),
    ).use { it.toMsgs() }

    /**
     * A chat's messages, each media placeholder ("📷 Photo"...) paired with the saved file of the
     * same kind that WhatsApp wrote closest to the message time (within -1/+30 min).
     */
    fun messagesWithMedia(pkg: String, chat: String): List<Pair<Msg, MediaItem?>> {
        val msgs = messages(pkg, chat)
        val wanted = msgs.filter { mediaKindOf(it.text) != null }
        if (wanted.isEmpty()) return msgs.map { it to null }

        val candidates = readableDatabase.rawQuery(
            "SELECT * FROM media WHERE pkg=? AND hidden=0 AND src_mtime BETWEEN ? AND ?",
            arrayOf(pkg, (wanted.first().ts - 60_000).toString(), (wanted.last().ts + 30 * 60_000).toString()),
        ).use { it.toMedia() }.toMutableList()

        val linked = HashMap<Long, MediaItem>()
        for (m in wanted) {
            val kind = mediaKindOf(m.text)
            val best = candidates
                .filter { it.kind == kind && it.srcMtime in (m.ts - 60_000)..(m.ts + 30 * 60_000) }
                .minByOrNull { abs(it.srcMtime - m.ts) } ?: continue
            linked[m.id] = best
            candidates.remove(best)
        }
        return msgs.map { it to linked[it.id] }
    }

    fun search(query: String): List<Msg> {
        val like = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        return readableDatabase.rawQuery(
            """SELECT * FROM messages WHERE hidden=0 AND
               (text LIKE ? ESCAPE '\' OR chat LIKE ? ESCAPE '\' OR sender LIKE ? ESCAPE '\')
               ORDER BY ts DESC LIMIT 200""",
            arrayOf(like, like, like),
        ).use { it.toMsgs() }
    }

    // ---- media ----

    fun hasMedia(pkg: String, relPath: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM media WHERE pkg=? AND rel_path=?", arrayOf(pkg, relPath),
    ).use { it.moveToFirst() }

    fun insertMedia(item: MediaItem) {
        val values = ContentValues().apply {
            put("pkg", item.pkg); put("rel_path", item.relPath); put("kind", item.kind.name)
            put("size", item.size); put("local_path", item.localPath); put("captured_at", item.capturedAt)
            put("src_mtime", item.srcMtime)
        }
        if (writableDatabase.insertWithOnConflict("media", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) changed()
    }

    /** Media still inside the delete-for-everyone window and not yet flagged. */
    fun liveMedia(pkg: String, since: Long): List<MediaItem> = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE pkg=? AND deleted_at IS NULL AND hidden=0 AND captured_at>=?",
        arrayOf(pkg, since.toString()),
    ).use { it.toMedia() }

    fun markMediaDeleted(ids: List<Long>, at: Long) {
        if (ids.isEmpty()) return
        writableDatabase.execSQL(
            "UPDATE media SET deleted_at=? WHERE id IN (${ids.joinToString(",")})", arrayOf<Any>(at),
        )
        changed()
    }

    fun media(deletedOnly: Boolean): List<MediaItem> = readableDatabase.rawQuery(
        "SELECT * FROM media WHERE hidden=0 ${if (deletedOnly) "AND deleted_at IS NOT NULL" else ""} ORDER BY captured_at DESC",
        null,
    ).use { it.toMedia() }

    // ---- cleanup ----

    /**
     * Drops anything that survived the delete window (never deleted by the sender), and
     * deleted items older than [deletedBefore] (null = keep forever).
     * Returns local media files that should now be removed from disk.
     */
    fun purge(liveBefore: Long, deletedBefore: Long?): List<String> {
        val db = writableDatabase
        val where = buildString {
            append("((deleted_at IS NULL OR hidden=1) AND captured_at < $liveBefore)")
            if (deletedBefore != null) append(" OR (deleted_at IS NOT NULL AND deleted_at < $deletedBefore)")
        }
        val files = db.rawQuery("SELECT local_path FROM media WHERE $where", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM media WHERE $where")
            db.execSQL("DELETE FROM messages WHERE $where")
            db.execSQL("DELETE FROM raw_log WHERE id NOT IN (SELECT id FROM raw_log ORDER BY id DESC LIMIT 50)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return files
    }

    // ---- notification inspector ----

    fun addRaw(pkg: String, dump: String) {
        writableDatabase.execSQL(
            "INSERT INTO raw_log(pkg, at, dump) VALUES (?, ?, ?)",
            arrayOf<Any>(pkg, System.currentTimeMillis(), dump),
        )
        writableDatabase.execSQL("DELETE FROM raw_log WHERE id NOT IN (SELECT id FROM raw_log ORDER BY id DESC LIMIT 50)")
        changed()
    }

    fun raws(): List<RawEntry> = readableDatabase.rawQuery(
        "SELECT * FROM raw_log ORDER BY id DESC", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(RawEntry(c.long("id"), c.str("pkg"), c.long("at"), c.str("dump"))) }
    }

    fun clearRaw() {
        writableDatabase.execSQL("DELETE FROM raw_log")
        changed()
    }

    // ---- cursor helpers ----

    private fun Cursor.str(col: String): String = getString(getColumnIndexOrThrow(col)) ?: ""
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.int(col: String): Int = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.longOrNull(col: String): Long? =
        getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }

    private fun Cursor.toMsgs(): List<Msg> = buildList {
        while (moveToNext()) add(
            Msg(
                id = long("id"), pkg = str("pkg"), chat = str("chat"), sender = str("sender"),
                text = str("text"), ts = long("ts"), capturedAt = long("captured_at"),
                deletedAt = longOrNull("deleted_at"), isGroup = int("is_group") == 1,
                hidden = int("hidden") == 1,
            )
        )
    }

    private fun Cursor.toMedia(): List<MediaItem> = buildList {
        while (moveToNext()) add(
            MediaItem(
                id = long("id"), pkg = str("pkg"), relPath = str("rel_path"),
                kind = MediaKind.valueOf(str("kind")), size = long("size"),
                localPath = str("local_path"), capturedAt = long("captured_at"),
                deletedAt = longOrNull("deleted_at"), srcMtime = long("src_mtime"),
            )
        )
    }
}
