package com.documentplatform.notifications.jdbc;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.ConditionKey;
import com.documentplatform.notifications.ConditionState;
import com.documentplatform.notifications.ConditionTiming;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.OutboxEntry;
import com.documentplatform.notifications.OutboxState;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.SummaryBucket;
import com.documentplatform.notifications.SummaryItem;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.support.Pg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static com.documentplatform.notifications.support.TestCatalogue.E1;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.TEAMS;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Postgres stores against a real Postgres (Testcontainers), each test in a fresh schema. */
class PostgresStoresTest {

    private static final Instant T0 = Instant.parse("2026-11-20T10:05:00.123Z");
    private static final EventTypeId TYPE = EventTypeId.of("intake.files-rejected");

    private DataSource ds;

    @BeforeEach
    void schema() {
        ds = Pg.freshSchema();
    }

    @Test
    void migrationCreatesOnlyNotifTables() {
        assertEquals(List.of("notif_approval_request", "notif_condition", "notif_destination", "notif_event_settings",
                "notif_outbox",
                "notif_schema_version", "notif_summary_item", "notif_tenant"), Pg.tables(ds));
    }

    @Test
    void tenantSettingsRoundTrip() {
        PostgresSettingsStore store = new PostgresSettingsStore(ds);
        assertTrue(store.tenant(TENANT).isEmpty());
        TenantSettings t = new TenantSettings(URI.create("https://app.customer.example/"), ZoneId.of("Europe/Amsterdam"),
                Map.of(MAIL, ChannelKind.EMAIL, TEAMS, ChannelKind.TEAMS));
        store.saveTenant(TENANT, t);
        assertEquals(t, store.tenant(TENANT).orElseThrow());
        TenantSettings fewer = new TenantSettings(t.appBaseUrl(), t.zone(), Map.of(MAIL, ChannelKind.EMAIL));
        store.saveTenant(TENANT, fewer);
        assertEquals(fewer, store.tenant(TENANT).orElseThrow(), "a disconnected destination is gone");
    }

    @Test
    void eventSettingsRoundTripPerLevel() {
        PostgresSettingsStore store = new PostgresSettingsStore(ds);
        EventSettings technical = new EventSettings(true, Schedule.onceADay(LocalTime.of(8, 30)),
                Set.of(RoleId.of("ops"), RoleId.of("it-admin")), Set.of(TEAMS),
                new ConditionTiming(Duration.ofMinutes(20), Duration.ofHours(2)), Map.of("percent", 90L, "files", 5L));
        store.saveTechnical(TENANT, TYPE, technical);
        assertEquals(technical, store.technical(TENANT, TYPE).orElseThrow());

        EventSettings business = new EventSettings(false, Schedule.every4Hours(), Set.of(RoleId.of("approver")),
                Set.of(), null, Map.of());
        store.saveBusiness(TENANT, E1, TYPE, business);
        assertEquals(business, store.business(TENANT, E1, TYPE).orElseThrow());
        assertTrue(store.business(TENANT, EntityId.of("e2"), TYPE).isEmpty());

        EventSettings changed = business.withEnabled(true).withSchedule(Schedule.immediately());
        store.saveBusiness(TENANT, E1, TYPE, changed);
        assertEquals(changed, store.business(TENANT, E1, TYPE).orElseThrow());
        assertEquals(technical, store.technical(TENANT, TYPE).orElseThrow(), "the levels are kept apart");
    }

    private static OutboxEntry entry(String key, Instant now) {
        return OutboxEntry.pending(key, TENANT, TEAMS, ChannelKind.TEAMS, E1, Set.of(RoleId.of("approver")),
                Set.of(TYPE), new Message(MessageKind.EVENT, "Files rejected: LN 12345",
                        List.of("Time: 2026-11-20 10:05 Z", "Files: 3"), URI.create("https://app.customer.example/x")),
                now);
    }

    @Test
    void outboxSavesEachKeyOnceAndClaimsWithALease() {
        PostgresOutboxStore outbox = new PostgresOutboxStore(ds);
        OutboxEntry e = entry("m-1", T0);
        assertTrue(outbox.enqueue(e));
        assertFalse(outbox.enqueue(entry("m-1", T0.plusSeconds(60))), "same key: kept once");
        assertEquals(e, outbox.find("m-1").orElseThrow());

        OutboxEntry claimed = outbox.claim("m-1", T0, T0.plus(Duration.ofMinutes(5))).orElseThrow();
        assertEquals(OutboxState.SENDING, claimed.state());
        assertTrue(outbox.claim("m-1", T0.plusSeconds(10), T0.plus(Duration.ofMinutes(6))).isEmpty(), "leased");
        assertTrue(outbox.claimDue(T0.plus(Duration.ofMinutes(4)), T0.plus(Duration.ofMinutes(9)), 10).isEmpty());
        assertEquals(1, outbox.claimDue(T0.plus(Duration.ofMinutes(5)), T0.plus(Duration.ofMinutes(10)), 10).size(),
                "a lapsed lease is claimed again");

        outbox.markRetry("m-1", 1, T0.plus(Duration.ofMinutes(7)), FailureCode.WEBHOOK_RATE_LIMITED);
        OutboxEntry retry = outbox.find("m-1").orElseThrow();
        assertEquals(OutboxState.PENDING, retry.state());
        assertEquals(1, retry.attempts());
        assertEquals(FailureCode.WEBHOOK_RATE_LIMITED, retry.lastFailure());
        assertTrue(outbox.claimDue(T0.plus(Duration.ofMinutes(6)), T0.plus(Duration.ofMinutes(11)), 10).isEmpty());

        outbox.claim("m-1", T0.plus(Duration.ofMinutes(7)), T0.plus(Duration.ofMinutes(12))).orElseThrow();
        outbox.markSent("m-1", 2, T0.plus(Duration.ofMinutes(7)));
        OutboxEntry sent = outbox.find("m-1").orElseThrow();
        assertEquals(OutboxState.SENT, sent.state());
        assertEquals(2, sent.attempts());
        assertTrue(outbox.claimDue(T0.plus(Duration.ofDays(1)), T0.plus(Duration.ofDays(2)), 10).isEmpty());

        outbox.enqueue(entry("m-2", T0));
        outbox.claim("m-2", T0, T0.plusSeconds(60));
        outbox.markFailed("m-2", 1, FailureCode.WEBHOOK_ADDRESS_INVALID, T0.plus(Duration.ofMinutes(8)));
        assertEquals(OutboxState.FAILED, outbox.find("m-2").orElseThrow().state());

        assertEquals(1, outbox.purgeFinishedBefore(T0.plus(Duration.ofMinutes(8))));
        assertTrue(outbox.find("m-1").isEmpty());
        assertEquals(1, outbox.purgeFinishedBefore(T0.plus(Duration.ofMinutes(9))));
    }

    @Test
    void concurrentWorkersNeverClaimTheSameMessage() throws Exception {
        PostgresOutboxStore outbox = new PostgresOutboxStore(ds);
        IntStream.range(0, 200).forEach(i -> outbox.enqueue(entry("m-" + i, T0)));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> workers = IntStream.range(0, 4).<Callable<Integer>>mapToObj(w -> () -> {
                int n = 0;
                List<OutboxEntry> batch;
                while (!(batch = outbox.claimDue(T0, T0.plus(Duration.ofMinutes(5)), 7)).isEmpty()) {
                    for (OutboxEntry e : batch) {
                        outbox.markSent(e.key(), 1, T0);
                        n++;
                    }
                }
                return n;
            }).toList();
            int total = 0;
            for (Future<Integer> f : pool.invokeAll(workers)) {
                total += f.get();
            }
            assertEquals(200, total, "each of the 200 claimed exactly once across 4 workers");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void summaryItemsAreKeptOnceAndComeBackInOrderWhenDue() {
        PostgresSummaryStore store = new PostgresSummaryStore(ds);
        SummaryBucket hourly = new SummaryBucket(TENANT, MAIL, Schedule.everyHour(), E1, Set.of(RoleId.of("ops")),
                Instant.parse("2026-11-20T11:00:00Z"));
        SummaryBucket daily = new SummaryBucket(TENANT, MAIL, Schedule.onceADay(LocalTime.of(8, 0)), E1, Set.of(),
                Instant.parse("2026-11-21T08:00:00Z"));
        NotificationEvent event = NotificationEvent.builder(TYPE, TENANT).entity(E1)
                .identifier(RecordIdentifier.of("LN 12345")).count("files", 3)
                .time("seen", Instant.parse("2026-11-20T09:00:00Z")).link(AppLink.of("/intake/b-1"))
                .occurredAt(T0).build();
        SummaryItem a = new SummaryItem("s-a", hourly, event, MessageKind.EVENT, T0, null, 0);
        SummaryItem b = new SummaryItem("s-b", hourly, event, MessageKind.CONDITION_ALERT, T0.plusSeconds(5),
                T0.minusSeconds(900), 4);
        SummaryItem c = new SummaryItem("s-c", daily, event, MessageKind.EVENT, T0, null, 0);
        store.add(a);
        store.add(b);
        store.add(a);
        store.add(c);
        assertEquals(3, store.pending());
        assertTrue(store.due(Instant.parse("2026-11-20T10:59:59Z")).isEmpty());
        assertEquals(List.of(a, b), store.due(Instant.parse("2026-11-20T11:00:00Z")));
        store.remove(List.of("s-a", "s-b"));
        assertEquals(List.of(c), store.due(Instant.parse("2026-11-22T00:00:00Z")));
    }

    @Test
    void conditionsCountFailuresAndRememberAlerts() {
        PostgresConditionStore store = new PostgresConditionStore(ds);
        NotificationEvent e = NotificationEvent.builder(TYPE, TENANT).identifier(RecordIdentifier.of("mail 1")).build();
        ConditionKey key = ConditionKey.of(e);
        ConditionState first = store.recordFailure(key, e, T0);
        assertEquals(1, first.failures());
        assertEquals(T0, first.since());
        ConditionState second = store.recordFailure(key, e, T0.plusSeconds(60));
        assertEquals(2, second.failures());
        assertEquals(T0, second.since(), "the condition keeps its start");
        assertEquals(0, second.alerts());
        assertNull(second.lastAlertAt());

        store.markAlerted(key, 1, T0.plus(Duration.ofMinutes(15)));
        ConditionState alerted = store.find(key).orElseThrow();
        assertEquals(1, alerted.alerts());
        assertEquals(T0.plus(Duration.ofMinutes(15)), alerted.lastAlertAt());
        assertEquals(List.of(alerted), store.active());

        NotificationEvent other = NotificationEvent.builder(TYPE, TENANT).entity(E1).build();
        store.recordFailure(ConditionKey.of(other), other, T0);
        assertEquals(2, store.active().size());
        store.remove(key);
        assertEquals(Optional.empty(), store.find(key));
        assertEquals(1, store.active().size());
    }

    @Test
    void schemaScriptsAreListedInOrder() {
        assertEquals(List.of("V1__notifications.sql"), NotificationSchema.scripts());
        assertTrue(NotificationSchema.script("V1__notifications.sql").contains("create table notif_outbox"));
    }
}
