package com.documentplatform.notifications.spring.internal;

import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.spring.NotificationsConfigException;
import com.documentplatform.notifications.transport.HttpTransport;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.OutboundCode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.documentplatform.notifications.spring.NotificationsProperties.PREFIX;

/**
 * The notification senders' {@link HttpTransport} over platform-outbound (P059, LV-24.3 step 1): https only (plain
 * http only where internal addresses are allowed), the address guard with connection pinning, the outbound proxy with
 * its bypass list and sign-in, the private CA added to the system's, no redirects, the sender's time-outs, answers
 * up to {@link HttpTransport#MAX_REPLY_BYTES}. Used only for the settings that say {@code via-outbound}.
 *
 * <p>Internal: wired by the notifications auto-configuration; may change in any release, not for apps.
 */
public final class OutboundHttpTransport implements HttpTransport {

    private static final System.Logger LOG = System.getLogger(OutboundHttpTransport.class.getName());

    private final Outbound outbound;

    public OutboundHttpTransport(Outbound outbound) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    @Override
    public Reply post(URI address, Map<String, String> headers, byte[] body, Duration connectTimeout,
                      Duration readTimeout) throws Failed {
        Outbound.Call call = new Outbound.Call("POST", address, new ArrayList<>(headers.entrySet()),
                HttpRequest.BodyPublishers.ofByteArray(body), readTimeout);
        try {
            Outbound.Answer a = outbound.send(call, connectTimeout, MAX_REPLY_BYTES, true);
            return new Reply(a.status(), a.tooLarge() ? new byte[0] : a.body(), a.header("Retry-After").orElse(null));
        } catch (Outbound.Failure f) {
            throw new Failed(kind(f.code()), f.code().name());
        }
    }

    /** Refused: someone must change a setting. Timeout: connected, no answer. Otherwise no connection. */
    static Failed.Kind kind(OutboundCode code) {
        return switch (code) {
            case OUT_ADDRESS_BLOCKED, OUT_ADDRESS_NOT_SECURE, OUT_TLS_FAILED, OUT_PROXY_SIGN_IN_REFUSED,
                 OUT_CREDENTIAL_REFUSED, OUT_SECRET_NOT_FOUND, OUT_VALUE_NOT_ALLOWED -> Failed.Kind.REFUSED;
            case OUT_TIMEOUT -> Failed.Kind.TIMEOUT;
            default -> Failed.Kind.UNREACHABLE;
        };
    }

    /**
     * The start-up check of design note 2.4, for the Microsoft sign-in hosts whose token requests go through
     * platform-outbound: each host is checked by the address guard without connecting. A blocked address stops the
     * app (someone switched {@code via-outbound} on); a name that does not resolve yet is a warning, as DNS may come
     * later. Every problem is listed.
     */
    public static void checkAtStart(NotificationsConfig config, Installation installation) {
        AddressGuard guard = new AddressGuard(installation.allowInternalAddresses());
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, MicrosoftOAuth> e : config.outboundSignIns().entrySet()) {
            String host = e.getValue().authority().getHost();
            boolean viaProxy = installation.proxy() != null && !installation.proxy().bypasses(host);
            Optional<OutboundCode> refused = guard.check(host, viaProxy);
            if (refused.isEmpty()) {
                continue;
            }
            String key = PREFIX + "." + e.getKey() + ".oauth.authority";
            switch (refused.get()) {
                case OUT_ADDRESS_BLOCKED -> problems.add(key + ": an internal address, but "
                        + "platform.outbound.allow-internal-addresses is false (or a cloud metadata address, always "
                        + "refused); allow internal addresses, or switch " + PREFIX + "." + e.getKey()
                        + ".via-outbound off");
                case OUT_HOST_UNKNOWN -> LOG.log(System.Logger.Level.WARNING, "{0}: the name does not resolve yet; "
                        + "the token request will be checked again when it is made", key);
                default -> problems.add(key + ": refused by the outbound settings (" + refused.get().name() + ")");
            }
        }
        if (!problems.isEmpty()) {
            throw new NotificationsConfigException(problems);
        }
    }
}
