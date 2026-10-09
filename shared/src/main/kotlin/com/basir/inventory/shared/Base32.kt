package com.basir.inventory.shared

/**
 * Base32 with an alphabet that drops look-alike characters (0/O, 1/I), so keys survive
 * being read aloud or retyped. Decoding is case-insensitive and ignores dashes/whitespace.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val LOOKUP = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c ->
            table[c.code] = i
            table[c.lowercaseChar().code] = i
        }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Returns null when the text contains characters outside the alphabet. */
    fun decode(text: String): ByteArray? {
        val clean = text.filterNot { it == '-' || it.isWhitespace() }
        val out = java.io.ByteArrayOutputStream(clean.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) LOOKUP[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** Splits [text] into dash-separated groups for display. */
    fun group(text: String, size: Int = 5): String = text.chunked(size).joinToString("-")
}
