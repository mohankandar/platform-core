package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * The usage ledger: append-only, hash-chained records of what the app metered, plus the chain head (newest sequence
 * and hash) kept apart and updated with every append. The Postgres one is {@code jdbc.PostgresUsageLedger}; the
 * testkit has an in-memory one. Implementations build every record with {@link UsageChain#link(ChainHead, UsageEntry)}
 * from the stored head, under a lock or transaction, so two appends never share a sequence number.
 *
 * <p>SPI: implemented by stores; apps call {@link UsageRecorder}.
 */
public interface UsageLedger {

    /** Appends one entry, moves the chain head to it, and returns the sealed record. */
    UsageRecord append(UsageEntry entry);

    /** Every record, in sequence order. */
    List<UsageRecord> records();

    /** The stored chain head; empty only when nothing was ever recorded. */
    Optional<ChainHead> head();

    /** The records and the head read together (one transaction or lock), so an append in between cannot be mistaken for a removal. */
    Snapshot snapshot();

    /** Records and head as of one moment. */
    record Snapshot(List<UsageRecord> records, Optional<ChainHead> head) {
    }

    /** Checks the whole chain and that it ends at the stored head (so removed newest records are found). */
    default ChainCheck verifyChain() {
        Snapshot s = snapshot();
        return UsageChain.verify(s.records(), s.head());
    }

    /**
     * Usage of one slot, product and type in one month: the sum for {@link AllowanceType.Aggregation#SUM} types, the
     * highest reading for {@link AllowanceType.Aggregation#PEAK} types. Billable and non-billable records both count.
     */
    default long usage(String slotId, String product, AllowanceType type, YearMonth period) {
        return UsageTotals.aggregate(records(), slotId, product, type, period, false);
    }

    /** As {@link #usage}, counting billable records only. */
    default long billableUsage(String slotId, String product, AllowanceType type, YearMonth period) {
        return UsageTotals.aggregate(records(), slotId, product, type, period, true);
    }
}
