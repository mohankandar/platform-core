package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.ActionLink;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DeliveryStatus;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.documentplatform.notifications.approval.ApprovalAction.APPROVE;
import static com.documentplatform.notifications.approval.ApprovalAction.DECLINE;
import static com.documentplatform.notifications.approval.ApprovalFixture.ANN;
import static com.documentplatform.notifications.approval.ApprovalFixture.APPROVER;
import static com.documentplatform.notifications.approval.ApprovalFixture.AS_ANN;
import static com.documentplatform.notifications.approval.ApprovalFixture.AS_CY;
import static com.documentplatform.notifications.approval.ApprovalFixture.AS_DEE;
import static com.documentplatform.notifications.approval.ApprovalFixture.AS_REQ;
import static com.documentplatform.notifications.approval.ApprovalFixture.CY;
import static com.documentplatform.notifications.approval.ApprovalFixture.DEE;
import static com.documentplatform.notifications.approval.ApprovalFixture.E1;
import static com.documentplatform.notifications.approval.ApprovalFixture.KEY_REF;
import static com.documentplatform.notifications.approval.ApprovalFixture.OTHER_TENANT;
import static com.documentplatform.notifications.approval.ApprovalFixture.REQ;
import static com.documentplatform.notifications.approval.ApprovalFixture.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The safety rules of approval links, with in-memory stores and channels. */
class ApprovalsTest {

    private final ApprovalFixture f = new ApprovalFixture();

    @Test
    void eachApproverGetsTheirOwnLinksAtOnceAndTheRequesterNone() {
        ApprovalRequest r = f.create();
        List<DeliveryReport> sent = f.send();

        assertEquals(1, sent.size(), "one outbox message for the e-mail destination");
        assertEquals(DeliveryStatus.SENT, sent.get(0).status(), "sent at once although the event type is 4-hourly");
        assertEquals(2, f.email.sent().size(), "one e-mail per approver; none to the requester");
        assertTrue(f.email.sent().stream().noneMatch(m -> m.recipients().contains(REQ)));
        OutgoingMessage toCy = f.mailTo(CY);
        OutgoingMessage toDee = f.mailTo(DEE);
        assertEquals(1, toCy.recipients().size());
        assertNotEquals(toCy.idempotencyKey(), toDee.idempotencyKey(), "each copy has its own Message-ID");
        assertEquals(List.of("Approve", "Decline"), toCy.message().actions().stream().map(ActionLink::label).toList());
        for (ActionLink link : toCy.message().actions()) {
            assertTrue(link.url().toString().startsWith("https://app.example.test/approvals/link/"), link.label());
        }
        assertNotEquals(f.token(CY, APPROVE), f.token(DEE, APPROVE), "the links are personal");
        assertNotEquals(f.token(CY, APPROVE), f.token(CY, DECLINE));

        String text = toCy.message().text();
        assertTrue(text.startsWith("Print request waiting: PR-1001\n"), text);
        assertTrue(text.contains("\nExpires: 2026-11-21 10:05 Z\n"), text);
        assertTrue(text.contains("\nApprove: https://app.example.test/approvals/link/"), text);
        assertTrue(text.contains("\nDecline: https://app.example.test/approvals/link/"), text);
        assertTrue(text.endsWith("\nhttps://app.example.test/print-requests/PR-1001"), text);
        assertEquals(r.id().toString(), toCy.message().actionRequest());
        assertTrue(f.outbox.all().stream().allMatch(e -> e.message().actions().isEmpty()),
                "the outbox stores no links (no tokens)");
    }

    @Test
    void openingALinkDecidesNothing() {
        ApprovalRequest r = f.create();
        f.send();
        for (int i = 0; i < 3; i++) { // a mail scanner, a preview, the person
            ApprovalResult approve = f.approvals.open(f.token(CY, APPROVE), AS_CY);
            ApprovalResult decline = f.approvals.open(f.token(CY, DECLINE), AS_CY);
            assertEquals(new ApprovalResult.Ready(r, APPROVE), approve);
            assertEquals(new ApprovalResult.Ready(r, DECLINE), decline);
        }
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision(), "still open");
        assertTrue(f.audit.isEmpty(), "opening is not a decision");
        String approve = f.token(CY, APPROVE);
        f.email.clear();
        f.send();
        assertTrue(f.email.sent().isEmpty(), "the requester was told nothing");

        assertInstanceOf(ApprovalResult.Decided.class, f.approvals.decide(approve, AS_CY, null),
                "the confirming POST decides");
    }

    @Test
    void aLinkWorksOnlyForThePersonItWasSentTo() {
        ApprovalRequest r = f.create();
        f.send();
        String cysLink = f.token(CY, APPROVE);

        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_NOT_FOR_YOU), f.approvals.open(cysLink, AS_DEE));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_NOT_FOR_YOU),
                f.approvals.decide(cysLink, AS_DEE, null), "even though Dee is an approver too");
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_NOT_FOR_YOU),
                f.approvals.decide(cysLink, AS_ANN, null));
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision());
        assertEquals(2, f.audit.size());
        assertEquals(ApprovalAudit.Outcome.REFUSED, f.audit.get(0).outcome());
        assertEquals(ApprovalRefusal.LINK_NOT_FOR_YOU, f.audit.get(0).refusal());
    }

    @Test
    void aPersonWhoNoLongerHoldsTheApproverRoleIsRefused() {
        ApprovalRequest r = f.create();
        f.send();
        String link = f.token(CY, APPROVE);
        assertInstanceOf(ApprovalResult.Ready.class, f.approvals.open(link, AS_CY));

        f.people.revoke(TENANT, E1, APPROVER, CY); // after the message went out

        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.NOT_AN_APPROVER), f.approvals.open(link, AS_CY));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.NOT_AN_APPROVER),
                f.approvals.decide(link, AS_CY, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.NOT_AN_APPROVER),
                f.approvals.decide(r.id(), APPROVE, AS_CY, null), "also from the app's own screen");
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision());
    }

    @Test
    void anApproverCheckThatFailsRefuses() {
        Approvals failing = f.approvals(List.of(ApprovalType.of("print", ApprovalFixture.WAITING,
                ApprovalFixture.APPROVED, ApprovalFixture.DECLINED, APPROVER)), (t, e, role, user) -> {
            throw new IllegalStateException("directory down");
        });
        failing.create(new NewApprovalRequest("print", TENANT, E1, REQ.userId(), null, null));
        f.send();
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.APPROVER_CHECK_UNAVAILABLE),
                failing.decide(f.token(CY, APPROVE), AS_CY, null));
    }

    @Test
    void nobodyDecidesOnTheirOwnRequest() {
        f.alsoToChannels();
        ApprovalRequest r = f.create();
        f.send();
        assertTrue(f.email.sent().stream().noneMatch(m -> m.recipients().contains(REQ)),
                "the requester holds the approver role but gets no links");
        String channelLink = ApprovalFixture.token(f.teams.sent().get(0), APPROVE);

        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.OWN_REQUEST), f.approvals.open(channelLink, AS_REQ));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.OWN_REQUEST),
                f.approvals.decide(channelLink, AS_REQ, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.OWN_REQUEST),
                f.approvals.decide(r.id(), APPROVE, AS_REQ, null));
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision());
    }

    @Test
    void linksExpireWithTheRequest() {
        ApprovalRequest r = f.create();
        f.send();
        assertEquals(r.createdAt().plus(Duration.ofHours(24)), r.expiresAt(), "24 hours by default");
        String link = f.token(CY, APPROVE);

        f.clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertInstanceOf(ApprovalResult.Ready.class, f.approvals.open(link, AS_CY));
        f.clock.advance(Duration.ofSeconds(1));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_EXPIRED), f.approvals.open(link, AS_CY));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_EXPIRED), f.approvals.decide(link, AS_CY, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_EXPIRED),
                f.approvals.decide(r.id(), APPROVE, AS_CY, null));
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision());
    }

    @Test
    void eachRequestTypeHasItsOwnExpiry() {
        ApprovalRequest r = f.create("quick-print");
        f.send();
        assertEquals(r.createdAt().plus(Duration.ofHours(1)), r.expiresAt());
        f.clock.advance(Duration.ofHours(1));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_EXPIRED),
                f.approvals.open(f.token(CY, APPROVE), AS_CY));
        assertThrows(IllegalArgumentException.class, () -> ApprovalType.of("x", ApprovalFixture.WAITING,
                ApprovalFixture.APPROVED, ApprovalFixture.DECLINED, APPROVER).validFor(Duration.ofMinutes(1)));
    }

    @Test
    void aMessageNotSentBeforeTheRequestClosedIsNotSentAtAll() {
        f.email.failWith(FailureCode.MAIL_SERVER_UNREACHABLE);
        ApprovalRequest r = f.create();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, f.send().get(0).status());
        f.email.succeed();
        f.clock.advance(Duration.ofHours(25));
        List<DeliveryReport> later = f.notifier.sendDue(f.clock.instant());
        assertEquals(DeliveryStatus.FAILED, later.get(0).status());
        assertEquals(FailureCode.ACTION_REQUEST_CLOSED, later.get(0).failure());
        assertTrue(f.email.sent().isEmpty());

        // Decided (from the app's screen) before the sender ran: nothing goes out either.
        ApprovalRequest second = f.create();
        f.approvals.decide(second.id(), APPROVE, AS_CY, null);
        f.email.clear();
        List<DeliveryReport> reports = f.send();
        assertTrue(reports.stream().anyMatch(d -> d.failure() == FailureCode.ACTION_REQUEST_CLOSED), reports.toString());
        assertTrue(f.email.sent().stream().allMatch(m -> m.message().actions().isEmpty()),
                "only the requester's outcome went out");
        assertNotEquals(r.id(), second.id());
    }

    @Test
    void declineNeedsAReason() {
        ApprovalRequest r = f.create();
        f.send();
        String decline = f.token(CY, DECLINE);
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REASON_REQUIRED), f.approvals.decide(decline, AS_CY, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REASON_REQUIRED),
                f.approvals.decide(decline, AS_CY, " \n\t "));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REASON_INVALID),
                f.approvals.decide(decline, AS_CY, "x".repeat(2001)));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REASON_INVALID),
                f.approvals.decide(decline, AS_CY, "bad\u0000reason"));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REASON_REQUIRED),
                f.approvals.decide(r.id(), DECLINE, AS_CY, ""));
        assertNull(f.store.find(TENANT, r.id()).orElseThrow().decision());

        String reason = "Customer file 4711 is under legal hold";
        ApprovalResult done = f.approvals.decide(decline, AS_CY, "  " + reason + "  ");
        Decision d = assertInstanceOf(ApprovalResult.Decided.class, done).request().decision();
        assertEquals(DECLINE, d.action());
        assertEquals(reason, d.reason(), "kept, trimmed, for the app's page and audit");
        assertFalse(d.toString().contains("legal hold"));

        f.send();
        OutgoingMessage told = f.mailTo(REQ);
        assertFalse(told.message().text().contains("legal hold"), "the reason never goes into a message");
        assertTrue(f.outbox.all().stream().noneMatch(e -> e.message().text().contains("legal hold")));
    }

    @Test
    void approvingNeedsNoReason() {
        f.create();
        f.send();
        Decision d = assertInstanceOf(ApprovalResult.Decided.class,
                f.approvals.decide(f.token(CY, APPROVE), AS_CY, "  ")).request().decision();
        assertNull(d.reason());
        assertEquals("Cy Lee", d.byName());
    }

    @Test
    void theFirstDecisionWinsWhenSeveralComeAtOnce() throws Exception {
        ApprovalRequest r = f.create();
        f.send();
        List<String> links = List.of(f.token(CY, APPROVE), f.token(DEE, DECLINE));
        List<SignedInPerson> who = List.of(AS_CY, AS_DEE);
        int n = 16;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<ApprovalResult>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i % 2;
            results.add(pool.submit(() -> {
                go.await();
                return f.approvals.decide(links.get(k), who.get(k), "Not needed now");
            }));
        }
        go.countDown();
        List<ApprovalResult> all = new ArrayList<>();
        for (Future<ApprovalResult> result : results) {
            all.add(result.get());
        }
        pool.shutdown();

        List<ApprovalResult> won = all.stream().filter(x -> x instanceof ApprovalResult.Decided).toList();
        assertEquals(1, won.size(), all.toString());
        assertEquals(n - 1, all.stream().filter(x -> x instanceof ApprovalResult.AlreadyDecided).count());
        Decision winner = ((ApprovalResult.Decided) won.get(0)).request().decision();
        assertEquals(winner, f.store.find(TENANT, r.id()).orElseThrow().decision(), "the stored one is the winner");
        all.stream().filter(x -> x instanceof ApprovalResult.AlreadyDecided).forEach(x ->
                assertEquals(winner, ((ApprovalResult.AlreadyDecided) x).request().decision()));

        f.email.clear();
        f.send();
        assertEquals(1, f.email.sent().size(), "the requester is told once");
        assertEquals(n, f.audit.size(), "every attempt audited");
        assertEquals(1, f.audit.stream().filter(a -> a.outcome() == ApprovalAudit.Outcome.DECIDED).count());
    }

    @Test
    void aLaterClickSeesTheOutcomeAndChangesNothing() {
        ApprovalRequest r = f.create();
        f.send();
        f.approvals.decide(f.token(CY, APPROVE), AS_CY, null);

        ApprovalResult seen = f.approvals.open(f.token(DEE, DECLINE), AS_DEE);
        ApprovalRequest shown = assertInstanceOf(ApprovalResult.AlreadyDecided.class, seen).request();
        assertEquals("Already approved by Cy Lee at 2026-11-20 10:05 Z",
                shown.outcomeText(ZoneOffset.UTC).orElseThrow());

        ApprovalResult late = f.approvals.decide(f.token(DEE, DECLINE), AS_DEE, "Too late anyway");
        assertEquals(APPROVE, assertInstanceOf(ApprovalResult.AlreadyDecided.class, late).request().decision().action());
        assertEquals(AS_CY.userId(), f.store.find(TENANT, r.id()).orElseThrow().decision().byUserId());
        assertInstanceOf(ApprovalResult.AlreadyDecided.class, f.approvals.decide(f.token(CY, DECLINE), AS_CY, "x"),
                "the decider cannot change it either");

        // Even after expiry, a decided request shows its outcome.
        f.clock.advance(Duration.ofDays(2));
        assertInstanceOf(ApprovalResult.AlreadyDecided.class, f.approvals.open(f.token(DEE, APPROVE), AS_DEE));
        f.email.clear();
        f.send();
        assertEquals(1, f.email.sent().size(), "one outcome message only");
    }

    @Test
    void theRequesterIsToldTheOutcome() {
        f.create();
        f.send();
        String approve = f.token(CY, APPROVE);
        f.email.clear();
        f.approvals.decide(approve, AS_CY, null);
        f.send();
        OutgoingMessage told = f.mailTo(REQ);
        assertEquals(1, told.recipients().size());
        assertTrue(told.message().actions().isEmpty());
        String text = told.message().text();
        assertTrue(text.startsWith("Print request approved: PR-1001\nTime: 2026-11-20 10:05 Z"), text);
        assertTrue(text.endsWith("\nhttps://app.example.test/print-requests/PR-1001"), text);
        assertFalse(text.contains("Cy"), "no names in messages");

        ApprovalFixture g = new ApprovalFixture();
        g.alsoToChannels();
        g.create();
        g.send();
        g.email.clear();
        g.teams.clear();
        g.approvals.decide(ApprovalFixture.token(g.slack.sent().get(0), DECLINE), AS_DEE, "No budget");
        g.send();
        assertTrue(g.mailTo(REQ).message().text().startsWith("Print request declined: PR-1001\n"));
        assertTrue(g.teams.sent().isEmpty(), "the outcome goes to the requester only, not to a channel");
    }

    @Test
    void everyDecisionAttemptIsAudited() {
        ApprovalRequest r = f.create();
        f.send();
        f.approvals.decide(f.token(CY, DECLINE), AS_CY, null);
        f.approvals.decide(f.token(CY, DECLINE), AS_CY, "Wrong printer");
        f.approvals.decide(f.token(DEE, APPROVE), AS_DEE, null);
        f.approvals.decide("not-a-token", AS_DEE, null);

        assertEquals(List.of(ApprovalAudit.Outcome.REFUSED, ApprovalAudit.Outcome.DECIDED,
                ApprovalAudit.Outcome.ALREADY_DECIDED, ApprovalAudit.Outcome.REFUSED),
                f.audit.stream().map(ApprovalAudit.Entry::outcome).toList());
        ApprovalAudit.Entry decided = f.audit.get(1);
        assertEquals(r.id(), decided.request());
        assertEquals(E1, decided.entity());
        assertEquals("print", decided.type());
        assertEquals(CY.userId(), decided.userId());
        assertEquals(DECLINE, decided.action());
        assertEquals("Wrong printer", decided.reason());
        assertFalse(decided.toString().contains("Wrong printer"));
        assertEquals(ApprovalRefusal.REASON_REQUIRED, f.audit.get(0).refusal());
        assertEquals(ApprovalRefusal.LINK_INVALID, f.audit.get(3).refusal());
        assertNull(f.audit.get(3).request());
    }

    @Test
    void aTamperedOrForeignTokenIsRefused() {
        f.create();
        f.send();
        String good = f.token(CY, APPROVE);
        String declineOfCy = f.token(CY, DECLINE);
        int dot = good.indexOf('.');
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < good.length(); i += 7) { // flip characters across payload and signature
            if (i == dot) {
                continue;
            }
            char c = good.charAt(i);
            bad.add(good.substring(0, i) + (c == 'A' ? 'B' : 'A') + good.substring(i + 1));
        }
        bad.add(good.substring(0, dot) + declineOfCy.substring(declineOfCy.indexOf('.'))); // signature of another
        bad.add(good.substring(0, good.length() - 1));
        bad.add(good + "A");
        bad.add(good.replace('.', '-'));
        bad.add("");
        bad.add(".");
        bad.add("a.b");
        bad.add("x".repeat(500));
        bad.add(null);
        for (String t : bad) {
            assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_INVALID), f.approvals.open(t, AS_CY),
                    String.valueOf(t));
            assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_INVALID), f.approvals.decide(t, AS_CY, null));
        }
        SignedInPerson cyElsewhere = new SignedInPerson(OTHER_TENANT, CY.userId(), "Cy Lee");
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_INVALID), f.approvals.open(good, cyElsewhere),
                "the signature covers the tenant");

        // Made with another key: refused, also once the key is replaced (the old links stop working).
        f.secrets.put(KEY_REF, "another-key-of-at-least-32-characters-xyz");
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.LINK_INVALID), f.approvals.open(good, AS_CY));
        assertTrue(f.store.all().stream().allMatch(x -> x.decision() == null));
    }

    @Test
    void channelLinksWorkForAnyApproverWhoSignsIn() {
        f.alsoToChannels();
        f.create();
        f.send();
        assertEquals(1, f.teams.sent().size());
        assertEquals(1, f.slack.sent().size());
        assertTrue(f.teams.sent().get(0).recipients().isEmpty());
        String teamsLink = ApprovalFixture.token(f.teams.sent().get(0), APPROVE);
        assertNotEquals(teamsLink, ApprovalFixture.token(f.slack.sent().get(0), APPROVE), "one set per channel");

        assertInstanceOf(ApprovalResult.Ready.class, f.approvals.open(teamsLink, AS_CY));
        assertInstanceOf(ApprovalResult.Ready.class, f.approvals.open(teamsLink, AS_DEE));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.NOT_AN_APPROVER), f.approvals.open(teamsLink, AS_ANN));
        assertInstanceOf(ApprovalResult.Decided.class, f.approvals.decide(teamsLink, AS_DEE, null));
    }

    @Test
    void keyProblemsHaveTheirCodes() {
        f.secrets.unavailable(true);
        f.create();
        List<DeliveryReport> down = f.send();
        assertEquals(DeliveryStatus.RETRY_SCHEDULED, down.get(0).status());
        assertEquals(FailureCode.SECRET_UNAVAILABLE, down.get(0).failure());
        f.secrets.unavailable(false);

        ApprovalFixture g = new ApprovalFixture();
        g.secrets.put(KEY_REF, "short-key");
        g.create();
        assertEquals(FailureCode.ACTION_KEY_INVALID, g.send().get(0).failure());

        ApprovalFixture h = new ApprovalFixture();
        ActionTokens noKey = new ActionTokens(h.secrets, SecretRef.of("notifications/missing"));
        Notifier n = Notifier.builder(h.settings, h.people).channel(h.email).clock(h.clock).outbox(h.outbox)
                .summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
                .actionLinks(new ApprovalLinks(h.store, noKey, AppLink.of("/approvals/link"))).build();
        Approvals a = Approvals.builder(List.of(ApprovalType.of("print", ApprovalFixture.WAITING,
                        ApprovalFixture.APPROVED, ApprovalFixture.DECLINED, APPROVER)), h.settings.catalogue(),
                h.store, noKey, n).approvers((t, e, r, u) -> true).audit(x -> { }).clock(h.clock).build();
        a.create(new NewApprovalRequest("print", TENANT, E1, REQ.userId(), null, null));
        List<DeliveryReport> missing = n.sendDue(h.clock.instant());
        assertEquals(DeliveryStatus.FAILED, missing.get(0).status());
        assertEquals(FailureCode.ACTION_KEY_NOT_FOUND, missing.get(0).failure());

        // Checking a link while the store is down: refused for now, nothing decided.
        ApprovalFixture k = new ApprovalFixture();
        k.create();
        k.send();
        String link = k.token(CY, APPROVE);
        k.secrets.unavailable(true);
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.KEY_UNAVAILABLE), k.approvals.decide(link, AS_CY, null));
        k.secrets.unavailable(false);
        assertInstanceOf(ApprovalResult.Decided.class, k.approvals.decide(link, AS_CY, null));
    }

    @Test
    void withoutALinkMakerTheMessageFailsWithItsCode() {
        Notifier plain = Notifier.builder(f.settings, f.people).channel(f.email).clock(f.clock).outbox(f.outbox)
                .summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore()).build();
        Approvals a = Approvals.builder(List.of(ApprovalType.of("print", ApprovalFixture.WAITING,
                        ApprovalFixture.APPROVED, ApprovalFixture.DECLINED, APPROVER)), f.settings.catalogue(), f.store,
                f.tokens, plain).approvers(ApproverCheck.from(f.people)).audit(x -> { }).clock(f.clock).build();
        a.create(new NewApprovalRequest("print", TENANT, E1, REQ.userId(), null, null));
        assertEquals(FailureCode.ACTION_LINKS_NOT_AVAILABLE, plain.sendDue(f.clock.instant()).get(0).failure());
    }

    @Test
    void aDecisionFromTheAppsOwnScreenFollowsTheSameRules() {
        ApprovalRequest r = f.create();
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.NOT_AN_APPROVER),
                f.approvals.decide(r.id(), APPROVE, AS_ANN, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_UNKNOWN),
                f.approvals.decide(UUID.randomUUID(), APPROVE, AS_CY, null));
        assertEquals(new ApprovalResult.Refused(ApprovalRefusal.REQUEST_UNKNOWN),
                f.approvals.decide(r.id(), APPROVE, new SignedInPerson(OTHER_TENANT, CY.userId(), "Cy"), null),
                "another tenant's request is not found");
        assertInstanceOf(ApprovalResult.Decided.class, f.approvals.decide(r.id(), DECLINE, AS_CY, "Duplicate"));
        assertInstanceOf(ApprovalResult.AlreadyDecided.class, f.approvals.decide(r.id(), APPROVE, AS_DEE, null));
    }

    @Test
    void registrationIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> f.approvals(List.of(ApprovalType.of("print",
                com.documentplatform.notifications.EventTypeId.of("no.such-event"), ApprovalFixture.APPROVED,
                ApprovalFixture.DECLINED, APPROVER)), (t, e, r, u) -> true));
        ApprovalType t = ApprovalType.of("print", ApprovalFixture.WAITING, ApprovalFixture.APPROVED,
                ApprovalFixture.DECLINED, APPROVER);
        assertThrows(IllegalArgumentException.class, () -> f.approvals(List.of(t, t), (x, e, r, u) -> true));
        assertThrows(IllegalArgumentException.class, () -> f.approvals.create(new NewApprovalRequest("unknown",
                TENANT, E1, REQ.userId(), null, null)));
        assertEquals(ApprovalType.DEFAULT_VALID_FOR, Duration.ofHours(24));
    }
}
