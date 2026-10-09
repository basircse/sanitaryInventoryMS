package com.basir.inventory.backup

import android.content.Context
import android.os.Build
import com.basir.inventory.AppContainer
import com.basir.inventory.BuildConfig
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.license.LicenseState
import com.basir.inventory.util.ErrorLog
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily usage ping for the provider's monitoring sheet. Carries counts and totals only —
 * no item names, prices or customer data.
 */
object Heartbeat {
    /** Builds and sends one heartbeat; true when the relay accepted it. */
    suspend fun send(context: Context, c: AppContainer): Boolean {
        val error = ErrorLog.last(context)
        val ok = BackupUploader.post(build(c, error))
        if (ok && error != null) ErrorLog.clearIfUnchanged(context, error)
        return ok
    }

    private suspend fun build(c: AppContainer, error: ErrorLog.Entry?): JsonObject {
        val license = c.license.refresh()
        val items = c.db.items().all()
        val threshold = c.settings.globalThresholdMilli.first()
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val current = c.db.system().currentLicense()

        return buildJsonObject {
            put("type", "heartbeat")
            put("token", BuildConfig.BACKUP_TOKEN)
            put("shop", BuildConfig.SHOP_NAME)
            put("heartbeat", buildJsonObject {
                put("deviceCode", c.license.deviceCode)
                put("sentAt", System.currentTimeMillis())
                put("appVersion", BuildConfig.VERSION_NAME)
                put("phone", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                put("lastOpenedAt", c.settings.getLong(SettingsRepository.LAST_OPENED) ?: 0L)
                put("itemCount", items.size)
                put("outOfStockCount", items.count { it.qtyMilli <= 0 })
                put("lowStockCount", items.count { it.qtyMilli > 0 && it.qtyMilli <= (it.thresholdMilli ?: threshold) })
                put("stockValuePoisha", items.sumOf { lineTotal(it.sellingPoisha, it.qtyMilli.coerceAtLeast(0)) })
                put("salesTodayCount", c.db.sales().countSince(startOfToday - 1))
                put("salesTodayPoisha", c.db.sales().totalSince(startOfToday - 1))
                put("licenseState", when (license) {
                    is LicenseState.Active -> "active"
                    is LicenseState.Expired -> "expired"
                    LicenseState.ClockTampered -> "clock_set_back"
                    else -> "not_activated"
                })
                put("licenseSerial", current?.serial ?: 0L)
                put("licenseExpiresAt", current?.expiresAt ?: 0L)
                put("lastBackupAt", c.settings.getLong(SettingsRepository.LAST_BACKUP) ?: 0L)
                put("pendingUploads", c.backupManager.pendingUploads())
                put("lastError", error?.text.orEmpty())
                put("lastErrorAt", error?.at ?: 0L)
            })
        }
    }
}
