package com.documentplatform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

import static com.documentplatform.notifications.Fixture.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * notifications-3 (P037), design rule 1: event types are added and removed in later releases; what was saved for a
 * type the next release no longer has (a summary item waiting, a failing condition) is ignored, never sent and never
 * an error.
 */
class RemovedEventTypesTest {

    /** The fixture's catalogue without "Files rejected" and "Store down": the next release removed them. */
    private static EventCatalogue nextRelease() {
        return EventCatalogue.builder(IT_ADMIN)
                .register(EventType.builder(QUOTA.value(), "Quota used", EventKind.BUSINESS)
                        .schedule(Schedule.onceADay(LocalTime.of(8, 0))).recipients(APPROVER)
                        .count("percent", "Percent used").threshold("percent", "Percent used", 80, 50, 100).build())
                .register(EventType.builder(LICENCE_EXPIRED.value(), "Licence expired", EventKind.BUSINESS)
                        .critical().recipients(IT_ADMIN, APPROVER).build())
                .build();
    }

    private static NotificationEvent rejected() {
        return event(FILES_REJECTED).identifier(RecordIdentifier.of("b-1")).count("files", 2)
                .link(AppLink.of("/intake/b-1")).build();
    }

    private static NotificationEvent storeDown() {
        return event(STORE_DOWN).identifier(RecordIdentifier.of("primary")).link(AppLink.of("/health")).build();
    }

    @Test
    void savedDataOfARemovedTypeIsIgnoredAfterTheUpgrade() {
        Fixture f = new Fixture();
        // This release: an hourly summary item and a failing condition are saved, nothing is sent yet.
        f.via(f.notifier).publish(rejected());
        f.via(f.notifier).conditionFailing(storeDown());
        assertTrue(f.email.sent().isEmpty());
        assertEquals(1, f.notifier.pendingSummaryItems());

        // The next release removes both types; the same stores hold what was saved.
        NotificationSettings upgraded = new NotificationSettings(nextRelease(), f.store);
        upgraded.connectChannels(TENANT, tenantSettings(java.time.ZoneOffset.UTC));
        Notifier next = f.notifier(upgraded, f.resolver, List.of(f.email, f.teams, f.slack));
        f.clock.advance(Duration.ofHours(2));
        List<DeliveryReport> reports = assertDoesNotThrow(() -> next.tick(f.clock.instant()));
        next.sendDue(f.clock.instant());
        assertTrue(reports.stream().noneMatch(r -> r.eventTypes().contains(FILES_REJECTED)
                || r.eventTypes().contains(STORE_DOWN)), reports.toString());
        assertTrue(f.email.sent().isEmpty(), "nothing is sent for a removed type");
        assertThrows(NotificationException.class, () -> next.publish(rejected()),
                "a new event of a removed type is refused as unknown");
    }

    @Test
    void withTheTypesStillThereTheSameDataIsSent() {
        Fixture f = new Fixture();
        f.via(f.notifier).publish(rejected());
        f.via(f.notifier).conditionFailing(storeDown());
        f.clock.advance(Duration.ofHours(2));
        f.notifier.tick(f.clock.instant());
        f.notifier.sendDue(f.clock.instant());
        assertFalse(f.email.sent().isEmpty(), "control: the saved data is sent when the types exist");
    }
}
