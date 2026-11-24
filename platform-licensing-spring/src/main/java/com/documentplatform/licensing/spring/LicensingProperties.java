package com.documentplatform.licensing.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The install config of licensing, prefix {@code platform.licensing}. Checked at startup, which stops the app with a
 * specific message for each wrong value. The trusted keys are inside the app's build ({@code LicenceBuild}), never here.
 */
@ConfigurationProperties(prefix = LicensingProperties.PREFIX)
public class LicensingProperties {

    public static final String PREFIX = "platform.licensing";

    /** Switches the whole module off when false. */
    private boolean enabled = true;

    /** This app's product code, as listed in licences (for example PRODUCT_A). */
    private String productCode;

    /** This installation's customer (tenant) ID, as in the licence (TEN-...). Required; the setup command writes it. */
    private String tenantId;

    /** How far the server clock may be behind the latest time seen before it is flagged. */
    private Duration clockTolerance = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    /** Licensing notifications; used only when the app has platform-notifications. */
    private final Notifications notifications = new Notifications();

    public Notifications getNotifications() {
        return notifications;
    }

    /** {@code platform.licensing.notifications.*}, checked by {@code LicensingNotificationsConfig}. */
    public static class Notifications {

        /** Switches licensing notifications off when false (licensing itself keeps working). */
        private boolean enabled = true;

        /** How often the licence state and usage are checked (1 minute to 24 hours). */
        private Duration checkInterval = Duration.ofHours(1);

        /** Where messages point in the app, as a path (for example /admin/licence). Optional. */
        private String link;

        /** This app version, shown in "version not covered by licence updates" (for example 2.4.0). Optional. */
        private String releaseVersion;

        /** This app version's release date (YYYY-MM-DD); without it "version not covered" is not checked. */
        private String releaseDate;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCheckInterval() {
            return checkInterval;
        }

        public void setCheckInterval(Duration checkInterval) {
            this.checkInterval = checkInterval;
        }

        public String getLink() {
            return link;
        }

        public void setLink(String link) {
            this.link = link;
        }

        public String getReleaseVersion() {
            return releaseVersion;
        }

        public void setReleaseVersion(String releaseVersion) {
            this.releaseVersion = releaseVersion;
        }

        public String getReleaseDate() {
            return releaseDate;
        }

        public void setReleaseDate(String releaseDate) {
            this.releaseDate = releaseDate;
        }
    }
}
