package com.basir.inventory.web.license;

import com.basir.inventory.web.AppProperties;
import com.basir.inventory.web.crypto.License;
import com.basir.inventory.web.crypto.License.BackupMode;
import com.basir.inventory.web.crypto.License.DeviceCode;
import com.basir.inventory.web.crypto.License.ParseResult;
import com.basir.inventory.web.crypto.License.Payload;
import com.basir.inventory.web.db.StateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Offline license checks, the same rules as the Android app's LicenseManager. The whole web
 * installation counts as one "device": its device code comes from a random installation id kept in
 * the database, and every browser that opens the app shares the license.
 */
@Service
public class LicenseService {
    /** A key must be entered within this many days of being issued. */
    public static final int MAX_KEY_AGE_DAYS = 7;
    public static final int REMIND_DAYS = 3;
    /** How far the clock may go backwards (timezone/NTP corrections) before we call it tampering. */
    static final long CLOCK_TOLERANCE_MS = TimeUnit.HOURS.toMillis(24);
    /** last_seen is written at most this often, not on every request. */
    private static final long LAST_SEEN_STEP_MS = TimeUnit.MINUTES.toMillis(1);

    public enum State { NOT_ACTIVATED, ACTIVE, EXPIRED, CLOCK_TAMPERED }

    public enum ActivationError { MALFORMED, BAD_SIGNATURE, WRONG_DEVICE, ALREADY_USED, TOO_OLD, FROM_FUTURE, CLOCK }

    public static class ActivationException extends RuntimeException {
        public final ActivationError error;

        ActivationException(ActivationError error) {
            super(error.name());
            this.error = error;
        }
    }

    public record LicenseRow(long serial, String keyText, int issuedDay, int validityDays, int backupMode, long activatedAt, long expiresAt) {
        public BackupMode mode() {
            BackupMode m = BackupMode.fromCode(backupMode);
            return m == null ? BackupMode.DAILY : m;
        }
    }

    /** What the browser needs to decide between the lock screen and the app. */
    public record Status(State state, String deviceCode, Long serial, Long expiresAt, Integer daysLeft, String backupMode, boolean expiringSoon) {
        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    private static final RowMapper<LicenseRow> ROW = (rs, n) -> new LicenseRow(
            rs.getLong("serial"), rs.getString("key_text"), rs.getInt("issued_day"), rs.getInt("validity_days"),
            rs.getInt("backup_mode"), rs.getLong("activated_at"), rs.getLong("expires_at"));

    private final JdbcTemplate jdbc;
    private final StateStore store;
    private final byte[] signPublic;
    private final byte[] deviceId;
    private final String deviceCode;
    LongSupplier now = System::currentTimeMillis;

    public LicenseService(JdbcTemplate jdbc, StateStore store, AppProperties props) {
        this.jdbc = jdbc;
        this.store = store;
        this.signPublic = props.signPublicKey();
        String installation = store.state(StateStore.INSTALLATION_ID);
        if (installation == null) {
            installation = "web-" + UUID.randomUUID();
            store.putState(StateStore.INSTALLATION_ID, installation);
        }
        this.deviceId = DeviceCode.fromId(installation);
        this.deviceCode = DeviceCode.display(deviceId);
    }

    public byte[] deviceId() {
        return deviceId.clone();
    }

    public String deviceCode() {
        return deviceCode;
    }

    public LicenseRow current() {
        List<LicenseRow> rows = jdbc.query("SELECT * FROM licenses ORDER BY activated_at DESC LIMIT 1", ROW);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Re-evaluates the license; called for every API request. */
    public synchronized Status refresh() {
        long t = now.getAsLong();
        long lastSeen = lastSeen();
        if (t < lastSeen - CLOCK_TOLERANCE_MS) return status(State.CLOCK_TAMPERED, current(), t);
        if (t > lastSeen + LAST_SEEN_STEP_MS) store.putState(StateStore.LAST_SEEN, t);
        LicenseRow license = current();
        State state = license == null ? State.NOT_ACTIVATED : t >= license.expiresAt() ? State.EXPIRED : State.ACTIVE;
        return status(state, license, t);
    }

    private Status status(State state, LicenseRow l, long t) {
        if (l == null) return new Status(state, deviceCode, null, null, null, null, false);
        Integer daysLeft = state == State.ACTIVE ? daysLeft(l.expiresAt(), t) : null;
        return new Status(state, deviceCode, l.serial(), l.expiresAt(), daysLeft, l.mode().name(),
                daysLeft != null && daysLeft <= REMIND_DAYS);
    }

    /** Whole calendar days until expiry (0 = expires today). */
    private static int daysLeft(long expiresAt, long t) {
        LocalDate today = Instant.ofEpochMilli(t).atZone(AppProperties.ZONE).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(expiresAt).atZone(AppProperties.ZONE).toLocalDate();
        return (int) Math.max(0, end.toEpochDay() - today.toEpochDay());
    }

    private long lastSeen() {
        Long v = store.stateLong(StateStore.LAST_SEEN);
        return v == null ? 0L : v;
    }

    public synchronized LicenseRow activate(String keyText) {
        Payload payload = switch (License.parse(keyText, signPublic)) {
            case ParseResult.Malformed m -> throw new ActivationException(ActivationError.MALFORMED);
            case ParseResult.BadSignature b -> throw new ActivationException(ActivationError.BAD_SIGNATURE);
            case ParseResult.Valid v -> v.payload();
        };
        long t = now.getAsLong();
        long lastSeen = lastSeen();
        if (t < lastSeen - CLOCK_TOLERANCE_MS) throw new ActivationException(ActivationError.CLOCK);
        if (!Arrays.equals(payload.device(), deviceId)) throw new ActivationException(ActivationError.WRONG_DEVICE);
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM licenses WHERE serial = ?", Integer.class, payload.serial());
        if (used != null && used > 0) throw new ActivationException(ActivationError.ALREADY_USED);

        int today = Payload.dayOf(Instant.ofEpochMilli(t).atZone(AppProperties.ZONE).toLocalDate());
        if (payload.issuedDay() > today + 1) throw new ActivationException(ActivationError.FROM_FUTURE);
        if (today - payload.issuedDay() > MAX_KEY_AGE_DAYS) throw new ActivationException(ActivationError.TOO_OLD);

        LicenseRow row = new LicenseRow(payload.serial(), keyText.trim(), payload.issuedDay(), payload.validityDays(),
                payload.backupMode().code, t, t + TimeUnit.DAYS.toMillis(payload.validityDays()));
        jdbc.update("INSERT INTO licenses (serial, key_text, issued_day, validity_days, backup_mode, activated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.serial(), row.keyText(), row.issuedDay(), row.validityDays(), row.backupMode(), row.activatedAt(), row.expiresAt());
        store.putState(StateStore.LAST_SEEN, Math.max(t, lastSeen));
        return row;
    }
}
