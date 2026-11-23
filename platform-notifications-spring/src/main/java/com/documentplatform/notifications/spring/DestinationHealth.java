package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How each destination is doing, from the outcome of the last messages this app instance sent to it. Holds ids, codes
 * and times only: never an address, a login or a secret.
 *
 * <ul>
 *   <li><b>Working</b>: the last message went through.</li>
 *   <li><b>Having problems</b>: the last attempt failed for a temporary reason (being retried), or one message was
 *       refused (a recipient, a message format) while the destination itself answers.</li>
 *   <li><b>Down</b>: the destination cannot work until someone changes something (login refused, webhook no longer
 *       valid, secret missing), or temporary failures have gone on for {@link #DOWN_AFTER}.</li>
 *   <li><b>Not used yet</b>: nothing was sent to it since this instance started.</li>
 * </ul>
 */
final class DestinationHealth {

    /** Temporary failures without a success in between for this long count as Down. */
    public static final Duration DOWN_AFTER = Duration.ofMinutes(15);

    /** Permanent failures that concern one message, not the destination. */
    private static final Set<FailureCode> ONE_MESSAGE = EnumSet.of(FailureCode.MAIL_RECIPIENT_REFUSED,
            FailureCode.MAIL_MESSAGE_REJECTED, FailureCode.WEBHOOK_PAYLOAD_REJECTED, FailureCode.NO_RECIPIENTS,
            FailureCode.DESTINATION_NOT_CONNECTED);

    public enum State {
        WORKING("Working"), HAVING_PROBLEMS("Having problems"), DOWN("Down"), NOT_USED_YET("Not used yet");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param failure      the code of the last failure, null when working or not used yet
     * @param failingSince the first failure since the last success, null when working or not used yet
     * @param lastAttempt  null when not used yet
     */
    public record Status(DestinationId destination, ChannelKind channel, State state, FailureCode failure,
                         Instant failingSince, Instant lastAttempt) {

        /** The fixed reason text for the failure code, for example "Mail server refused the login". */
        public Optional<String> reason() {
            return Optional.ofNullable(failure).map(f -> f.describe(channel));
        }
    }

    private record Last(ChannelKind channel, FailureCode failure, Instant failingSince, Instant at) {
    }

    private final Clock clock;
    private final Map<DestinationId, ChannelKind> known = new ConcurrentHashMap<>();
    private final Map<DestinationId, Last> last = new ConcurrentHashMap<>();

    public DestinationHealth(Clock clock) {
        this.clock = clock;
    }

    /** A destination from the install config, listed as Not used yet until something is sent to it. */
    public void expect(DestinationId destination, ChannelKind channel) {
        known.putIfAbsent(destination, channel);
    }

    public void record(DestinationId destination, ChannelKind channel, SendResult result) {
        Instant now = clock.instant();
        known.putIfAbsent(destination, channel);
        last.compute(destination, (id, before) -> {
            if (result.sent()) {
                return new Last(channel, null, null, now);
            }
            Instant since = before != null && before.failure() != null ? before.failingSince() : now;
            return new Last(channel, result.failure(), since, now);
        });
    }

    /** Every destination seen or expected, by id. */
    public Map<DestinationId, Status> statuses() {
        Instant now = clock.instant();
        Map<DestinationId, Status> out = new TreeMap<>((a, b) -> a.value().compareTo(b.value()));
        known.forEach((id, channel) -> {
            Last l = last.get(id);
            if (l == null) {
                out.put(id, new Status(id, channel, State.NOT_USED_YET, null, null, null));
            } else {
                out.put(id, new Status(id, l.channel(), state(l, now), l.failure(), l.failingSince(), l.at()));
            }
        });
        return out;
    }

    private static State state(Last l, Instant now) {
        FailureCode f = l.failure();
        if (f == null) {
            return State.WORKING;
        }
        if (ONE_MESSAGE.contains(f)) {
            return State.HAVING_PROBLEMS;
        }
        if (!f.retryable()) {
            return State.DOWN;
        }
        return Duration.between(l.failingSince(), now).compareTo(DOWN_AFTER) >= 0 ? State.DOWN : State.HAVING_PROBLEMS;
    }
}
