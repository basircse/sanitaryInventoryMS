package com.basir.inventory.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT * FROM categories ORDER BY level, name COLLATE NOCASE")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun categories(): List<CategoryEntity>

    @Insert suspend fun insertCategory(c: CategoryEntity): Long
    @Update suspend fun updateCategory(c: CategoryEntity)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: Long)
    @Query("SELECT COUNT(*) FROM categories WHERE parentId = :id") suspend fun childCount(id: Long): Int
    @Query("SELECT COUNT(*) FROM items WHERE categoryId = :id") suspend fun itemsInCategory(id: Long): Int

    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE")
    fun observeSuppliers(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers")
    suspend fun suppliers(): List<SupplierEntity>

    @Insert suspend fun insertSupplier(s: SupplierEntity): Long
    @Update suspend fun updateSupplier(s: SupplierEntity)
    @Query("DELETE FROM suppliers WHERE id = :id") suspend fun deleteSupplier(id: Long)
    @Query("SELECT COUNT(*) FROM items WHERE supplierId = :id") suspend fun itemsOfSupplier(id: Long): Int
    @Query("SELECT COUNT(*) FROM suppliers WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun supplierNameCount(name: String, exceptId: Long): Int
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items")
    suspend fun all(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: Long): Flow<ItemEntity?>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): ItemEntity?

    @Query("SELECT COUNT(*) FROM items WHERE sku = :sku COLLATE NOCASE AND id != :exceptId")
    suspend fun skuCount(sku: String, exceptId: Long): Int

    @Query("SELECT COUNT(*) FROM items WHERE sku LIKE :prefix || '%'")
    suspend fun countWithSkuPrefix(prefix: String): Int

    @Insert suspend fun insert(item: ItemEntity): Long
    @Update suspend fun update(item: ItemEntity)
    @Query("DELETE FROM items WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE items SET qtyMilli = qtyMilli + :delta, updatedAt = :now WHERE id = :id")
    suspend fun addQty(id: Long, delta: Long, now: Long)

    @Insert suspend fun insertMovement(m: MovementEntity): Long

    @Query("SELECT * FROM stock_movements WHERE itemId = :itemId ORDER BY at DESC, id DESC LIMIT 200")
    fun observeMovements(itemId: Long): Flow<List<MovementEntity>>

    @Query("DELETE FROM stock_movements WHERE itemId = :itemId")
    suspend fun deleteMovements(itemId: Long)
}

data class SaleRow(
    val id: Long,
    val itemId: Long,
    val itemName: String?,
    val unit: String?,
    val supplierId: Long?,
    val qtyMilli: Long,
    val unitPoisha: Long,
    val totalPoisha: Long,
    val costPoisha: Long,
    val soldAt: Long,
)

@Dao
interface SaleDao {
    @Insert suspend fun insert(s: SaleEntity): Long

    @Query("SELECT COUNT(*) FROM sales WHERE itemId = :itemId")
    suspend fun countForItem(itemId: Long): Int

    @Query(
        """SELECT s.id, s.itemId, i.name AS itemName, i.unit AS unit, i.supplierId AS supplierId, s.qtyMilli,
           s.unitPoisha, s.totalPoisha, s.costPoisha, s.soldAt
           FROM sales s LEFT JOIN items i ON i.id = s.itemId
           WHERE s.soldAt >= :from AND s.soldAt < :to ORDER BY s.soldAt DESC""",
    )
    fun observeRange(from: Long, to: Long): Flow<List<SaleRow>>

    @Query(
        """SELECT s.id, s.itemId, i.name AS itemName, i.unit AS unit, i.supplierId AS supplierId, s.qtyMilli,
           s.unitPoisha, s.totalPoisha, s.costPoisha, s.soldAt
           FROM sales s LEFT JOIN items i ON i.id = s.itemId ORDER BY s.soldAt DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<SaleRow>>

    @Query("SELECT COALESCE(SUM(totalPoisha), 0) FROM sales WHERE soldAt >= :from")
    fun observeTotalSince(from: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM sales WHERE soldAt > :after")
    suspend fun countSince(after: Long): Int

    @Query("SELECT COALESCE(SUM(totalPoisha), 0) FROM sales WHERE soldAt > :after")
    suspend fun totalSince(after: Long): Long

    @Query("SELECT * FROM sales") suspend fun all(): List<SaleEntity>
}

@Dao
interface SystemDao {
    @Query("SELECT * FROM licenses ORDER BY activatedAt DESC LIMIT 1")
    suspend fun currentLicense(): LicenseEntity?

    @Query("SELECT COUNT(*) FROM licenses WHERE serial = :serial")
    suspend fun licenseUsed(serial: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLicense(l: LicenseEntity)

    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM kv WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Upsert suspend fun put(kv: KvEntity)

    @Query("SELECT * FROM kv") suspend fun allKv(): List<KvEntity>
}

/** Bulk operations used only by restore. */
@Dao
interface RestoreDao {
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM suppliers") suspend fun clearSuppliers()
    @Query("DELETE FROM items") suspend fun clearItems()
    @Query("DELETE FROM sales") suspend fun clearSales()
    @Query("DELETE FROM stock_movements") suspend fun clearMovements()
    @Query("SELECT * FROM stock_movements") suspend fun movements(): List<MovementEntity>

    @Insert suspend fun insertCategories(list: List<CategoryEntity>)
    @Insert suspend fun insertSuppliers(list: List<SupplierEntity>)
    @Insert suspend fun insertItems(list: List<ItemEntity>)
    @Insert suspend fun insertSales(list: List<SaleEntity>)
    @Insert suspend fun insertMovements(list: List<MovementEntity>)
}
