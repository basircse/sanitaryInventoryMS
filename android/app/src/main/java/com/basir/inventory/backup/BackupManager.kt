package com.basir.inventory.backup

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import com.basir.inventory.BuildConfig
import com.basir.inventory.data.SettingsRepository
import com.basir.inventory.data.db.AppDatabase
import com.basir.inventory.data.db.CategoryEntity
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.KvEntity
import com.basir.inventory.data.db.MovementEntity
import com.basir.inventory.data.db.SaleEntity
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.license.LicenseManager
import com.basir.inventory.security.KeystoreSecrets
import com.basir.inventory.shared.BackupData
import com.basir.inventory.shared.BackupEnvelope
import com.basir.inventory.shared.BackupFormatException
import com.basir.inventory.shared.CategoryDto
import com.basir.inventory.shared.ItemDto
import com.basir.inventory.shared.MovementDto
import com.basir.inventory.shared.RestorePackage
import com.basir.inventory.shared.SaleDto
import com.basir.inventory.shared.SupplierDto
import com.basir.inventory.shared.UsageSummary
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Creates encrypted backups (readable by this phone and by the provider), keeps the newest
 * [KEEP_LOCAL] in Android/data/com.basir.inventory/files/Backups, and queues a copy for e-mailing.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val license: LicenseManager,
    private val secrets: KeystoreSecrets,
) {
    private val boxPublic = Base64.decode(BuildConfig.BOX_PUBLIC, Base64.DEFAULT)
    private val mutex = Mutex()

    val backupDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "Backups").apply { mkdirs() }
    val outboxDir: File get() = File(context.filesDir, "outbox").apply { mkdirs() }

    fun pendingUploads(): Int = outboxDir.listFiles { f -> f.extension == EXT }?.size ?: 0

    suspend fun createBackup(): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            val data = snapshot()
            val bytes = BackupEnvelope.encrypt(data.encode(), boxPublic, secrets.backupWrapper)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(data.summary.createdAt))
            val name = "Ajwa_Backup_$stamp.$EXT"

            val file = File(backupDir, name)
            writeAtomically(file, bytes)
            writeAtomically(File(outboxDir, name), bytes)
            File(outboxDir, "$name.json").writeText(BackupData.json.encodeToString(UsageSummary.serializer(), data.summary))

            prune(backupDir, KEEP_LOCAL)
            prune(outboxDir, KEEP_OUTBOX)
            settings.putLong(SettingsRepository.LAST_BACKUP, data.summary.createdAt)
            file
        }
    }

    /** Consistent copy of every table plus a usage summary. */
    suspend fun snapshot(): BackupData = db.withTransaction {
        val now = System.currentTimeMillis()
        val categories = db.catalog().categories()
        val suppliers = db.catalog().suppliers()
        val items = db.items().all()
        val sales = db.sales().all()
        val movements = db.restore().movements()
        val kv = db.system().allKv().associate { it.key to it.value }
        val threshold = settings.globalThresholdMilli.first()
        val lastBackup = settings.getLong(SettingsRepository.LAST_BACKUP)
        val current = db.system().currentLicense()

        val summary = UsageSummary(
            shopName = BuildConfig.SHOP_NAME,
            deviceCode = license.deviceCode,
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = now,
            licenseSerial = current?.serial,
            licenseExpiresAt = current?.expiresAt,
            lastOpenedAt = settings.getLong(SettingsRepository.LAST_OPENED) ?: now,
            itemCount = items.size,
            outOfStockCount = items.count { it.qtyMilli <= 0 },
            lowStockCount = items.count { it.qtyMilli > 0 && it.qtyMilli <= (it.thresholdMilli ?: threshold) },
            stockValuePoisha = items.sumOf { lineTotal(it.sellingPoisha, it.qtyMilli.coerceAtLeast(0)) },
            salesCountSinceLastBackup = db.sales().countSince(lastBackup ?: 0),
            salesPoishaSinceLastBackup = db.sales().totalSince(lastBackup ?: 0),
            salesCountTotal = sales.size,
            lastBackupAt = lastBackup,
        )
        BackupData(
            summary = summary,
            categories = categories.map { CategoryDto(it.id, it.parentId, it.name, it.level) },
            suppliers = suppliers.map { SupplierDto(it.id, it.name, it.phone, it.note) },
            items = items.map {
                ItemDto(it.id, it.name, it.sku, it.categoryId, it.supplierId, it.unit, it.purchasePoisha, it.sellingPoisha,
                    it.qtyMilli, it.thresholdMilli, it.createdAt, it.updatedAt)
            },
            sales = sales.map { SaleDto(it.id, it.itemId, it.qtyMilli, it.unitPoisha, it.totalPoisha, it.costPoisha, it.soldAt) },
            movements = movements.map { MovementDto(it.id, it.itemId, it.type, it.qtyDeltaMilli, it.note, it.at) },
            settings = kv.filterKeys { it in SettingsRepository.PORTABLE_KEYS },
        )
    }

    /**
     * Reads a file chosen by the user: either this phone's own backup (.ajwb) or a restore file the
     * provider made for this phone (.ajwr). Throws [BackupFormatException] with a readable reason.
     */
    suspend fun readFile(bytes: ByteArray): BackupData = withContext(Dispatchers.Default) {
        val json = when {
            BackupEnvelope.isBackup(bytes) -> BackupEnvelope.decryptLocally(bytes, secrets.backupWrapper)
            RestorePackage.isRestorePackage(bytes) ->
                RestorePackage.open(bytes, license.deviceId, Base64.decode(BuildConfig.SIGN_PUBLIC, Base64.DEFAULT))
            else -> throw BackupFormatException("Not an Ajwa backup file")
        }
        val data = runCatching { BackupData.decode(json) }.getOrElse { throw BackupFormatException("Backup content is unreadable") }
        if (data.schemaVersion > BackupData.SCHEMA_VERSION) throw BackupFormatException("Backup is from a newer app version")
        data
    }

    /** Replaces all shop data with [data], after first backing up the current data. */
    suspend fun restore(data: BackupData) {
        createBackup()
        db.withTransaction {
            val r = db.restore()
            r.clearMovements(); r.clearSales(); r.clearItems(); r.clearCategories(); r.clearSuppliers()
            r.insertCategories(data.categories.map { CategoryEntity(it.id, it.parentId, it.name, it.level) })
            r.insertSuppliers(data.suppliers.map { SupplierEntity(it.id, it.name, it.phone, it.note) })
            r.insertItems(data.items.map {
                ItemEntity(it.id, it.name, it.sku, it.categoryId, it.supplierId, it.unit, it.purchasePoisha, it.sellingPoisha,
                    it.qtyMilli, it.thresholdMilli, it.createdAt, it.updatedAt)
            })
            r.insertSales(data.sales.map { SaleEntity(it.id, it.itemId, it.qtyMilli, it.unitPoisha, it.totalPoisha, it.costPoisha, it.soldAt) })
            r.insertMovements(data.movements.map { MovementEntity(it.id, it.itemId, it.type, it.qtyDeltaMilli, it.note, it.at) })
            data.settings.filterKeys { it in SettingsRepository.PORTABLE_KEYS }
                .forEach { (k, v) -> db.system().put(KvEntity(k, v)) }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun prune(dir: File, keep: Int) {
        val files = dir.listFiles { f -> f.extension == EXT }?.sortedByDescending { it.name } ?: return
        files.drop(keep).forEach {
            it.delete()
            File(dir, it.name + ".json").delete()
        }
    }

    companion object {
        const val EXT = "ajwb"
        const val KEEP_LOCAL = 3
        /** Unsent backups kept while offline; older ones are dropped. */
        const val KEEP_OUTBOX = 30
    }
}
