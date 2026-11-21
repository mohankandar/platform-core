package com.documentplatform.notifications;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Idempotency keys. A key names what a message is, not when it was made: the same event for the same destination,
 * the n-th alert of a condition that started at a given time, the summary of one bucket. Re-creating a message after
 * a restart therefore gives the same key, and the store keeps the one already saved.
 *
 * <p>Times enter keys as epoch milliseconds, and the notifier works in whole milliseconds, so a value read back from
 * a database (microseconds) gives the same key.
 */
final class Keys {

    private static final char SEPARATOR = '\u001f';

    private Keys() {
    }

    /** A new one-off event. */
    static String newEvent() {
        return "ev-" + UUID.randomUUID();
    }

    /** The occurrence of the message asking for a decision on one request: one per request. */
    static String actionRequest(TenantId tenant, String requestId) {
        return hash("q", tenant.value(), requestId);
    }

    /** One recipient's copy of a message whose links are made for each recipient. */
    static String forPerson(String messageKey, String userId) {
        return messageKey + "." + hash("p", userId).substring(2, 18);
    }

    static String condition(ConditionKey key, Instant since) {
        return hash("c", key.type().value(), key.tenant().value(),
                key.entity() == null ? "" : key.entity().value(),
                key.identifier() == null ? "" : key.identifier().value(), Long.toString(since.toEpochMilli()));
    }

    /** The n-th alert (first alert or a repeat) of a condition. */
    static String alert(ConditionKey key, Instant since, int alertNumber) {
        return hash("a", condition(key, since), Integer.toString(alertNumber));
    }

    static String recovered(ConditionKey key, Instant since) {
        return hash("r", condition(key, since));
    }

    /** One occurrence for one destination: the outbox key of a single message, or a summary item's key. */
    static String forDestination(String occurrenceKey, DestinationId destination) {
        return hash("m", occurrenceKey, destination.value());
    }

    static String summary(SummaryBucket b) {
        return hash("s", b.tenant().value(), b.destination().value(), b.schedule().frequency().name(),
                b.schedule().dailyAt() == null ? "" : b.schedule().dailyAt().toString(),
                b.entity() == null ? "" : b.entity().value(),
                b.roles().stream().map(RoleId::value).sorted().collect(Collectors.joining(",")),
                Long.toString(b.dueAt().toEpochMilli()));
    }

    private static String hash(String kind, String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                sha.update(part.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) SEPARATOR);
            }
            return kind + "-" + HexFormat.of().formatHex(sha.digest(), 0, 20);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
