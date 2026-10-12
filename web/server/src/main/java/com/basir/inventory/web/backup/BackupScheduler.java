package com.basir.inventory.web.backup;

import com.basir.inventory.web.AppProperties;
import com.basir.inventory.web.db.StateStore;
import com.basir.inventory.web.inventory.InventoryService;
import com.basir.inventory.web.license.LicenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The phone's WorkManager jobs, as scheduled tasks: backups at 9 PM (daily/weekly/monthly per the
 * license key), a catch-up if one was missed while the PC was off, uploads of queued backups, and the
 * daily heartbeat for the provider's monitoring sheet. Backups keep running after the license
 * expires, so the provider still sees usage.
 */
@Component
public class BackupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private final BackupService backups;
    private final LicenseService license;
    private final InventoryService inventory;
    private final StateStore store;
    private final RelayClient relay;
    private final AppProperties props;

    public BackupScheduler(BackupService backups, LicenseService license, InventoryService inventory, StateStore store,
                           RelayClient relay, AppProperties props) {
        this.backups = backups;
        this.license = license;
        this.inventory = inventory;
        this.store = store;
        this.relay = relay;
        this.props = props;
    }

    @Scheduled(cron = "0 0 21 * * *", zone = "Asia/Dhaka")
    public void nightly() {
        LicenseService.LicenseRow l = license.current();
        if (l == null) return;
        // Half a day of slack so a weekly backup made at 9:00:05 last week still counts as due.
        if (sinceLastBackup() >= l.mode().intervalDays * DAY - 12 * HOUR) backupAndUpload();
    }

    /** Safety net and uploads; every 30 minutes, first run a minute after start. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 30 * 60_000)
    public void maintenance() {
        LicenseService.LicenseRow l = license.current();
        if (l != null && sinceLastBackup() > (l.mode().intervalDays + 1L) * DAY) backupAndUpload();
        else if (backups.pendingUploads() > 0) backups.uploadPending();

        Long last = store.stateLong(StateStore.LAST_HEARTBEAT);
        if (relay.configured() && (last == null || System.currentTimeMillis() - last > 20 * HOUR) && sendHeartbeat()) {
            store.putState(StateStore.LAST_HEARTBEAT, System.currentTimeMillis());
        }
    }

    private long sinceLastBackup() {
        Long last = store.stateLong(StateStore.LAST_BACKUP);
        return System.currentTimeMillis() - (last == null ? 0 : last);
    }

    private void backupAndUpload() {
        try {
            backups.createBackup();
        } catch (RuntimeException e) {
            log.error("Automatic backup failed", e);
            backups.recordError("backup: " + e);
            return;
        }
        backups.uploadPending();
    }

    /** Counts and totals only — no item names, prices or customer data. */
    boolean sendHeartbeat() {
        LicenseService.Status status = license.refresh();
        LicenseService.LicenseRow current = license.current();
        InventoryService.Stats stats = inventory.stats();
        long startOfToday = LocalDate.now(AppProperties.ZONE).atStartOfDay(AppProperties.ZONE).toInstant().toEpochMilli();

        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("deviceCode", license.deviceCode());
        hb.put("sentAt", System.currentTimeMillis());
        hb.put("appVersion", props.version());
        hb.put("phone", "Web server · " + System.getProperty("os.name"));
        hb.put("lastOpenedAt", orZero(store.stateLong(StateStore.LAST_OPENED)));
        hb.put("itemCount", stats.totalItems());
        hb.put("outOfStockCount", stats.outOfStock());
        hb.put("lowStockCount", stats.lowStock());
        hb.put("stockValuePoisha", stats.stockValuePoisha());
        hb.put("salesTodayCount", inventory.salesCountSince(startOfToday));
        hb.put("salesTodayPoisha", inventory.salesTotalSince(startOfToday));
        hb.put("licenseState", switch (status.state()) {
            case ACTIVE -> "active";
            case EXPIRED -> "expired";
            case CLOCK_TAMPERED -> "clock_set_back";
            case NOT_ACTIVATED -> "not_activated";
        });
        hb.put("licenseSerial", current == null ? 0L : current.serial());
        hb.put("licenseExpiresAt", current == null ? 0L : current.expiresAt());
        hb.put("lastBackupAt", orZero(store.stateLong(StateStore.LAST_BACKUP)));
        hb.put("pendingUploads", backups.pendingUploads());
        String error = store.state(StateStore.LAST_ERROR);
        hb.put("lastError", error == null ? "" : error);
        hb.put("lastErrorAt", orZero(store.stateLong(StateStore.LAST_ERROR_AT)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "heartbeat");
        body.put("token", relay.token());
        body.put("shop", props.shopName());
        body.put("heartbeat", hb);
        boolean ok = relay.post(body);
        if (ok && error != null) store.putState(StateStore.LAST_ERROR, "");
        return ok;
    }

    private static long orZero(Long v) {
        return v == null ? 0 : v;
    }
}
