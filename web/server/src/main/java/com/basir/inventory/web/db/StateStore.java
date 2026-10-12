package com.basir.inventory.web.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two small key-value tables: {@code settings} holds shop settings that travel inside backups
 * (same keys as the phone), {@code app_state} holds this installation's own state (license
 * clock, backup times, keys) and never leaves it.
 */
@Component
public class StateStore {
    public static final String GLOBAL_THRESHOLD = "global_threshold_milli";
    public static final long DEFAULT_THRESHOLD = 5_000L;
    /** Settings keys carried inside backups. */
    public static final Set<String> PORTABLE_KEYS = Set.of(GLOBAL_THRESHOLD);

    public static final String INSTALLATION_ID = "installation_id";
    public static final String LAST_SEEN = "last_seen_ms";
    public static final String LAST_OPENED = "last_opened_ms";
    public static final String LAST_BACKUP = "last_backup_ms";
    public static final String LAST_HEARTBEAT = "last_heartbeat_ms";
    public static final String LOCAL_BACKUP_KEY = "local_backup_key";
    public static final String LAST_ERROR = "last_error";
    public static final String LAST_ERROR_AT = "last_error_ms";

    private final JdbcTemplate jdbc;

    public StateStore(JdbcTemplate jdbc, SchemaInitializer schemaReady) {
        this.jdbc = jdbc;
    }

    // settings (portable)

    public long globalThreshold() {
        String v = get("settings", GLOBAL_THRESHOLD);
        try {
            return v == null ? DEFAULT_THRESHOLD : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_THRESHOLD;
        }
    }

    public void setGlobalThreshold(long milli) {
        put("settings", GLOBAL_THRESHOLD, Long.toString(milli));
    }

    public Map<String, String> portableSettings() {
        return jdbc.query("SELECT name, value FROM settings", (rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .stream().filter(e -> PORTABLE_KEYS.contains(e.getKey()) && e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public void putSetting(String key, String value) {
        put("settings", key, value);
    }

    // app_state (local)

    public String state(String key) {
        return get("app_state", key);
    }

    public Long stateLong(String key) {
        String v = state(key);
        try {
            return v == null ? null : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void putState(String key, String value) {
        put("app_state", key, value);
    }

    public void putState(String key, long value) {
        put("app_state", key, Long.toString(value));
    }

    private String get(String table, String key) {
        List<String> v = jdbc.queryForList("SELECT value FROM " + table + " WHERE name = ?", String.class, key);
        return v.isEmpty() ? null : v.get(0);
    }

    private void put(String table, String key, String value) {
        jdbc.update("INSERT INTO " + table + " (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)", key, value);
    }
}
