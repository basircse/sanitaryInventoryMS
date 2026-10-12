package com.basir.inventory.web.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Brings the database to the shape the web app needs. The shop tables use the same layout as the
 * admin app's MySQL export (so an exported backup can be imported straight into this database);
 * this adds AUTO_INCREMENT ids, which the export leaves out, and the web-only tables
 * {@code licenses} and {@code app_state}. Safe to run on every start. Beans that use the database
 * depend on it (via {@link StateStore}) so it runs first.
 */
@Component
public class SchemaInitializer implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final List<String> TABLES = List.of(
            """
            CREATE TABLE IF NOT EXISTS categories (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, parent_id BIGINT NULL, name VARCHAR(200) NOT NULL, level INT NOT NULL,
              FOREIGN KEY (parent_id) REFERENCES categories(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS suppliers (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(200) NOT NULL, phone VARCHAR(50), note TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS items (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(300) NOT NULL, sku VARCHAR(100) NOT NULL UNIQUE,
              category_id BIGINT NULL, supplier_id BIGINT NULL, unit VARCHAR(10) NOT NULL,
              purchase_price DECIMAL(14,2) NOT NULL, selling_price DECIMAL(14,2) NOT NULL,
              quantity DECIMAL(16,3) NOT NULL, low_stock_limit DECIMAL(16,3) NULL,
              created_at DATETIME, updated_at DATETIME,
              FOREIGN KEY (category_id) REFERENCES categories(id),
              FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS sales (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, item_id BIGINT NOT NULL, quantity DECIMAL(16,3) NOT NULL,
              unit_price DECIMAL(14,2) NOT NULL, total DECIMAL(14,2) NOT NULL, total_cost DECIMAL(14,2) NOT NULL,
              sold_at DATETIME NOT NULL, INDEX (sold_at),
              FOREIGN KEY (item_id) REFERENCES items(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS stock_movements (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, item_id BIGINT NOT NULL, type VARCHAR(20) NOT NULL,
              quantity_change DECIMAL(16,3) NOT NULL, note TEXT, at DATETIME NOT NULL, INDEX (at),
              FOREIGN KEY (item_id) REFERENCES items(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS settings (
              name VARCHAR(100) PRIMARY KEY, value TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            // Web-only. Not dropped by the admin app's MySQL export, so a re-import keeps the license.
            """
            CREATE TABLE IF NOT EXISTS licenses (
              serial BIGINT PRIMARY KEY, key_text TEXT NOT NULL, issued_day INT NOT NULL, validity_days INT NOT NULL,
              backup_mode INT NOT NULL, activated_at BIGINT NOT NULL, expires_at BIGINT NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""",
            """
            CREATE TABLE IF NOT EXISTS app_state (
              name VARCHAR(100) PRIMARY KEY, value TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"""
    );

    private static final List<String> AUTO_ID_TABLES = List.of("categories", "suppliers", "items", "sales", "stock_movements");

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        // One connection throughout: FOREIGN_KEY_CHECKS is per session.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String sql : TABLES) st.execute(sql);
            for (String table : AUTO_ID_TABLES) {
                if (!hasAutoIncrement(c, table)) {
                    log.info("Adding AUTO_INCREMENT to {}.id", table);
                    st.execute("SET FOREIGN_KEY_CHECKS = 0");
                    try {
                        st.execute("ALTER TABLE " + table + " MODIFY id BIGINT NOT NULL AUTO_INCREMENT");
                    } finally {
                        st.execute("SET FOREIGN_KEY_CHECKS = 1");
                    }
                }
            }
        }
    }

    private static boolean hasAutoIncrement(Connection c, String table) throws SQLException {
        try (var ps = c.prepareStatement(
                "SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'id'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1).toLowerCase().contains("auto_increment");
            }
        }
    }
}
