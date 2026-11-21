package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.documentplatform.notifications.approval.ApprovalAction.APPROVE;
import static com.documentplatform.notifications.approval.ApprovalFixture.APPROVED;
import static com.documentplatform.notifications.approval.ApprovalFixture.APPROVER;
import static com.documentplatform.notifications.approval.ApprovalFixture.AS_CY;
import static com.documentplatform.notifications.approval.ApprovalFixture.CY;
import static com.documentplatform.notifications.approval.ApprovalFixture.DECLINED;
import static com.documentplatform.notifications.approval.ApprovalFixture.EXPIRED;
import static com.documentplatform.notifications.approval.ApprovalFixture.PRINT;
import static com.documentplatform.notifications.approval.ApprovalFixture.REQ;
import static com.documentplatform.notifications.approval.ApprovalFixture.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner answer Q2 to P016: the requester is told when a request expires with nobody deciding. */
class ApprovalExpiryTest {

    private final ApprovalFixture f = new ApprovalFixture();
    private final Approvals approvals = f.approvals(List.of(
            ApprovalType.of(PRINT, WAITING, APPROVED, DECLINED, APPROVER).whenExpired(EXPIRED),
            ApprovalType.of("quiet-print", WAITING, APPROVED, DECLINED, APPROVER)), ApproverCheck.from(f.people));

    private ApprovalRequest create(String type) {
        return approvals.create(new NewApprovalRequest(type, ApprovalFixture.TENANT, ApprovalFixture.E1,
                REQ.userId(), ApprovalFixture.PR, com.documentplatform.notifications.AppLink.of("/print-requests/PR-1001")))
                .request();
    }

    @Test
    void theRequesterIsToldOnceWhenNobodyDecides() {
        f.alsoToChannels();
        create(PRINT);
        f.send();
        f.email.clear();
        f.teams.clear();
        f.slack.clear();

        f.clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertEquals(0, approvals.tellExpired(f.clock.instant()), "still open");
        f.clock.advance(Duration.ofSeconds(1));
        assertEquals(1, approvals.tellExpired(f.clock.instant()));
        f.send();
        OutgoingMessage told = f.mailTo(REQ);
        assertEquals(1, told.recipients().size(), "to the requester only");
        String text = told.message().text();
        assertTrue(text.startsWith("Print request expired: PR-1001\nTime: 2026-11-21 10:05 Z"), text);
        assertTrue(text.endsWith("\nhttps://app.example.test/print-requests/PR-1001"), text);
        assertTrue(told.message().actions().isEmpty(), "no links in it");
        assertTrue(f.teams.sent().isEmpty() && f.slack.sent().isEmpty(), "not to a channel");

        f.email.clear();
        f.clock.advance(Duration.ofHours(1));
        assertEquals(0, approvals.tellExpired(f.clock.instant()), "told once");
        f.send();
        assertTrue(f.email.sent().isEmpty());
    }

    @Test
    void decidedRequestsAndTypesWithoutTheEventAreNotTold() {
        create(PRINT);
        f.send();
        String approve = f.token(CY, APPROVE);
        assertInstanceOf(ApprovalResult.Decided.class, approvals.decide(approve, AS_CY, null));
        create("quiet-print");
        f.send();
        f.email.clear();
        f.clock.advance(Duration.ofDays(2));
        assertEquals(0, approvals.tellExpired(f.clock.instant()));
        assertEquals(0, f.approvals.tellExpired(f.clock.instant()), "types without the event never tell");
        f.send();
        assertTrue(f.email.sent().isEmpty());
    }

    @Test
    void severalInstancesAtOnceTellOnce() throws Exception {
        for (int i = 0; i < 30; i++) {
            create(PRINT);
        }
        f.clock.advance(Duration.ofDays(1));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            runs.add(pool.submit(() -> approvals.tellExpired(f.clock.instant())));
        }
        int total = 0;
        for (Future<Integer> r : runs) {
            total += r.get();
        }
        pool.shutdown();
        assertEquals(30, total);
    }

    @Test
    void anUnknownExpiredEventIsRefusedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> f.approvals(List.of(ApprovalType.of(PRINT, WAITING,
                APPROVED, DECLINED, APPROVER).whenExpired(EventTypeId.of("no.such-event"))), (t, e, r, u) -> true));
        ApprovalType t = ApprovalType.of(PRINT, WAITING, APPROVED, DECLINED, APPROVER).whenExpired(EXPIRED)
                .validFor(Duration.ofHours(2));
        assertEquals(EXPIRED, t.expiredEvent(), "validFor keeps it");
        assertFalse(ApprovalType.of(PRINT, WAITING, APPROVED, DECLINED, APPROVER).expired().isPresent());
    }

    @Test
    void purgingRemovesOldRequestsDecidedOrNot() {
        ApprovalRequest r = create(PRINT);
        f.clock.advance(Duration.ofDays(1));
        approvals.tellExpired(f.clock.instant());
        f.clock.advance(Duration.ofDays(30));
        assertEquals(0, approvals.purgeExpiredBefore(f.clock.instant().minus(Duration.ofDays(30)).minusSeconds(1)));
        assertEquals(1, approvals.purgeExpiredBefore(f.clock.instant().minus(Duration.ofDays(29))));
        assertTrue(approvals.find(ApprovalFixture.TENANT, r.id()).isEmpty());
    }
}
