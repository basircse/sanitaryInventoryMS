package com.basir.inventory.web.crypto;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Base32 without look-alike characters (0/O, 1/I). Same alphabet as shared/Base32.kt, so keys from
 * the admin app decode here. Decoding is case-insensitive and ignores dashes/whitespace.
 */
public final class Base32 {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int[] LOOKUP = new int[128];

    static {
        Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            char c = ALPHABET.charAt(i);
            LOOKUP[c] = i;
            LOOKUP[Character.toLowerCase(c)] = i;
        }
    }

    private Base32() {}

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    /** Returns null when the text contains characters outside the alphabet. */
    public static byte[] decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : text.toCharArray()) {
            if (c == '-' || Character.isWhitespace(c)) continue;
            int v = c < 128 ? LOOKUP[c] : -1;
            if (v < 0) return null;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** Splits [text] into dash-separated groups for display. */
    public static String group(String text, int size) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) parts.add(text.substring(i, Math.min(text.length(), i + size)));
        return String.join("-", parts);
    }
}
