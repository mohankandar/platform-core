package com.documentplatform.identity.signin;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * How the test sign-in reads the issuer's discovery document and key set. Production apps use the platform's
 * outbound rules ({@code com.documentplatform.identity.spring.OutboundIssuerFetch} on platform-outbound: https only, the address
 * guard, the proxy, the private certificate authority, no redirects, size limits); tests use the identity testkit's
 * mock issuer.
 */
@FunctionalInterface
public interface IssuerFetch {

    /** An answer: the HTTP status and the body (at most the size asked for). */
    record Response(int status, byte[] body) {
        public Response {
            Objects.requireNonNull(body, "body");
        }

        @Override
        public String toString() {
            return "Response[" + status + "]";
        }
    }

    /**
     * The call was not made or got no answer. {@code reason} is a neutral code (for example the outbound rules'
     * {@code OUT_ADDRESS_BLOCKED} or {@code OUT_TLS_FAILED}); never an address, header or body.
     */
    final class Refused extends IOException {
        private final String reason;

        public Refused(String reason) {
            super(reason, null);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * Reads one address with GET.
     *
     * @param maxBytes        the largest body accepted; a larger one is refused ({@link Refused})
     * @param localTestIssuer the address is plain {@code http} on this machine and the test sign-in has already found
     *                        it allowed ({@link LocalTestIssuers}); only then may an {@code http} address be called
     * @throws Refused when the call was refused by the outbound rules, could not connect, or the body was too large
     */
    Response get(URI uri, int maxBytes, boolean localTestIssuer) throws IOException;
}
