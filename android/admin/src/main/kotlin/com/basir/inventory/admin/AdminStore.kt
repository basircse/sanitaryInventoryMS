package com.basir.inventory.admin

import com.basir.inventory.shared.BackupMode
import com.basir.inventory.shared.ProviderKeys
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Properties

/** Files kept in %APPDATA%\AjwaAdmin: the provider private keys and the log of issued licenses. */
object AdminStore {
    val dir: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AjwaAdmin").apply { mkdirs() }
    val keysFile = File(dir, "provider_keys.properties")
    private val logFile = File(dir, "issued_keys.csv")
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun loadKeys(file: File = keysFile): ProviderKeys? {
        if (!file.exists()) return null
        val p = Properties().apply { file.inputStream().use(::load) }
        val sign = p.getProperty("signPrivate") ?: return null
        val box = p.getProperty("boxPrivate") ?: return null
        return ProviderKeys(unb64.decode(sign), unb64.decode(box))
    }

    fun saveKeys(keys: ProviderKeys, file: File = keysFile) {
        val p = Properties().apply {
            setProperty("signPrivate", b64.encodeToString(keys.signPrivate))
            setProperty("boxPrivate", b64.encodeToString(keys.boxPrivate))
            setProperty("signPublic", b64.encodeToString(keys.signPublic))
            setProperty("boxPublic", b64.encodeToString(keys.boxPublic))
        }
        file.outputStream().use { p.store(it, "Ajwa provider PRIVATE keys - keep secret, keep a backup copy") }
    }

    /** The public halves in the format the Android build reads. */
    fun publicProperties(keys: ProviderKeys): String =
        "signPublic=${b64.encodeToString(keys.signPublic)}\nboxPublic=${b64.encodeToString(keys.boxPublic)}\n"

    data class IssuedKey(
        val issuedAt: String, val serial: Long, val device: String, val customer: String,
        val days: Int, val mode: BackupMode, val key: String,
    )

    fun issuedKeys(): List<IssuedKey> {
        if (!logFile.exists()) return emptyList()
        return logFile.readLines().drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 7) return@mapNotNull null
            IssuedKey(
                c[0], c[1].toLongOrNull() ?: return@mapNotNull null, c[2], c[3].replace(';', ','),
                c[4].toIntOrNull() ?: 30, BackupMode.valueOf(c[5]), c[6],
            )
        }
    }

    fun nextSerial(): Long = (issuedKeys().maxOfOrNull { it.serial } ?: 1000L) + 1

    fun recordIssued(serial: Long, device: String, customer: String, days: Int, mode: BackupMode, key: String) {
        if (!logFile.exists()) logFile.writeText("issued_at,serial,device,customer,days,mode,key\n")
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        logFile.appendText("$now,$serial,$device,${customer.replace(',', ';')},$days,${mode.name},$key\n")
    }
}
