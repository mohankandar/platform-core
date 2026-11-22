package com.documentplatform.integration.safety;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The installation's outbound proxy (HTTP CONNECT for https). Integrations that use the proxy send every call,
 * including token requests, through it, except to the hosts listed as bypassing it.
 *
 * @param host   proxy host name or address
 * @param port   proxy port
 * @param bypass host names that do not use the proxy: exact ({@code api.internal}) or a suffix ({@code .internal})
 */
public record ProxySettings(String host, int port, List<String> bypass) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");
    private static final Pattern BYPASS = Pattern.compile("\\.?[A-Za-z0-9.-]{1,253}");

    public ProxySettings {
        Objects.requireNonNull(host, "host");
        if (!HOST.matcher(host).matches()) throw new IllegalArgumentException("Proxy host is not valid");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Proxy port is not valid");
        bypass = bypass == null ? List.of() : List.copyOf(bypass);
        for (String b : bypass) {
            if (b == null || !BYPASS.matcher(b).matches()) {
                throw new IllegalArgumentException("Proxy bypass entry is not valid");
            }
        }
    }

    public static ProxySettings of(String host, int port) {
        return new ProxySettings(host, port, List.of());
    }

    /** Whether calls to {@code targetHost} skip the proxy. */
    public boolean bypasses(String targetHost) {
        String h = targetHost.toLowerCase(Locale.ROOT);
        for (String b : bypass) {
            String e = b.toLowerCase(Locale.ROOT);
            if (e.startsWith(".") ? h.endsWith(e) || h.equals(e.substring(1)) : h.equals(e)) return true;
        }
        return false;
    }
}
