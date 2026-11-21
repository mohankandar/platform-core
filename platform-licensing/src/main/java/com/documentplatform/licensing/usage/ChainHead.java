package com.documentplatform.licensing.usage;

import java.util.Objects;

/**
 * The newest link of the usage chain: its sequence number and hash, stored apart from the records and updated in the
 * same transaction as every append. Checked on read, so deleting the newest records (which leaves no broken link) is
 * detected.
 */
public record ChainHead(long sequence, String hash) {

    public ChainHead {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        Objects.requireNonNull(hash, "hash");
    }

    public static ChainHead of(UsageRecord r) {
        return new ChainHead(r.sequence(), r.hash());
    }
}
