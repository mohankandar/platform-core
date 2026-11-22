package com.documentplatform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static com.documentplatform.notifications.Fixture.BOB;
import static com.documentplatform.notifications.Fixture.CY;
import static com.documentplatform.notifications.Fixture.E1;
import static com.documentplatform.notifications.Fixture.E2;
import static com.documentplatform.notifications.Fixture.FILES_REJECTED;
import static com.documentplatform.notifications.Fixture.ITEMS_WAITING;
import static com.documentplatform.notifications.Fixture.QUOTA;
import static com.documentplatform.notifications.Fixture.TEAMS;
import static com.documentplatform.notifications.Fixture.TENANT;
import static com.documentplatform.notifications.Fixture.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryTest {

    private final Fixture f = new Fixture();

    private static NotificationEvent rejected(String batch, long files) {
        return event(FILES_REJECTED).identifier(RecordIdentifier.of(batch)).count("files", files)
                .link(AppLink.of("/intake/" + batch)).build();
    }

    @Test
    void hourlyEventsWaitForTheNextWholeHourAndGoAsOneSummary() {
        assertEquals(DeliveryStatus.QUEUED_FOR_SUMMARY, f.via(f.notifier).publish(rejected("b-1", 2)).get(0).status());
        f.clock.advance(Duration.ofMinutes(20));
        f.via(f.notifier).publish(rejected("b-2", 3));
        assertTrue(f.notifier.tick(Instant.parse("2026-11-20T10:59:59Z")).isEmpty());
        assertTrue(f.email.sent().isEmpty());

        List<DeliveryReport> reports = f.notifier.tick(Instant.parse("2026-11-20T11:00:00Z"));
        assertEquals(1, reports.size());
        assertEquals(MessageKind.SUMMARY, reports.get(0).kind());
        assertEquals(DeliveryStatus.SENT, reports.get(0).status());
        assertEquals(Set.of(FILES_REJECTED), reports.get(0).eventTypes());

        OutgoingMessage out = f.email.sent().get(0);
        assertEquals(Set.of(BOB), out.recipients());
        assertEquals("""
                Summary: 2 notifications
                Period: 2026-11-20 10:05 Z to 2026-11-20 10:25 Z
                Files rejected: 2
                  Files (total): 5
                  - b-1, 2026-11-20 10:05 Z https://app.example.test/intake/b-1
                  - b-2, 2026-11-20 10:25 Z https://app.example.test/intake/b-2
                https://app.example.test/""", out.message().text());
    }

    @Test
    void aSentSummaryIsNotSentAgainAndTheNextHoldsOnlyNewItems() {
        f.via(f.notifier).publish(rejected("b-1", 1));
        f.notifier.tick(Instant.parse("2026-11-20T11:00:00Z"));
        assertTrue(f.notifier.tick(Instant.parse("2026-11-20T12:00:00Z")).isEmpty());
        assertEquals(1, f.email.sent().size());
        assertEquals(0, f.notifier.pendingSummaryItems());

        f.clock.set(Instant.parse("2026-11-20T12:10:00Z"));
        f.via(f.notifier).publish(rejected("b-2", 1));
        f.notifier.tick(Instant.parse("2026-11-20T13:00:00Z"));
        String second = f.email.sent().get(1).message().text();
        assertTrue(second.contains("b-2") && !second.contains("b-1"), second);
    }

    @Test
    void every4HoursGoesAtTheNext4HourMarkPerEntity() {
        f.via(f.notifier).publish(event(ITEMS_WAITING).entity(E1).identifier(RecordIdentifier.of("C-1")).count("items", 4).build());
        f.via(f.notifier).publish(event(ITEMS_WAITING).entity(E2).identifier(RecordIdentifier.of("C-2")).count("items", 1).build());
        assertTrue(f.notifier.tick(Instant.parse("2026-11-20T11:00:00Z")).isEmpty());
        List<DeliveryReport> reports = f.notifier.tick(Instant.parse("2026-11-20T12:00:00Z"));
        // E1's reviewers get E1's summary; E2 has no reviewers
        assertEquals(List.of(DeliveryStatus.SENT, DeliveryStatus.FAILED),
                reports.stream().map(DeliveryReport::status).toList());
        assertEquals(FailureCode.NO_RECIPIENTS, reports.get(1).failure());
        assertEquals(Set.of(BOB, CY), f.email.sent().get(0).recipients());
        assertTrue(f.email.sent().get(0).message().text().contains("C-1"));
        assertTrue(!f.email.sent().get(0).message().text().contains("C-2"));
    }

    @Test
    void dailySummaryGoesAtTheChosenTimeInTheTenantsZone() {
        Fixture amsterdam = new Fixture(ZoneId.of("Europe/Amsterdam"));
        amsterdam.via(amsterdam.notifier).publish(event(QUOTA).entity(E1).count("percent", 85).build());
        assertTrue(amsterdam.notifier.tick(Instant.parse("2026-11-21T06:59:00Z")).isEmpty());
        assertEquals(DeliveryStatus.SENT,
                amsterdam.notifier.tick(Instant.parse("2026-11-21T07:00:00Z")).get(0).status());
        assertTrue(amsterdam.email.sent().get(0).message().text().contains("Period: 2026-11-20 11:05 CET"));
    }

    /** A notifier on the fixture's stores where entity E1 has its own zone (New York) and E2 has none. */
    private Notifier withEntityZones(EntityZones zones) {
        return Notifier.builder(f.settings, f.resolver).channels(List.of(f.email, f.teams, f.slack)).clock(f.clock)
                .outbox(f.outbox).summaries(f.summaries).conditions(f.conditions).entityZones(zones).build();
    }

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final EntityZones E1_IN_NEW_YORK = (tenant, entity) ->
            entity.equals(E1) ? java.util.Optional.of(NEW_YORK) : java.util.Optional.empty();

    @Test
    void dailySummaryGoesAtTheEntitysLocalTimeAcrossADaylightSavingChange() {
        Notifier n = withEntityZones(E1_IN_NEW_YORK);
        // Before the change (New York on EDT, UTC-4): 08:00 local is 12:00 UTC.
        f.clock.set(Instant.parse("2026-10-30T13:00:00Z"));
        f.via(n).publish(event(QUOTA).entity(E1).count("percent", 85).build());
        assertTrue(n.tick(Instant.parse("2026-10-31T11:59:00Z")).isEmpty());
        assertEquals(DeliveryStatus.SENT, n.tick(Instant.parse("2026-10-31T12:00:00Z")).get(0).status());
        String before = f.email.sent().get(0).message().text();
        assertTrue(before.contains("Period: 2026-10-30 09:00 EDT"), before);
        assertEquals(Set.of(CY), f.email.sent().get(0).recipients());

        // Daylight saving ends on 2026-11-01 at 02:00 local (EST, UTC-5): 08:00 local is now 13:00 UTC.
        f.clock.set(Instant.parse("2026-10-31T13:00:00Z"));
        f.via(n).publish(event(QUOTA).entity(E1).count("percent", 90).build());
        f.via(n).publish(event(QUOTA).entity(E2).count("percent", 85).build());
        List<DeliveryReport> utc = n.tick(Instant.parse("2026-11-01T08:00:00Z"));
        assertEquals(1, utc.size(), "E2 has no zone of its own: 08:00 in the tenant's zone (UTC)");
        assertEquals(Set.of(Fixture.ANN), f.email.sent().get(1).recipients());
        assertTrue(f.email.sent().get(1).message().text().contains("Period: 2026-10-31 13:00 Z"));
        assertTrue(n.tick(Instant.parse("2026-11-01T12:00:00Z")).isEmpty(), "not at the old 12:00 UTC");
        assertTrue(n.tick(Instant.parse("2026-11-01T12:59:00Z")).isEmpty());
        assertEquals(DeliveryStatus.SENT, n.tick(Instant.parse("2026-11-01T13:00:00Z")).get(0).status());
        String after = f.email.sent().get(2).message().text();
        assertTrue(after.contains("Period: 2026-10-31 09:00 EDT"), after);
    }

    @Test
    void eventsWithoutAnEntityKeepTheTenantsZoneAndAFailingLookupFallsBackToIt() {
        Notifier n = withEntityZones(E1_IN_NEW_YORK);
        f.via(n).publish(rejected("b-1", 2));
        n.tick(Instant.parse("2026-11-20T11:00:00Z"));
        assertTrue(f.email.sent().get(0).message().text().contains("- b-1, 2026-11-20 10:05 Z"));

        Notifier failing = withEntityZones((tenant, entity) -> {
            throw new IllegalStateException("identity store down");
        });
        f.via(failing).publish(event(QUOTA).entity(E1).count("percent", 85).build());
        assertEquals(DeliveryStatus.SENT, failing.tick(Instant.parse("2026-11-21T08:00:00Z")).get(0).status(),
                "08:00 in the tenant's zone");
    }

    @Test
    void eachDestinationGetsItsOwnSummary() {
        f.settings.setTechnicalEvent(TENANT, FILES_REJECTED,
                EventSettings.defaultsFor(f.settings.catalogue().require(FILES_REJECTED))
                        .withDestinations(TEAMS, Fixture.MAIL));
        f.via(f.notifier).publish(rejected("b-1", 1));
        assertEquals(2, f.notifier.tick(Instant.parse("2026-11-20T11:00:00Z")).size());
        assertEquals(1, f.teams.sent().size());
        assertEquals(1, f.email.sent().size());
    }

    @Test
    void longSummariesListTenItemsPerTypeAndCountTheRest() {
        for (int i = 1; i <= 13; i++) {
            f.via(f.notifier).publish(rejected("b-" + i, 1));
        }
        f.notifier.tick(Instant.parse("2026-11-20T11:00:00Z"));
        String text = f.email.sent().get(0).message().text();
        assertTrue(text.startsWith("Summary: 13 notifications"));
        assertTrue(text.contains("Files (total): 13"));
        assertTrue(text.contains("b-10,") && !text.contains("b-11,"));
        assertTrue(text.contains("  ... and 3 more"));
    }

    @Test
    void nothingPublishedMeansNoSummary() {
        assertTrue(f.advance(Duration.ofDays(2)).isEmpty());
        assertTrue(f.email.sent().isEmpty());
    }
}
