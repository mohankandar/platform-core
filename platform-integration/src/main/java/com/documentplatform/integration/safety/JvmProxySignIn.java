package com.documentplatform.integration.safety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Whether this JVM lets the HTTP client send Basic proxy sign-in: the JDK's {@code jdk.http.auth.tunneling
 * .disabledSchemes} (CONNECT tunnels for https; {@code Basic} in the JDK's own {@code conf/net.properties} by default)
 * and {@code jdk.http.auth.proxying.disabledSchemes} (plain http through the proxy; empty by default). A start option
 * ({@code -D…}) wins over the JDK's file, as in the JDK. Only read; the library never sets JVM properties. The install
 * guide gives the customer the start option {@value #TUNNEL_START_OPTION}.
 *
 * @param basicDisabledForTunnels  Basic cannot go on CONNECT
 * @param basicDisabledForProxying Basic cannot go on plain-http requests through the proxy
 */
public record JvmProxySignIn(boolean basicDisabledForTunnels, boolean basicDisabledForProxying) {

    public static final String TUNNEL_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes";
    public static final String PROXYING_PROPERTY = "jdk.http.auth.proxying.disabledSchemes";
    /** The start option that lets Basic through CONNECT tunnels (an empty list of disabled schemes). */
    public static final String TUNNEL_START_OPTION = "-D" + TUNNEL_PROPERTY + "=";

    /** What this JVM was started with (read once: the JDK reads these settings once too). */
    public static JvmProxySignIn current() {
        return Holder.CURRENT;
    }

    private static final class Holder {
        static final JvmProxySignIn CURRENT = read();
    }

    private static JvmProxySignIn read() {
        Properties net = new Properties();
        String home = System.getProperty("java.home");
        if (home != null) {
            Path file = Path.of(home, "conf", "net.properties");
            try (InputStream in = Files.newInputStream(file)) {
                net.load(in);
            } catch (IOException | RuntimeException unreadable) {
                // As the JDK does: no file, no default from it.
            }
        }
        return new JvmProxySignIn(disablesBasic(setting(TUNNEL_PROPERTY, net)),
                disablesBasic(setting(PROXYING_PROPERTY, net)));
    }

    private static String setting(String name, Properties net) {
        String v = System.getProperty(name);
        return v != null ? v : net.getProperty(name);
    }

    static boolean disablesBasic(String schemes) {
        if (schemes == null) return false;
        for (String s : schemes.split(",")) {
            if (s.trim().toLowerCase(Locale.ROOT).equals("basic")) return true;
        }
        return false;
    }
}
