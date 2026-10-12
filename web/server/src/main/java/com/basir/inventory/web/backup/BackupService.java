package com.basir.inventory.web.backup;

import com.basir.inventory.web.AppProperties;
import com.basir.inventory.web.crypto.BackupCrypto;
import com.basir.inventory.web.crypto.BackupCrypto.BackupFormatException;
import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.db.Units;
import com.basir.inventory.web.inventory.InventoryService;
import com.basir.inventory.web.license.LicenseService;
import com.basir.inventory.web.model.Models.BackupData;
import com.basir.inventory.web.model.Models.Category;
import com.basir.inventory.web.model.Models.Item;
import com.basir.inventory.web.model.Models.Movement;
import com.basir.inventory.web.model.Models.Sale;
import com.basir.inventory.web.model.Models.Supplier;
import com.basir.inventory.web.model.Models.UsageSummary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Encrypted backups, as on the phone: readable by this installation and by the provider's admin
 * app, newest {@link #KEEP_LOCAL} kept in data/backups, and a copy queued in data/outbox for the
 * e-mail relay.
 */
@Service
public class BackupService {
    public static final String EXT = "ajwb";
    static final int KEEP_LOCAL = 3;
    /** Unsent backups kept while offline; older ones are dropped. */
    static final int KEEP_OUTBOX = 30;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(AppProperties.ZONE);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StateStore store;
    private final LicenseService license;
    private final InventoryService inventory;
    private final RelayClient relay;
    private final AppProperties props;
    private final ObjectMapper json;
    private final BackupCrypto.LocalKeyWrapper localWrapper;

    public BackupService(JdbcTemplate jdbc, TransactionTemplate tx, StateStore store, LicenseService license,
                         InventoryService inventory, RelayClient relay, AppProperties props, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.store = store;
        this.license = license;
        this.inventory = inventory;
        this.relay = relay;
        this.props = props;
        this.json = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.localWrapper = new LocalWrapper(localKey(store));
    }

    public Path backupDir() {
        return dir("backups");
    }

    public Path outboxDir() {
        return dir("outbox");
    }

    private Path dir(String name) {
        Path p = props.dataPath().resolve(name);
        try {
            return Files.createDirectories(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int pendingUploads() {
        return listBackups(outboxDir()).size();
    }

    public List<Path> localBackups() {
        return listBackups(backupDir());
    }

    public synchronized Path createBackup() {
        BackupData data = snapshot();
        try {
            byte[] bytes = BackupCrypto.encryptBackup(json.writeValueAsString(data), props.boxPublicKey(), localWrapper);
            String name = "Ajwa_Backup_" + STAMP.format(Instant.ofEpochMilli(data.summary().createdAt())) + "." + EXT;
            Path file = backupDir().resolve(name);
            writeAtomically(file, bytes);
            prune(backupDir(), KEEP_LOCAL);
            // Queue for e-mailing only when a relay is set up; otherwise the queue would only grow.
            if (relay.configured()) {
                writeAtomically(outboxDir().resolve(name), bytes);
                Files.writeString(outboxDir().resolve(name + ".json"), json.writeValueAsString(data.summary()));
                prune(outboxDir(), KEEP_OUTBOX);
            }
            store.putState(StateStore.LAST_BACKUP, data.summary().createdAt());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Consistent copy of every table plus a usage summary (one REPEATABLE READ transaction). */
    public BackupData snapshot() {
        return tx.execute(status -> {
            long now = System.currentTimeMillis();
            List<Category> categories = jdbc.query("SELECT * FROM categories", InventoryService.CATEGORY);
            List<Supplier> suppliers = jdbc.query("SELECT * FROM suppliers", InventoryService.SUPPLIER);
            List<Item> items = jdbc.query("SELECT * FROM items", InventoryService.ITEM);
            List<Sale> sales = jdbc.query("SELECT * FROM sales", InventoryService.SALE);
            List<Movement> movements = jdbc.query("SELECT * FROM stock_movements", InventoryService.MOVEMENT);
            Long lastBackup = store.stateLong(StateStore.LAST_BACKUP);
            LicenseService.LicenseRow current = license.current();
            InventoryService.Stats stats = InventoryService.stats(items, store.globalThreshold());
            long since = lastBackup == null ? 0 : lastBackup + 1;
            Long lastOpened = store.stateLong(StateStore.LAST_OPENED);

            UsageSummary summary = new UsageSummary(
                    props.shopName(), license.deviceCode(), props.version(), now,
                    current == null ? null : current.serial(), current == null ? null : current.expiresAt(),
                    lastOpened == null ? now : lastOpened,
                    stats.totalItems(), stats.outOfStock(), stats.lowStock(), stats.stockValuePoisha(),
                    inventory.salesCountSince(since), inventory.salesTotalSince(since), sales.size(), lastBackup);
            return new BackupData(BackupData.SCHEMA_VERSION, summary, categories, suppliers, items, sales, movements,
                    store.portableSettings());
        });
    }

    /**
     * Reads an uploaded file: this installation's own backup (.ajwb) or a restore file the provider
     * made in the admin app for this installation's device code (.ajwr).
     */
    public BackupData readFile(byte[] bytes) {
        String text;
        if (BackupCrypto.isBackup(bytes)) text = BackupCrypto.decryptBackupLocally(bytes, localWrapper);
        else if (BackupCrypto.isRestorePackage(bytes)) text = BackupCrypto.openRestorePackage(bytes, license.deviceId(), props.signPublicKey());
        else throw new BackupFormatException("Not an Ajwa backup file");
        BackupData data;
        try {
            data = json.readValue(text, BackupData.class);
        } catch (IOException e) {
            throw new BackupFormatException("Backup content is unreadable");
        }
        if (data.schemaVersion() > BackupData.SCHEMA_VERSION) throw new BackupFormatException("Backup is from a newer app version");
        return data;
    }

    /** Replaces all shop data with [data], after first backing up the current data. */
    public synchronized void restore(BackupData data) {
        createBackup();
        tx.executeWithoutResult(status -> {
            // Same connection for the whole transaction, so this session setting covers every statement.
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String t : List.of("stock_movements", "sales", "items", "categories", "suppliers")) jdbc.update("DELETE FROM " + t);
                jdbc.batchUpdate("INSERT INTO categories (id, parent_id, name, level) VALUES (?, ?, ?, ?)",
                        nonNull(data.categories()).stream().map(c -> new Object[]{c.id(), c.parentId(), c.name(), c.level()}).toList());
                jdbc.batchUpdate("INSERT INTO suppliers (id, name, phone, note) VALUES (?, ?, ?, ?)",
                        nonNull(data.suppliers()).stream().map(s -> new Object[]{s.id(), s.name(), s.phone(), s.note()}).toList());
                jdbc.batchUpdate("""
                                INSERT INTO items (id, name, sku, category_id, supplier_id, unit, purchase_price, selling_price, quantity,
                                                   low_stock_limit, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        nonNull(data.items()).stream().map(i -> new Object[]{
                                i.id(), i.name(), i.sku(), i.categoryId(), i.supplierId(), i.unit(), Units.taka(i.purchasePoisha()),
                                Units.taka(i.sellingPoisha()), Units.qty(i.qtyMilli()), Units.qty(i.thresholdMilli()),
                                Units.time(i.createdAt()), Units.time(i.updatedAt())}).toList());
                jdbc.batchUpdate("INSERT INTO sales (id, item_id, quantity, unit_price, total, total_cost, sold_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        nonNull(data.sales()).stream().map(s -> new Object[]{
                                s.id(), s.itemId(), Units.qty(s.qtyMilli()), Units.taka(s.unitPoisha()), Units.taka(s.totalPoisha()),
                                Units.taka(s.costPoisha()), Units.time(s.soldAt())}).toList());
                jdbc.batchUpdate("INSERT INTO stock_movements (id, item_id, type, quantity_change, note, at) VALUES (?, ?, ?, ?, ?, ?)",
                        nonNull(data.movements()).stream().map(m -> new Object[]{
                                m.id(), m.itemId(), m.type(), Units.qty(m.qtyDeltaMilli()), m.note(), Units.time(m.at())}).toList());
                if (data.settings() != null) {
                    data.settings().forEach((k, v) -> {
                        if (StateStore.PORTABLE_KEYS.contains(k)) store.putSetting(k, v);
                    });
                }
            } finally {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        });
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Uploads every queued file, oldest first; false if one failed and should be retried later. */
    public synchronized boolean uploadPending() {
        if (!relay.configured()) return true;
        for (Path file : listBackups(outboxDir()).reversed()) {
            try {
                Path summaryFile = file.resolveSibling(file.getFileName() + ".json");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("token", relay.token());
                body.put("shop", props.shopName());
                body.put("fileName", file.getFileName().toString());
                body.put("file", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
                if (Files.exists(summaryFile)) body.put("summary", json.readTree(Files.readString(summaryFile)));
                if (!relay.post(body)) {
                    recordError("backup upload failed");
                    return false;
                }
                Files.deleteIfExists(file);
                Files.deleteIfExists(summaryFile);
            } catch (IOException e) {
                recordError("backup upload: " + e);
                return false;
            }
        }
        return true;
    }

    public void recordError(String text) {
        store.putState(StateStore.LAST_ERROR, text);
        store.putState(StateStore.LAST_ERROR_AT, System.currentTimeMillis());
    }

    /** Newest first. */
    private static List<Path> listBackups(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith("." + EXT))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void prune(Path dir, int keep) throws IOException {
        List<Path> files = listBackups(dir);
        for (Path p : files.subList(Math.min(keep, files.size()), files.size())) {
            Files.deleteIfExists(p);
            Files.deleteIfExists(p.resolveSibling(p.getFileName() + ".json"));
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Random key kept in app_state; wraps each backup's content key so this installation can reopen it. */
    private static byte[] localKey(StateStore store) {
        String v = store.state(StateStore.LOCAL_BACKUP_KEY);
        if (v == null) {
            v = Base64.getEncoder().encodeToString(BackupCrypto.randomBytes(32));
            store.putState(StateStore.LOCAL_BACKUP_KEY, v);
        }
        return Base64.getDecoder().decode(v);
    }

    private record LocalWrapper(byte[] key) implements BackupCrypto.LocalKeyWrapper {
        private static final byte[] AAD = "AJWB-web-local".getBytes();

        @Override
        public byte[] wrap(byte[] contentKey) {
            return BackupCrypto.seal(key, contentKey, AAD);
        }

        @Override
        public byte[] unwrap(byte[] wrapped) {
            try {
                return BackupCrypto.open(key, wrapped, AAD);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
