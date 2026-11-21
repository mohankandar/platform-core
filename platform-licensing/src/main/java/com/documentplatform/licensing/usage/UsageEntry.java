package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.Licence;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;

/**
 * One meter reading to append to the ledger. Holds no document data: a slot, a product, a type, a period, an amount.
 * Usually built by {@link UsageRecorder}, which sets {@code billable} from the licence kind.
 */
public record UsageEntry(String slotId, String product, AllowanceType type, YearMonth period, long amount,
                         boolean billable, Instant recordedAt) {

    public UsageEntry {
        Licence.requireId(slotId, "slot ID");
        Licence.requireProductCode(product);
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (amount < 0) {
            throw new UsageException(UsageError.USAGE_NEGATIVE_AMOUNT, "Amount " + amount + " is below zero.");
        }
    }
}
