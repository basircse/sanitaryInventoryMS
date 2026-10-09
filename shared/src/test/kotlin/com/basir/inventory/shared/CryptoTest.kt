package com.basir.inventory.shared

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    private val keys = ProviderKeys.generate()
    private val device = DeviceCode.fromAndroidId("abc123")

    private fun payload(serial: Long = 42) = LicensePayload(
        device = device,
        issuedDay = LicensePayload.dayOf(LocalDate.of(2026, 10, 9)),
        validityDays = 30,
        backupMode = BackupMode.WEEKLY,
        serial = serial,
    )

    @Test
    fun base32RoundTrip() {
        val data = ByteArray(78) { (it * 7).toByte() }
        assertTrue(data.contentEquals(Base32.decode(Base32.encode(data))))
        assertNull(Base32.decode("ABC0"))
    }

    @Test
    fun licenseRoundTripIgnoresCaseAndSpacing() {
        val key = LicenseCodec.sign(payload(), keys.signPrivate)
        val messy = "  " + key.lowercase().replace("-", " - ") + "\n"
        val result = LicenseCodec.parse(messy, keys.signPublic)
        assertIs<LicenseParseResult.Valid>(result)
        assertEquals(payload(), result.payload)
        assertEquals(LocalDate.of(2026, 10, 9), result.payload.issuedDate)
    }

    @Test
    fun tamperedOrForeignKeyIsRejected() {
        val key = LicenseCodec.sign(payload(), keys.signPrivate)
        val flipped = key.replaceRange(10, 11, if (key[10] == 'A') "B" else "A")
        assertIs<LicenseParseResult.BadSignature>(LicenseCodec.parse(flipped, keys.signPublic))
        assertIs<LicenseParseResult.BadSignature>(LicenseCodec.parse(key, ProviderKeys.generate().signPublic))
        assertIs<LicenseParseResult.Malformed>(LicenseCodec.parse("HELLO-WORLD", keys.signPublic))
    }

    @Test
    fun deviceCodeDisplayParses() {
        val text = DeviceCode.display(device)
        assertEquals(9, text.length)
        assertTrue(device.contentEquals(DeviceCode.parse(text.lowercase())))
    }

    private val localKey = object : LocalKeyWrapper {
        val k = ByteArray(32) { 9 }
        override fun wrap(contentKey: ByteArray) = Aead.seal(k, contentKey, ByteArray(0))
        override fun unwrap(wrapped: ByteArray) = Aead.open(k, wrapped, ByteArray(0))
    }

    @Test
    fun backupOpensForProviderAndPhone() {
        val json = """{"hello":"বাংলা"}"""
        val file = BackupEnvelope.encrypt(json, keys.boxPublic, localKey)
        assertEquals(json, BackupEnvelope.decryptAsProvider(file, keys.boxPrivate))
        assertEquals(json, BackupEnvelope.decryptLocally(file, localKey))
        assertFailsWith<BackupFormatException> {
            BackupEnvelope.decryptAsProvider(file, ProviderKeys.generate().boxPrivate)
        }
        file[file.size - 3] = (file[file.size - 3] + 1).toByte()
        assertFailsWith<BackupFormatException> { BackupEnvelope.decryptAsProvider(file, keys.boxPrivate) }
    }

    @Test
    fun restorePackageIsBoundToDevice() {
        val pkg = RestorePackage.create("data", device, keys.signPrivate)
        assertEquals("data", RestorePackage.open(pkg, device, keys.signPublic))
        assertFailsWith<BackupFormatException> {
            RestorePackage.open(pkg, DeviceCode.fromAndroidId("other"), keys.signPublic)
        }
        assertFailsWith<BackupFormatException> {
            RestorePackage.open(pkg, device, ProviderKeys.generate().signPublic)
        }
    }
}
