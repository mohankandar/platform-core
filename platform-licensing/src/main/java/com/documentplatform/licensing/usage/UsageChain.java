package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.json.Json;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The hash chain of the usage ledger. Every ledger implementation uses {@link #link} to build records and
 * {@link #verify} to check them, so the chain is the same whatever the store.
 */
public final class UsageChain {

    /** The {@code previousHash} of the first record: 64 zeros. */
    public static final String GENESIS = "0".repeat(64);

    private UsageChain() {
    }

    /** The record that follows {@code previous} (null for the first); its time is kept in whole microseconds. */
    public static UsageRecord link(UsageRecord previous, UsageEntry entry) {
        return seal(previous == null ? null : ChainHead.of(previous), entry);
    }

    /** The record that follows the chain head {@code head} (null for the first record). */
    public static UsageRecord link(ChainHead head, UsageEntry entry) {
        return seal(head, entry);
    }

    private static UsageRecord seal(ChainHead head, UsageEntry entry) {
        Objects.requireNonNull(entry, "entry");
        // LV-22: the database keeps microseconds; hashing a finer time would break the chain when it is read back.
        Instant recordedAt = entry.recordedAt().truncatedTo(ChronoUnit.MICROS);
        long sequence = head == null ? 1 : head.sequence() + 1;
        String previousHash = head == null ? GENESIS : head.hash();
        UsageRecord unsealed = new UsageRecord(sequence, entry.slotId(), entry.product(), entry.type(),
                entry.period(), entry.amount(), entry.type().unit(), entry.billable(), recordedAt,
                previousHash, "");
        return new UsageRecord(sequence, entry.slotId(), entry.product(), entry.type(), entry.period(),
                entry.amount(), entry.type().unit(), entry.billable(), recordedAt, previousHash,
                hashOf(unsealed));
    }

    public static String hashOf(UsageRecord record) {
        return Ed25519.sha256Hex(Json.canonical(record.hashedFields()));
    }

    /** Checks the links only (no head): for a list that is not a whole ledger. */
    public static ChainCheck verify(List<UsageRecord> records) {
        return links(records);
    }

    /**
     * Checks a whole ledger, which must start at sequence 1 from {@link #GENESIS} and end at {@code head} (empty when
     * nothing was ever recorded).
     */
    public static ChainCheck verify(List<UsageRecord> records, Optional<ChainHead> head) {
        ChainCheck links = links(records);
        if (!links.intact()) {
            return links;
        }
        UsageRecord last = records.isEmpty() ? null : records.get(records.size() - 1);
        if (head.isEmpty()) {
            if (last == null) {
                return links;
            }
            return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, last.sequence(),
                    "There are " + records.size() + " record(s) but no chain head: the head was removed.");
        }
        ChainHead h = head.get();
        if (last == null || last.sequence() < h.sequence()) {
            long from = last == null ? 1 : last.sequence() + 1;
            return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, from, "The chain head is record "
                    + h.sequence() + " but the newest record is " + (last == null ? "none" : "record " + last.sequence())
                    + ": the newest record(s) " + from + ".." + h.sequence() + " were removed.");
        }
        if (last.sequence() != h.sequence() || !last.hash().equals(h.hash())) {
            return ChainCheck.broken(ChainCheck.Problem.USAGE_CHAIN_HEAD_MISMATCH, last.sequence(),
                    "The newest record (" + last.sequence() + ") does not match the chain head (record "
                            + h.sequence() + "): records or the head were changed.");
        }
        return links;
    }

    private static ChainCheck links(List<UsageRecord> records) {
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
