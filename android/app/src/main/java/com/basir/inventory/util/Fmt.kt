package com.basir.inventory.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display formatting. In Bangla mode digits are shown as Bangla numerals and taka uses the
 * subcontinent grouping (১২,৩৪,৫৬৭). Money is stored in poisha, quantities in thousandths.
 */
object Fmt {
    @Volatile var bangla: Boolean = true

    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    val locale: Locale get() = if (bangla) Locale("bn", "BD") else Locale.ENGLISH

    fun digits(text: String): String =
        if (!bangla) text else buildString(text.length) {
            for (c in text) append(if (c in '0'..'9') BN_DIGITS[c - '0'] else c)
        }

    /** Bangla and ASCII digits -> ASCII, for parsing user input. */
    fun toAscii(text: String): String = buildString(text.length) {
        for (c in text) {
            val i = BN_DIGITS.indexOf(c)
            append(if (i >= 0) '0' + i else c)
        }
    }

    fun num(n: Number): String = digits(group(n.toLong().toString()))

    fun taka(poisha: Long): String {
        val negative = poisha < 0
        val abs = kotlin.math.abs(poisha)
        val whole = group((abs / 100).toString())
        val frac = abs % 100
        val text = if (frac == 0L) whole else "$whole.${frac.toString().padStart(2, '0')}"
        return (if (negative) "-" else "") + "৳" + digits(text)
    }

    fun qty(milli: Long): String = digits(plainQty(milli))

    fun qtyUnit(milli: Long, unitLabel: String): String = "${qty(milli)} $unitLabel"

    /** Quantity as an editable ASCII string ("12.5"). */
    fun plainQty(milli: Long): String =
        BigDecimal.valueOf(milli, 3).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    fun plainMoney(poisha: Long): String =
        BigDecimal.valueOf(poisha, 2).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    /** Parses "12.5" / "১২.৫" into thousandths; null if not a non-negative number. */
    fun parseQty(text: String): Long? = parseScaled(text, 3)

    /** Parses taka text into poisha. */
    fun parseMoney(text: String): Long? = parseScaled(text, 2)

    private fun parseScaled(text: String, scale: Int): Long? {
        val clean = toAscii(text.trim()).replace(",", "")
        if (clean.isEmpty()) return null
        val value = clean.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return runCatching { value.setScale(scale, RoundingMode.HALF_UP).unscaledValue().longValueExact() }.getOrNull()
    }

    /** 1234567 -> 12,34,567 (lakh/crore grouping). */
    private fun group(digitsOnly: String): String {
        val neg = digitsOnly.startsWith("-")
        val d = digitsOnly.removePrefix("-")
        if (d.length <= 3) return digitsOnly
        val last3 = d.takeLast(3)
        val rest = d.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return (if (neg) "-" else "") + "$rest,$last3"
    }

    private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, locale)

    fun date(ms: Long): String = digits(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(pattern("d MMM yyyy")))

    fun date(d: LocalDate): String = digits(d.format(pattern("d MMM yyyy")))

    fun dateTime(ms: Long): String =
        digits(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(pattern("d MMM yyyy, h:mm a")))

    fun time(ms: Long): String = digits(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(pattern("h:mm a")))
}

/** Money × quantity, where quantity is in thousandths. */
fun lineTotal(unitPoisha: Long, qtyMilli: Long): Long =
    BigDecimal.valueOf(unitPoisha).multiply(BigDecimal.valueOf(qtyMilli))
        .divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).toLong()
