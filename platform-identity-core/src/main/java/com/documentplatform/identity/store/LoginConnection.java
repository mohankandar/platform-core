package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A customer's login-system connection for one product ({@code idn_login_connection}). All entities of the customer
 * share it. Holds no secret: {@code secretReference} only names where a secret is kept (for example
 * {@code vault:kv/customers/TEN-1/login#client_secret}) and is null when no secret is needed (direct sign-in).
 */
public record LoginConnection(UUID connectionId, String tenantId, String productCode, String providerId,
                              IdentityProviderType providerType, URI issuer, String browserClientId,
                              String apiAudience, String secretReference, IdentityConnectionStatus status,
                              long configurationVersion) {

    private static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    /** A reference has a scheme and no spaces: {@code vault:kv/path#field}. A bare value is refused. */
    private static final Pattern REFERENCE = Pattern.compile("[a-z][a-z0-9+.-]*:\\S+");

    public LoginConnection {
        Objects.requireNonNull(connectionId, "connectionId");
        EntityUuid.requireTenantId(tenantId);
        requireProductCode(productCode);
        providerId = requireText(providerId, "providerId");
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(issuer, "issuer");
        if (!issuer.isAbsolute() || issuer.getHost() == null) {
            throw new IllegalArgumentException("issuer must be an absolute URI");
        }
        if (browserClientId != null) browserClientId = requireText(browserClientId, "browserClientId");
        apiAudience = requireText(apiAudience, "apiAudience");
        if (secretReference != null
                && (secretReference.length() > 500 || !REFERENCE.matcher(secretReference).matches())) {
            throw new IllegalArgumentException("secretReference must be a reference such as vault:kv/path#field, "
                    + "never the secret itself");
        }
        Objects.requireNonNull(status, "status");
        if (configurationVersion < 1) throw new IllegalArgumentException("configurationVersion must be positive");
    }

    /** Product codes as in the licence: 2 to 32 of A-Z 0-9 _, starting with a letter. */
    public static String requireProductCode(String code) {
        if (code == null || !PRODUCT.matcher(code).matches()) {
            throw new IllegalArgumentException("product code must be 2 to 32 of A-Z 0-9 _ (starting with a letter)");
        }
        return code;
    }

    /** The same connection at another version. */
    public LoginConnection withVersion(long version) {
        return new LoginConnection(connectionId, tenantId, productCode, providerId, providerType, issuer,
                browserClientId, apiAudience, secretReference, status, version);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " starts or ends with a space");
        return value;
    }
}
