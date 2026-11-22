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
 */
public record EraseEntry(UUID logId, long seq, Instant loggedAt, EraseRequest request, String prev, String hash) {

    public static final String FORMAT = "platform-erase-log/1";

    /** The hash before the first entry. */
    public static final String GENESIS = "0".repeat(64);

    static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

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
    }

    /** The next entry after {@code head}, with its hash. */
    public static EraseEntry link(UUID logId, ChainPoint head, Instant loggedAt, EraseRequest request) {
        long seq = head.seq() + 1;
        return new EraseEntry(logId, seq, loggedAt, request, head.hash(),
                hash(logId, seq, loggedAt, request, head.hash()));
    }

    /** Whether {@link #hash} follows from the fields and {@link #prev}. */
    public boolean hashMatches() {
        return hash.equals(hash(logId, seq, loggedAt, request, prev));
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

    @Override
    public String toString() {
        return "erase log entry " + seq + ": " + request;
    }
}
