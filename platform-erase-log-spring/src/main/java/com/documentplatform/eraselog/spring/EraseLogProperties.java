package com.documentplatform.eraselog.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The install's erase log settings ({@code platform.erase-log.*}), checked at startup by {@link EraseLogConfig#from}.
 *
 * <pre>
 * platform.erase-log.location=/mnt/erase-log/<app>         # required: a directory outside the database's storage
 * platform.erase-log.backup-retention-days=35              # 30-90, the installation's backup retention
 * platform.erase-log.recheck=PT1M                          # how often a blocked guard looks again
 * platform.erase-log.exit-after-replay=true                # --erase-replay runs the replay, then the app exits
 * </pre>
 */
@ConfigurationProperties(prefix = EraseLogProperties.PREFIX)
public class EraseLogProperties {

    public static final String PREFIX = "platform.erase-log";

    /** Switches the module off when false. */
    private boolean enabled = true;
    /** The log's directory: outside the database's storage and its backups, backed up on its own. */
    private String location;
    /** The installation's backup retention in days (30-90). The log keeps entries 30 days longer. */
    private Integer backupRetentionDays = 35;
    /** How often the guard looks again while it blocks work (to lift after the replay), and the cheap recheck. */
    private Duration recheck = Duration.ofMinutes(1);
    /** With {@code --erase-replay}: exit after the replay (exit code 0 passed, 1 not). */
    private boolean exitAfterReplay = true;
    /**
     * Whether the log is keyed (P044, SEC-3). Unset: keyed whenever the app has the key service; {@code true}: refuse
     * to start without it; {@code false}: unkeyed (not recommended).
     */
    private Boolean keyed;

    public Boolean getKeyed() {
        return keyed;
    }

    public void setKeyed(Boolean keyed) {
        this.keyed = keyed;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public Integer getBackupRetentionDays() {
        return backupRetentionDays;
    }

    public void setBackupRetentionDays(Integer backupRetentionDays) {
        this.backupRetentionDays = backupRetentionDays;
    }

    public Duration getRecheck() {
        return recheck;
    }

    public void setRecheck(Duration recheck) {
        this.recheck = recheck;
    }

    public boolean isExitAfterReplay() {
        return exitAfterReplay;
    }

    public void setExitAfterReplay(boolean exitAfterReplay) {
        this.exitAfterReplay = exitAfterReplay;
    }
}
