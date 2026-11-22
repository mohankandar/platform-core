package com.documentplatform.identity.store;

import com.documentplatform.identity.IdentityProviderType;

import java.net.URI;
import java.util.Objects;

/**
 * The values of a login connection the IT Admin edits in a draft: everything but its ID, customer, product, status
 * and version. Holds no secret: {@code secretReference} only names where one is kept, or is null.
 */
public record ConnectionValues(String providerId, IdentityProviderType providerType, URI issuer,
                               String browserClientId, String apiAudience, String secretReference) {

    public ConnectionValues {
        Objects.requireNonNull(providerType, "providerType");
        Objects.requireNonNull(issuer, "issuer");
    }

    /** The values of a stored connection. */
    public static ConnectionValues of(LoginConnection c) {
        return new ConnectionValues(c.providerId(), c.providerType(), c.issuer(), c.browserClientId(), c.apiAudience(),
                c.secretReference());
    }

    /** Whether two connections carry the same values (ID, status and version aside). */
    public static boolean same(LoginConnection a, LoginConnection b) {
        return of(a).equals(of(b));
    }

    @Override
    public String toString() {
        return "ConnectionValues[provider=" + providerId + ", issuer=" + issuer + ", audience=" + apiAudience + "]";
    }
}
