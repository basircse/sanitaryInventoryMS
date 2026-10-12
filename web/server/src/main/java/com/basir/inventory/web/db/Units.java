package com.basir.inventory.web.db;

import com.basir.inventory.web.AppProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Conversions between the MySQL columns (taka DECIMAL(14,2), units DECIMAL(16,3), Bangladesh-time
 * DATETIME — the layout the admin app's MySQL export creates) and the API's whole numbers
 * (poisha, thousandths, epoch milliseconds).
 */
public final class Units {
    private Units() {}

    public static long poisha(BigDecimal taka) {
        return taka.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal taka(long poisha) {
        return BigDecimal.valueOf(poisha, 2);
    }

    public static long milli(BigDecimal qty) {
        return qty.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal qty(long milli) {
        return BigDecimal.valueOf(milli, 3);
    }

    public static BigDecimal qty(Long milli) {
        return milli == null ? null : qty(milli.longValue());
    }

    public static long ms(LocalDateTime t) {
        return t == null ? 0 : t.atZone(AppProperties.ZONE).toInstant().toEpochMilli();
    }

    public static LocalDateTime time(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), AppProperties.ZONE);
    }

    /** Money × quantity, where quantity is in thousandths. */
    public static long lineTotal(long unitPoisha, long qtyMilli) {
        return BigDecimal.valueOf(unitPoisha).multiply(BigDecimal.valueOf(qtyMilli))
                .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    // ResultSet helpers

    public static long poisha(ResultSet rs, String col) throws SQLException {
        return poisha(rs.getBigDecimal(col));
    }

    public static long milli(ResultSet rs, String col) throws SQLException {
        return milli(rs.getBigDecimal(col));
    }

    public static Long milliOrNull(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return v == null ? null : milli(v);
    }

    public static long ms(ResultSet rs, String col) throws SQLException {
        return ms(rs.getObject(col, LocalDateTime.class));
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
