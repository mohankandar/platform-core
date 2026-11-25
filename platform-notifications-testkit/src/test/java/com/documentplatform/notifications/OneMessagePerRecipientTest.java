package com.documentplatform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.documentplatform.notifications.Fixture.APPROVAL_WAITING;
import static com.documentplatform.notifications.Fixture.APPROVER;
import static com.documentplatform.notifications.Fixture.BOB;
import static com.documentplatform.notifications.Fixture.CY;
import static com.documentplatform.notifications.Fixture.E1;
import static com.documentplatform.notifications.Fixture.TENANT;
import static com.documentplatform.notifications.Fixture.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P055 (LV-24.1): a notice to several people is one e-mail each, sent, failed and retried on its own. */
class OneMessagePerRecipientTest {

    private static final Recipient DEE = new Recipient("u-dee", "dee@example.test");

    private final Fixture f = new Fixture();
    private final Scripted mail = new Scripted();
    private final Notifier notifier = f.notifier(f.settings, f.resolver, List.of(mail));

    OneMessagePerRecipientTest() {
        f.resolver.grant(TENANT, E1, APPROVER, BOB, DEE); // with CY: three holders
    }

    private DeliveryReport publish() {
        List<DeliveryReport> reports = f.via(notifier).publish(
                event(APPROVAL_WAITING).entity(E1).identifier(RecordIdentifier.of("C-1")).build());
        assertEquals(1, reports.size());
        return reports.get(0);
    }

    private List<DeliveryReport> advance(Duration by) {
        f.clock.advance(by);
        return notifier.tick(f.clock.instant());
    }

    private List<OutboxState> states() {
        return f.outbox.all().stream().map(OutboxEntry::state).sorted().toList();
    }

    @Test
    void oneMessageEach() {
        assertEquals(DeliveryStatus.SENT, publish().status());
        assertEquals(List.of(BOB, CY, DEE), mail.to());
        assertEquals(3, mail.sent.stream().map(OutgoingMessage::idempotencyKey).distinct().count());
        assertEquals(1, mail.sent.stream().map(OutgoingMessage::message).distinct().count(), "the same content");
        assertTrue(advance(Duration.ofHours(1)).isEmpty());
        assertEquals(3, mail.sent.size(), "sent once each");
    }

    @Test
    void aRefusedRecipientDoesNotStopTheOthersAndIsRecorded() {
        mail.fail.put(BOB, FailureCode.MAIL_RECIPIENT_REFUSED);
        DeliveryReport r = publish();
        assertEquals(DeliveryStatus.FAILED, r.status());
        assertEquals(FailureCode.MAIL_RECIPIENT_REFUSED, r.failure());
        assertEquals(List.of(CY, DEE), mail.to());
        assertEquals(List.of(OutboxState.SENT, OutboxState.SENT, OutboxState.FAILED, OutboxState.FAILED), states(),
                "per recipient, and the notice itself");
        assertTrue(advance(Duration.ofHours(5)).isEmpty());
    }

    @Test
    void onlyTheOneRefusedForNowIsTriedAgain() {
        mail.fail.put(BOB, FailureCode.MAIL_TEMPORARILY_REFUSED);
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, publish().status());
        assertEquals(List.of(CY, DEE), mail.to());
        mail.fail.clear();
        assertEquals(DeliveryStatus.SENT, advance(Duration.ofMinutes(1)).get(0).status());
        assertEquals(List.of(CY, DEE, BOB), mail.to());
    }

    @Test
    void aRefusalIsStillReportedWhenAnotherRecipientGetsItOnARetry() {
        mail.fail.put(BOB, FailureCode.MAIL_TEMPORARILY_REFUSED);
        mail.fail.put(CY, FailureCode.MAIL_RECIPIENT_REFUSED);
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, publish().status());
        mail.fail.clear();
        DeliveryReport last = advance(Duration.ofMinutes(1)).get(0);
        assertEquals(DeliveryStatus.FAILED, last.status());
        assertEquals(FailureCode.MAIL_RECIPIENT_REFUSED, last.failure());
        assertEquals(List.of(DEE, BOB), mail.to());
    }

    @Test
    void aServerThatFailsIsAskedOnceNotOncePerRecipient() {
        mail.failAll = FailureCode.MAIL_LOGIN_REFUSED;
        assertEquals(DeliveryStatus.FAILED, publish().status());
        assertEquals(1, mail.attempts, "a refused login is not repeated for every recipient");
    }

    @Test
    void aServerThatStopsHalfwaySendsTheRestLaterAndNobodyTwice() {
        mail.fail.put(CY, FailureCode.MAIL_SERVER_UNREACHABLE);
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, publish().status());
        assertEquals(List.of(BOB), mail.to());
        mail.fail.clear();
        assertEquals(DeliveryStatus.SENT, advance(Duration.ofMinutes(1)).get(0).status());
        assertEquals(List.of(BOB, CY, DEE), mail.to());
    }

    @Test
    void someoneWhoLostTheRoleBeforeTheRetryGetsNothingAndTheOneAlreadySentIsNotSentAgain() {
        mail.fail.put(CY, FailureCode.MAIL_SERVER_UNREACHABLE);
        publish();
        mail.fail.clear();
        f.resolver.revoke(TENANT, E1, APPROVER, CY, DEE);
        assertEquals(DeliveryStatus.SENT, advance(Duration.ofMinutes(1)).get(0).status());
        assertEquals(List.of(BOB), mail.to());
    }

    /** Fails per recipient. */
    private static final class Scripted implements Channel {
        final Map<Recipient, FailureCode> fail = new HashMap<>();
        final List<OutgoingMessage> sent = new ArrayList<>();
        FailureCode failAll;
        int attempts;

        @Override
        public ChannelKind kind() {
            return ChannelKind.EMAIL;
        }

        @Override
        public SendResult send(OutgoingMessage m) {
            attempts++;
            FailureCode code = failAll != null ? failAll
                    : m.recipients().stream().map(fail::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (code != null) {
                return SendResult.failed(code);
            }
            sent.add(m);
            return SendResult.ok();
        }

        List<Recipient> to() {
            return sent.stream().map(m -> {
                assertEquals(1, m.recipients().size(), "one recipient per message: " + m.recipients());
                return m.recipients().iterator().next();
            }).toList();
        }
    }
}
