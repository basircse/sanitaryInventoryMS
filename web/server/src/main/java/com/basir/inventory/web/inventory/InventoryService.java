package com.basir.inventory.web.inventory;

import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.db.Units;
import com.basir.inventory.web.inventory.InventoryException.Reason;
import com.basir.inventory.web.model.Models.Category;
import com.basir.inventory.web.model.Models.Item;
import com.basir.inventory.web.model.Models.Movement;
import com.basir.inventory.web.model.Models.MovementType;
import com.basir.inventory.web.model.Models.Sale;
import com.basir.inventory.web.model.Models.SaleRow;
import com.basir.inventory.web.model.Models.StockUnit;
import com.basir.inventory.web.model.Models.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Shop data: the same rules as the Android app's InventoryRepository. Stock only changes through
 * sell / stock-in / adjust, each logged in stock_movements; price and cost are copied onto every
 * sale. Rows are locked while stock changes, since several phones may sell at the same time.
 */
@Service
public class InventoryService {
    public static final RowMapper<Category> CATEGORY = (rs, n) ->
            new Category(rs.getLong("id"), Units.longOrNull(rs, "parent_id"), rs.getString("name"), rs.getInt("level"));

    public static final RowMapper<Supplier> SUPPLIER = (rs, n) ->
            new Supplier(rs.getLong("id"), rs.getString("name"), rs.getString("phone"), rs.getString("note"));

    public static final RowMapper<Item> ITEM = (rs, n) -> new Item(
            rs.getLong("id"), rs.getString("name"), rs.getString("sku"),
            Units.longOrNull(rs, "category_id"), Units.longOrNull(rs, "supplier_id"), rs.getString("unit"),
            Units.poisha(rs, "purchase_price"), Units.poisha(rs, "selling_price"),
            Units.milli(rs, "quantity"), Units.milliOrNull(rs, "low_stock_limit"),
            Units.ms(rs, "created_at"), Units.ms(rs, "updated_at"));

    public static final RowMapper<Sale> SALE = (rs, n) -> new Sale(
            rs.getLong("id"), rs.getLong("item_id"), Units.milli(rs, "quantity"), Units.poisha(rs, "unit_price"),
            Units.poisha(rs, "total"), Units.poisha(rs, "total_cost"), Units.ms(rs, "sold_at"));

    public static final RowMapper<Movement> MOVEMENT = (rs, n) -> new Movement(
            rs.getLong("id"), rs.getLong("item_id"), rs.getString("type"), Units.milli(rs, "quantity_change"),
            rs.getString("note"), Units.ms(rs, "at"));

    public static final RowMapper<SaleRow> SALE_ROW = (rs, n) -> new SaleRow(
            rs.getLong("id"), rs.getLong("item_id"), rs.getString("item_name"), rs.getString("item_unit"),
            Units.longOrNull(rs, "supplier_id"), Units.milli(rs, "quantity"), Units.poisha(rs, "unit_price"),
            Units.poisha(rs, "total"), Units.poisha(rs, "total_cost"), Units.ms(rs, "sold_at"));

    private static final String SALE_ROW_SQL = """
            SELECT s.*, i.name AS item_name, i.unit AS item_unit, i.supplier_id
            FROM sales s LEFT JOIN items i ON i.id = s.item_id""";

    private final JdbcTemplate jdbc;
    private final StateStore store;
    LongSupplier now = System::currentTimeMillis;

    public InventoryService(JdbcTemplate jdbc, StateStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    // ------------------------------------------------------------ categories

    public List<Category> categories() {
        return jdbc.query("SELECT * FROM categories ORDER BY level, name", CATEGORY);
    }

    @Transactional
    public long addCategory(String name, Long parentId) {
        String clean = requireName(name);
        int level = 1;
        if (parentId != null) {
            List<Category> parent = jdbc.query("SELECT * FROM categories WHERE id = ?", CATEGORY, parentId);
            if (parent.isEmpty()) throw new InventoryException(Reason.NOT_FOUND);
            if (parent.get(0).level() >= 3) throw new InventoryException(Reason.BAD_INPUT);
            level = parent.get(0).level() + 1;
        }
        return insert("INSERT INTO categories (parent_id, name, level) VALUES (?, ?, ?)", parentId, clean, level);
    }

    public void renameCategory(long id, String name) {
        if (jdbc.update("UPDATE categories SET name = ? WHERE id = ?", requireName(name), id) == 0) {
            throw new InventoryException(Reason.NOT_FOUND);
        }
    }

    @Transactional
    public void deleteCategory(long id) {
        if (count("SELECT COUNT(*) FROM categories WHERE parent_id = ?", id) > 0
                || count("SELECT COUNT(*) FROM items WHERE category_id = ?", id) > 0) {
            throw new InventoryException(Reason.IN_USE);
        }
        jdbc.update("DELETE FROM categories WHERE id = ?", id);
    }

    // ------------------------------------------------------------ suppliers

    public List<Supplier> suppliers() {
        return jdbc.query("SELECT * FROM suppliers ORDER BY name", SUPPLIER);
    }

    @Transactional
    public long saveSupplier(long id, String name, String phone, String note) {
        String clean = requireName(name);
        String cleanPhone = phone == null || phone.isBlank() ? null : phone.trim();
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        if (count("SELECT COUNT(*) FROM suppliers WHERE name = ? AND id != ?", clean, id) > 0) {
            throw new InventoryException(Reason.NAME_TAKEN);
        }
        if (id == 0) return insert("INSERT INTO suppliers (name, phone, note) VALUES (?, ?, ?)", clean, cleanPhone, cleanNote);
        if (jdbc.update("UPDATE suppliers SET name = ?, phone = ?, note = ? WHERE id = ?", clean, cleanPhone, cleanNote, id) == 0) {
            throw new InventoryException(Reason.NOT_FOUND);
        }
        return id;
    }

    @Transactional
    public void deleteSupplier(long id) {
        if (count("SELECT COUNT(*) FROM items WHERE supplier_id = ?", id) > 0) throw new InventoryException(Reason.IN_USE);
        jdbc.update("DELETE FROM suppliers WHERE id = ?", id);
    }

    // ------------------------------------------------------------ items

    public List<Item> items() {
        return jdbc.query("SELECT * FROM items ORDER BY name", ITEM);
    }

    public Item item(long id) {
        List<Item> list = jdbc.query("SELECT * FROM items WHERE id = ?", ITEM, id);
        if (list.isEmpty()) throw new InventoryException(Reason.NOT_FOUND);
        return list.get(0);
    }

    public List<Movement> movements(long itemId) {
        return jdbc.query("SELECT * FROM stock_movements WHERE item_id = ? ORDER BY at DESC, id DESC LIMIT 200", MOVEMENT, itemId);
    }

    /** Inserts (id == 0, qtyMilli = opening stock) or updates an item; returns its id. */
    @Transactional
    public long saveItem(Item item) {
        String name = requireName(item.name());
        if (item.purchasePoisha() < 0 || item.sellingPoisha() < 0 || item.qtyMilli() < 0
                || (item.thresholdMilli() != null && item.thresholdMilli() < 0)) {
            throw new InventoryException(Reason.BAD_INPUT);
        }
        String unit = unit(item.unit());
        if (item.categoryId() != null && count("SELECT COUNT(*) FROM categories WHERE id = ?", item.categoryId()) == 0) {
            throw new InventoryException(Reason.NOT_FOUND);
        }
        String supplierName = null;
        if (item.supplierId() != null) {
            List<String> s = jdbc.queryForList("SELECT name FROM suppliers WHERE id = ?", String.class, item.supplierId());
            if (s.isEmpty()) throw new InventoryException(Reason.NOT_FOUND);
            supplierName = s.get(0);
        }
        String sku = item.sku() == null ? "" : item.sku().trim().toUpperCase(Locale.ROOT);
        if (sku.isEmpty()) sku = generateSku(supplierName);
        if (count("SELECT COUNT(*) FROM items WHERE sku = ? AND id != ?", sku, item.id()) > 0) {
            throw new InventoryException(Reason.SKU_TAKEN);
        }
        LocalDateTime t = Units.time(now.getAsLong());
        if (item.id() == 0) {
            long id = insert("""
                    INSERT INTO items (name, sku, category_id, supplier_id, unit, purchase_price, selling_price, quantity,
                                       low_stock_limit, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    name, sku, item.categoryId(), item.supplierId(), unit, Units.taka(item.purchasePoisha()),
                    Units.taka(item.sellingPoisha()), Units.qty(item.qtyMilli()), Units.qty(item.thresholdMilli()), t, t);
            if (item.qtyMilli() != 0) movement(id, MovementType.INITIAL, item.qtyMilli(), null, t);
            return id;
        }
        // Stock only changes through sell/stock-in/adjust so the history stays complete.
        int updated = jdbc.update("""
                        UPDATE items SET name = ?, sku = ?, category_id = ?, supplier_id = ?, unit = ?, purchase_price = ?,
                                         selling_price = ?, low_stock_limit = ?, updated_at = ? WHERE id = ?""",
                name, sku, item.categoryId(), item.supplierId(), unit, Units.taka(item.purchasePoisha()),
                Units.taka(item.sellingPoisha()), Units.qty(item.thresholdMilli()), t, item.id());
        if (updated == 0) throw new InventoryException(Reason.NOT_FOUND);
        return item.id();
    }

    /** SUPPLIER-0001 style code, e.g. RAK-0007; GEN- when the supplier name has no Latin letters. */
    private String generateSku(String supplierName) {
        StringBuilder letters = new StringBuilder();
        if (supplierName != null) {
            for (char c : supplierName.toCharArray()) {
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) letters.append(c);
                if (letters.length() == 3) break;
            }
        }
        String prefix = (letters.isEmpty() ? "GEN" : letters.toString().toUpperCase(Locale.ROOT)) + "-";
        int n = count("SELECT COUNT(*) FROM items WHERE sku LIKE ?", prefix + "%") + 1;
        while (count("SELECT COUNT(*) FROM items WHERE sku = ?", prefix + pad(n)) > 0) n++;
        return prefix + pad(n);
    }

    private static String pad(int n) {
        return String.format(Locale.ROOT, "%04d", n);
    }

    @Transactional
    public void deleteItem(long id) {
        if (count("SELECT COUNT(*) FROM sales WHERE item_id = ?", id) > 0) throw new InventoryException(Reason.HAS_SALES);
        jdbc.update("DELETE FROM stock_movements WHERE item_id = ?", id);
        jdbc.update("DELETE FROM items WHERE id = ?", id);
    }

    /** Records a sale at the item's current selling price and deducts stock atomically. */
    @Transactional
    public Sale sell(long itemId, long qtyMilli) {
        if (qtyMilli <= 0) throw new InventoryException(Reason.BAD_QTY);
        Item item = lock(itemId);
        if (qtyMilli > item.qtyMilli()) throw new InventoryException(Reason.NOT_ENOUGH_STOCK);
        long ms = now.getAsLong();
        LocalDateTime t = Units.time(ms);
        long total = Units.lineTotal(item.sellingPoisha(), qtyMilli);
        long cost = Units.lineTotal(item.purchasePoisha(), qtyMilli);
        long id = insert("INSERT INTO sales (item_id, quantity, unit_price, total, total_cost, sold_at) VALUES (?, ?, ?, ?, ?, ?)",
                itemId, Units.qty(qtyMilli), Units.taka(item.sellingPoisha()), Units.taka(total), Units.taka(cost), t);
        addQty(itemId, -qtyMilli, t);
        movement(itemId, MovementType.SALE, -qtyMilli, null, t);
        return new Sale(id, itemId, qtyMilli, item.sellingPoisha(), total, cost, ms);
    }

    @Transactional
    public void stockIn(long itemId, long qtyMilli, String note) {
        if (qtyMilli <= 0) throw new InventoryException(Reason.BAD_QTY);
        lock(itemId);
        LocalDateTime t = Units.time(now.getAsLong());
        addQty(itemId, qtyMilli, t);
        movement(itemId, MovementType.STOCK_IN, qtyMilli, note, t);
    }

    /** Sets stock to a counted value, recording the difference. */
    @Transactional
    public void adjustTo(long itemId, long newQtyMilli, String note) {
        if (newQtyMilli < 0) throw new InventoryException(Reason.BAD_QTY);
        Item item = lock(itemId);
        long delta = newQtyMilli - item.qtyMilli();
        if (delta == 0) return;
        LocalDateTime t = Units.time(now.getAsLong());
        addQty(itemId, delta, t);
        movement(itemId, MovementType.ADJUST, delta, note, t);
    }

    private Item lock(long itemId) {
        List<Item> list = jdbc.query("SELECT * FROM items WHERE id = ? FOR UPDATE", ITEM, itemId);
        if (list.isEmpty()) throw new InventoryException(Reason.NOT_FOUND);
        return list.get(0);
    }

    private void addQty(long itemId, long delta, LocalDateTime t) {
        jdbc.update("UPDATE items SET quantity = quantity + ?, updated_at = ? WHERE id = ?", Units.qty(delta), t, itemId);
    }

    private void movement(long itemId, MovementType type, long delta, String note, LocalDateTime t) {
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        jdbc.update("INSERT INTO stock_movements (item_id, type, quantity_change, note, at) VALUES (?, ?, ?, ?, ?)",
                itemId, type.name(), Units.qty(delta), cleanNote, t);
    }

    // ------------------------------------------------------------ sales

    public List<SaleRow> salesBetween(long fromMs, long toMsExclusive) {
        return jdbc.query(SALE_ROW_SQL + " WHERE s.sold_at >= ? AND s.sold_at < ? ORDER BY s.sold_at DESC, s.id DESC",
                SALE_ROW, Units.time(fromMs), Units.time(toMsExclusive));
    }

    public List<SaleRow> recentSales(int limit) {
        return jdbc.query(SALE_ROW_SQL + " ORDER BY s.sold_at DESC, s.id DESC LIMIT ?", SALE_ROW, limit);
    }

    public long salesTotalSince(long fromMs) {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(total), 0) FROM sales WHERE sold_at >= ?",
                (rs, n) -> Units.poisha(rs.getBigDecimal(1)), Units.time(fromMs));
        return v == null ? 0 : v;
    }

    public int salesCountSince(long fromMs) {
        return count("SELECT COUNT(*) FROM sales WHERE sold_at >= ?", Units.time(fromMs));
    }

    // ------------------------------------------------------------ stats

    public record Stats(int totalItems, int lowStock, int outOfStock, long stockValuePoisha) {}

    public Stats stats() {
        return stats(items(), store.globalThreshold());
    }

    public static Stats stats(List<Item> items, long threshold) {
        int low = 0, out = 0;
        long value = 0;
        for (Item it : items) {
            if (it.qtyMilli() <= 0) out++;
            else if (it.qtyMilli() <= (it.thresholdMilli() != null ? it.thresholdMilli() : threshold)) low++;
            value += Units.lineTotal(it.sellingPoisha(), Math.max(0, it.qtyMilli()));
        }
        return new Stats(items.size(), low, out, value);
    }

    // ------------------------------------------------------------ helpers

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new InventoryException(Reason.BAD_INPUT);
        return name.trim();
    }

    private static String unit(String unit) {
        for (StockUnit u : StockUnit.values()) if (u.name().equals(unit)) return unit;
        throw new InventoryException(Reason.BAD_INPUT);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private long insert(String sql, Object... args) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}
