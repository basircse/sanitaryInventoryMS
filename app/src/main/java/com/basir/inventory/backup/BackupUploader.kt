package com.basir.inventory.backup

import android.util.Base64
import com.basir.inventory.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends queued backups to the provider's Google Apps Script web app, which e-mails them and files
 * them in Google Drive. No mail credentials live in the app; see backup-relay/Code.gs.
 */
object BackupUploader {
    val isConfigured: Boolean get() = BuildConfig.BACKUP_ENDPOINT.startsWith("https://")

    /** Uploads every queued file; returns false if any upload failed and should be retried. */
    fun uploadAll(outbox: File): Boolean {
        if (!isConfigured) return true
        val files = outbox.listFiles { f -> f.extension == BackupManager.EXT }?.sortedBy { it.name } ?: return true
        for (file in files) {
            val summaryFile = File(outbox, file.name + ".json")
            val summary = summaryFile.takeIf { it.exists() }?.readText()
            if (!upload(file, summary)) return false
            file.delete()
            summaryFile.delete()
        }
        return true
    }

    private fun upload(file: File, summaryJson: String?): Boolean {
        val body = buildJsonObject {
            put("token", BuildConfig.BACKUP_TOKEN)
            put("shop", BuildConfig.SHOP_NAME)
            put("fileName", file.name)
            put("file", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            if (summaryJson != null) put("summary", Json.parseToJsonElement(summaryJson) as JsonElement)
        }
        return post(body)
    }

    /** POSTs a JSON object to the relay; true when it answers {"ok":true}. */
    fun post(json: JsonObject): Boolean {
        if (!isConfigured) return false
        val body = json.toString().toByteArray()
        return runCatching {
            val conn = (URL(BuildConfig.BACKUP_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 60_000
                // Apps Script answers a POST with a redirect to the result; follow it manually as GET.
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            var code = conn.responseCode
            var text: String
            if (code in 301..303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                val follow = (URL(location).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                code = follow.responseCode
                text = follow.inputStream.bufferedReader().use { it.readText() }
                follow.disconnect()
            } else {
                text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
            }
            code in 200..299 && text.contains("\"ok\":true")
        }.getOrDefault(false)
    }
}
