package com.documentplatform.eraselog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One entry of the erase log: the erasure ({@link #request()}), its number in the log ({@code seq}, from 1, no gaps),
 * when it was written ({@code loggedAt}), and a hash chained to the entry before it ({@code prev}), so that changing,
 * removing or reordering an entry is detected. Holds no value.
 *
 * <p>{@code hash} = SHA-256 (lower-case hex) of
 * {@code platform-erase-log/1|<log ID>|<seq>|<loggedAt>|<entity>|<product>|<recordType>|<recordId>|<kind>|
 * <erasedAt>|<by type>|<by id>|<auditId>|<prev>}; the first entry's {@code prev} is {@link #GENESIS} (or, after
 * pruning, the checkpoint's hash). Every field has a fixed character set without {@code |}, so the text is unambiguous.
 *
 * <p>Keyed entries ({@value #KEYED_FORMAT}, P044 SEC-3) also carry {@code mac} = HMAC-SHA256 under the log's
 * {@link ChainKey} (version {@code keyVersion}) of {@code "platform-erase-log/mac/1" NUL <log ID> NUL <seq> NUL <hash>}.
 * The hash covers every field and {@code prev}, so the MAC fixes the entry and its place in the chain. An unkeyed entry
 * has {@code keyVersion} 0 and no MAC.
 */
public record EraseEntry(UUID logId, long seq, Instant loggedAt, EraseRequest request, String prev, String hash,
                         int keyVersion, String mac) {

    public static final String FORMAT = "platform-erase-log/1";

    /** The format of an entry with a MAC. */
    public static final String KEYED_FORMAT = "platform-erase-log/2";

    /** The hash before the first entry. */
    public static final String GENESIS = "0".repeat(64);

    static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final String MAC_FORMAT = "platform-erase-log/mac/1";

    public EraseEntry {
        Objects.requireNonNull(logId, "logId");
        if (seq < 1) {
            throw new IllegalArgumentException("seq starts at 1");
        }
        Objects.requireNonNull(loggedAt, "loggedAt");
        Objects.requireNonNull(request, "request");
        if (prev == null || !HASH.matcher(prev).matches() || hash == null || !HASH.matcher(hash).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "entry " + seq + " has no valid hash");
        }
        if (keyVersion < 0 || (keyVersion == 0) != (mac == null) || mac != null && !HASH.matcher(mac).matches()) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_FORMAT, "entry " + seq + " has no valid MAC");
        }
    }

    /** An unkeyed entry ({@value #FORMAT}). */
    public EraseEntry(UUID logId, long seq, Instant loggedAt, EraseRequest request, String prev, String hash) {
        this(logId, seq, loggedAt, request, prev, hash, 0, null);
    }

    /** The next unkeyed entry after {@code head}, with its hash. */
    public static EraseEntry link(UUID logId, ChainPoint head, Instant loggedAt, EraseRequest request) {
        long seq = head.seq() + 1;
        return new EraseEntry(logId, seq, loggedAt, request, head.hash(),
                hash(logId, seq, loggedAt, request, head.hash()));
    }

    /** The next entry after {@code head}, with its hash and, when {@code key} is given, its MAC. */
    public static EraseEntry link(UUID logId, ChainPoint head, Instant loggedAt, EraseRequest request, ChainKey key) {
        EraseEntry e = link(logId, head, loggedAt, request);
        if (key == null) return e;
        int v = key.version();
        return new EraseEntry(logId, e.seq, loggedAt, request, e.prev, e.hash, v,
                HexFormat.of().formatHex(key.mac(v, macInput(logId, e.seq, e.hash))));
    }

    /** Whether {@link #hash} follows from the fields and {@link #prev}. */
    public boolean hashMatches() {
        return hash.equals(hash(logId, seq, loggedAt, request, prev));
    }

    /** Whether the entry carries a MAC. */
    public boolean keyed() {
        return mac != null;
    }

    /** Whether the entry carries a MAC made with {@code key} for its hash (constant-time comparison). */
    public boolean macMatches(ChainKey key) {
        if (mac == null) return false;
        return MessageDigest.isEqual(key.mac(keyVersion, macInput(logId, seq, hash)), HexFormat.of().parseHex(mac));
    }

    /** This entry as a point in the chain. */
    public ChainPoint point() {
        return new ChainPoint(seq, hash);
    }

    public String kind() {
        return request.kind();
    }

    static String hash(UUID logId, long seq, Instant loggedAt, EraseRequest r, String prev) {
        String text = String.join("|", FORMAT, logId.toString(), Long.toString(seq), loggedAt.toString(),
                r.entity().toString(), r.product(), r.recordType(), r.recordId(), r.kind(), r.erasedAt().toString(),
                r.by().type().name(), r.by().id(), r.auditId(), prev);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] macInput(UUID logId, long seq, String hash) {
        return String.join("\0", MAC_FORMAT, logId.toString(), Long.toString(seq), hash)
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public String toString() {
        return "erase log entry " + seq + ": " + request;
    }
}
