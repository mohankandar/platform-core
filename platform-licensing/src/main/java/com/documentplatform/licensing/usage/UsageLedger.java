package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;

import java.time.YearMonth;
import java.util.List;

/**
 * The usage ledger: append-only, hash-chained records of what the app metered. The app supplies a database-backed
 * implementation (later task); the testkit has an in-memory one. Implementations build every record with
 * {@link UsageChain#link} under a lock or transaction, so two appends never share a sequence number.
 */
public interface UsageLedger {

    /** Appends one entry and returns the sealed record. */
    UsageRecord append(UsageEntry entry);

    /** Every record, in sequence order. */
    List<UsageRecord> records();

    /** Checks the whole chain. */
    default ChainCheck verifyChain() {
        return UsageChain.verify(records());
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
