package com.basir.inventory.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.basir.inventory.shared.LocalKeyWrapper
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets bound to this phone through Android Keystore (the raw keys never leave secure hardware):
 *  - the random SQLCipher passphrase, stored encrypted in private prefs;
 *  - the key that lets this phone reopen its own backup files.
 */
class KeystoreSecrets(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun databasePassphrase(): ByteArray {
        val stored = prefs.getString(PREF_DB, null)
        if (stored != null) return decrypt(DB_ALIAS, Base64.decode(stored, Base64.NO_WRAP))
        val pass = ByteArray(32).also(SecureRandom()::nextBytes)
        prefs.edit().putString(PREF_DB, Base64.encodeToString(encrypt(DB_ALIAS, pass), Base64.NO_WRAP)).commit()
        return pass
    }

    val backupWrapper: LocalKeyWrapper = object : LocalKeyWrapper {
        override fun wrap(contentKey: ByteArray) = encrypt(BACKUP_ALIAS, contentKey)
        override fun unwrap(wrapped: ByteArray) = decrypt(BACKUP_ALIAS, wrapped)
    }

    /** Returns iv(12) || ciphertext. */
    private fun encrypt(alias: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(alias: String, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, data, 0, 12))
        return cipher.doFinal(data, 12, data.size - 12)
    }

    @Synchronized
    private fun key(alias: String): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val DB_ALIAS = "ajwa_db"
        const val BACKUP_ALIAS = "ajwa_backup"
        const val PREF_DB = "db_pass"
    }
}
