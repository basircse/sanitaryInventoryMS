package com.basir.inventory.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.basir.inventory.AjwaApp
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.license.LicenseState
import com.basir.inventory.shared.BackupMode
import com.basir.inventory.ui.Notifications
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

private val Context.container get() = (applicationContext as AjwaApp).container

/** Creates a backup silently, then queues it for upload. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.container.backupManager.createBackup()
        WorkScheduler.uploadPending(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Sends queued backups once the phone is online; retries with backoff. */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (BackupUploader.uploadAll(applicationContext.container.backupManager.outboxDir)) Result.success() else Result.retry()
}

/** Re-checks the license twice a day and shows expiry reminders. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        when (val s = c.license.refresh()) {
            is LicenseState.Active -> if (s.expiringSoon) Notifications.expiringSoon(applicationContext, s.daysLeft)
            is LicenseState.Expired -> Notifications.expired(applicationContext)
            else -> Unit
        }
        // Safety net in case the periodic backup was delayed by the system for too long.
        WorkScheduler.catchUpIfOverdue(applicationContext)
        return Result.success()
    }
}

object WorkScheduler {
    private const val BACKUP = "auto_backup"
    private const val BACKUP_NOW = "backup_now"
    private const val UPLOAD = "upload_backups"
    private const val REMINDER = "license_reminder"
    private const val PREFS = "work"
    private const val KEY_MODE = "scheduled_mode"
    private val BACKUP_TIME: LocalTime = LocalTime.of(21, 0)

    /** (Re)schedules automatic backups for [mode]; a no-op if already scheduled for that mode. */
    fun scheduleBackups(context: Context, mode: BackupMode) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val same = prefs.getInt(KEY_MODE, -1) == mode.code
        val request = PeriodicWorkRequestBuilder<BackupWorker>(mode.intervalDays.toLong(), TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(BACKUP_TIME).toMinutes(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BACKUP,
            if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
        prefs.edit().putInt(KEY_MODE, mode.code).apply()
    }

    fun scheduleReminders(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REMINDER, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun backupNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            BACKUP_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build(),
        )
    }

    fun uploadPending(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Runs a backup now if the last one is older than the interval plus a day. */
    suspend fun catchUpIfOverdue(context: Context) {
        val c = (context.applicationContext as AjwaApp).container
        val mode = (c.license.state.value as? LicenseState.Active)?.backupMode ?: storedMode(context) ?: return
        val last = c.settings.getLong(SettingsRepository.LAST_BACKUP) ?: 0L
        val overdueAfter = TimeUnit.DAYS.toMillis(mode.intervalDays + 1L)
        if (System.currentTimeMillis() - last > overdueAfter) backupNow(context)
        if (c.backupManager.pendingUploads() > 0) uploadPending(context)
    }

    private fun storedMode(context: Context): BackupMode? =
        BackupMode.fromCode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, -1))

    private fun delayUntilNext(time: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
