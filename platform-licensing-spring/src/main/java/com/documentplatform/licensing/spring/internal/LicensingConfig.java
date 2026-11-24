package com.documentplatform.licensing.spring.internal;

import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.spring.LicensingProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The checked licensing config. Internal: read by the licensing and identity auto-configurations; apps set
 * {@code platform.licensing.*} ({@link LicensingProperties}) and use {@code LicenceService}.
 */
public record LicensingConfig(TrustedKeys trustedKeys, Instant builtAt, String productCode, String tenantId,
                              Duration clockTolerance) {

    /** @throws LicensingConfigException listing every problem */
    public static LicensingConfig from(LicensingProperties p, Supplier<LicenceBuild> build, List<String> removed) {
        String prefix = LicensingProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();
        removed.forEach(name -> problems.add(name + " is no longer a setting: the trusted keys come from the app's own "
                + "build and cannot be set at an installation; remove it"));
        LicenceBuild built = null;
        try {
            built = build.get();
        } catch (LicenceBuild.Invalid e) {
            problems.add(e.getMessage());
        }
        String product = blankToNull(p.getProductCode());
        if (product == null) {
            problems.add(prefix + "product-code is missing: give this app's product code as listed in licences");
        } else {
            try {
                Licence.requireProductCode(product);
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "product-code: " + e.getMessage());
            }
        }
        String tenant = blankToNull(p.getTenantId());
        if (tenant == null) {
            problems.add(prefix + "tenant-id is missing: give this installation's customer ID (TEN-...) as in the "
                    + "licence; the setup command writes it into the config fragment it creates "
                    + "(platform-tenant.properties)");
        } else {
            try {
                Licence.requireId(tenant, "tenant ID");
            } catch (IllegalArgumentException e) {
                problems.add(prefix + "tenant-id: " + e.getMessage());
            }
        }
        Duration tolerance = p.getClockTolerance();
        if (tolerance == null || tolerance.isNegative()) {
            problems.add(prefix + "clock-tolerance must be zero or more (for example 1h)");
        } else if (tolerance.compareTo(Duration.ofDays(1)) > 0) {
            problems.add(prefix + "clock-tolerance must be at most 1 day; it is " + tolerance);
        }
        if (!problems.isEmpty()) {
            throw new LicensingConfigException(problems);
        }
        return new LicensingConfig(built.keys(), built.builtAt(), product, tenant, tolerance);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
