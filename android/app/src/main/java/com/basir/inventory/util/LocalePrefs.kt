package com.basir.inventory.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language (Bangla by default), applied by wrapping the activity's base context. */
object LocalePrefs {
    private const val PREFS = "ui"
    private const val KEY = "language"

    fun isBangla(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "bn") == "bn"

    fun setBangla(context: Context, bangla: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, if (bangla) "bn" else "en").commit()
        Fmt.bangla = bangla
    }

    fun wrap(base: Context): Context {
        val locale = if (isBangla(base)) Locale("bn", "BD") else Locale.ENGLISH
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
