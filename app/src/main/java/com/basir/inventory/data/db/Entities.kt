package com.basir.inventory.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/* Money is in poisha (1/100 taka); quantities in thousandths of a unit (12.5 ft = 12500). */

/** Category tree, at most 3 levels; items may sit at any level. */
@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long?,
    val name: String,
    val level: Int,
)

@Entity(tableName = "suppliers", indices = [Index(value = ["name"], unique = true)])
data class SupplierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String? = null,
    val note: String? = null,
)

enum class StockUnit { PCS, BOX, FT, SET }

@Entity(
    tableName = "items",
    indices = [Index(value = ["sku"], unique = true), Index("categoryId"), Index("supplierId"), Index("name")],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sku: String,
    val categoryId: Long?,
    val supplierId: Long?,
    val unit: String,
    val purchasePoisha: Long,
    val sellingPoisha: Long,
    val qtyMilli: Long,
    /** Per-item low-stock limit; null means use the global limit. */
    val thresholdMilli: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One sale line; price and cost are copied at sale time so later price edits don't rewrite history. */
@Entity(tableName = "sales", indices = [Index("itemId"), Index("soldAt")])
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val qtyMilli: Long,
    val unitPoisha: Long,
    val totalPoisha: Long,
    val costPoisha: Long,
    val soldAt: Long,
)

enum class MovementType { INITIAL, SALE, STOCK_IN, ADJUST, RESTORE }

/** Audit trail of every stock change. */
@Entity(tableName = "stock_movements", indices = [Index("itemId"), Index("at")])
data class MovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val type: String,
    val qtyDeltaMilli: Long,
    val note: String?,
    val at: Long,
)

/** Every license key accepted on this phone; the serial blocks re-use of a key. */
@Entity(tableName = "licenses")
data class LicenseEntity(
    @PrimaryKey val serial: Long,
    val keyText: String,
    val issuedDay: Int,
    val validityDays: Int,
    val backupMode: Int,
    val activatedAt: Long,
    val expiresAt: Long,
)

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
)
