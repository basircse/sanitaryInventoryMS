package com.basir.inventory.admin

import com.basir.inventory.shared.BackupData
import com.basir.inventory.shared.BackupEnvelope
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a decrypted backup into a MySQL script (tables + data). Import it with
 * `mysql -u root -p < file.sql`, phpMyAdmin (Import) or MySQL Workbench (Run SQL Script).
 * Money is written in taka (DECIMAL 2 places), quantities in units (DECIMAL 3 places) and times as
 * Bangladesh local time.
 */
object MysqlExport {
    private val zone = ZoneId.of("Asia/Dhaka")
    private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun toSql(d: BackupData, database: String): String = buildString {
        val db = database.replace(Regex("[^A-Za-z0-9_]"), "_")
        val s = d.summary
        appendLine("-- Ajwa Inventory backup: ${s.shopName}, device ${s.deviceCode}, made ${time(s.createdAt).trim('\'')} (Asia/Dhaka)")
        appendLine("SET NAMES utf8mb4;")
        appendLine("CREATE DATABASE IF NOT EXISTS `$db` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
        appendLine("USE `$db`;")
        appendLine("SET FOREIGN_KEY_CHECKS = 0;")
        appendLine("DROP TABLE IF EXISTS stock_movements, sales, items, categories, suppliers, settings, backup_info;")
        appendLine("SET FOREIGN_KEY_CHECKS = 1;")
        appendLine()
        appendLine(
            """
            CREATE TABLE backup_info (
              shop_name VARCHAR(200), device_code VARCHAR(20), app_version VARCHAR(20),
              backup_at DATETIME, license_serial BIGINT, license_expires_at DATETIME,
              last_opened_at DATETIME, previous_backup_at DATETIME
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE categories (
              id BIGINT PRIMARY KEY, parent_id BIGINT NULL, name VARCHAR(200) NOT NULL, level INT NOT NULL,
              FOREIGN KEY (parent_id) REFERENCES categories(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE suppliers (
              id BIGINT PRIMARY KEY, name VARCHAR(200) NOT NULL, phone VARCHAR(50), note TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE items (
              id BIGINT PRIMARY KEY, name VARCHAR(300) NOT NULL, sku VARCHAR(100) NOT NULL UNIQUE,
              category_id BIGINT NULL, supplier_id BIGINT NULL, unit VARCHAR(10) NOT NULL,
              purchase_price DECIMAL(14,2) NOT NULL, selling_price DECIMAL(14,2) NOT NULL,
              quantity DECIMAL(16,3) NOT NULL, low_stock_limit DECIMAL(16,3) NULL,
              created_at DATETIME, updated_at DATETIME,
              FOREIGN KEY (category_id) REFERENCES categories(id),
              FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE sales (
              id BIGINT PRIMARY KEY, item_id BIGINT NOT NULL, quantity DECIMAL(16,3) NOT NULL,
              unit_price DECIMAL(14,2) NOT NULL, total DECIMAL(14,2) NOT NULL, total_cost DECIMAL(14,2) NOT NULL,
              sold_at DATETIME NOT NULL, INDEX (sold_at),
              FOREIGN KEY (item_id) REFERENCES items(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE stock_movements (
              id BIGINT PRIMARY KEY, item_id BIGINT NOT NULL, type VARCHAR(20) NOT NULL,
              quantity_change DECIMAL(16,3) NOT NULL, note TEXT, at DATETIME NOT NULL, INDEX (at),
              FOREIGN KEY (item_id) REFERENCES items(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE settings (
              name VARCHAR(100) PRIMARY KEY, value TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """.trimIndent()
        )
        appendLine()

        insert("backup_info", listOf(listOf(
            str(s.shopName), str(s.deviceCode), str(s.appVersion), time(s.createdAt), num(s.licenseSerial),
            time(s.licenseExpiresAt), time(s.lastOpenedAt), time(s.lastBackupAt),
        )))
        // Parents before children so the self-reference is satisfied.
        insert("categories", d.categories.sortedWith(compareBy({ it.level }, { it.id })).map {
            listOf(num(it.id), num(it.parentId), str(it.name), num(it.level.toLong()))
        })
        insert("suppliers", d.suppliers.map { listOf(num(it.id), str(it.name), str(it.phone), str(it.note)) })
        insert("items", d.items.map {
            listOf(
                num(it.id), str(it.name), str(it.sku), num(it.categoryId), num(it.supplierId), str(it.unit),
                taka(it.purchasePoisha), taka(it.sellingPoisha), qty(it.qtyMilli), qty(it.thresholdMilli),
                time(it.createdAt), time(it.updatedAt),
            )
        })
        insert("sales", d.sales.map {
            listOf(num(it.id), num(it.itemId), qty(it.qtyMilli), taka(it.unitPoisha), taka(it.totalPoisha), taka(it.costPoisha), time(it.soldAt))
        })
        insert("stock_movements", d.movements.map {
            listOf(num(it.id), num(it.itemId), str(it.type), qty(it.qtyDeltaMilli), str(it.note), time(it.at))
        })
        insert("settings", d.settings.map { (k, v) -> listOf(str(k), str(v)) })
    }

    private fun StringBuilder.insert(table: String, rows: List<List<String>>) {
        // Batches keep each statement well under MySQL's default max_allowed_packet.
        rows.chunked(500).forEach { chunk ->
            appendLine("INSERT INTO $table VALUES")
            appendLine(chunk.joinToString(",\n") { "  (" + it.joinToString(", ") + ")" } + ";")
        }
    }

    private fun str(v: String?): String = if (v == null) "NULL" else "'" + buildString {
        v.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u0000' -> append("\\0")
                '\u001a' -> append("\\Z")
                else -> append(c)
            }
        }
    } + "'"

    private fun num(v: Long?) = v?.toString() ?: "NULL"
    private fun taka(poisha: Long) = BigDecimal.valueOf(poisha, 2).toPlainString()
    private fun qty(milli: Long?) = milli?.let { BigDecimal.valueOf(it, 3).toPlainString() } ?: "NULL"
    private fun time(ms: Long?) = if (ms == null || ms <= 0) "NULL" else "'" + Instant.ofEpochMilli(ms).atZone(zone).format(dtf) + "'"
}

/** Command line: gradlew :admin:exportSql -Pbackup=<file.ajwb>  → writes <file>.sql next to it. */
fun main(args: Array<String>) {
    val keys = AdminStore.loadKeys() ?: error("No provider keys in ${AdminStore.keysFile}")
    val input = File(args.firstOrNull() ?: error("Give the .ajwb file"))
    val data = BackupData.decode(BackupEnvelope.decryptAsProvider(input.readBytes(), keys.boxPrivate))
    val out = File(input.parentFile, input.nameWithoutExtension + ".sql")
    out.writeText(MysqlExport.toSql(data, "ajwa_inventory"))
    println("Wrote $out (${data.items.size} items, ${data.sales.size} sales, ${data.movements.size} stock movements)")
}
