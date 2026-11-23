package com.documentplatform.identity.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The product's push-API caller settings ({@code platform.identity.push.*}). The {@code PushCaller} bean is there when
 * {@code product-code} is set. No product's claim names are built in: set them here.
 *
 * <pre>
 * platform.identity.push.product-code=PRODUCT_A
 * platform.identity.push.client-id-claims=client_id,azp,cid,sub   # the default; first present wins
 * platform.identity.push.entity-claim=                             # optional; must equal the binding when present
 * platform.identity.push.source-claim=                             # optional; must equal the binding when present
 * platform.identity.push.require-token-and-certificate=false
 * platform.identity.push.cache-for=30s                             # 0s to 10m; a binding switched off counts within it
 * </pre>
 */
@ConfigurationProperties(prefix = PushCallerProperties.PREFIX)
public class PushCallerProperties {

    public static final String PREFIX = "platform.identity.push";

    private String productCode;
    private List<String> clientIdClaims = new ArrayList<>(List.of("client_id", "azp", "cid", "sub"));
    private String entityClaim;
    private String sourceClaim;
    private boolean requireTokenAndCertificate = false;
    private Duration cacheFor = Duration.ofSeconds(30);

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public List<String> getClientIdClaims() {
        return clientIdClaims;
    }

    public void setClientIdClaims(List<String> clientIdClaims) {
        this.clientIdClaims = clientIdClaims;
    }

    public String getEntityClaim() {
        return entityClaim;
    }

    public void setEntityClaim(String entityClaim) {
        this.entityClaim = entityClaim;
    }

    public String getSourceClaim() {
        return sourceClaim;
    }

    public void setSourceClaim(String sourceClaim) {
        this.sourceClaim = sourceClaim;
    }

    public boolean isRequireTokenAndCertificate() {
        return requireTokenAndCertificate;
    }

    public void setRequireTokenAndCertificate(boolean requireTokenAndCertificate) {
        this.requireTokenAndCertificate = requireTokenAndCertificate;
    }

    public Duration getCacheFor() {
        return cacheFor;
    }

    public void setCacheFor(Duration cacheFor) {
        this.cacheFor = cacheFor;
    }
}
