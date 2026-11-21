package com.documentplatform.licensing.usage;

/**
 * Where usage stands against an allowance. Reported only: the module never stops work at any level, and overage is
 * recorded like any other usage.
 */
public enum UsageLevel {
    /** Below 80%. */
    WITHIN,
    /** 80% or more, below 100%. */
    NEAR_LIMIT,
    /** Exactly 100%. */
    AT_LIMIT,
    /** Above 100% (also any use where the slot has no allowance for it). */
    OVER_LIMIT;

    /** @param limit the allowance amount, or 0 when there is no allowance */
    public static UsageLevel of(long used, long limit) {
        if (used < 0 || limit < 0) {
            throw new IllegalArgumentException("usage and limit must not be negative");
        }
        if (used > limit) {
            return OVER_LIMIT;
        }
        if (limit == 0) {
            return WITHIN; // nothing used, nothing allowed
        }
        if (used == limit) {
            return AT_LIMIT;
        }
        // used >= ceil(0.8 * limit), without overflow
        return used >= limit - limit / 5 ? NEAR_LIMIT : WITHIN;
    }
}
