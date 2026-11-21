package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.Deployment;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceType;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryNoticeLog;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.SummaryItem;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.documentplatform.notifications.inmemory.InMemoryChannel;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemoryRecipientResolver;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LicensingNoticesTest {

    static final RoleId IT_ADMIN = RoleId.of("it-admin");
    static final RoleId BUSINESS_ADMIN = RoleId.of("business-admin");
    static final String TENANT = TestLicences.TENANT;
    static final String SLOT_ON = "ENT-TEST-0001";
    static final String SLOT_OFF = "ENT-TEST-0002";
    static final EntityId ENTITY = EntityId.of("entity-1");
    static final Recipient BIZ = new Recipient("u-biz", "biz@example.test");
    static final Recipient IT = new Recipient("u-it", "it@example.test");

    /** A licensing app and its notifications, all in memory; {@link #restart()} keeps every store. */
    static final class App {
        final MutableClock clock;
        final TestLicences licences = new TestLicences();
        final InMemoryLicenceStore licenceStore = new InMemoryLicenceStore();
        final InMemoryLastSeenStore lastSeen = new InMemoryLastSeenStore();
        final InMemoryUsageLedger ledger = new InMemoryUsageLedger();
        final InMemoryNoticeLog noticeLog = new InMemoryNoticeLog();
        final InMemorySettingsStore settingsStore = new InMemorySettingsStore();
        final InMemoryOutboxStore outbox = new InMemoryOutboxStore();
        SummaryStore summaries = new InMemorySummaryStore();
        final InMemoryConditionStore conditions = new InMemoryConditionStore();
        final InMemoryRecipientResolver resolver = new InMemoryRecipientResolver();
        final InMemoryChannel email = new InMemoryChannel(ChannelKind.EMAIL);
        final EventCatalogue catalogue;
        LicensingNotices.Options options = new LicensingNotices.Options(AppLink.of("/admin/licence"), null, null);
        LicenceService service;
        NotificationSettings settings;
        Notifier notifier;
        LicensingNotices notices;

        App(Instant now) {
            this(now, LicensingEvents.register(EventCatalogue.builder(IT_ADMIN), IT_ADMIN, BUSINESS_ADMIN).build());
        }

        App(Instant now, EventCatalogue catalogue) {
            this.clock = new MutableClock(now);
            this.catalogue = catalogue;
            resolver.grant(TenantId.of(TENANT), null, IT_ADMIN, IT)
                    .grant(TenantId.of(TENANT), ENTITY, BUSINESS_ADMIN, BIZ);
            restart();
            settings.connectChannels(TenantId.of(TENANT), new TenantSettings(URI.create("https://app.example.test/"),
                    ZoneOffset.UTC, Map.of(DestinationId.of("mail"), ChannelKind.EMAIL)));
        }

        /** New service, notifier and checker on the same stores: what a restart (or a second instance) is. */
        App restart() {
            service = new LicenceService(licenceStore, new LicenceVerifier(licences.trustedKeys()),
                    new ClockGuard(clock, lastSeen), ledger, TestLicences.PRODUCT, null);
            settings = new NotificationSettings(catalogue, settingsStore);
            notifier = Notifier.builder(settings, resolver).channel(email).clock(clock).outbox(outbox)
                    .summaries(summaries).conditions(conditions).build();
            notices = new LicensingNotices(service, notifier, settings, noticeLog,
                    tenant -> Map.of(SLOT_ON, ENTITY.value()), clock, options);
            return this;
        }

        LicensingNotices.Result check() {
            LicensingNotices.Result r = notices.check();
            notifier.tick(clock.instant());
            return r;
        }

        /** Moves the clock and checks hourly on the way, as the worker does. */
        void runFor(Duration d) {
            Instant end = clock.instant().plus(d);
            while (clock.instant().isBefore(end)) {
                clock.advance(Duration.ofHours(1));
                check();
            }
        }

        /** Moves to the next daily summary time (08:00 UTC) and sends. */
        void sendDailySummaries() {
            Instant now = clock.instant();
            Instant next = now.atZone(ZoneOffset.UTC).toLocalDate().atTime(8, 0).toInstant(ZoneOffset.UTC);
            if (!next.isAfter(now)) {
                next = next.plus(Duration.ofDays(1));
            }
            clock.set(next.plusSeconds(1));
            notifier.tick(clock.instant());
        }

        List<String> subjects() {
            return email.sent().stream().map(m -> m.message().subject()).toList();
        }

        List<MessageKind> kinds() {
            return email.sent().stream().map(m -> m.message().kind()).toList();
        }

        void store(Licence l) {
            service.store(licences.sign(l));
        }
    }

    static Licence licence(String id, long version, LicenceType type, LocalDate starts, LocalDate expires,
                           LocalDate updatesUntil, String keyId) {
        return new Licence(id, version, "Test Customer Ltd", TENANT, List.of(TestLicences.PRODUCT),
                List.of(new EntitySlot(SLOT_ON, List.of(new Allowance(TestLicences.PRODUCT,
                                AllowanceType.PAGES_PER_MONTH, 1000))),
                        new EntitySlot(SLOT_OFF, List.of(new Allowance(TestLicences.PRODUCT,
                                AllowanceType.PAGES_PER_MONTH, 1000)))),
                type, LicenceKind.PRODUCTION, starts, expires, updatesUntil, Deployment.ON_PREM, keyId,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    static Licence subscription(App app, String expires) {
        return licence("LIC-TEST-0001", 1, LicenceType.SUBSCRIPTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse(expires), null, app.licences.keyId());
    }

    @Test
    void expiringIsPublishedOncePerBandForTheActivatedSlotOnly() {
        App app = new App(Instant.parse("2026-11-16T09:00:00Z")); // 60 days before 2027-01-15
        app.store(subscription(app, "2027-01-15"));

        assertEquals(1, app.check().published(), "one activated slot, band 60");
        app.runFor(Duration.ofHours(30));
        app.restart();
        assertEquals(0, app.check().published(), "nothing again after a restart");
        assertEquals(Set.of("expiring|" + TENANT + "|LIC-TEST-0001|v1|" + SLOT_ON + "|60"), app.noticeLog.claimed());

        // The app is down on day 30 itself and comes back on day 25: band 30 still goes out, once.
        app.clock.set(Instant.parse("2026-12-21T09:00:00Z"));
        assertEquals(1, app.check().published());
        app.restart();
        app.runFor(Duration.ofHours(5));
        app.sendDailySummaries();
        List<OutgoingMessage> sent = app.email.sent();
        assertEquals(2, sent.size(), app.subjects().toString());
        assertTrue(sent.stream().allMatch(m -> m.recipients().equals(Set.of(BIZ))), "to the Business Admin");
        String text = sent.get(1).message().text();
        assertTrue(text.contains("Licence expiring"), text);
        assertTrue(text.contains("LIC-TEST-0001"), text);
    }

    @Test
    void graceAlertsOnceAndDailyThenIntakeStoppedTakesOverAndARenewalRecovers() {
        App app = new App(Instant.parse("2026-11-11T09:00:00Z")); // grace day 1
        app.store(subscription(app, "2026-11-10"));

        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT), app.kinds(), "critical: alert at once");
        OutgoingMessage alert = app.email.sent().get(0);
        assertEquals(Set.of(BIZ, IT), alert.recipients(), "Business Admin and IT Admin");
        assertTrue(alert.message().text().contains("Grace days left: 29"), alert.message().text());
        assertTrue(alert.message().text().contains("Grace period ends: 2026-12-11 00:00"), alert.message().text());

        app.runFor(Duration.ofHours(22));
        app.restart();
        app.runFor(Duration.ofHours(1));
        assertEquals(1, app.email.sent().size(), "no duplicate alert, also after a restart");
        app.runFor(Duration.ofHours(2));
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.CONDITION_ONGOING), app.kinds(), "daily repeat");

        app.clock.set(Instant.parse("2026-12-11T00:30:00Z")); // grace over
        app.check();
        List<String> subjects = app.subjects();
        assertTrue(subjects.get(subjects.size() - 1).contains("Intake stopped, licence expired"), subjects.toString());
        assertTrue(subjects.stream().noneMatch(s -> s.startsWith("Recovered")), "grace ends without Recovered");
        app.restart();
        app.runFor(Duration.ofHours(23));
        assertEquals(subjects.size(), app.email.sent().size(), "intake stopped alerts once");

        // A renewal is stored: intake stopped recovers; grace does not come back.
        app.store(licence("LIC-TEST-0003", 2, LicenceType.SUBSCRIPTION, LocalDate.parse("2026-12-01"),
                LocalDate.parse("2027-12-31"), null, app.licences.keyId()));
        app.check();
        String last = app.subjects().get(app.subjects().size() - 1);
        assertTrue(last.startsWith("Recovered: Intake stopped, licence expired"), last);
        app.runFor(Duration.ofDays(2));
        assertEquals(last, app.subjects().get(app.subjects().size() - 1), "nothing after the recovery");
    }

    @Test
    void aRenewalDuringGraceSendsRecoveredForGrace() {
        App app = new App(Instant.parse("2026-11-20T09:00:00Z"));
        app.store(subscription(app, "2026-11-10"));
        app.check();
        app.store(licence("LIC-TEST-0003", 2, LicenceType.SUBSCRIPTION, LocalDate.parse("2026-11-01"),
                LocalDate.parse("2027-12-31"), null, app.licences.keyId()));
        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.RECOVERED), app.kinds());
    }

    @Test
    void aLicenceWithoutThisProductAlertsTheItAdminAndRecovers() {
        App app = new App(Instant.parse("2026-11-21T12:00:00Z"));
        Licence otherProduct = new Licence("LIC-TEST-0001", 1, "Test Customer Ltd", TENANT, List.of("PRODUCT_B"),
                List.of(new EntitySlot(SLOT_ON, List.of(new Allowance("PRODUCT_B", AllowanceType.PAGES_PER_MONTH, 10)))),
                LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-12-31"), null, Deployment.ON_PREM, app.licences.keyId(),
                Instant.parse("2026-01-01T00:00:00Z"));
        app.store(otherProduct);
        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT), app.kinds(), "critical: alert at once");
        OutgoingMessage alert = app.email.sent().get(0);
        assertEquals(Set.of(IT), alert.recipients(), "technical: the IT Admin only");
        assertTrue(alert.message().subject().contains("Licence does not cover this product"), alert.message().subject());
        assertTrue(alert.message().text().contains(TestLicences.PRODUCT), "the product code is the identifier");
        app.restart();
        app.runFor(Duration.ofHours(3));
        assertEquals(1, app.email.sent().size(), "no duplicate alert");

        app.store(licence("LIC-TEST-0003", 2, LicenceType.SUBSCRIPTION, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-12-31"), null, app.licences.keyId()));
        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.RECOVERED), app.kinds());
    }

    @Test
    void aClockBehindAlertsTheItAdminOnceAndRecovers() {
        App app = new App(Instant.parse("2026-11-21T12:00:00Z"));
        app.store(subscription(app, "2027-12-31"));
        app.check();
        app.clock.set(Instant.parse("2026-11-21T09:00:00Z")); // turned back 3 hours
        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT), app.kinds());
        OutgoingMessage alert = app.email.sent().get(0);
        assertEquals(Set.of(IT), alert.recipients(), "technical: the IT Admin only");
        assertTrue(alert.message().subject().contains("Server clock behind"), alert.message().subject());
        assertTrue(alert.message().text().contains("Minutes behind: 180"), alert.message().text());
        app.restart();
        app.runFor(Duration.ofHours(1)); // still 2 hours behind
        assertEquals(1, app.email.sent().size());
        app.clock.set(Instant.parse("2026-11-21T11:30:00Z")); // within the 1-hour tolerance
        app.check();
        assertEquals(List.of(MessageKind.CONDITION_ALERT, MessageKind.RECOVERED), app.kinds());
    }

    @Test
    void usageNearAtAndOverLimitPerActivatedSlot() {
        App app = new App(Instant.parse("2026-11-05T09:00:00Z"));
        app.store(subscription(app, "2027-12-31"));
        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 790, false);
        app.service.recordUsage(SLOT_OFF, AllowanceType.PAGES_PER_MONTH, 5000, false);
        assertEquals(0, app.check().published(), "79%: nothing; the slot that is not activated gets nothing");

        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 10, false); // 80%
        assertEquals(1, app.check().published());
        app.restart();
        assertEquals(0, app.check().published(), "near limit once a month");

        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 200, false); // 100%
        LicensingNotices.Result at = app.check();
        assertEquals(1, at.published());
        assertEquals(0, at.failing(), "at the limit is not over it");

        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 1, false); // over
        assertEquals(1, app.check().failing());
        app.restart();
        app.runFor(Duration.ofHours(3));
        app.sendDailySummaries();
        String summary = app.email.sent().get(app.email.sent().size() - 1).message().text();
        assertTrue(summary.contains("Usage near limit: 1"), summary);
        assertTrue(summary.contains("Usage at limit: 1"), summary);
        assertTrue(summary.contains("Usage over limit: 1"), summary);
        assertTrue(summary.contains("PRODUCT_A:PAGES_PER_MONTH"), summary);
        assertEquals(1, app.email.sent().size(), "one daily summary, to the Business Admin");

        // A new month: back within the allowance, the over-limit condition recovers.
        app.clock.set(Instant.parse("2026-12-01T09:00:00Z"));
        assertEquals(1, app.check().cleared());
        assertEquals(0, app.check().cleared());
    }

    @Test
    void theNearLimitPercentageIsTheAdminsThreshold() {
        App app = new App(Instant.parse("2026-11-05T09:00:00Z"));
        app.store(subscription(app, "2027-12-31"));
        app.settings.setBusinessEvent(TenantId.of(TENANT), ENTITY, LicensingEvents.USAGE_NEAR_LIMIT,
                app.settings.effective(TenantId.of(TENANT), ENTITY, LicensingEvents.USAGE_NEAR_LIMIT)
                        .withThreshold(LicensingEvents.NEAR_LIMIT_PERCENT, 90));
        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 850, false);
        assertEquals(0, app.check().published());
        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 50, false);
        assertEquals(1, app.check().published());
    }

    @Test
    void aVersionNotCoveredByUpdatesIsReportedOnceToTheItAdmin() {
        App app = new App(Instant.parse("2026-11-21T09:00:00Z"));
        app.options = new LicensingNotices.Options(null, RecordIdentifier.of("2.4.0"), LocalDate.parse("2026-10-01"));
        app.restart();
        app.store(licence("LIC-TEST-0002", 1, LicenceType.PERPETUAL, LocalDate.parse("2026-01-01"), null,
                LocalDate.parse("2026-06-30"), app.licences.keyId()));
        assertEquals(1, app.check().published());
        app.restart();
        assertEquals(0, app.check().published());
        app.sendDailySummaries();
        OutgoingMessage m = app.email.sent().get(0);
        assertEquals(Set.of(IT), m.recipients());
        assertTrue(m.message().text().contains("2.4.0"), m.message().text());

        App covered = new App(Instant.parse("2026-11-21T09:00:00Z"));
        covered.options = new LicensingNotices.Options(null, null, LocalDate.parse("2026-06-30"));
        covered.restart();
        covered.store(licence("LIC-TEST-0002", 1, LicenceType.PERPETUAL, LocalDate.parse("2026-01-01"), null,
                LocalDate.parse("2026-06-30"), covered.licences.keyId()));
        assertEquals(0, covered.check().published());
    }

    @Test
    void aPublishThatFailsIsTriedAgainOnTheNextCheck() {
        App app = new App(Instant.parse("2026-11-16T09:00:00Z"));
        FailingSummaries failing = new FailingSummaries();
        app.summaries = failing;
        app.restart();
        app.store(subscription(app, "2027-01-15"));
        failing.fail = true;
        assertThrows(IllegalStateException.class, app.notices::check);
        assertTrue(app.noticeLog.claimed().isEmpty(), "the claim was given back");
        failing.fail = false;
        assertEquals(1, app.check().published());
    }

    @Test
    void messagesCarryNoKeysSignaturesPricesOrCustomerName() {
        App app = new App(Instant.parse("2026-11-11T09:00:00Z"));
        Licence l = subscription(app, "2026-11-10");
        String file = app.licences.sign(l);
        app.service.store(file);
        app.service.recordUsage(SLOT_ON, AllowanceType.PAGES_PER_MONTH, 1200, false);
        app.check();
        app.clock.set(Instant.parse("2026-11-11T06:00:00Z"));
        app.check();
        app.runFor(Duration.ofHours(30));
        app.sendDailySummaries();
        assertTrue(app.email.sent().size() >= 3, app.subjects().toString());
        String signature = file.replaceAll("(?s).*\"value\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        String publicKey = com.documentplatform.licensing.Ed25519.encodePublic(app.licences.keyPair().getPublic());
        for (OutgoingMessage m : app.email.sent()) {
            String all = m.message().subject() + "\n" + m.message().text();
            assertFalse(all.contains(signature), all);
            assertFalse(all.contains(publicKey.substring(20)), all);
            assertFalse(all.contains(app.licences.keyId()), "no key ID: " + all);
            assertFalse(all.contains("Test Customer Ltd"), "no customer name: " + all);
            assertFalse(all.toLowerCase().contains("price"), all);
            assertTrue(all.contains("https://app.example.test/admin/licence"), "the link: " + all);
        }
    }

    @Test
    void nothingIsPublishedWhenTheCatalogueHasNoLicensingTypes() {
        App app = new App(Instant.parse("2026-11-11T09:00:00Z"), EventCatalogue.builder(IT_ADMIN).build());
        app.store(subscription(app, "2026-11-10"));
        assertEquals(new LicensingNotices.Result(0, 0, 0, null), app.check());
        assertTrue(app.email.sent().isEmpty());
    }

    @Test
    void aRefusedLicenceIsReportedByCodeAndPublishesNothing() {
        App app = new App(Instant.parse("2026-11-11T09:00:00Z"));
        assertEquals("LICENCE_MISSING", app.check().refusal());
        assertTrue(app.email.sent().isEmpty());
    }

    /** A summary store that can fail, as a database that went away does. */
    static final class FailingSummaries implements SummaryStore {
        final InMemorySummaryStore delegate = new InMemorySummaryStore();
        boolean fail;

        @Override
        public void add(SummaryItem item) {
            if (fail) {
                throw new IllegalStateException("database unreachable");
            }
            delegate.add(item);
        }

        @Override
        public List<SummaryItem> due(Instant now) {
            return delegate.due(now);
        }

        @Override
        public void remove(Collection<String> itemKeys) {
            delegate.remove(itemKeys);
        }

        @Override
        public int pending() {
            return delegate.pending();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        void set(Instant t) {
            now = t;
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
        public Instant instant() {
            return now;
        }
    }
}
