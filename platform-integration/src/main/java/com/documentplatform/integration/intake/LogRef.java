package com.documentplatform.integration.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The short stable reference a log line carries in place of an intake ID (a watched-folder drop named by the
 * customer's file or folder name, a source's item ID): {@code #} and the first 12 hex characters of the SHA-256 of the
 * ID. Such IDs may hold personal data, so log lines never carry them (P037); the same ID always gives the same
 * reference, which support can compute from the real name in {@code problem.json} or the app's records.
 */
public final class LogRef {

    private LogRef() {
    }

    /** {@code #<12 hex>} for {@code id}; {@code #none} for null. */
    public static String of(String id) {
        if (id == null) return "#none";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return "#" + HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
