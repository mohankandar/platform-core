package com.documentplatform.licensing.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The install config of licensing, prefix {@code platform.licensing}. Checked at startup by
 * {@link LicensingConfig#from}, which stops the app with a specific message for each wrong value. Only public keys
 * go here; no secret is ever needed on the app side.
 */
@ConfigurationProperties(prefix = LicensingProperties.PREFIX)
public class LicensingProperties {

    public static final String PREFIX = "platform.licensing";

    /** Switches the whole module off when false. */
    private boolean enabled = true;

    /** The public keys licences may be signed with: key ID → Base64 X.509 Ed25519 public key. Keep old keys after a rotation. */
    private Map<String, String> trustedKeys = new LinkedHashMap<>();

    /** This app's product code, as listed in licences (for example PRODUCT_A). */
    private String productCode;

    /** This installation's tenant ID. Optional: when empty, the tenant of the stored licence is used. */
    private String tenantId;

    /** How far the server clock may be behind the latest time seen before it is flagged. */
    private Duration clockTolerance = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getTrustedKeys() {
        return trustedKeys;
    }

    public void setTrustedKeys(Map<String, String> trustedKeys) {
        this.trustedKeys = trustedKeys;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public Duration getClockTolerance() {
        return clockTolerance;
    }

    public void setClockTolerance(Duration clockTolerance) {
        this.clockTolerance = clockTolerance;
    }
}
