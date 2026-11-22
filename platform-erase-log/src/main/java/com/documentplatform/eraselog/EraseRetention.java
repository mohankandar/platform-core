package com.documentplatform.eraselog;

import java.time.Duration;

/**
 * How long backups are kept, and so how long the erase log keeps its entries: at least the backup retention plus
 * {@value #MARGIN_DAYS} days (default 35 + 30 = 65 days). After that every backup an entry could apply to is gone,
 * and the entry is pruned.
 *
 * @param backupRetentionDays the installation's backup retention, {@value #MIN_DAYS}–{@value #MAX_DAYS} days
 *                            (default {@value #DEFAULT_DAYS}), set by contract
 */
public record EraseRetention(int backupRetentionDays) {

    public static final int DEFAULT_DAYS = 35;
    public static final int MIN_DAYS = 30;
    public static final int MAX_DAYS = 90;
    public static final int MARGIN_DAYS = 30;

    public static final EraseRetention DEFAULT = new EraseRetention(DEFAULT_DAYS);

    public EraseRetention {
        if (backupRetentionDays < MIN_DAYS || backupRetentionDays > MAX_DAYS) {
            throw new EraseLogException(EraseLogError.ERASE_LOG_SETTINGS_INVALID, "the backup retention is "
                    + MIN_DAYS + " to " + MAX_DAYS + " days, not " + backupRetentionDays);
        }
    }

    /** How long the log keeps an entry: backup retention + {@value #MARGIN_DAYS} days. */
    public Duration logRetention() {
        return Duration.ofDays(logRetentionDays());
    }

    public int logRetentionDays() {
        return backupRetentionDays + MARGIN_DAYS;
    }
}
