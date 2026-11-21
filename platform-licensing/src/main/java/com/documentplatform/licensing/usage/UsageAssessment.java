package com.documentplatform.licensing.usage;

import com.documentplatform.licensing.AllowanceType;

import java.time.YearMonth;

/**
 * Usage of one allowance in one month, for the app's screens and notifications.
 *
 * @param limit the allowance amount; 0 when the slot has no allowance for this product and type
 */
public record UsageAssessment(String slotId, String product, AllowanceType type, YearMonth period, long used,
                              long limit, UsageLevel level) {

    /** Whole percent of the allowance used (rounded down); empty meaning when {@code limit} is 0. */
    public long percentUsed() {
        return limit == 0 ? 0 : (long) Math.floor(used * 100.0 / limit);
    }

    public boolean hasAllowance() {
        return limit > 0;
    }
}
