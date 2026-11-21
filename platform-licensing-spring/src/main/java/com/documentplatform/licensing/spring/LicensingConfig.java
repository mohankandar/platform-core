package com.documentplatform.licensing.spring;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.TrustedKeys;

import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The checked licensing config. */
public record LicensingConfig(TrustedKeys trustedKeys, String productCode, String tenantId, Duration clockTolerance) {

    /** @throws LicensingConfigException listing every problem */
    public static LicensingConfig from(LicensingProperties p) {
        String prefix = LicensingProperties.PREFIX + ".";
        List<String> problems = new ArrayList<>();
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        if (p.getTrustedKeys() == null || p.getTrustedKeys().isEmpty()) {
            problems.add(prefix + "trusted-keys is empty: give at least one key ID with its Base64 Ed25519 public key "
                    + "(from the keys file shipped with this release)");
        } else {
            p.getTrustedKeys().forEach((id, value) -> {
                try {
                    Licence.requireKeyId(id);
                } catch (IllegalArgumentException e) {
                    problems.add(prefix + "trusted-keys: key ID '" + id.replaceAll("[^A-Za-z0-9._:-]", "?")
                            + "' is not valid: " + e.getMessage());
                    return;
                }
                try {
                    keys.put(id, Ed25519.decodePublic(value == null ? "" : value.strip()));
                } catch (IllegalArgumentException e) {
                    problems.add(prefix + "trusted-keys." + id + " is not a Base64 X.509 Ed25519 public key");
                }
            });
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
        if (tenant != null) {
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
        return new LicensingConfig(TrustedKeys.of(keys), product, tenant, tolerance);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
