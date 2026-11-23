package com.documentplatform.identity.spring;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.LocalTestIssuers;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The login-connection test sign-in's {@link IssuerFetch} over the platform's outbound rules ({@link Outbound}, platform-outbound):
 * https only, the address guard before each call, the installation's proxy and private certificate authority, no
 * redirects, timeouts and size limits. Failures are the outbound rule's code ({@code OUT_…}); no address, header or
 * body reaches a message.
 *
 * <p>A local test issuer ({@code http} on {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, which the test
 * sign-in allows only under a development licence) is called directly on this machine, without the proxy; any other
 * {@code http} address is refused as by {@link Outbound}.
 *
 * <pre>
 * IssuerFetch fetch = new OutboundIssuerFetch(new Outbound(installation));
 * LoginConnections connections = new LoginConnections(store, new OidcTestSignIn(fetch, clock), developmentLicence);
 * </pre>
 */
public final class OutboundIssuerFetch implements IssuerFetch {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private final Outbound outbound;
    private final Outbound local;
    private final boolean useProxy;

    /** Calls through the installation's proxy when one is set. */
    public OutboundIssuerFetch(Outbound outbound) {
        this(outbound, true);
    }

    /** @param useProxy whether the calls use the installation's proxy (when one is set) */
    public OutboundIssuerFetch(Outbound outbound, boolean useProxy) {
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.useProxy = useProxy;
        // Only for local test issuers: loopback and plain http allowed, nothing else changed.
        this.local = new Outbound(new Installation(false, true, false, null, null));
    }

    @Override
    public Response get(URI uri, int maxBytes, boolean localTestIssuer) throws IOException {
        Objects.requireNonNull(uri, "uri");
        boolean onThisMachine = localTestIssuer && LocalTestIssuers.isLocalHttp(uri);
        Outbound via = onThisMachine ? local : outbound;
        Outbound.Call call = new Outbound.Call("GET", uri, List.of(Map.entry("Accept", "application/json")), null,
                READ_TIMEOUT);
        try {
            Outbound.Answer a = via.send(call, CONNECT_TIMEOUT, maxBytes, useProxy && !onThisMachine);
            if (a.tooLarge()) throw new Refused("TOO_LARGE");
            return new Response(a.status(), a.body());
        } catch (Outbound.Failure f) {
            throw new Refused(f.code().name());
        }
    }
}
