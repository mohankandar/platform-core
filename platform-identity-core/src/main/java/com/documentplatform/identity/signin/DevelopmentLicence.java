package com.documentplatform.identity.signin;

/**
 * Whether the licence in force is signed by a development key (marked {@code "development": true} in the release's
 * {@code trusted-keys.json}). Only then may a login connection use an {@code http} issuer on this machine
 * ({@link LocalTestIssuers}). identity-jdbc's {@code LicenceSigningKey} answers it from the licence service; an app
 * without one uses {@link #NONE}.
 */
@FunctionalInterface
public interface DevelopmentLicence {

    /** No development licence: http issuers are always refused. */
    DevelopmentLicence NONE = () -> false;

    boolean signedByDevelopmentKey();
}
