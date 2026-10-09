package com.basir.inventory.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Plain data written inside an encrypted backup. Shared by the phone (writes/restores) and the
 * admin app (reads). Money is stored in poisha (1/100 taka); quantities in thousandths of a unit,
 * so 12.5 ft is 12500.
 */

@Serializable
data class BackupData(
    val schemaVersion: Int = SCHEMA_VERSION,
    val summary: UsageSummary,
    val categories: List<CategoryDto>,
    val suppliers: List<SupplierDto>,
    val items: List<ItemDto>,
    val sales: List<SaleDto>,
    val movements: List<MovementDto>,
    val settings: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): BackupData = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

/** Usage snapshot; also sent as the readable body of the backup e-mail. */
@Serializable
data class UsageSummary(
    val shopName: String,
    val deviceCode: String,
    val appVersion: String,
    val createdAt: Long,
    val licenseSerial: Long?,
    val licenseExpiresAt: Long?,
    val lastOpenedAt: Long,
    val itemCount: Int,
    val outOfStockCount: Int,
    val lowStockCount: Int,
    val stockValuePoisha: Long,
    val salesCountSinceLastBackup: Int,
    val salesPoishaSinceLastBackup: Long,
    val salesCountTotal: Int,
    val lastBackupAt: Long?,
)

@Serializable
data class CategoryDto(val id: Long, val parentId: Long?, val name: String, val level: Int)

@Serializable
data class SupplierDto(val id: Long, val name: String, val phone: String?, val note: String?)

@Serializable
data class ItemDto(
    val id: Long,
    val name: String,
    val sku: String,
    val categoryId: Long?,
    val supplierId: Long?,
    val unit: String,
    val purchasePoisha: Long,
    val sellingPoisha: Long,
    val qtyMilli: Long,
    val thresholdMilli: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class SaleDto(
    val id: Long,
    val itemId: Long,
    val qtyMilli: Long,
    val unitPoisha: Long,
    val totalPoisha: Long,
    val costPoisha: Long,
    val soldAt: Long,
)

@Serializable
data class MovementDto(
    val id: Long,
    val itemId: Long,
    val type: String,
    val qtyDeltaMilli: Long,
    val note: String?,
    val at: Long,
)
