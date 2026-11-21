package com.documentplatform.licensing;

/**
 * The kinds of allowance a licence can grant an entity slot. Each product uses the types it needs. The unit is part
 * of the type, so a licence and a usage record can never disagree on it.
 */
public enum AllowanceType {
    /** Pages processed in a calendar month. Monthly usage is the sum of the amounts recorded. */
    PAGES_PER_MONTH("pages", Aggregation.SUM),
    /** Storage in use, in gigabytes (10^9 bytes). Monthly usage is the highest reading recorded. */
    STORAGE("GB", Aggregation.PEAK),
    /** Seats in use at the same time. The app records the daily peak; monthly usage is the highest reading. */
    CONCURRENT_SEATS("seats", Aggregation.PEAK),
    /** Seats assigned to named users. Monthly usage is the highest reading recorded. */
    NAMED_SEATS("seats", Aggregation.PEAK);

    /** How the readings of one month combine into the month's usage. */
    public enum Aggregation { SUM, PEAK }

    private final String unit;
    private final Aggregation aggregation;

    AllowanceType(String unit, Aggregation aggregation) {
        this.unit = unit;
        this.aggregation = aggregation;
    }

    public String unit() {
        return unit;
    }

    public Aggregation aggregation() {
        return aggregation;
    }
}
