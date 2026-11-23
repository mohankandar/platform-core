package com.documentplatform.outbound;

import com.documentplatform.notifications.secret.SecretRef;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The installation's outbound proxy (HTTP CONNECT for https). Integrations that use the proxy send every call,
 * including token requests, through it, except to the hosts listed as bypassing it.
 *
 * @param host     proxy host name or address
 * @param port     proxy port
 * @param bypass   host names that do not use the proxy: exact ({@code api.internal}) or a suffix ({@code .internal})
 * @param user     the proxy sign-in's user name (Basic), or null when the proxy needs no sign-in
 * @param password where the proxy password is kept in the secret store (a reference, never the password); set exactly
 *                 when {@code user} is
 */
public record ProxySettings(String host, int port, List<String> bypass, String user, SecretRef password) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:\\[\\]-]{1,253}");
    private static final Pattern BYPASS = Pattern.compile("\\.?[A-Za-z0-9.-]{1,253}");
    /** Basic sign-in: no ':' (it separates user and password) and no control characters. */
    private static final Pattern USER = Pattern.compile("[^:\\p{Cntrl}]{1,200}");

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
        if ((user == null) != (password == null)) {
            throw new IllegalArgumentException("Proxy sign-in needs both the user and the password's secret reference");
        }
        if (user != null && !USER.matcher(user).matches()) {
            throw new IllegalArgumentException("Proxy user is not valid (1-200 characters, no ':' or control "
                    + "characters)");
        }
    }

    /** Without sign-in. */
    public ProxySettings(String host, int port, List<String> bypass) {
        this(host, port, bypass, null, null);
    }

    public static ProxySettings of(String host, int port) {
        return new ProxySettings(host, port, List.of());
    }

    /** With Basic sign-in: the user and where its password is kept in the secret store. */
    public ProxySettings withSignIn(String user, SecretRef password) {
        return new ProxySettings(host, port, bypass, user, password);
    }

    /** Whether the proxy asks for sign-in. */
    public boolean signIn() {
        return user != null;
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

    @Override
    public String toString() {
        return "ProxySettings[" + host + ":" + port + ", bypass " + bypass.size() + (signIn() ? ", sign-in" : "") + "]";
    }
}
