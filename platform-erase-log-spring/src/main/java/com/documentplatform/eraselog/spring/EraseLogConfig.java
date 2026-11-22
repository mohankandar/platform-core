package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseRetention;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The checked erase log settings. {@link #from} lists every problem at once and stops the app
 * ({@link EraseLogConfigException}).
 */
public record EraseLogConfig(Path location, EraseRetention retention, Duration recheck, boolean exitAfterReplay) {

    public EraseLogConfig {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(recheck, "recheck");
    }

    public static EraseLogConfig from(EraseLogProperties p) {
        String prefix = EraseLogProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();
        Path location = null;
        if (p.getLocation() == null || p.getLocation().isBlank()) {
            problems.add(prefix + "location is required: a directory outside the database's storage and its backups, "
                    + "backed up on its own");
        } else {
            try {
                location = Path.of(p.getLocation().strip());
                if (!location.isAbsolute()) {
                    problems.add(prefix + "location must be an absolute path");
                }
            } catch (InvalidPathException e) {
                problems.add(prefix + "location is not a valid path");
            }
        }
        Integer days = p.getBackupRetentionDays();
        if (days == null || days < EraseRetention.MIN_DAYS || days > EraseRetention.MAX_DAYS) {
            problems.add(prefix + "backup-retention-days must be " + EraseRetention.MIN_DAYS + " to "
                    + EraseRetention.MAX_DAYS + " (the installation's backup retention; default "
                    + EraseRetention.DEFAULT_DAYS + ")");
        }
        Duration recheck = p.getRecheck();
        if (recheck == null || recheck.compareTo(Duration.ofSeconds(5)) < 0 || recheck.compareTo(Duration.ofHours(1)) > 0) {
            problems.add(prefix + "recheck must be 5 seconds to 1 hour");
        }
        if (!problems.isEmpty()) {
            throw new EraseLogConfigException(problems);
        }
        return new EraseLogConfig(location, new EraseRetention(days), recheck, p.isExitAfterReplay());
    }
}
