package com.documentplatform.notifications;

import com.documentplatform.notifications.inmemory.InMemoryChannel;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.documentplatform.notifications.Fixture.ANN;
import static com.documentplatform.notifications.Fixture.APPROVAL_WAITING;
import static com.documentplatform.notifications.Fixture.BOB;
import static com.documentplatform.notifications.Fixture.CY;
import static com.documentplatform.notifications.Fixture.E1;
import static com.documentplatform.notifications.Fixture.E2;
import static com.documentplatform.notifications.Fixture.FILES_REJECTED;
import static com.documentplatform.notifications.Fixture.LEVEL_LOWERED;
import static com.documentplatform.notifications.Fixture.LICENCE_EXPIRED;
import static com.documentplatform.notifications.Fixture.MAIL;
import static com.documentplatform.notifications.Fixture.SLACK;
import static com.documentplatform.notifications.Fixture.STORE_DOWN;
import static com.documentplatform.notifications.Fixture.TEAMS;
import static com.documentplatform.notifications.Fixture.TENANT;
import static com.documentplatform.notifications.Fixture.event;
import static com.documentplatform.notifications.NotificationException.Reason.ENTITY_REQUIRED;
import static com.documentplatform.notifications.NotificationException.Reason.IS_A_CONDITION;
import static com.documentplatform.notifications.NotificationException.Reason.NOT_A_CONDITION;
import static com.documentplatform.notifications.NotificationException.Reason.UNKNOWN_EVENT_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingTest {

    private final Fixture f = new Fixture();

    private EventSettings defaults(EventTypeId type) {
        return EventSettings.defaultsFor(f.settings.catalogue().require(type));
    }

    private static NotificationEvent approval(EntityId entity) {
        return event(APPROVAL_WAITING).entity(entity).identifier(RecordIdentifier.of("C-1")).build();
    }

    @Test
    void enabledImmediateEventGoesByEmailToTheRoleHoldersOfItsEntity() {
        List<DeliveryReport> reports = f.via(f.notifier).publish(approval(E1));
        assertEquals(1, reports.size());
        assertEquals(DeliveryStatus.SENT, reports.get(0).status());
        assertEquals(MAIL, reports.get(0).destination());
        assertEquals(Set.of(CY), f.email.sent().get(0).recipients());

        f.via(f.notifier).publish(approval(E2));
        assertEquals(Set.of(ANN), f.email.sent().get(1).recipients());
    }

    @Test
    void switchedOffEventIsNotSent() {
        List<DeliveryReport> reports = f.via(f.notifier).publish(event(LEVEL_LOWERED).entity(E1).build());
        assertEquals(DeliveryStatus.DISABLED, reports.get(0).status());
        assertTrue(f.email.sent().isEmpty());

        f.settings.setBusinessEvent(TENANT, E1, LEVEL_LOWERED, defaults(LEVEL_LOWERED).withEnabled(true));
        assertEquals(DeliveryStatus.SENT, f.via(f.notifier).publish(event(LEVEL_LOWERED).entity(E1).build()).get(0).status());
        assertEquals(DeliveryStatus.DISABLED, f.via(f.notifier).publish(event(LEVEL_LOWERED).entity(E2).build()).get(0).status());
    }

    @Test
    void webhooksPostToTheAssignedChannelWithoutRecipients() {
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                defaults(FILES_REJECTED).withSchedule(Schedule.immediately()).withDestinations(TEAMS, SLACK));
        List<DeliveryReport> reports = f.via(f.notifier).publish(event(FILES_REJECTED).count("files", 2).build());
        assertEquals(List.of(SLACK, TEAMS), reports.stream().map(DeliveryReport::destination).toList());
        assertTrue(f.email.sent().isEmpty());
        assertEquals(1, f.teams.sent().size());
        assertEquals(1, f.slack.sent().size());
        assertTrue(f.teams.sent().get(0).recipients().isEmpty());
    }

    @Test
    void criticalEventGoesImmediatelyToTheItAdmin() {
        f.via(f.notifier).publish(event(LICENCE_EXPIRED).entity(E1).build());
        assertEquals(List.of(Set.of(ANN), Set.of(CY)),
                f.email.sent().stream().map(OutgoingMessage::recipients).toList(), "a message of their own each");
    }

    @Test
    void tenantWithoutChannelsHasNoDestination() {
        NotificationSettings bare = new NotificationSettings(Fixture.catalogue(), new InMemorySettingsStore());
        Notifier n = f.notifier(bare, f.resolver, List.of(f.email));
        assertEquals(DeliveryStatus.NO_DESTINATION, f.via(n).publish(approval(E1)).get(0).status());
    }

    @Test
    void noOneHoldingTheRoleIsReportedNotSent() {
        DeliveryReport r = f.via(f.notifier).publish(approval(EntityId.of("e-nobody"))).get(0);
        assertEquals(DeliveryStatus.FAILED, r.status());
        assertEquals(FailureCode.NO_RECIPIENTS, r.failure());
    }

    @Test
    void channelFailuresCarryTheirSpecificCode() {
        f.email.failWith(FailureCode.MAIL_LOGIN_REFUSED);
        DeliveryReport r = f.via(f.notifier).publish(approval(E1)).get(0);
        assertEquals(DeliveryStatus.FAILED, r.status());
        assertEquals(FailureCode.MAIL_LOGIN_REFUSED, r.failure());
    }

    @Test
    void missingSenderAndFaultySenderAreReported() {
        Notifier noEmail = f.notifier(f.settings, f.resolver, List.of(f.teams));
        assertEquals(FailureCode.CHANNEL_NOT_AVAILABLE, f.via(noEmail).publish(approval(E1)).get(0).failure());

        Channel faulty = new Channel() {
            public ChannelKind kind() {
                return ChannelKind.EMAIL;
            }

            public SendResult send(OutgoingMessage m) {
                throw new IllegalStateException("smtp said: 535 secret-detail");
            }
        };
        Notifier n = f.notifier(f.settings, f.resolver, List.of(faulty));
        assertEquals(FailureCode.SENDER_FAILED, f.via(n).publish(approval(E1)).get(0).failure());

        RecipientResolver broken = (t, e, r) -> {
            throw new IllegalStateException("directory down");
        };
        Notifier n2 = f.notifier(f.settings, broken, List.of(new InMemoryChannel(ChannelKind.EMAIL)));
        assertEquals(FailureCode.RECIPIENTS_UNAVAILABLE, f.via(n2).publish(approval(E1)).get(0).failure());
    }

    @Test
    void disconnectedDestinationsAreSkipped() {
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                defaults(FILES_REJECTED).withSchedule(Schedule.immediately()).withDestinations(TEAMS));
        f.settings.connectChannels(TENANT, new TenantSettings(Fixture.tenantSettings(ZoneOffset.UTC).appBaseUrl(),
                ZoneOffset.UTC, java.util.Map.of(MAIL, ChannelKind.EMAIL)));
        // the saved settings now name a destination that is gone, so the defaults apply: e-mail, every hour
        List<DeliveryReport> reports = f.via(f.notifier).publish(event(FILES_REJECTED).build());
        assertEquals(1, reports.size());
        assertEquals(MAIL, reports.get(0).destination());
        assertEquals(DeliveryStatus.QUEUED_FOR_SUMMARY, reports.get(0).status());
        f.advance(java.time.Duration.ofHours(1));
        assertEquals(Set.of(BOB), f.email.sent().get(0).recipients());
    }

    @Test
    void misuseOfThePublishApiIsRefused() {
        assertEquals(UNKNOWN_EVENT_TYPE, assertThrows(NotificationException.class,
                () -> f.via(f.notifier).publish(event(EventTypeId.of("not.registered")).build())).reason());
        assertEquals(ENTITY_REQUIRED, assertThrows(NotificationException.class,
                () -> f.via(f.notifier).publish(event(APPROVAL_WAITING).build())).reason());
        assertEquals(IS_A_CONDITION, assertThrows(NotificationException.class,
                () -> f.via(f.notifier).publish(event(STORE_DOWN).build())).reason());
        assertEquals(NOT_A_CONDITION, assertThrows(NotificationException.class,
                () -> f.via(f.notifier).conditionFailing(event(FILES_REJECTED).build())).reason());
    }
}
