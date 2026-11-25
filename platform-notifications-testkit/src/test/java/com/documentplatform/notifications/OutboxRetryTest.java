package com.documentplatform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.documentplatform.notifications.Fixture.APPROVAL_WAITING;
import static com.documentplatform.notifications.Fixture.CY;
import static com.documentplatform.notifications.Fixture.E1;
import static com.documentplatform.notifications.Fixture.FILES_REJECTED;
import static com.documentplatform.notifications.Fixture.TEAMS;
import static com.documentplatform.notifications.Fixture.TENANT;
import static com.documentplatform.notifications.Fixture.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outbox: saved before sending, temporary failures retried with backoff, permanent ones stopped and reported. */
class OutboxRetryTest {

    private final Fixture f = new Fixture();

    private DeliveryReport publishToTeams() {
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                EventSettings.defaultsFor(f.settings.catalogue().require(FILES_REJECTED))
                        .withSchedule(Schedule.immediately()).withDestinations(TEAMS));
        List<DeliveryReport> reports = f.via(f.notifier).publish(
                event(FILES_REJECTED).identifier(RecordIdentifier.of("batch-7")).count("files", 3).build());
        assertEquals(1, reports.size());
        return reports.get(0);
    }

    private OutboxEntry only() {
        assertEquals(1, f.outbox.all().size());
        return f.outbox.all().get(0);
    }

    @Test
    void everyMessageIsSavedBeforeItIsSent() {
        assertEquals(DeliveryStatus.SENT, publishToTeams().status());
        OutboxEntry e = only();
        assertEquals(OutboxState.SENT, e.state());
        assertEquals(1, e.attempts());
        assertEquals(e.key(), f.teams.sent().get(0).idempotencyKey());
    }

    @Test
    void temporaryFailuresAreRetriedWithBackoffUntilSent() {
        f.teams.failWith(FailureCode.WEBHOOK_UNREACHABLE);
        DeliveryReport first = publishToTeams();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, first.status());
        assertEquals(FailureCode.WEBHOOK_UNREACHABLE, first.failure());

        assertTrue(f.advance(Duration.ofSeconds(59)).isEmpty(), "not before the first delay (1 min)");
        assertEquals(List.of(DeliveryStatus.RETRY_SCHEDULED),
                f.advance(Duration.ofSeconds(1)).stream().map(DeliveryReport::status).toList());
        assertEquals(2, f.teams.attempts());

        f.teams.succeed();
        assertTrue(f.advance(Duration.ofSeconds(119)).isEmpty(), "second delay doubles to 2 min");
        assertEquals(DeliveryStatus.SENT, f.advance(Duration.ofSeconds(1)).get(0).status());
        assertEquals(3, f.teams.attempts());
        assertEquals(1, f.teams.sent().size());
        assertEquals(OutboxState.SENT, only().state());
        assertTrue(f.advance(Duration.ofHours(5)).isEmpty(), "sent once, never again");
    }

    @Test
    void permanentFailuresStopAtOnceAndAreReported() {
        f.teams.failWith(FailureCode.WEBHOOK_ADDRESS_INVALID);
        DeliveryReport r = publishToTeams();
        assertEquals(DeliveryStatus.FAILED, r.status());
        assertEquals(FailureCode.WEBHOOK_ADDRESS_INVALID, r.failure());
        for (int i = 0; i < 24; i++) {
            assertTrue(f.advance(Duration.ofHours(1)).isEmpty());
        }
        assertEquals(1, f.teams.attempts());
        assertEquals(OutboxState.FAILED, only().state());
        assertEquals(FailureCode.WEBHOOK_ADDRESS_INVALID, only().lastFailure());
    }

    @Test
    void retryAfterFromTheReceiverIsHonoured() {
        f.teams.failWith(FailureCode.WEBHOOK_RATE_LIMITED, Duration.ofMinutes(10));
        publishToTeams();
        assertEquals(f.clock.instant().plus(Duration.ofMinutes(10)), only().nextAttemptAt());
        f.teams.succeed();
        assertTrue(f.advance(Duration.ofMinutes(9)).isEmpty(), "the backoff (1 min) is replaced by Retry-After");
        assertEquals(DeliveryStatus.SENT, f.advance(Duration.ofMinutes(1)).get(0).status());
    }

    @Test
    void retriesRunOutAfterTheGiveUpTime() {
        Notifier n = Notifier.builder(f.settings, f.resolver).channels(List.of(f.email, f.teams)).clock(f.clock)
                .outbox(f.outbox).summaries(f.summaries).conditions(f.conditions)
                .retryPolicy(new RetryPolicy(Duration.ofMinutes(1), Duration.ofMinutes(4), Duration.ofMinutes(10)))
                .build();
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                EventSettings.defaultsFor(f.settings.catalogue().require(FILES_REJECTED))
                        .withSchedule(Schedule.immediately()).withDestinations(TEAMS));
        f.teams.failWith(FailureCode.WEBHOOK_SERVER_ERROR);
        f.via(n).publish(event(FILES_REJECTED).count("files", 1).build());
        DeliveryReport last = null;
        for (int minute = 1; minute <= 30; minute++) {
            f.clock.advance(Duration.ofMinutes(1));
            for (DeliveryReport r : n.tick(f.clock.instant())) {
                last = r;
            }
        }
        assertEquals(DeliveryStatus.FAILED, last.status());
        assertEquals(FailureCode.WEBHOOK_SERVER_ERROR, last.failure());
        // attempts at 0, +1, +3, +7 min; the next (+11) would be past the 10 minutes
        assertEquals(4, f.teams.attempts());
        assertEquals(OutboxState.FAILED, only().state());
    }

    @Test
    void recipientsAreLookedUpAgainOnEachAttempt() {
        f.email.failWith(FailureCode.MAIL_SERVER_UNREACHABLE);
        f.via(f.notifier).publish(event(APPROVAL_WAITING).entity(E1).identifier(RecordIdentifier.of("C-1")).build());
        Recipient dee = new Recipient("u-dee", "dee@example.test");
        f.resolver.grant(TENANT, E1, Fixture.APPROVER, dee);
        f.email.succeed();
        assertEquals(DeliveryStatus.SENT, f.advance(Duration.ofMinutes(1)).get(0).status());
        assertEquals(List.of(Set.of(CY), Set.of(dee)),
                f.email.sent().stream().map(OutgoingMessage::recipients).toList(), "a message of their own each");
        assertTrue(f.outbox.all().get(0).message().text().contains("C-1"));
    }

    @Test
    void aSenderThatStoppedMidSendIsTakenOverWhenItsLeaseRunsOut() {
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                EventSettings.defaultsFor(f.settings.catalogue().require(FILES_REJECTED))
                        .withSchedule(Schedule.immediately()).withDestinations(TEAMS));
        Channel dies = new Channel() {
            public ChannelKind kind() {
                return ChannelKind.TEAMS;
            }

            public SendResult send(OutgoingMessage m) {
                throw new ProcessStopped();
            }
        };
        Notifier crashing = f.notifier(f.settings, f.resolver, List.of(dies));
        try {
            f.via(crashing).publish(event(FILES_REJECTED).count("files", 1).build());
        } catch (ProcessStopped expected) {
            // the process "stopped" while sending
        }
        assertEquals(OutboxState.SENDING, only().state());

        // A restarted notifier on the same stores: nothing during the lease, then the message is sent.
        Notifier restarted = f.notifier(f.settings, f.resolver, List.of(f.email, f.teams));
        f.clock.advance(Duration.ofMinutes(4));
        assertTrue(restarted.tick(f.clock.instant()).isEmpty());
        f.clock.advance(Duration.ofMinutes(1));
        assertEquals(DeliveryStatus.SENT, restarted.tick(f.clock.instant()).get(0).status());
        assertEquals(1, f.teams.sent().size());
        assertEquals(only().key(), f.teams.sent().get(0).idempotencyKey());
    }

    @Test
    void finishedMessagesAreClearedAfterTheRetention() {
        publishToTeams();
        f.advance(Duration.ofDays(29));
        assertEquals(1, f.outbox.all().size());
        f.advance(Duration.ofDays(1).plusSeconds(1));
        assertTrue(f.outbox.all().isEmpty());
    }

    @Test
    void retryPolicyBackoffDoublesUpToTheMaximum() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L),
                List.of(1, 2, 3, 4, 5, 6, 20).stream().map(n -> p.backoff(n).toMinutes()).toList());
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        assertEquals(now.plusSeconds(1), p.next(now, 1, Duration.ZERO, now).orElseThrow());
        assertTrue(p.next(now, 1, Duration.ofHours(25), now).isEmpty(), "a Retry-After past the give-up time gives up");
    }

    /** Stands in for the process stopping (an Error is not caught as a sender fault). */
    private static final class ProcessStopped extends Error {
    }
}
