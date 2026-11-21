package com.documentplatform.notifications;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.documentplatform.notifications.Fixture.ANN;
import static com.documentplatform.notifications.Fixture.BOB;
import static com.documentplatform.notifications.Fixture.DELIVERY_FAILING;
import static com.documentplatform.notifications.Fixture.SOURCE_UNREACHABLE;
import static com.documentplatform.notifications.Fixture.STORE_DOWN;
import static com.documentplatform.notifications.Fixture.TENANT;
import static com.documentplatform.notifications.Fixture.event;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTrackerTest {

    private final Fixture f = new Fixture();

    private static NotificationEvent store(String id) {
        return event(STORE_DOWN).identifier(RecordIdentifier.of(id)).link(AppLink.of("/health")).build();
    }

    private List<MessageKind> sentKinds() {
        return f.email.sent().stream().map(m -> m.message().kind()).toList();
    }

    @Test
    void noAlertBeforeTheThresholdThenOneAlert() {
        assertTrue(f.via(f.notifier).conditionFailing(store("primary")).isEmpty());
        assertTrue(f.advance(Duration.ofMinutes(14)).isEmpty());
        List<DeliveryReport> reports = f.advance(Duration.ofMinutes(1));
        assertEquals(1, reports.size());
        assertEquals(MessageKind.CONDITION_ALERT, reports.get(0).kind());
        assertEquals(Set.of(ANN), f.email.sent().get(0).recipients());
        assertEquals("""
                Store down: primary
                Since: 2026-11-20 10:05 Z
                Failures: 1
                https://app.example.test/health""", f.email.sent().get(0).message().text());
    }

    @Test
    void noDuplicateAlertsDuringAContinuousFailure() {
        f.via(f.notifier).conditionFailing(store("primary"));
        for (int minute = 1; minute < 4 * 60; minute++) {
            f.clock.advance(Duration.ofMinutes(1));
            f.via(f.notifier).conditionFailing(store("primary"));
            f.notifier.tick(f.clock.instant());
        }
        assertEquals(List.of(MessageKind.CONDITION_ALERT), sentKinds());
        assertTrue(f.email.sent().get(0).message().text().contains("Failures: 16"));
    }

    @Test
    void repeatsWhileItContinues() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.advance(Duration.ofMinutes(15));
        f.advance(Duration.ofHours(3).plusMinutes(59));
        assertEquals(List.of(MessageKind.CONDITION_ALERT), sentKinds());
        f.advance(Duration.ofMinutes(1));
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.CONDITION_ONGOING), sentKinds());
        assertTrue(f.email.sent().get(1).message().subject().startsWith("Still ongoing: Store down: primary"));
        assertTrue(f.email.sent().get(1).message().text().contains("Duration: 4 h 15 min"));
        f.advance(Duration.ofHours(4));
        assertEquals(3, f.email.sent().size());
    }

    @Test
    void recoveredIsSentOnce() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.advance(Duration.ofMinutes(20));
        f.clock.advance(Duration.ofMinutes(10));
        ConditionKey key = ConditionKey.of(store("primary"));
        List<DeliveryReport> reports = f.via(f.notifier).conditionCleared(key);
        assertEquals(MessageKind.RECOVERED, reports.get(0).kind());
        assertTrue(f.via(f.notifier).conditionCleared(key).isEmpty());
        assertTrue(f.advance(Duration.ofHours(5)).isEmpty());
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.RECOVERED), sentKinds());
        assertEquals("""
                Recovered: Store down: primary
                Since: 2026-11-20 10:05 Z
                Recovered at: 2026-11-20 10:35 Z
                Duration: 30 min
                https://app.example.test/health""", f.email.sent().get(1).message().text());
        assertFalse(f.notifier.isConditionActive(key));
    }

    @Test
    void aSupersededConditionEndsWithoutRecoveredAndStopsRepeating() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.advance(Duration.ofMinutes(15));
        ConditionKey key = ConditionKey.of(store("primary"));
        f.notifier.conditionSuperseded(key);
        assertFalse(f.notifier.isConditionActive(key));
        assertTrue(f.via(f.notifier).conditionCleared(key).isEmpty(), "nothing left to recover");
        assertTrue(f.advance(Duration.ofHours(8)).isEmpty(), "no repeats");
        assertEquals(List.of(MessageKind.CONDITION_ALERT), sentKinds());
        f.notifier.conditionSuperseded(key); // a second call finds nothing
    }

    @Test
    void clearedBeforeTheThresholdSendsNothing() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.advance(Duration.ofMinutes(10));
        assertTrue(f.via(f.notifier).conditionCleared(ConditionKey.of(store("primary"))).isEmpty());
        assertTrue(f.advance(Duration.ofHours(1)).isEmpty());
        assertTrue(f.email.sent().isEmpty());
    }

    @Test
    void afterRecoveryANewFailureStartsANewCondition() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.advance(Duration.ofMinutes(15));
        f.via(f.notifier).conditionCleared(ConditionKey.of(store("primary")));
        f.via(f.notifier).conditionFailing(store("primary"));
        assertTrue(f.advance(Duration.ofMinutes(14)).isEmpty());
        f.advance(Duration.ofMinutes(1));
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.RECOVERED, MessageKind.CONDITION_ALERT), sentKinds());
        assertTrue(f.email.sent().get(2).message().text().contains("Failures: 1"));
    }

    @Test
    void separateRecordsAreSeparateConditions() {
        f.via(f.notifier).conditionFailing(store("primary"));
        f.clock.advance(Duration.ofMinutes(5));
        f.via(f.notifier).conditionFailing(store("replica"));
        f.advance(Duration.ofMinutes(10));
        assertEquals(1, f.email.sent().size());
        f.advance(Duration.ofMinutes(5));
        assertEquals(2, f.email.sent().size());
        assertTrue(f.email.sent().get(1).message().subject().endsWith("replica"));
    }

    @Test
    void adjustedThresholdIsUsed() {
        f.settings.setTechnicalEvent(TENANT, STORE_DOWN,
                EventSettings.defaultsFor(f.settings.catalogue().require(STORE_DOWN))
                        .withConditionTiming(new ConditionTiming(Duration.ZERO, Duration.ofMinutes(30))));
        List<DeliveryReport> reports = f.via(f.notifier).conditionFailing(store("primary"));
        assertEquals(MessageKind.CONDITION_ALERT, reports.get(0).kind());
        f.advance(Duration.ofMinutes(30));
        assertEquals(2, f.email.sent().size());
    }

    @Test
    void typeDefaultThresholdIsUsed() {
        f.via(f.notifier).conditionFailing(event(SOURCE_UNREACHABLE).identifier(RecordIdentifier.of("mail-1")).build());
        assertTrue(f.advance(Duration.ofMinutes(29)).isEmpty());
        assertEquals(1, f.advance(Duration.ofMinutes(1)).size());
        assertEquals(Set.of(BOB), f.email.sent().get(0).recipients());
    }

    @Test
    void hourlyCriticalConditionGoesIntoTheHourlySummary() {
        f.via(f.notifier).conditionFailing(event(DELIVERY_FAILING).identifier(RecordIdentifier.of("target-1")).build());
        List<DeliveryReport> alert = f.advance(Duration.ofMinutes(15));
        assertEquals(DeliveryStatus.QUEUED_FOR_SUMMARY, alert.get(0).status());
        f.clock.advance(Duration.ofMinutes(5));
        f.via(f.notifier).conditionCleared(ConditionKey.of(event(DELIVERY_FAILING).identifier(RecordIdentifier.of("target-1")).build()));
        f.advance(Duration.ofMinutes(35)); // 11:00
        assertEquals(1, f.email.sent().size());
        String text = f.email.sent().get(0).message().text();
        assertTrue(text.contains("Delivery failing: 2"), text);
        assertTrue(text.contains("- Alert: target-1") && text.contains("- Recovered: target-1"), text);
    }
}
