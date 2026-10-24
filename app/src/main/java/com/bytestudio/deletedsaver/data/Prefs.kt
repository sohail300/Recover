package com.bytestudio.deletedsaver.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var alerts: Boolean
        get() = sp.getBoolean("alerts", true)
        set(v) = sp.edit { putBoolean("alerts", v) }

    var business: Boolean
        get() = sp.getBoolean("business", true)
        set(v) = sp.edit { putBoolean("business", v) }

    /** How long to keep deleted messages/media. 0 = forever. */
    var keepDeletedDays: Int
        get() = sp.getInt("keep_days", 30)
        set(v) = sp.edit { putInt("keep_days", v) }

    /** Last time a WhatsApp notification arrived with its content hidden (e.g. by an app locker). */
    var lastHiddenAt: Long
        get() = sp.getLong("last_hidden", 0)
        set(v) = sp.edit { putLong("last_hidden", v) }

    /** Last time a WhatsApp notification arrived with readable message text. */
    var lastReadableAt: Long
        get() = sp.getLong("last_readable", 0)
        set(v) = sp.edit { putLong("last_readable", v) }

    fun packages(): Set<String> =
        if (business) setOf(PKG_WHATSAPP, PKG_BUSINESS) else setOf(PKG_WHATSAPP)
}
