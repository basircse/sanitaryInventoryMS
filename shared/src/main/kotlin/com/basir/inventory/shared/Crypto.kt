package com.basir.inventory.shared

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The provider's two key pairs: Ed25519 to sign license keys / restore files, X25519 to receive
 * encrypted backups. Private halves live only in the Windows admin app.
 */
class ProviderKeys(
    val signPrivate: ByteArray,
    val boxPrivate: ByteArray,
) {
    val signPublic: ByteArray = Ed25519PrivateKeyParameters(signPrivate, 0).generatePublicKey().encoded
    val boxPublic: ByteArray = X25519PrivateKeyParameters(boxPrivate, 0).generatePublicKey().encoded

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): ProviderKeys =
            ProviderKeys(
                Ed25519PrivateKeyParameters(random).encoded,
                X25519PrivateKeyParameters(random).encoded,
            )
    }
}

internal object Aead {
    const val NONCE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, sealed, 0, NONCE),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, NONCE, sealed.size - NONCE)
    }

    fun hkdf(secret: ByteArray, salt: ByteArray, info: String): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(secret, salt, info.toByteArray()))
        return ByteArray(32).also { gen.generateBytes(it, 0, it.size) }
    }
}

internal fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

internal fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

/** Wraps the per-file content key so this phone alone can reopen its own backups (Android Keystore). */
interface LocalKeyWrapper {
    fun wrap(contentKey: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

class BackupFormatException(message: String) : Exception(message)

/**
 * Encrypted backup file. The random content key is wrapped twice: once for the provider's X25519
 * key (so the admin app can read emailed backups) and optionally once for the phone's Keystore.
 *
 * Layout: "AJWB" | ver u8 | ephemeralPub 32B | providerWrap u16+bytes | localWrap u16+bytes | sealed data
 * The data is gzip(JSON) sealed with AES-256-GCM; the header is bound in as associated data.
 */
object BackupEnvelope {
    private val MAGIC = "AJWB".toByteArray()
    private const val VERSION = 1

    fun encrypt(json: String, providerBoxPublic: ByteArray, local: LocalKeyWrapper?): ByteArray {
        val contentKey = Aead.randomBytes(32)
        val eph = X25519PrivateKeyParameters(SecureRandom())
        val ephPub = eph.generatePublicKey().encoded
        val kek = sharedKey(eph, X25519PublicKeyParameters(providerBoxPublic, 0), ephPub, providerBoxPublic)
        val providerWrap = Aead.seal(kek, contentKey, MAGIC)
        val localWrap = local?.wrap(contentKey) ?: ByteArray(0)

        val header = ByteArrayOutputStream().apply {
            write(MAGIC)
            write(VERSION)
            write(ephPub)
            writeBlock(providerWrap)
            writeBlock(localWrap)
        }.toByteArray()
        return header + Aead.seal(contentKey, gzip(json.toByteArray()), header)
    }

    /** Opens a backup using the provider's private key (admin app). */
    fun decryptAsProvider(file: ByteArray, providerBoxPrivate: ByteArray): String {
        val h = parseHeader(file)
        val priv = X25519PrivateKeyParameters(providerBoxPrivate, 0)
        val pub = priv.generatePublicKey().encoded
        val kek = sharedKey(priv, X25519PublicKeyParameters(h.ephPub, 0), h.ephPub, pub)
        val contentKey = runCatching { Aead.open(kek, h.providerWrap, MAGIC) }
            .getOrElse { throw BackupFormatException("This backup was not made for these provider keys") }
        return openBody(file, h, contentKey)
    }

    /** Opens a backup on the phone that made it. */
    fun decryptLocally(file: ByteArray, local: LocalKeyWrapper): String {
        val h = parseHeader(file)
        if (h.localWrap.isEmpty()) throw BackupFormatException("Backup has no key for this phone")
        val contentKey = runCatching { local.unwrap(h.localWrap) }
            .getOrElse { throw BackupFormatException("Backup was made on a different phone") }
        return openBody(file, h, contentKey)
    }

    fun isBackup(file: ByteArray): Boolean = file.size > 5 && file.copyOf(4).contentEquals(MAGIC)

    private class Header(val ephPub: ByteArray, val providerWrap: ByteArray, val localWrap: ByteArray, val length: Int)

    private fun parseHeader(file: ByteArray): Header {
        if (!isBackup(file)) throw BackupFormatException("Not an Ajwa backup file")
        val buf = ByteBuffer.wrap(file)
        buf.position(4)
        if (buf.get().toInt() != VERSION) throw BackupFormatException("Unsupported backup version")
        return try {
            val eph = ByteArray(32).also { buf.get(it) }
            val prov = buf.readBlock()
            val local = buf.readBlock()
            Header(eph, prov, local, buf.position())
        } catch (e: RuntimeException) {
            throw BackupFormatException("Backup file is damaged")
        }
    }

    private fun openBody(file: ByteArray, h: Header, contentKey: ByteArray): String {
        val header = file.copyOfRange(0, h.length)
        val body = file.copyOfRange(h.length, file.size)
        val plain = runCatching { Aead.open(contentKey, body, header) }
            .getOrElse { throw BackupFormatException("Backup file is damaged or was modified") }
        return String(gunzip(plain))
    }

    private fun sharedKey(
        priv: X25519PrivateKeyParameters, pub: X25519PublicKeyParameters, ephPub: ByteArray, recipientPub: ByteArray,
    ): ByteArray {
        val secret = ByteArray(32)
        X25519Agreement().apply { init(priv) }.calculateAgreement(pub, secret, 0)
        return Aead.hkdf(secret, ephPub + recipientPub, "AJWB-v1-wrap")
    }
}

/**
 * A backup re-issued by the provider for one specific phone (used when a shop moves to a new phone).
 * Signed with the provider's Ed25519 key so the app only accepts genuine restore files.
 *
 * Layout: "AJWR" | ver u8 | device 5B | sealed gzip(JSON) u32+bytes | signature 64B
 */
object RestorePackage {
    private val MAGIC = "AJWR".toByteArray()
    private const val VERSION = 1

    fun create(json: String, device: ByteArray, signPrivate: ByteArray): ByteArray {
        val head = MAGIC + byteArrayOf(VERSION.toByte()) + device
        val sealed = Aead.seal(deviceKey(device), gzip(json.toByteArray()), head)
        val body = head + ByteBuffer.allocate(4).putInt(sealed.size).array() + sealed
        val signer = Ed25519Signer().apply {
            init(true, Ed25519PrivateKeyParameters(signPrivate, 0))
            update(body, 0, body.size)
        }
        return body + signer.generateSignature()
    }

    fun isRestorePackage(file: ByteArray): Boolean = file.size > 5 && file.copyOf(4).contentEquals(MAGIC)

    fun open(file: ByteArray, device: ByteArray, signPublic: ByteArray): String {
        if (!isRestorePackage(file) || file.size < 4 + 1 + DeviceCode.SIZE + 4 + 64) {
            throw BackupFormatException("Not an Ajwa restore file")
        }
        val body = file.copyOfRange(0, file.size - 64)
        val sig = file.copyOfRange(file.size - 64, file.size)
        val ok = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(signPublic, 0))
            update(body, 0, body.size)
        }.verifySignature(sig)
        if (!ok) throw BackupFormatException("Restore file is not genuine or was modified")
        val fileDevice = body.copyOfRange(5, 5 + DeviceCode.SIZE)
        if (!fileDevice.contentEquals(device)) throw BackupFormatException("Restore file was made for a different phone")
        val head = body.copyOfRange(0, 5 + DeviceCode.SIZE)
        val len = ByteBuffer.wrap(body, head.size, 4).int
        val sealed = body.copyOfRange(head.size + 4, head.size + 4 + len)
        return String(gunzip(Aead.open(deviceKey(device), sealed, head)))
    }

    private fun deviceKey(device: ByteArray) = Aead.hkdf(device, MAGIC, "AJWR-v1-device")
}

private fun ByteArrayOutputStream.writeBlock(bytes: ByteArray) {
    write(bytes.size shr 8 and 0xFF)
    write(bytes.size and 0xFF)
    write(bytes)
}

private fun ByteBuffer.readBlock(): ByteArray {
    val len = short.toInt() and 0xFFFF
    return ByteArray(len).also { get(it) }
}
