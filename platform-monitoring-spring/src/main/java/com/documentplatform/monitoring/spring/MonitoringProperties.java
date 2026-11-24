package com.documentplatform.monitoring.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The monitoring settings ({@code platform.monitoring.*}).
 *
 * <pre>
 * platform.monitoring.enabled=true
 * platform.monitoring.every=60s                    # how often each check runs (a check may say otherwise)
 * platform.monitoring.startup-checks=true          # refuse to start while a required check is DOWN
 * platform.monitoring.customer-keys-required=false # the product's customerKeys is "required": key service required
 * platform.monitoring.product-code=PRODUCT_A       # the login-system check's product
 * platform.monitoring.tenant-id=TEN-…              # defaults to platform.licensing.tenant-id
 * platform.monitoring.secrets=app/db-password,app/smtp-password   # references whose secrets must resolve
 * platform.monitoring.folders.export.path=/srv/export
 * platform.monitoring.folders.export.writable=true
 * platform.monitoring.folders.export.warn-at=90
 * platform.monitoring.folders.export.down-at=95
 * platform.monitoring.folders.export.required=false
 * platform.monitoring.services.ocr.url=http://ocr.internal:8080/health
 * platform.monitoring.services.ocr.expected-status=200
 * platform.monitoring.services.ocr.timeout=5s
 * platform.monitoring.services.ocr.required=false
 * </pre>
 */
@ConfigurationProperties(prefix = MonitoringProperties.PREFIX)
public class MonitoringProperties {

    public static final String PREFIX = "platform.monitoring";

    private boolean enabled = true;
    private Duration every = Duration.ofSeconds(60);
    private Duration tick = Duration.ofSeconds(5);
    private boolean startupChecks = true;
    private boolean customerKeysRequired = false;
    private String productCode;
    private String tenantId;
    private List<String> secrets = new ArrayList<>();
    /** More property name prefixes the diagnostic bundle may hold (P045, SEC-11), for example "myapp.". */
    private List<String> bundleProperties = new ArrayList<>();
    private Map<String, Folder> folders = new LinkedHashMap<>();
    private Map<String, Service> services = new LinkedHashMap<>();

    public List<String> getBundleProperties() {
        return bundleProperties;
    }

    public void setBundleProperties(List<String> bundleProperties) {
        this.bundleProperties = bundleProperties;
    }

    public static class Folder {
        private String path;
        private boolean writable = true;
        private int warnAt = 90;
        private int downAt = 95;
        private boolean required = false;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isWritable() {
            return writable;
        }

        public void setWritable(boolean writable) {
            this.writable = writable;
        }

        public int getWarnAt() {
            return warnAt;
        }

        public void setWarnAt(int warnAt) {
            this.warnAt = warnAt;
        }

        public int getDownAt() {
            return downAt;
        }

        public void setDownAt(int downAt) {
            this.downAt = downAt;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    public static class Service {
        private URI url;
        private int expectedStatus = 200;
        private Duration timeout = Duration.ofSeconds(5);
        private boolean required = false;

        public URI getUrl() {
            return url;
        }

        public void setUrl(URI url) {
            this.url = url;
        }

        public int getExpectedStatus() {
            return expectedStatus;
        }

        public void setExpectedStatus(int expectedStatus) {
            this.expectedStatus = expectedStatus;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getEvery() {
        return every;
    }

    public void setEvery(Duration every) {
        this.every = every;
    }

    public Duration getTick() {
        return tick;
    }

    public void setTick(Duration tick) {
        this.tick = tick;
    }

    public boolean isStartupChecks() {
        return startupChecks;
    }

    public void setStartupChecks(boolean startupChecks) {
        this.startupChecks = startupChecks;
    }

    public boolean isCustomerKeysRequired() {
        return customerKeysRequired;
    }

    public void setCustomerKeysRequired(boolean customerKeysRequired) {
        this.customerKeysRequired = customerKeysRequired;
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

    public List<String> getSecrets() {
        return secrets;
    }

    public void setSecrets(List<String> secrets) {
        this.secrets = secrets;
    }

    public Map<String, Folder> getFolders() {
        return folders;
    }

    public void setFolders(Map<String, Folder> folders) {
        this.folders = folders;
    }

    public Map<String, Service> getServices() {
        return services;
    }

    public void setServices(Map<String, Service> services) {
        this.services = services;
    }
}
