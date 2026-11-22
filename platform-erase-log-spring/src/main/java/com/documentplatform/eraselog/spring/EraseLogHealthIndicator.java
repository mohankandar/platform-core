package com.documentplatform.eraselog.spring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLogError;
import com.documentplatform.eraselog.EraseRetention;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * {@code /actuator/health/eraseLog}: UP when the log is reachable, its chain intact and the database in step;
 * OUT_OF_SERVICE after a restore until the replay has verified; DOWN for the other problems. Details: the log's ID and
 * location, its last entry, the database's applied position, entries pending, the code and message, the retention.
 * No values. Uses the guard's last state (the startup check, then the recheck), so a health call reads nothing.
 */
public final class EraseLogHealthIndicator extends AbstractHealthIndicator {

    private final EraseGuard guard;
    private final EraseRetention retention;
    private final String location;

    public EraseLogHealthIndicator(EraseGuard guard, EraseRetention retention, String location) {
        super("Erase log health check failed");
        this.guard = guard;
        this.retention = retention;
        this.location = location;
    }

    @Override
    protected void doHealthCheck(Health.Builder b) {
        EraseGuard.State s = guard.state();
        if (s.mayTakeWork()) {
            b.up();
        } else if (s.code() == EraseLogError.ERASE_LOG_RESTORE_DETECTED) {
            b.status(Status.OUT_OF_SERVICE);
        } else {
            b.down();
        }
        b.withDetail("mayTakeWork", s.mayTakeWork());
        if (s.code() != null) {
            b.withDetail("code", s.code().name());
        }
        b.withDetail("message", s.message());
        if (s.logId() != null) {
            b.withDetail("logId", s.logId().toString());
        }
        b.withDetail("location", location);
        b.withDetail("logHead", s.logHead());
        if (s.applied() >= 0) {
            b.withDetail("applied", s.applied());
            b.withDetail("pending", s.pending());
        }
        b.withDetail("backupRetentionDays", retention.backupRetentionDays());
        b.withDetail("logRetentionDays", retention.logRetentionDays());
        if (s.checkedAt() != null) {
            b.withDetail("checkedAt", s.checkedAt().toString());
        }
    }
}
