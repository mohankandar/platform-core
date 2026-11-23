package com.documentplatform.identity.signin;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The rule for login issuers (shared by the setup command and the login-connection operations):
 * <ul>
 *   <li>an {@code https} issuer as before: absolute, with a host, no query, fragment or user part;</li>
 *   <li>an {@code http} issuer only for a local test login server: its host is exactly {@code localhost},
 *       {@code 127.0.0.1} or {@code [::1]}, <b>and</b> the licence in force is signed by a development key (a key
 *       marked {@code "development": true} in {@code trusted-keys.json}). Never on a production licence.</li>
 * </ul>
 */
public final class LocalTestIssuers {

    /** The hosts of a local test login server, as {@link URI#getHost()} gives them. */
    public static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** What is wrong with an issuer address. */
    public enum Problem implements com.documentplatform.errors.PlatformCode {
        /** Not an absolute https (or http) address with a host, or it has a query, fragment or user part. */
        NOT_AN_ISSUER,
        /** http, but not on this machine or not under a development licence. */
        HTTP_NOT_ALLOWED
    }

    private LocalTestIssuers() {
    }

    /** Whether the address is plain http on {@code localhost}, {@code 127.0.0.1} or {@code [::1]}. */
    public static boolean isLocalHttp(URI uri) {
        return uri != null && "http".equals(uri.getScheme()) && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    /**
     * Checks an issuer address.
     *
     * @param developmentLicence whether the licence in force is signed by a development key
     * @return empty when the issuer may be used
     */
    public static Optional<Problem> check(URI issuer, boolean developmentLicence) {
        if (issuer == null || !issuer.isAbsolute() || issuer.getHost() == null || issuer.getRawQuery() != null
                || issuer.getRawFragment() != null || issuer.getRawUserInfo() != null
                || issuer.toString().length() > 2000) {
            return Optional.of(Problem.NOT_AN_ISSUER);
        }
        String scheme = issuer.getScheme();
        if ("https".equals(scheme)) return Optional.empty();
        if (!"http".equals(scheme)) return Optional.of(Problem.NOT_AN_ISSUER);
        return isLocalHttp(issuer) && developmentLicence ? Optional.empty() : Optional.of(Problem.HTTP_NOT_ALLOWED);
    }
}
