package com.documentplatform.monitoring.checks;

import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.util.Map;
import java.util.Objects;

/**
 * The licence in force, read only, never enforcing: missing, signature invalid ({@code LICENCE_TAMPERED},
 * {@code LICENCE_FORGED}, …), another customer's ({@code LICENCE_WRONG_CUSTOMER}), not started, expiring (with the
 * days left), in grace, expired. Licensing itself decides what is allowed; this only reports.
 */
public final class LicenceCheck implements DependencyCheck {

    private final LicenceService licences;

    public LicenceCheck(LicenceService licences) {
        this.licences = Objects.requireNonNull(licences, "licences");
    }

    @Override
    public String name() {
        return "licence";
    }

    @Override
    public Verdict check() {
        LicenceStatus s;
        try {
            s = licences.status();
        } catch (LicenceException e) {
            return Verdict.down(e.code());
        }
        String ends = s.licence().expiresOn() == null ? "-" : s.licence().expiresOn().toString();
        return switch (s.state()) {
            case VALID -> Verdict.working();
            case NOT_STARTED -> Verdict.down(MonitoringCode.MON_LICENCE_NOT_STARTED,
                    Map.of("startsOn", s.licence().startsOn().toString()));
            case EXPIRING -> Verdict.problems(MonitoringCode.MON_LICENCE_EXPIRING, Map.of("expiresOn", ends,
                    "days", String.valueOf(s.daysUntilExpiry().orElse(0))));
            case EXPIRED_IN_GRACE -> Verdict.problems(MonitoringCode.MON_LICENCE_IN_GRACE, Map.of("expiresOn", ends,
                    "days", String.valueOf(s.graceDaysLeft().orElse(0))));
            case EXPIRED_GRACE_OVER -> Verdict.down(MonitoringCode.MON_LICENCE_EXPIRED, Map.of("expiresOn", ends));
        };
    }
}
