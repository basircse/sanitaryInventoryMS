package com.basir.inventory.web.model;

import java.util.List;
import java.util.Map;

/**
 * API and backup records. Money is in poisha (1/100 taka) and quantities in thousandths of a unit,
 * like the Android app; field names match shared/BackupData.kt so backups stay interchangeable.
 */
public final class Models {
    private Models() {}

    public record Category(long id, Long parentId, String name, int level) {}

    public record Supplier(long id, String name, String phone, String note) {}

    public record Item(
            long id, String name, String sku, Long categoryId, Long supplierId, String unit,
            long purchasePoisha, long sellingPoisha, long qtyMilli, Long thresholdMilli,
            long createdAt, long updatedAt) {}

    public record Sale(long id, long itemId, long qtyMilli, long unitPoisha, long totalPoisha, long costPoisha, long soldAt) {}

    public record Movement(long id, long itemId, String type, long qtyDeltaMilli, String note, long at) {}

    /** A sale joined with its item, for lists and reports. */
    public record SaleRow(
            long id, long itemId, String itemName, String unit, Long supplierId,
            long qtyMilli, long unitPoisha, long totalPoisha, long costPoisha, long soldAt) {}

    public enum MovementType { INITIAL, SALE, STOCK_IN, ADJUST, RESTORE }

    public enum StockUnit { PCS, BOX, FT, SET }

    /** Usage snapshot; also the readable body of the backup e-mail. */
    public record UsageSummary(
            String shopName, String deviceCode, String appVersion, long createdAt,
            Long licenseSerial, Long licenseExpiresAt, long lastOpenedAt,
            int itemCount, int outOfStockCount, int lowStockCount, long stockValuePoisha,
            int salesCountSinceLastBackup, long salesPoishaSinceLastBackup, int salesCountTotal,
            Long lastBackupAt) {}

    public record BackupData(
            int schemaVersion,
            UsageSummary summary,
            List<Category> categories,
            List<Supplier> suppliers,
            List<Item> items,
            List<Sale> sales,
            List<Movement> movements,
            Map<String, String> settings) {
        public static final int SCHEMA_VERSION = 1;
    }
}
