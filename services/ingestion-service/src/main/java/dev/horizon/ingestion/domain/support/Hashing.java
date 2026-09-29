package dev.horizon.ingestion.domain.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic hashing used wherever the domain needs a stable fingerprint.
 *
 * <p>SHA-256 hex, lower case, 64 characters — the exact shape the {@code char(64)} columns
 * ({@code documents.dedup_key}, {@code documents.payload_hash}) and the
 * {@code corpus-collected.event.json} contract require.
 *
 * <p>Pure and side-effect free: the same input always produces the same digest on any JVM, which is
 * what makes the corpus snapshot reproducible (ADR-0015).
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {}

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] input) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        return toHex(digest.digest(input));
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }
}
