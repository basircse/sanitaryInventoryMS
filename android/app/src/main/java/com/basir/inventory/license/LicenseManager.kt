package com.basir.inventory.license

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.basir.inventory.BuildConfig
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.data.db.AppDatabase
import com.basir.inventory.data.db.LicenseEntity
import com.basir.inventory.shared.BackupMode
import com.basir.inventory.shared.DeviceCode
import com.basir.inventory.shared.LicenseCodec
import com.basir.inventory.shared.LicenseParseResult
import com.basir.inventory.shared.LicensePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

sealed interface LicenseState {
    data object Loading : LicenseState
    data object NotActivated : LicenseState
    data class Active(val license: LicenseEntity, val daysLeft: Int) : LicenseState {
        val backupMode: BackupMode get() = BackupMode.fromCode(license.backupMode) ?: BackupMode.DAILY
        val expiringSoon: Boolean get() = daysLeft <= REMIND_DAYS
    }
    data class Expired(val license: LicenseEntity) : LicenseState
    /** The phone clock is behind the latest time the app has seen. */
    data object ClockTampered : LicenseState

    companion object {
        const val REMIND_DAYS = 3
    }
}

enum class ActivationError { MALFORMED, BAD_SIGNATURE, WRONG_DEVICE, ALREADY_USED, TOO_OLD, FROM_FUTURE, CLOCK }

/**
 * Offline license checks. A key is an Ed25519-signed payload (see shared/License.kt) and is valid
 * for [LicensePayload.validityDays] days from the moment it is entered on this phone.
 */
class LicenseManager(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val signPublic = Base64.decode(BuildConfig.SIGN_PUBLIC, Base64.DEFAULT)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<LicenseState>(LicenseState.Loading)
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    @SuppressLint("HardwareIds")
    val deviceId: ByteArray = DeviceCode.fromAndroidId(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown",
    )
    val deviceCode: String = DeviceCode.display(deviceId)

    /** Re-evaluates the license; call on start, on resume and periodically. */
    suspend fun refresh(): LicenseState = mutex.withLock {
        val t = now()
        val lastSeen = settings.getLong(SettingsRepository.LAST_SEEN) ?: 0L
        val result = when {
            t < lastSeen - CLOCK_TOLERANCE_MS -> LicenseState.ClockTampered
            else -> {
                if (t > lastSeen) settings.putLong(SettingsRepository.LAST_SEEN, t)
                evaluate(db.system().currentLicense(), t)
            }
        }
        _state.value = result
        result
    }

    private fun evaluate(license: LicenseEntity?, t: Long): LicenseState = when {
        license == null -> LicenseState.NotActivated
        t >= license.expiresAt -> LicenseState.Expired(license)
        else -> LicenseState.Active(license, daysLeft(license.expiresAt, t))
    }

    /** Whole calendar days until expiry (0 = expires today). */
    private fun daysLeft(expiresAt: Long, t: Long): Int {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
        val end = Instant.ofEpochMilli(expiresAt).atZone(zone).toLocalDate()
        return (end.toEpochDay() - today.toEpochDay()).toInt().coerceAtLeast(0)
    }

    suspend fun activate(keyText: String): Result<LicenseEntity> = mutex.withLock {
        val payload = when (val r = LicenseCodec.parse(keyText, signPublic)) {
            LicenseParseResult.Malformed -> return fail(ActivationError.MALFORMED)
            LicenseParseResult.BadSignature -> return fail(ActivationError.BAD_SIGNATURE)
            is LicenseParseResult.Valid -> r.payload
        }
        val t = now()
        if (t < (settings.getLong(SettingsRepository.LAST_SEEN) ?: 0L) - CLOCK_TOLERANCE_MS) return fail(ActivationError.CLOCK)
        if (!payload.device.contentEquals(deviceId)) return fail(ActivationError.WRONG_DEVICE)
        if (db.system().licenseUsed(payload.serial) > 0) return fail(ActivationError.ALREADY_USED)

        val today = LicensePayload.dayOf(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate())
        if (payload.issuedDay > today + 1) return fail(ActivationError.FROM_FUTURE)
        if (today - payload.issuedDay > MAX_KEY_AGE_DAYS) return fail(ActivationError.TOO_OLD)

        val license = LicenseEntity(
            serial = payload.serial,
            keyText = keyText.trim(),
            issuedDay = payload.issuedDay,
            validityDays = payload.validityDays,
            backupMode = payload.backupMode.code,
            activatedAt = t,
            expiresAt = t + TimeUnit.DAYS.toMillis(payload.validityDays.toLong()),
        )
        db.system().insertLicense(license)
        settings.putLong(SettingsRepository.LAST_SEEN, maxOf(t, settings.getLong(SettingsRepository.LAST_SEEN) ?: 0L))
        _state.value = evaluate(license, t)
        Result.success(license)
    }

    private fun fail(e: ActivationError) = Result.failure<LicenseEntity>(ActivationException(e))

    class ActivationException(val error: ActivationError) : Exception(error.name)

    companion object {
        /** A key must be entered within this many days of being issued. */
        const val MAX_KEY_AGE_DAYS = 7
        /** How far the clock may go backwards (timezone/NTP corrections) before we call it tampering. */
        val CLOCK_TOLERANCE_MS: Long = TimeUnit.HOURS.toMillis(24)

        fun expiryDate(license: LicenseEntity): LocalDate =
            Instant.ofEpochMilli(license.expiresAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
