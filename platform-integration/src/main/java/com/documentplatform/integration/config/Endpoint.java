package com.documentplatform.integration.config;

import java.net.URI;
import java.util.Objects;

/**
 * Where the destination (or source) is.
 *
 * @param baseAddress https address (plain http only on premises with internal addresses allowed), with an optional base
 *                    path; no query, fragment, user info or placeholders. Step paths are added to it.
 * @param useProxy    whether calls go through the installation's outbound proxy (when one is set)
 */
public record Endpoint(URI baseAddress, boolean useProxy) {

    public Endpoint {
        Objects.requireNonNull(baseAddress, "baseAddress");
        String scheme = baseAddress.getScheme();
        if (!baseAddress.isAbsolute() || baseAddress.getHost() == null || baseAddress.getRawQuery() != null
                || baseAddress.getRawFragment() != null || baseAddress.getRawUserInfo() != null
                || baseAddress.toString().contains("{") || scheme == null
                || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            // The address is not repeated: a token in it would end up in the exception text.
            throw new IllegalArgumentException("Base address is not valid (absolute http(s) address, no query, user "
                    + "info or placeholders)");
        }
    }

    public static Endpoint of(String baseAddress) {
        return new Endpoint(URI.create(baseAddress), true);
    }

    public boolean secure() {
        return baseAddress.getScheme().equalsIgnoreCase("https");
    }
}
