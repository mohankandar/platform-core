package com.documentplatform.notifications.transport;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * How a notification sender makes one HTTPS call, when it does not use its own HTTP client (P059, LV-24.3 step 1):
 * the Microsoft sign-in token request of a mail server ({@code MicrosoftTokens}) and the Teams and Slack webhook posts.
 * platform-notifications-spring supplies one over platform-outbound when {@code via-outbound} is on, so these calls
 * follow the installation's outbound rules (address guard, proxy with its sign-in, private CA, https only, no
 * redirects). Without one, each sender keeps its own client, as before 1.3.0.
 *
 * <p>An implementation never puts an address, header or body in an exception text or a log. It reads at most
 * {@link #MAX_REPLY_BYTES} of an answer.
 *
 * <p>SPI: implemented by platform-notifications-spring (or the app); called by the senders.
 */
public interface HttpTransport {

    /** The most of an answer's body that is read; a longer body is cut there. */
    int MAX_REPLY_BYTES = 64 * 1024;

    /**
     * Sends one POST and returns the answer, whatever its status.
     *
     * @throws Failed when the call could not be made or got no answer
     */
    Reply post(URI address, Map<String, String> headers, byte[] body, Duration connectTimeout, Duration readTimeout)
            throws Failed;

    /**
     * An answer: the status, the body (up to {@link #MAX_REPLY_BYTES}) and the {@code Retry-After} header, if any.
     * Its text shows the status only: a body can hold a token.
     */
    record Reply(int status, byte[] body, String retryAfter) {
        public Reply {
            body = body == null ? new byte[0] : body;
        }

        @Override
        public String toString() {
            return "Reply[" + status + "]";
        }
    }

    /**
     * The call could not be made or got no answer. {@code reason} is a code name such as {@code OUT_ADDRESS_BLOCKED}
     * (for the log and health), never an address.
     */
    final class Failed extends Exception {

        /** What the sender makes of it. */
        public enum Kind {
            /** The outbound rules refused the call (address blocked, proxy sign-in refused, TLS failed): not retried. */
            REFUSED,
            /** No connection (host unknown, cannot connect, connection lost, proxy unreachable): retried. */
            UNREACHABLE,
            /** Connected, but no answer in time: retried. */
            TIMEOUT
        }

        private final Kind kind;
        private final String reason;

        public Failed(Kind kind, String reason) {
            super(Objects.requireNonNull(kind, "kind") + ": " + Objects.requireNonNull(reason, "reason"), null, false,
                    false);
            this.kind = kind;
            this.reason = reason;
        }

        public Kind kind() {
            return kind;
        }

        public String reason() {
            return reason;
        }
    }
}
