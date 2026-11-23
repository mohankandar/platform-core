package com.documentplatform.eraselog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a log's keyed part starts (P044, SEC-3): from entry {@code from} on, every entry must carry a valid MAC
 * ({@link ChainKey}); the hash just before it ({@code anchor}: the entry {@code from - 1}, or {@link EraseEntry#GENESIS}
 * for a new log) is sealed with the key, so the older, unkeyed entries are fixed too through the hash chain. A log is
 * sealed once: when it is created with a key, or by the explicit migration of an existing log
 * ({@link EraseLog#seal()}), which writes this record and rewrites no entry.
 *
 * <p>{@code mac} = HMAC-SHA256 under the key's version {@code keyVersion} of
 * {@code "platform-erase-log/seal/1" NUL <log ID> NUL <from> NUL <anchor>}.
 */
public record Seal(UUID logId, long from, String anchor, int keyVersion, String mac) {

    public static final String FORMAT = "platform-erase-log/seal/1";

    public Seal {
        Objects.requireNonNull(logId, "logId");
        if (from < 1) throw new IllegalArgumentException("from starts at 1");
        if (anchor == null || !EraseEntry.HASH.matcher(anchor).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the erase log's seal has no valid anchor");
        }
        if (keyVersion < 1 || mac == null || !EraseEntry.HASH.matcher(mac).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "the erase log's seal has no valid MAC");
        }
    }

    /** The seal for a log whose keyed part starts after {@code anchor}. */
    public static Seal make(ChainKey key, UUID logId, ChainPoint anchor) {
        int v = key.version();
        return new Seal(logId, anchor.seq() + 1, anchor.hash(), v,
                HexFormat.of().formatHex(key.mac(v, input(logId, anchor.seq() + 1, anchor.hash()))));
    }

    /** Whether the MAC was made with {@code key} for these fields. */
    public boolean matches(ChainKey key) {
        byte[] expected = key.mac(keyVersion, input(logId, from, anchor));
        return MessageDigest.isEqual(expected, HexFormat.of().parseHex(mac));
    }

    private static byte[] input(UUID logId, long from, String anchor) {
        return String.join("\0", FORMAT, logId.toString(), Long.toString(from), anchor)
                .getBytes(StandardCharsets.US_ASCII);
    }
}
