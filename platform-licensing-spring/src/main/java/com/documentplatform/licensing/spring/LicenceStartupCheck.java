package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStatus;
import org.springframework.beans.factory.InitializingBean;

/**
 * Reads the stored licence once while the app starts. A refusal ({@code LicenceException}: no licence ever stored,
 * unreadable, forged, tampered, wrong customer, unknown key ID) stops the start with its code and what to do. Every
 * state, including expired after grace, and a server clock behind, lets the app start; they are logged.
 */
final class LicenceStartupCheck implements InitializingBean {

    private static final System.Logger LOG = System.getLogger(LicenceStartupCheck.class.getName());

    private final LicenceService service;

    LicenceStartupCheck(LicenceService service) {
        this.service = service;
    }

    @Override
    public void afterPropertiesSet() {
        LicenceStatus s = service.status();
        System.Logger.Level level = s.intakeAllowed() && !s.clockBehind() ? System.Logger.Level.INFO
                : System.Logger.Level.WARNING;
        LOG.log(level, "Licence {0} v{1}: {2}, {3}. {4}", s.licence().licenceId(), s.licence().version(),
                s.licence().kind(), s.state(), s.message());
        s.clock().message().ifPresent(m -> LOG.log(System.Logger.Level.WARNING, m));
        if (!service.productLicensed(s)) {
            LOG.log(System.Logger.Level.WARNING, "The licence does not list this app's product {0}: intake is stopped.",
                    service.productCode().orElse("?"));
        }
    }
}
