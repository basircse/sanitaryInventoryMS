package com.basir.inventory.shared

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate

/** How often the app backs itself up; decided by the provider per license key. */
enum class BackupMode(val code: Int, val intervalDays: Int) {
    DAILY(1, 1),
    WEEKLY(2, 7),
    MONTHLY(3, 30);

    companion object {
        fun fromCode(code: Int): BackupMode? = entries.firstOrNull { it.code == code }
    }
}

/** Short identifier for one phone, shown on the activation screen and sent to the provider. */
object DeviceCode {
    const val SIZE = 5

    fun fromAndroidId(androidId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("AJWA-DEVICE|$androidId".toByteArray())
            .copyOf(SIZE)

    /** 5 bytes -> 8 Base32 chars, shown as XXXX-XXXX. */
    fun display(code: ByteArray): String = Base32.group(Base32.encode(code), 4)

    fun parse(text: String): ByteArray? =
        Base32.decode(text)?.takeIf { it.size == SIZE && text.filter(Char::isLetterOrDigit).length == 8 }
}

/**
 * Data carried inside a license key. Days are counted from [EPOCH] so they fit in 16 bits.
 *
 * Wire format (14 bytes): version u8 | device 5B | issuedDay u16 | validityDays u8 | backupMode u8 | serial u32
 */
data class LicensePayload(
    val device: ByteArray,
    val issuedDay: Int,
    val validityDays: Int,
    val backupMode: BackupMode,
    val serial: Long,
    val version: Int = CURRENT_VERSION,
) {
    val issuedDate: LocalDate get() = EPOCH.plusDays(issuedDay.toLong())

    fun toBytes(): ByteArray = ByteBuffer.allocate(SIZE).apply {
        put(version.toByte())
        put(device)
        putShort(issuedDay.toShort())
        put(validityDays.toByte())
        put(backupMode.code.toByte())
        putInt(serial.toInt())
    }.array()

    override fun equals(other: Any?): Boolean =
        other is LicensePayload && device.contentEquals(other.device) && issuedDay == other.issuedDay &&
            validityDays == other.validityDays && backupMode == other.backupMode &&
            serial == other.serial && version == other.version

    override fun hashCode(): Int = arrayOf<Any>(device.contentHashCode(), issuedDay, serial).contentHashCode()

    companion object {
        const val CURRENT_VERSION = 1
        const val SIZE = 14
        val EPOCH: LocalDate = LocalDate.of(2024, 1, 1)

        fun dayOf(date: LocalDate): Int = (date.toEpochDay() - EPOCH.toEpochDay()).toInt()

        fun fromBytes(bytes: ByteArray): LicensePayload? {
            if (bytes.size != SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            val version = buf.get().toInt() and 0xFF
            if (version != CURRENT_VERSION) return null
            val device = ByteArray(DeviceCode.SIZE).also { buf.get(it) }
            val issuedDay = buf.short.toInt() and 0xFFFF
            val validity = buf.get().toInt() and 0xFF
            val mode = BackupMode.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val serial = buf.int.toLong() and 0xFFFFFFFFL
            if (validity == 0) return null
            return LicensePayload(device, issuedDay, validity, mode, serial, version)
        }
    }
}

sealed interface LicenseParseResult {
    data class Valid(val payload: LicensePayload) : LicenseParseResult
    data object Malformed : LicenseParseResult
    data object BadSignature : LicenseParseResult
}

/** Encodes payload + Ed25519 signature as a grouped Base32 string, and verifies it. */
object LicenseCodec {
    private const val SIG_SIZE = 64

    fun sign(payload: LicensePayload, signingPrivateKey: ByteArray): String {
        val body = payload.toBytes()
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signingPrivateKey, 0))
            update(body, 0, body.size)
        }
        return Base32.group(Base32.encode(body + signer.generateSignature()))
    }

    fun parse(text: String, signingPublicKey: ByteArray): LicenseParseResult {
        val raw = Base32.decode(text) ?: return LicenseParseResult.Malformed
        if (raw.size != LicensePayload.SIZE + SIG_SIZE) return LicenseParseResult.Malformed
        val body = raw.copyOfRange(0, LicensePayload.SIZE)
        val sig = raw.copyOfRange(LicensePayload.SIZE, raw.size)
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(signingPublicKey, 0))
            update(body, 0, body.size)
        }
        if (!verifier.verifySignature(sig)) return LicenseParseResult.BadSignature
        val payload = LicensePayload.fromBytes(body) ?: return LicenseParseResult.Malformed
        return LicenseParseResult.Valid(payload)
    }
}
