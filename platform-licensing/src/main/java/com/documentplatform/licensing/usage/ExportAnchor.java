package com.documentplatform.licensing.usage;

import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the next usage export continues from: the previous file's sequence number, final chain value and last
 * period. The app keeps the anchor of the last file it exported ({@link UsageExport#nextAnchor()}).
 *
 * @param periodTo the previous file's last month; null for {@link #first()}
 */
public record ExportAnchor(long sequence, String finalChainValue, YearMonth periodTo) {

    public ExportAnchor {
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(finalChainValue, "finalChainValue");
        if (!finalChainValue.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("finalChainValue must be 64 lower-case hex characters");
        }
        if ((sequence == 0) != (periodTo == null)) {
            throw new IllegalArgumentException("only the first anchor (sequence 0) has no period");
        }
    }

    /** The anchor of the very first export: sequence 0, the genesis value, no period before it. */
    public static ExportAnchor first() {
        return new ExportAnchor(0, UsageChain.GENESIS, null);
    }

    public Optional<YearMonth> previousPeriodTo() {
        return Optional.ofNullable(periodTo);
    }
}
