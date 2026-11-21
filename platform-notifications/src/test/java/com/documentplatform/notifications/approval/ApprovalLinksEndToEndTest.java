package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DeliveryStatus;
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
import com.documentplatform.notifications.support.LogCapture;
import com.documentplatform.notifications.support.Pg;
import com.documentplatform.notifications.support.Stack;
import com.documentplatform.notifications.support.TestCatalogue;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.documentplatform.notifications.approval.ApprovalAction.APPROVE;
import static com.documentplatform.notifications.approval.ApprovalAction.DECLINE;
import static com.documentplatform.notifications.support.TestCatalogue.E1;
import static com.documentplatform.notifications.support.TestCatalogue.IT_ADMIN;
import static com.documentplatform.notifications.support.TestCatalogue.MAIL;
import static com.documentplatform.notifications.support.TestCatalogue.SLACK;
import static com.documentplatform.notifications.support.TestCatalogue.TEAMS;
import static com.documentplatform.notifications.support.TestCatalogue.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Approval links with everything real: Postgres stores, the SMTP sender against GreenMail, the Teams and Slack
 * senders against a local webhook server, the key behind a secret resolver.
 */
class ApprovalLinksEndToEndTest {

    static final RoleId APPROVER = RoleId.of("print-approver");
    static final EventTypeId WAITING = EventTypeId.of("approval.print-waiting");
    static final EventTypeId APPROVED = EventTypeId.of("approval.print-approved");
    static final EventTypeId DECLINED = EventTypeId.of("approval.print-declined");
    static final Recipient REQ = new Recipient("u-req", "req@customer.example");
    static final Recipient CY = new Recipient("u-cy", "cy@customer.example");
    static final Recipient DEE = new Recipient("u-dee", "dee@customer.example");
    static final SignedInPerson AS_CY = new SignedInPerson(TENANT, CY.userId(), "Cy Lee");
    static final SignedInPerson AS_DEE = new SignedInPerson(TENANT, DEE.userId(), "Dee Moss");
    static final SecretRef KEY_REF = SecretRef.of("notifications/approval-link-key");
    static final String KEY = "LINK-KEY-SECRET-0123456789-abcdefghijklmnopqrstuvwxyz";
    static final String REASON = "REASON-TEXT legal hold on file 4711";

    private Stack s;
    private NotificationSettings settings;
    private PostgresApprovalStore store;
    private ActionTokens tokens;
    private Notifier notifier;
    private Approvals approvals;
    private final List<ApprovalAudit.Entry> audit = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> approvers = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void wire() throws Exception {
        s = new Stack();
        s.secrets.put(KEY_REF, KEY);
        EventCatalogue catalogue = EventCatalogue.builder(IT_ADMIN)
                .register(EventType.builder(WAITING.value(), "Print request waiting", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).time("expiresAt", "Expires").build())
                .register(EventType.builder(APPROVED.value(), "Print request approved", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .register(EventType.builder(DECLINED.value(), "Print request declined", EventKind.BUSINESS)
                        .schedule(Schedule.immediately()).recipients(APPROVER).build())
                .build();
        settings = new NotificationSettings(catalogue, new PostgresSettingsStore(s.ds));
        settings.connectChannels(TENANT, TestCatalogue.tenant());
        settings.setBusinessEvent(TENANT, E1, WAITING, EventSettings.defaultsFor(catalogue.require(WAITING))
                .withDestinations(MAIL, TEAMS, SLACK));
        approvers.addAll(List.of(CY.userId(), DEE.userId()));
        RecipientResolver people = new RecipientResolver() {
            @Override
            public Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
                return role.equals(APPROVER) && E1.equals(entity)
                        ? Set.of(REQ, CY, DEE).stream().filter(r -> approvers.contains(r.userId())
                        || r.equals(REQ)).collect(java.util.stream.Collectors.toSet())
                        : Set.of();
            }

            @Override
            public java.util.Optional<Recipient> person(TenantId tenant, String userId) {
                return Set.of(REQ, CY, DEE).stream().filter(r -> r.userId().equals(userId)).findFirst();
            }
        };
        store = new PostgresApprovalStore(s.ds);
        tokens = new ActionTokens(s.secretResolver, KEY_REF);
        notifier = Notifier.builder(settings, people).channels(List.of(s.email, s.teams, s.slack)).clock(s.clock)
                .outbox(s.outbox).summaries(new PostgresSummaryStore(s.ds)).conditions(new PostgresConditionStore(s.ds))
                .actionLinks(new ApprovalLinks(store, tokens, AppLink.of("/approvals/link"))).build();
        approvals = approvals(notifier);
    }

    private Approvals approvals(Notifier n) {
        return Approvals.builder(List.of(ApprovalType.of("print", WAITING, APPROVED, DECLINED, APPROVER)),
                        settings.catalogue(), store, tokens, n)
                .approvers((tenant, entity, role, user) -> role.equals(APPROVER) && approvers.contains(user))
                .audit(audit::add).clock(s.clock).build();
    }

    @AfterEach
    void close() {
        s.close();
    }

    private ApprovalRequest create() {
        return approvals.create(new NewApprovalRequest("print", TENANT, E1, REQ.userId(),
                RecordIdentifier.of("PR-1001"), AppLink.of("/print-requests/PR-1001"))).request();
    }

    private List<DeliveryReport> send() {
        return notifier.sendDue(s.clock.instant());
    }

    private static final Pattern LINK = Pattern.compile("https://app\\.customer\\.example/approvals/link/([A-Za-z0-9_.-]+)");

    private String mailBody(String address) throws Exception {
        List<MimeMessage> to = new ArrayList<>();
        for (MimeMessage m : s.mail.getReceivedMessages()) {
            if (Arrays.stream(m.getAllRecipients()).anyMatch(a -> a.toString().equals(address))) {
                to.add(m);
            }
        }
        assertEquals(1, to.size(), "one e-mail to " + address);
        assertEquals(1, to.get(0).getAllRecipients().length, "addressed to that person only");
        return ((String) to.get(0).getContent()).replace("\r\n", "\n");
    }

    /** The token after "Approve: " or "Decline: " in a plain-text e-mail. */
    private static String token(String body, ApprovalAction action) {
        Matcher m = Pattern.compile(action.label() + ": " + LINK.pattern()).matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    @Test
    void theRealMessagesCarryTheTwoLinks() throws Exception {
        create();
        List<DeliveryReport> reports = send();
        assertEquals(List.of(DeliveryStatus.SENT, DeliveryStatus.SENT, DeliveryStatus.SENT),
                reports.stream().map(DeliveryReport::status).toList());

        assertEquals(2, s.mail.getReceivedMessages().length, "one e-mail per approver, none to the requester");
        String toCy = mailBody(CY.emailAddress());
        assertTrue(toCy.startsWith("Print request waiting: PR-1001\nTime: 2026-11-20 10:05 Z\n"
                + "Expires: 2026-11-21 10:05 Z\n\nApprove: https://app.customer.example/approvals/link/"), toCy);
        assertTrue(toCy.contains("\nDecline: https://app.customer.example/approvals/link/"), toCy);
        assertTrue(toCy.stripTrailing().endsWith("\n\nhttps://app.customer.example/print-requests/PR-1001"), toCy);
        String toDee = mailBody(DEE.emailAddress());
        assertFalse(token(toCy, APPROVE).equals(token(toDee, APPROVE)), "each approver has their own links");

        String teams = s.hooks.requests().stream().filter(r -> r.path().startsWith("/workflows")).findFirst()
                .orElseThrow().body();
        assertTrue(teams.contains("\"actions\":[{\"type\":\"Action.OpenUrl\",\"title\":\"Approve\",\"url\":"
                + "\"https://app.customer.example/approvals/link/"), teams);
        assertTrue(teams.contains("{\"type\":\"Action.OpenUrl\",\"title\":\"Decline\",\"url\":"
                + "\"https://app.customer.example/approvals/link/"), teams);
        assertTrue(teams.contains("{\"type\":\"Action.OpenUrl\",\"title\":\"Open in the app\",\"url\":"
                + "\"https://app.customer.example/print-requests/PR-1001\"}]"), teams);
        assertFalse(teams.contains("Action.Submit") || teams.contains("Action.Execute"), "no callbacks into Teams");

        String slack = s.hooks.requests().stream().filter(r -> r.path().startsWith("/services")).findFirst()
                .orElseThrow().body();
        assertTrue(slack.contains("{\"type\":\"actions\",\"elements\":[{\"type\":\"button\",\"text\":{\"type\":"
                + "\"plain_text\",\"text\":\"Approve\",\"emoji\":false},\"url\":"
                + "\"https://app.customer.example/approvals/link/"), slack);
        assertTrue(slack.contains("\"text\":\"Decline\""), slack);
        assertTrue(slack.contains("\"text\":\"Open in the app\""), slack);
    }

    @Test
    void approveFromTheEmailAndTheRequesterIsTold() throws Exception {
        ApprovalRequest r = create();
        send();
        String link = token(mailBody(CY.emailAddress()), APPROVE);
        assertEquals(new ApprovalResult.Ready(r, APPROVE), approvals.open(link, AS_CY));
        assertEquals(null, store.find(TENANT, r.id()).orElseThrow().decision(), "opening decided nothing");

        s.clock.advance(Duration.ofMinutes(3));
        ApprovalRequest decided = assertInstanceOf(ApprovalResult.Decided.class,
                approvals.decide(link, AS_CY, null)).request();
        assertEquals(decided, store.find(TENANT, r.id()).orElseThrow(), "round trip through Postgres");
        send();
        String told = mailBody(REQ.emailAddress());
        assertEquals("Print request approved: PR-1001\nTime: 2026-11-20 10:08 Z\n"
                + "https://app.customer.example/print-requests/PR-1001", told.stripTrailing());

        ApprovalResult late = approvals.open(token(mailBody(DEE.emailAddress()), DECLINE), AS_DEE);
        assertEquals("Already approved by Cy Lee at 2026-11-20 10:08 Z", assertInstanceOf(
                ApprovalResult.AlreadyDecided.class, late).request().outcomeText(java.time.ZoneOffset.UTC).orElseThrow());
    }

    @Test
    void theDatabaseLetsExactlyOneOfManyConcurrentDecisionsWin() throws Exception {
        ApprovalRequest r = create();
        send();
        List<String> links = List.of(token(mailBody(CY.emailAddress()), APPROVE),
                token(mailBody(DEE.emailAddress()), DECLINE));
        List<SignedInPerson> who = List.of(AS_CY, AS_DEE);
        // Two app instances (own Approvals, own store objects) on the same tables.
        List<Approvals> instances = List.of(approvals, Approvals.builder(
                        List.of(ApprovalType.of("print", WAITING, APPROVED, DECLINED, APPROVER)), settings.catalogue(),
                        new PostgresApprovalStore(s.ds), tokens, notifier)
                .approvers((t, e, role, u) -> true).audit(audit::add).clock(s.clock).build());
        int n = 24;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ApprovalResult>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i % 2;
            Approvals a = instances.get((i / 2) % 2);
            futures.add(pool.submit(() -> {
                go.await();
                return a.decide(links.get(k), who.get(k), REASON);
            }));
        }
        go.countDown();
        List<ApprovalResult> all = new ArrayList<>();
        for (Future<ApprovalResult> f : futures) {
            all.add(f.get());
        }
        pool.shutdown();
        assertEquals(1, all.stream().filter(x -> x instanceof ApprovalResult.Decided).count(), all.toString());
        assertEquals(n - 1, all.stream().filter(x -> x instanceof ApprovalResult.AlreadyDecided).count());
        Decision winner = all.stream().filter(x -> x instanceof ApprovalResult.Decided).findFirst()
                .map(x -> ((ApprovalResult.Decided) x).request().decision()).orElseThrow();
        assertEquals(winner, store.find(TENANT, r.id()).orElseThrow().decision());

        // And straight at the store: one update wins, whatever arrives at the same moment.
        ApprovalRequest second = create();
        CountDownLatch go2 = new CountDownLatch(1);
        ExecutorService pool2 = Executors.newFixedThreadPool(n);
        List<Future<ApprovalStore.Stored>> stored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Decision d = new Decision(i % 2 == 0 ? APPROVE : DECLINE, i % 2 == 0 ? CY.userId() : DEE.userId(),
                    "Approver " + i, s.clock.instant(), "r" + i);
            PostgresApprovalStore own = new PostgresApprovalStore(s.ds);
            stored.add(pool2.submit(() -> {
                go2.await();
                return own.decide(TENANT, second.id(), d, s.clock.instant());
            }));
        }
        go2.countDown();
        List<ApprovalStore.Stored.Kind> kinds = new ArrayList<>();
        for (Future<ApprovalStore.Stored> f : stored) {
            kinds.add(f.get().kind());
        }
        pool2.shutdown();
        assertEquals(1, kinds.stream().filter(k -> k == ApprovalStore.Stored.Kind.DECIDED).count(), kinds.toString());
        assertEquals(n - 1, kinds.stream().filter(k -> k == ApprovalStore.Stored.Kind.ALREADY_DECIDED).count());
    }

    @Test
    void theTableRefusesWhatTheRulesForbid() throws Exception {
        ApprovalRequest r = create();
        String id = r.id().toString();
        assertThrows(SQLException.class, () -> exec("update notif_approval_request set decision = 'APPROVED', "
                + "decided_by = 'u-req', decided_by_name = 'Rex', decided_at = now() where request_id = '" + id + "'"),
                "own request");
        assertThrows(SQLException.class, () -> exec("update notif_approval_request set decision = 'DECLINED', "
                + "decided_by = 'u-cy', decided_by_name = 'Cy', decided_at = now() where request_id = '" + id + "'"),
                "decline without reason");
        assertThrows(SQLException.class, () -> exec("update notif_approval_request set decision = 'APPROVED' "
                + "where request_id = '" + id + "'"), "decision without decider");

        assertEquals(ApprovalStore.Stored.Kind.EXPIRED, store.decide(TENANT, r.id(),
                new Decision(APPROVE, CY.userId(), "Cy", s.clock.instant(), null),
                r.expiresAt()).kind(), "not after expiry");
        assertEquals(ApprovalStore.Stored.Kind.UNKNOWN, store.decide(TENANT, UUID.randomUUID(),
                new Decision(APPROVE, CY.userId(), "Cy", s.clock.instant(), null), s.clock.instant()).kind());
        assertEquals(0, store.purgeExpiredBefore(r.expiresAt()));
        assertEquals(1, store.purgeExpiredBefore(r.expiresAt().plusSeconds(1)));
        assertTrue(store.find(TENANT, r.id()).isEmpty());
    }

    @Test
    void noTokenKeyReasonOrNameInLogsReportsOrOutbox() throws Exception {
        List<String> minted = new ArrayList<>();
        List<Object> results = new ArrayList<>();
        String logs;
        try (LogCapture capture = new LogCapture()) {
            ApprovalRequest r = create();
            results.addAll(send());
            for (Recipient p : List.of(CY, DEE)) {
                String body = mailBody(p.emailAddress());
                minted.add(token(body, APPROVE));
                minted.add(token(body, DECLINE));
            }
            for (var req : s.hooks.requests()) {
                Matcher m = LINK.matcher(req.body());
                while (m.find()) {
                    minted.add(m.group(1));
                }
            }
            String cyDecline = minted.get(1);
            String deeDecline = minted.get(3);
            results.add(approvals.open(cyDecline, AS_DEE)); // not for Dee
            results.add(approvals.decide(cyDecline, AS_CY, null)); // no reason
            results.add(approvals.decide(cyDecline.substring(0, 20) + "x" + cyDecline.substring(21), AS_CY, REASON));
            approvers.remove(DEE.userId());
            results.add(approvals.decide(deeDecline, AS_DEE, REASON)); // role removed
            approvers.add(DEE.userId());
            results.add(approvals.decide(cyDecline, AS_CY, REASON)); // decided
            results.add(approvals.decide(deeDecline, AS_DEE, REASON)); // too late
            s.secrets.remove(KEY_REF);
            results.add(approvals.open(cyDecline, AS_CY)); // key gone
            create();
            results.addAll(send()); // ACTION_KEY_NOT_FOUND
            results.addAll(send());
            results.add(r);
            logs = capture.all();
        }
        assertEquals(8, minted.size(), "2 e-mails x 2 links, Teams 2, Slack 2");
        // The JDBC driver's own FINEST trace shows the values it writes (the reason, the decider's name), as it does
        // for every stored value: that is the app's database logging. Tokens and the key are never written, so they
        // are not in it either.
        for (String secret : minted) {
            assertFalse(logs.contains(secret), "token in a log line");
        }
        assertFalse(logs.contains(KEY));
        String ours = logs.lines().filter(l -> !l.startsWith("FINEST org.postgresql."))
                .reduce("", (a, b) -> a + b + "\n");
        String everything = ours + "\n" + results + "\n" + audit;
        List<String> never = new ArrayList<>(minted);
        never.addAll(List.of(KEY, REASON, "legal hold", "Cy Lee", "Dee Moss", CY.emailAddress()));
        for (String secret : never) {
            String where = logs.lines().filter(l -> l.contains(secret)).findFirst().orElse("(not in logs)");
            assertFalse(everything.contains(secret), "found in logs or results: " + where.substring(0,
                    Math.min(300, where.length())));
        }
        String outbox = Pg.dumpAllTables(s.ds).lines().filter(l -> !l.startsWith("notif_approval_request "))
                .reduce("", (a, b) -> a + b + "\n");
        for (String secret : never) {
            assertFalse(outbox.contains(secret), "stored outside the request table: " + secret);
        }
        String requests = Pg.dumpAllTables(s.ds).lines().filter(l -> l.startsWith("notif_approval_request "))
                .reduce("", (a, b) -> a + b + "\n");
        for (String token : minted) {
            assertFalse(requests.contains(token), "no tokens stored");
        }
        assertFalse(requests.contains(KEY));
        assertTrue(requests.contains(REASON), "the reason is kept for the app's page");
        assertTrue(logs.contains("LINK_NOT_FOR_YOU") || logs.contains("REASON_REQUIRED"), "refusals logged by code");
        assertTrue(logs.contains("ACTION_KEY_NOT_FOUND"), logs);
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = s.ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
