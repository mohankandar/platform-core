package com.documentplatform.notifications.approval;

import com.documentplatform.notifications.ActionLink;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.inmemory.InMemoryApprovalStore;
import com.documentplatform.notifications.inmemory.InMemoryChannel;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemoryRecipientResolver;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import com.documentplatform.notifications.secret.SecretRef;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A made-up print-request approval, wired with in-memory fakes and a clock the test moves. */
final class ApprovalFixture {

    static final RoleId IT_ADMIN = RoleId.of("it-admin");
    static final RoleId APPROVER = RoleId.of("print-approver");

    static final EventTypeId WAITING = EventTypeId.of("approval.print-waiting");
    static final EventTypeId APPROVED = EventTypeId.of("approval.print-approved");
    static final EventTypeId DECLINED = EventTypeId.of("approval.print-declined");
    static final String PRINT = "print";

    static final TenantId TENANT = TenantId.of("t1");
    static final TenantId OTHER_TENANT = TenantId.of("t2");
    static final EntityId E1 = EntityId.of("e1");
    static final DestinationId MAIL = DestinationId.of("mail");
    static final DestinationId TEAMS = DestinationId.of("ops-teams");
    static final DestinationId SLACK = DestinationId.of("ops-slack");

    /** The requester: also holds the approver role, to show they still cannot decide on their own request. */
    static final Recipient REQ = new Recipient("u-req", "req@example.test");
    static final Recipient CY = new Recipient("u-cy", "cy@example.test");
    static final Recipient DEE = new Recipient("u-dee", "dee@example.test");
    /** Not an approver. */
    static final Recipient ANN = new Recipient("u-ann", "ann@example.test");

    static final SignedInPerson AS_REQ = new SignedInPerson(TENANT, REQ.userId(), "Rex Quill");
    static final SignedInPerson AS_CY = new SignedInPerson(TENANT, CY.userId(), "Cy Lee");
    static final SignedInPerson AS_DEE = new SignedInPerson(TENANT, DEE.userId(), "Dee Moss");
    static final SignedInPerson AS_ANN = new SignedInPerson(TENANT, ANN.userId(), "Ann Vale");

    static final SecretRef KEY_REF = SecretRef.of("notifications/approval-link-key");
    static final String KEY = "k3Y-for-TESTS-only-0123456789abcdefghijklmnop";
    static final Instant START = Instant.parse("2026-11-20T10:05:00Z");
    static final RecordIdentifier PR = RecordIdentifier.of("PR-1001");

    final MoveableClock clock = new MoveableClock(START);
    final NotificationSettings settings = new NotificationSettings(catalogue(), new InMemorySettingsStore());
    final InMemoryRecipientResolver people = new InMemoryRecipientResolver();
    final InMemoryChannel email = new InMemoryChannel(ChannelKind.EMAIL);
    final InMemoryChannel teams = new InMemoryChannel(ChannelKind.TEAMS);
    final InMemoryChannel slack = new InMemoryChannel(ChannelKind.SLACK);
    final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
    final InMemoryApprovalStore store = new InMemoryApprovalStore();
    final InMemorySecretResolver secrets = new InMemorySecretResolver().put(KEY_REF, KEY);
    final ActionTokens tokens = new ActionTokens(secrets, KEY_REF);
    final List<ApprovalAudit.Entry> audit = Collections.synchronizedList(new ArrayList<>());
    final Notifier notifier = notifierWithLinks();
    final Approvals approvals = approvals(List.of(ApprovalType.of(PRINT, WAITING, APPROVED, DECLINED, APPROVER),
            ApprovalType.of("quick-print", WAITING, APPROVED, DECLINED, APPROVER).validFor(Duration.ofHours(1))),
            ApproverCheck.from(people));

    ApprovalFixture() {
        settings.connectChannels(TENANT, new TenantSettings(URI.create("https://app.example.test/"), ZoneOffset.UTC,
                Map.of(MAIL, ChannelKind.EMAIL, TEAMS, ChannelKind.TEAMS, SLACK, ChannelKind.SLACK)));
        people.grant(TENANT, E1, APPROVER, REQ, CY, DEE).know(TENANT, ANN);
    }

    static EventCatalogue catalogue() {
        return EventCatalogue.builder(IT_ADMIN)
                // Every 4 hours on purpose: a request with links still goes at once.
                .register(EventType.builder(WAITING.value(), "Print request waiting", EventKind.BUSINESS)
                        .schedule(Schedule.every4Hours()).recipients(APPROVER).time("expiresAt", "Expires").build())
                .register(EventType.builder(APPROVED.value(), "Print request approved", EventKind.BUSINESS)
                        .schedule(Schedule.every4Hours()).recipients(APPROVER).build())
                .register(EventType.builder(DECLINED.value(), "Print request declined", EventKind.BUSINESS)
                        .schedule(Schedule.every4Hours()).recipients(APPROVER).build())
                .build();
    }

    Notifier notifierWithLinks() {
        return Notifier.builder(settings, people).channels(List.of(email, teams, slack)).clock(clock).outbox(outbox)
                .summaries(new InMemorySummaryStore()).conditions(new InMemoryConditionStore())
                .actionLinks(new ApprovalLinks(store, tokens, AppLink.of("/approvals/link"))).build();
    }

    Approvals approvals(List<ApprovalType> types, ApproverCheck check) {
        return Approvals.builder(types, settings.catalogue(), store, tokens, notifier).approvers(check)
                .audit(audit::add).clock(clock).build();
    }

    /** Sends the request messages to Teams and Slack as well as by e-mail. */
    void alsoToChannels() {
        settings.setBusinessEvent(TENANT, E1, WAITING, EventSettings.defaultsFor(settings.catalogue().require(WAITING))
                .withDestinations(MAIL, TEAMS, SLACK));
    }

    ApprovalRequest create() {
        return create(PRINT);
    }

    ApprovalRequest create(String type) {
        return approvals.create(new NewApprovalRequest(type, TENANT, E1, REQ.userId(), PR,
                AppLink.of("/print-requests/PR-1001"))).request();
    }

    List<DeliveryReport> send() {
        return notifier.sendDue(clock.instant());
    }

    /** The e-mail sent to this person. */
    OutgoingMessage mailTo(Recipient r) {
        List<OutgoingMessage> to = email.sent().stream().filter(m -> m.recipients().contains(r)).toList();
        if (to.size() != 1) {
            throw new AssertionError("Expected one e-mail to " + r + ", found " + to.size());
        }
        return to.get(0);
    }

    /** The token of this person's e-mail link. */
    String token(Recipient r, ApprovalAction action) {
        return token(mailTo(r), action);
    }

    static String token(OutgoingMessage m, ApprovalAction action) {
        ActionLink link = m.message().actions().stream().filter(a -> a.label().equals(action.label())).findFirst()
                .orElseThrow();
        String path = link.url().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static final class MoveableClock extends Clock {
        private Instant now;

        MoveableClock(Instant now) {
            this.now = now;
        }

        synchronized void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            return now;
        }
    }
}
