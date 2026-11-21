package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;

import java.time.YearMonth;
import java.util.Collection;

/** Combines the readings of one month into the month's usage, per {@link AllowanceType#aggregation()}. */
public final class UsageTotals {

    private UsageTotals() {
    }

    public static long aggregate(Collection<UsageRecord> records, String slotId, String product, AllowanceType type,
                                 YearMonth period, boolean billableOnly) {
        long total = 0;
        for (UsageRecord r : records) {
            if (r.slotId().equals(slotId) && r.product().equals(product) && r.type() == type
                    && r.period().equals(period) && (!billableOnly || r.billable())) {
                total = type.aggregation() == AllowanceType.Aggregation.SUM
                        ? Math.addExact(total, r.amount())
                        : Math.max(total, r.amount());
            }
        }
        return total;
    }
}
