package com.basir.inventory.data

import androidx.room.withTransaction
import com.basir.inventory.data.db.AppDatabase
import com.basir.inventory.data.db.CategoryEntity
import com.basir.inventory.data.db.ItemEntity
import com.basir.inventory.data.db.KvEntity
import com.basir.inventory.data.db.MovementEntity
import com.basir.inventory.data.db.MovementType
import com.basir.inventory.data.db.SaleEntity
import com.basir.inventory.data.db.SupplierEntity
import com.basir.inventory.util.lineTotal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Failure the UI can turn into a message. */
class InventoryException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_ENOUGH_STOCK, SKU_TAKEN, NAME_TAKEN, IN_USE, HAS_SALES, NOT_FOUND, BAD_QTY }
}

class InventoryRepository(private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val catalogDao = db.catalog()
    private val itemDao = db.items()
    private val saleDao = db.sales()

    // ------------------------------------------------------------ categories

    val categories: Flow<List<CategoryEntity>> = catalogDao.observeCategories()

    suspend fun addCategory(name: String, parent: CategoryEntity?): Long {
        require(parent == null || parent.level < 3)
        return catalogDao.insertCategory(CategoryEntity(parentId = parent?.id, name = name.trim(), level = (parent?.level ?: 0) + 1))
    }

    suspend fun renameCategory(c: CategoryEntity, name: String) = catalogDao.updateCategory(c.copy(name = name.trim()))

    suspend fun deleteCategory(c: CategoryEntity) = db.withTransaction {
        if (catalogDao.childCount(c.id) > 0 || catalogDao.itemsInCategory(c.id) > 0) throw InventoryException(InventoryException.Reason.IN_USE)
        catalogDao.deleteCategory(c.id)
    }

    // ------------------------------------------------------------ suppliers

    val suppliers: Flow<List<SupplierEntity>> = catalogDao.observeSuppliers()

    suspend fun saveSupplier(s: SupplierEntity): Long = db.withTransaction {
        val clean = s.copy(name = s.name.trim(), phone = s.phone?.trim()?.ifEmpty { null })
        if (catalogDao.supplierNameCount(clean.name, clean.id) > 0) throw InventoryException(InventoryException.Reason.NAME_TAKEN)
        if (clean.id == 0L) catalogDao.insertSupplier(clean) else clean.id.also { catalogDao.updateSupplier(clean) }
    }

    suspend fun deleteSupplier(s: SupplierEntity) = db.withTransaction {
        if (catalogDao.itemsOfSupplier(s.id) > 0) throw InventoryException(InventoryException.Reason.IN_USE)
        catalogDao.deleteSupplier(s.id)
    }

    // ------------------------------------------------------------ items

    val items: Flow<List<ItemEntity>> = itemDao.observeAll()

    fun item(id: Long): Flow<ItemEntity?> = itemDao.observe(id)

    fun movements(itemId: Long): Flow<List<MovementEntity>> = itemDao.observeMovements(itemId)

    /** Inserts or updates an item. On insert, [ItemEntity.qtyMilli] is the opening stock. */
    suspend fun saveItem(item: ItemEntity, supplierName: String?): Long = db.withTransaction {
        val t = now()
        val sku = item.sku.trim().ifEmpty { generateSku(supplierName) }
        if (itemDao.skuCount(sku, item.id) > 0) throw InventoryException(InventoryException.Reason.SKU_TAKEN)
        if (item.id == 0L) {
            val id = itemDao.insert(item.copy(name = item.name.trim(), sku = sku, createdAt = t, updatedAt = t))
            if (item.qtyMilli != 0L) {
                itemDao.insertMovement(MovementEntity(itemId = id, type = MovementType.INITIAL.name, qtyDeltaMilli = item.qtyMilli, note = null, at = t))
            }
            id
        } else {
            val existing = itemDao.get(item.id) ?: throw InventoryException(InventoryException.Reason.NOT_FOUND)
            // Stock only changes through sell/stock-in/adjust so the history stays complete.
            itemDao.update(item.copy(name = item.name.trim(), sku = sku, qtyMilli = existing.qtyMilli, createdAt = existing.createdAt, updatedAt = t))
            item.id
        }
    }

    /** SUPPLIER-0001 style code, e.g. RAK-0007; GEN- when the supplier name has no Latin letters. */
    private suspend fun generateSku(supplierName: String?): String {
        val prefix = supplierName.orEmpty().filter { it in 'A'..'Z' || it in 'a'..'z' }.take(3).uppercase().ifEmpty { "GEN" } + "-"
        var n = itemDao.countWithSkuPrefix(prefix) + 1
        while (itemDao.skuCount(prefix + n.toString().padStart(4, '0'), 0) > 0) n++
        return prefix + n.toString().padStart(4, '0')
    }

    suspend fun deleteItem(id: Long) = db.withTransaction {
        if (saleDao.countForItem(id) > 0) throw InventoryException(InventoryException.Reason.HAS_SALES)
        itemDao.deleteMovements(id)
        itemDao.delete(id)
    }

    /** Records a sale at the item's current selling price and deducts stock atomically. */
    suspend fun sell(itemId: Long, qtyMilli: Long): SaleEntity = db.withTransaction {
        if (qtyMilli <= 0) throw InventoryException(InventoryException.Reason.BAD_QTY)
        val item = itemDao.get(itemId) ?: throw InventoryException(InventoryException.Reason.NOT_FOUND)
        if (qtyMilli > item.qtyMilli) throw InventoryException(InventoryException.Reason.NOT_ENOUGH_STOCK)
        val t = now()
        val sale = SaleEntity(
            itemId = itemId,
            qtyMilli = qtyMilli,
            unitPoisha = item.sellingPoisha,
            totalPoisha = lineTotal(item.sellingPoisha, qtyMilli),
            costPoisha = lineTotal(item.purchasePoisha, qtyMilli),
            soldAt = t,
        )
        val id = saleDao.insert(sale)
        itemDao.addQty(itemId, -qtyMilli, t)
        itemDao.insertMovement(MovementEntity(itemId = itemId, type = MovementType.SALE.name, qtyDeltaMilli = -qtyMilli, note = null, at = t))
        sale.copy(id = id)
    }

    suspend fun stockIn(itemId: Long, qtyMilli: Long, note: String?) = db.withTransaction {
        if (qtyMilli <= 0) throw InventoryException(InventoryException.Reason.BAD_QTY)
        val t = now()
        itemDao.addQty(itemId, qtyMilli, t)
        itemDao.insertMovement(MovementEntity(itemId = itemId, type = MovementType.STOCK_IN.name, qtyDeltaMilli = qtyMilli, note = note?.ifBlank { null }, at = t))
    }

    /** Sets stock to a counted value, recording the difference. */
    suspend fun adjustTo(itemId: Long, newQtyMilli: Long, note: String?) = db.withTransaction {
        if (newQtyMilli < 0) throw InventoryException(InventoryException.Reason.BAD_QTY)
        val item = itemDao.get(itemId) ?: throw InventoryException(InventoryException.Reason.NOT_FOUND)
        val delta = newQtyMilli - item.qtyMilli
        if (delta == 0L) return@withTransaction
        val t = now()
        itemDao.addQty(itemId, delta, t)
        itemDao.insertMovement(MovementEntity(itemId = itemId, type = MovementType.ADJUST.name, qtyDeltaMilli = delta, note = note?.ifBlank { null }, at = t))
    }

    // ------------------------------------------------------------ sales

    fun salesBetween(from: Long, to: Long) = saleDao.observeRange(from, to)
    fun recentSales(limit: Int = 5) = saleDao.observeRecent(limit)
    fun salesTotalSince(from: Long) = saleDao.observeTotalSince(from)
}

/** Small key-value settings stored inside the encrypted database. */
class SettingsRepository(db: AppDatabase) {
    private val dao = db.system()

    val globalThresholdMilli: Flow<Long> = dao.observe(GLOBAL_THRESHOLD).map { it?.toLongOrNull() ?: DEFAULT_THRESHOLD }

    suspend fun setGlobalThreshold(milli: Long) = put(GLOBAL_THRESHOLD, milli.toString())

    suspend fun getLong(key: String): Long? = dao.get(key)?.toLongOrNull()
    fun observeLong(key: String): Flow<Long?> = dao.observe(key).map { it?.toLongOrNull() }
    suspend fun put(key: String, value: String) = dao.put(KvEntity(key, value))
    suspend fun putLong(key: String, value: Long) = put(key, value.toString())

    companion object {
        const val GLOBAL_THRESHOLD = "global_threshold_milli"
        const val LAST_SEEN = "last_seen_ms"
        const val LAST_OPENED = "last_opened_ms"
        const val LAST_BACKUP = "last_backup_ms"
        const val DEFAULT_THRESHOLD = 5_000L

        /** Keys carried inside backups (license/clock state stays with the phone). */
        val PORTABLE_KEYS = setOf(GLOBAL_THRESHOLD)
    }
}
