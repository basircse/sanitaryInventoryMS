package com.basir.inventory.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.basir.inventory.BuildConfig
import com.basir.inventory.MainActivity
import com.basir.inventory.R
import com.basir.inventory.util.Fmt
import com.basir.inventory.util.LocalePrefs

object Notifications {
    private const val CHANNEL = "reminders"
    private const val ID_LICENSE = 1

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val ctx = LocalePrefs.wrap(context)
        val channel = NotificationChannel(CHANNEL, ctx.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun expiringSoon(context: Context, daysLeft: Int) {
        val ctx = LocalePrefs.wrap(context)
        show(context, ctx.getString(R.string.notif_expiry_title), ctx.getString(R.string.notif_expiry_body, Fmt.num(daysLeft)))
    }

    fun expired(context: Context) {
        val ctx = LocalePrefs.wrap(context)
        show(context, ctx.getString(R.string.notif_expired_title), ctx.getString(R.string.notif_expired_body, Fmt.digits(BuildConfig.PROVIDER_PHONE)))
    }

    private fun show(context: Context, title: String, body: String) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(ID_LICENSE, n)
        } catch (_: SecurityException) {
            // Notification permission revoked between the check and the call.
        }
    }
}
