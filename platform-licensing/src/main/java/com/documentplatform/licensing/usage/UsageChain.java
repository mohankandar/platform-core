package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.json.Json;

import java.util.List;
import java.util.Objects;

/**
 * The hash chain of the usage ledger. Every ledger implementation uses {@link #link} to build records and
 * {@link #verify} to check them, so the chain is the same whatever the store.
 */
public final class UsageChain {

    /** The {@code previousHash} of the first record: 64 zeros. */
    public static final String GENESIS = "0".repeat(64);

    private UsageChain() {
    }

    /** The record that follows {@code previous} (null for the first). */
    public static UsageRecord link(UsageRecord previous, UsageEntry entry) {
        Objects.requireNonNull(entry, "entry");
        long sequence = previous == null ? 1 : previous.sequence() + 1;
        String previousHash = previous == null ? GENESIS : previous.hash();
        UsageRecord unsealed = new UsageRecord(sequence, entry.slotId(), entry.product(), entry.type(),
                entry.period(), entry.amount(), entry.type().unit(), entry.billable(), entry.recordedAt(),
                previousHash, "");
        return new UsageRecord(sequence, entry.slotId(), entry.product(), entry.type(), entry.period(),
                entry.amount(), entry.type().unit(), entry.billable(), entry.recordedAt(), previousHash,
                hashOf(unsealed));
    }

    public static String hashOf(UsageRecord record) {
        return Ed25519.sha256Hex(Json.canonical(record.hashedFields()));
    }

    /** Checks a whole ledger, which must start at sequence 1 from {@link #GENESIS}. */
    public static ChainCheck verify(List<UsageRecord> records) {
        String expectedPrevious = GENESIS;
        long expectedSequence = 1;
        for (UsageRecord r : records) {
            if (r.sequence() != expectedSequence) {
                return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_SEQUENCE_GAP, expectedSequence,
                        "Expected record " + expectedSequence + ", found record " + r.sequence()
                                + ": records are missing or out of order.");
            }
            if (!r.previousHash().equals(expectedPrevious)) {
                return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_LINK_BROKEN, r.sequence(),
                        "Record " + r.sequence() + " does not link to the record before it: a record was removed,"
                                + " inserted or re-chained.");
            }
            if (!r.unit().equals(r.type().unit()) || !hashOf(r).equals(r.hash())) {
                return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_RECORD_EDITED, r.sequence(),
                        "Record " + r.sequence() + " was changed after it was recorded.");
            }
            expectedPrevious = r.hash();
            expectedSequence++;
        }
        return ChainCheck.intact(records.size());
    }
}
