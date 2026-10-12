package com.basir.inventory.util

import android.content.Context

/**
 * Remembers the most recent error (crash or failed background job) so the next heartbeat can
 * report it to the provider. Kept in plain prefs so it survives a crash.
 */
object ErrorLog {
    private const val PREFS = "errors"
    private const val KEY_TEXT = "last_error"
    private const val KEY_AT = "last_error_at"

    data class Entry(val text: String, val at: Long)

    fun record(context: Context, where: String, error: Throwable) {
        val text = "$where: ${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(300)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_AT, System.currentTimeMillis())
            .commit()
    }

    fun last(context: Context): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        return Entry(text, prefs.getLong(KEY_AT, 0))
    }

    /** Clears the entry once reported, unless a newer error arrived meanwhile. */
    fun clearIfUnchanged(context: Context, reported: Entry) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_AT, 0) == reported.at) prefs.edit().remove(KEY_TEXT).remove(KEY_AT).apply()
    }

    /** Records crashes, then lets Android handle them as usual. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, "crash", error) }
            previous?.uncaughtException(thread, error)
        }
    }
}
