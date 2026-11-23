package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.jdbc.PostgresApprovalStore;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.support.Stack;
import com.documentplatform.notifications.support.TestCatalogue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.documentplatform.notifications.support.TestCatalogue.E1;
import static com.documentplatform.notifications.support.TestCatalogue.IT_ADMIN;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Told once with several instances on Postgres: two app instances (each with its own store, notifier and approvals over
 * the same database) call {@link Approvals#tellExpired} at the same moment; the {@code FOR UPDATE SKIP LOCKED} claim
 * gives each expired request to one of them, so every requester gets exactly one "expired" message.
 */
class ApprovalExpiryOnPostgresTest {

    static final RoleId APPROVER = RoleId.of("print-approver");
    static final EventTypeId WAITING = EventTypeId.of("approval.print-waiting");
    static final EventTypeId APPROVED = EventTypeId.of("approval.print-approved");
    static final EventTypeId DECLINED = EventTypeId.of("approval.print-declined");
    static final EventTypeId EXPIRED = EventTypeId.of("approval.print-expired");
    static final Recipient CY = new Recipient("u-cy", "cy@customer.example");
    static final SecretRef KEY_REF = SecretRef.of("notifications/approval-link-key");
    static final int REQUESTS = 30;
    static final int ROUNDS = 5;

    private Stack s;
    private NotificationSettings settings;

    @BeforeEach
    void wire() throws Exception {
        s = new Stack();
        s.secrets.put(KEY_REF, "LINK-KEY-SECRET-0123456789-abcdefghijklmnopqrstuvwxyz");
        EventCatalogue catalogue = EventCatalogue.builder(IT_ADMIN)
                .register(EventType.builder(WAITING.value(), "Print request waiting", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .register(EventType.builder(APPROVED.value(), "Print request approved", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .register(EventType.builder(DECLINED.value(), "Print request declined", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .register(EventType.builder(EXPIRED.value(), "Print request expired", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .build();
        settings = new NotificationSettings(catalogue, new PostgresSettingsStore(s.ds));
        settings.connectChannels(TENANT, TestCatalogue.tenant());
        settings.setBusinessEvent(TENANT, E1, WAITING, EventSettings.defaultsFor(catalogue.require(WAITING))
                .withDestinations(MAIL));
    }

    @AfterEach
    void close() {
        s.close();
    }

    private static String requester(int round, int i) {
        return "u-req-" + round + "-" + i;
    }

    /** One app instance: its own store, notifier and approvals, over the shared database. */
    private Approvals instance() {
        RecipientResolver people = new RecipientResolver() {
            @Override
            public Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
                return role.equals(APPROVER) && E1.equals(entity) ? Set.of(CY) : Set.of();
            }

            @Override
            public Optional<Recipient> person(TenantId tenant, String userId) {
                return userId.startsWith("u-req-")
                        ? Optional.of(new Recipient(userId, userId + "@customer.example")) : Optional.empty();
            }
        };
        PostgresApprovalStore store = new PostgresApprovalStore(s.ds);
        ActionTokens tokens = new ActionTokens(s.secretResolver, KEY_REF);
        Notifier notifier = Notifier.builder(settings, people).channels(List.of(s.email)).clock(s.clock)
                .outbox(s.outbox).summaries(new PostgresSummaryStore(s.ds))
                .conditions(new PostgresConditionStore(s.ds))
                .actionLinks(new ApprovalLinks(store, tokens, AppLink.of("/approvals/link"))).build();
        return Approvals.builder(List.of(ApprovalType.of("print", WAITING, APPROVED, DECLINED, APPROVER)
                                .whenExpired(EXPIRED)), settings.catalogue(), store, tokens, notifier)
                .approvers((tenant, entity, role, user) -> role.equals(APPROVER) && user.equals(CY.userId()))
                .audit(entry -> { }).clock(s.clock).build();
    }

    /** Outbox messages to each requester of the round (the "expired" message is the only one sent to a person). */
    private Map<String, Integer> toldPerRequester(int round) throws Exception {
        Map<String, Integer> out = new TreeMap<>();
        try (Connection c = s.ds.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select recipient_user, count(*) from notif_outbox "
                     + "where recipient_user like 'u-req-" + round + "-%' group by recipient_user")) {
            while (rs.next()) out.put(rs.getString(1), rs.getInt(2));
        }
        return out;
    }

    private int count(String sql) throws Exception {
        try (Connection c = s.ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void twoInstancesAtOnceTellEachRequesterOnce() throws Exception {
        Approvals first = instance();
        Approvals second = instance();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < REQUESTS; i++) {
                    first.create(new NewApprovalRequest("print", TENANT, E1, requester(round, i),
                            RecordIdentifier.of("PR-" + round + "-" + i), AppLink.of("/print-requests/" + i)));
                }
                s.clock.advance(Duration.ofDays(1));
                CyclicBarrier together = new CyclicBarrier(2);
                List<Future<Integer>> runs = new ArrayList<>();
                for (Approvals instance : List.of(first, second)) {
                    runs.add(pool.submit(() -> {
                        together.await(10, TimeUnit.SECONDS);
                        return instance.tellExpired(s.clock.instant());
                    }));
                }
                int told = 0;
                for (Future<Integer> r : runs) told += r.get(60, TimeUnit.SECONDS);
                assertEquals(REQUESTS, told, "round " + round + ": each expired request claimed by one instance");

                Map<String, Integer> perRequester = toldPerRequester(round);
                assertEquals(REQUESTS, perRequester.size(), "round " + round + ": every requester told");
                int r = round;
                perRequester.forEach((who, n) -> assertEquals(1, n, "round " + r + ": " + who + " told " + n + " times"));

                assertEquals(0, first.tellExpired(s.clock.instant()) + second.tellExpired(s.clock.instant()),
                        "told once: a later pass claims nothing");
                assertEquals(REQUESTS, count("select count(*) from notif_approval_request where expiry_told_at is not null "
                        + "and requester like 'u-req-" + round + "-%'"));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
