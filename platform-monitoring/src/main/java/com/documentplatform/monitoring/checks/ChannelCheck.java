package com.documentplatform.monitoring.checks;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A notification channel (mail, Teams, Slack): its settings are complete and its server or address is reachable (a
 * TCP connection is opened and closed). Nothing is sent and no sign-in is tried. Never required at start-up: a
 * notification waits in the outbox.
 */
public final class ChannelCheck implements DependencyCheck {

    private final String channel;
    private final Map<String, String> settings;
    private final String host;
    private final int port;
    private final String addressShown;
    private final boolean addressInvalid;
    private final Duration connectTimeout;

    private ChannelCheck(String channel, Map<String, String> settings, String host, int port, String addressShown,
                         boolean addressInvalid, Duration connectTimeout) {
        this.channel = channel;
        this.settings = settings;
        this.host = host;
        this.port = port;
        this.addressShown = addressShown;
        this.addressInvalid = addressInvalid;
        this.connectTimeout = connectTimeout;
    }

    /**
     * A mail server.
     *
     * @param settings the settings the channel needs, by name (for example {@code host}, {@code from}); a null or
     *                 blank value is reported as missing. Values are never shown, only names.
     */
    public static ChannelCheck mail(String name, Map<String, String> settings, String host, int port) {
        return new ChannelCheck(Objects.requireNonNull(name, "name"), new LinkedHashMap<>(settings), host, port,
                host + ":" + port, false, Duration.ofSeconds(5));
    }

    /** A Teams or Slack webhook: https, reachable on its host. Only the host is ever shown (the path is a secret). */
    public static ChannelCheck webhook(String name, URI address) {
        boolean ok = address != null && "https".equalsIgnoreCase(address.getScheme()) && address.getHost() != null;
        String host = ok ? address.getHost() : null;
        int port = ok ? (address.getPort() > 0 ? address.getPort() : 443) : 0;
        return new ChannelCheck(Objects.requireNonNull(name, "name"), Map.of("address", address == null ? "" : "set"),
                host, port, ok ? host : "(not an https address)", !ok && address != null, Duration.ofSeconds(5));
    }

    @Override
    public String name() {
        return "channel-" + channel;
    }

    @Override
    public String group() {
        return "notifications";
    }

    @Override
    public Verdict check() {
        for (Map.Entry<String, String> s : settings.entrySet()) {
            if (s.getValue() == null || s.getValue().isBlank()) {
                return Verdict.down(MonitoringCode.MON_CHANNEL_SETTINGS_INCOMPLETE, Map.of("channel", channel,
                        "setting", s.getKey()));
            }
        }
        if (addressInvalid || host == null || host.isBlank() || port < 1 || port > 65535) {
            return Verdict.down(MonitoringCode.MON_CHANNEL_ADDRESS_INVALID, Map.of("channel", channel,
                    "address", addressShown));
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            return Verdict.working();
        } catch (IOException | IllegalArgumentException e) {
            return Verdict.down(MonitoringCode.MON_CHANNEL_UNREACHABLE, Map.of("channel", channel,
                    "address", addressShown));
        }
    }
}
