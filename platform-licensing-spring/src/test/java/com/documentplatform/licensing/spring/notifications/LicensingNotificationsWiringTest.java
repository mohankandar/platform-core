package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.licensing.Ed25519;
import com.documentplatform.licensing.LastSeenStore;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceState;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.NoticeLog;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.inmemory.InMemoryLastSeenStore;
import com.documentplatform.licensing.inmemory.InMemoryLicenceStore;
import com.documentplatform.licensing.inmemory.InMemoryNoticeLog;
import com.documentplatform.licensing.inmemory.InMemoryUsageLedger;
import com.documentplatform.licensing.inmemory.TestLicences;
import com.documentplatform.licensing.spring.LicensingAutoConfiguration;
import com.documentplatform.licensing.spring.LicensingClock;
import com.documentplatform.licensing.spring.LicensingConfig;
import com.documentplatform.licensing.spring.LicensingConfigException;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.inmemory.InMemoryConditionStore;
import com.documentplatform.notifications.inmemory.InMemoryOutboxStore;
import com.documentplatform.notifications.inmemory.InMemoryRecipientResolver;
import com.documentplatform.notifications.inmemory.InMemorySettingsStore;
import com.documentplatform.notifications.inmemory.InMemorySummaryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static com.documentplatform.licensing.spring.notifications.LicensingNoticesTest.BUSINESS_ADMIN;
import static com.documentplatform.licensing.spring.notifications.LicensingNoticesTest.IT_ADMIN;
import static org.junit.jupiter.api.Assertions.*;

/** The licensing notifications auto-configuration, with and without platform-notifications. In-memory stores. */
class LicensingNotificationsWiringTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private final TestLicences licences = new TestLicences();
    private final InMemoryLicenceStore store = new InMemoryLicenceStore();

    LicensingNotificationsWiringTest() {
        // A licence stored the way the licence command does.
        new LicenceService(store, new LicenceVerifier(licences.trustedKeys()),
                new ClockGuard(Clock.fixed(NOW, ZoneOffset.UTC), new InMemoryLastSeenStore()), new InMemoryUsageLedger(),
                TestLicences.PRODUCT, null).store(licences.sign(licences.subscription(LicenceKind.PRODUCTION,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2027-12-31"))));
    }

    /** Licensing with the app's own in-memory stores (no database), both auto-configurations. */
    private ApplicationContextRunner licensing(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class,
                        LicensingNotificationsAutoConfiguration.class))
                .withBean(LicensingClock.class, () -> new LicensingClock(Clock.fixed(NOW, ZoneOffset.UTC)))
                .withBean(LicenceStore.class, () -> store)
                .withBean(LastSeenStore.class, InMemoryLastSeenStore::new)
                .withBean(UsageLedger.class, InMemoryUsageLedger::new)
                .withBean(NoticeLog.class, InMemoryNoticeLog::new)
                .withPropertyValues("platform.licensing.trusted-keys." + licences.keyId() + "="
                                + Ed25519.encodePublic(licences.keyPair().getPublic()),
                        "platform.licensing.product-code=" + TestLicences.PRODUCT)
                .withPropertyValues(properties);
    }

    /** Plus the app's notifications: catalogue with the licensing types, settings, notifier. */
    private ApplicationContextRunner withNotifications(ApplicationContextRunner runner, EventCatalogue catalogue) {
        NotificationSettings settings = new NotificationSettings(catalogue, new InMemorySettingsStore());
        return runner.withBean(NotificationSettings.class, () -> settings)
                .withBean(Notifier.class, () -> Notifier.builder(settings, new InMemoryRecipientResolver())
                        .outbox(new InMemoryOutboxStore()).summaries(new InMemorySummaryStore())
                        .conditions(new InMemoryConditionStore()).build());
    }

    private static EventCatalogue withLicensingTypes() {
        return LicensingEvents.register(EventCatalogue.builder(IT_ADMIN), IT_ADMIN, BUSINESS_ADMIN).build();
    }

    @Test
    void withoutPlatformNotificationsLicensingWorksAndPublishesNothing() {
        licensing().withClassLoader(new FilteredClassLoader("com.documentplatform.notifications")).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(LicenceState.VALID, ctx.getBean(LicenceService.class).status().state());
            assertTrue(ctx.getBeansOfType(LicensingNotices.class).isEmpty());
            assertTrue(ctx.getBeansOfType(LicensingNoticeWorker.class).isEmpty());
        });
    }

    @Test
    void withANotifierTheCheckIsWiredAndRunning() {
        withNotifications(licensing("platform.licensing.notifications.link=/admin/licence"), withLicensingTypes())
                .withBean(ActivatedSlots.class, () -> tenant -> Map.of(TestLicences.SLOT, "entity-1"))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    LicensingNotices notices = ctx.getBean(LicensingNotices.class);
                    assertEquals(LicensingEvents.ALL.size(), notices.registered().size());
                    assertTrue(ctx.getBean(LicensingNoticeWorker.class).isRunning());
                    assertNull(notices.check().refusal());
                });
    }

    @Test
    void businessEventsWithoutActivatedSlotsStopTheStartWithAClearMessage() {
        withNotifications(licensing(), withLicensingTypes()).run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure);
            LicensingConfigException e = root(failure, LicensingConfigException.class);
            assertTrue(e.getMessage().contains("ActivatedSlots"), e.getMessage());
        });
    }

    @Test
    void aCatalogueWithoutLicensingTypesNeedsNoActivatedSlotsAndChecksNothing() {
        withNotifications(licensing(), EventCatalogue.builder(IT_ADMIN).build()).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBean(LicensingNotices.class).registered().isEmpty());
            assertTrue(ctx.getBean(LicensingNoticeWorker.class).isRunning());
        });
    }

    @Test
    void wrongNotificationSettingsAreListedByKeyWithoutValues() {
        withNotifications(licensing("platform.licensing.notifications.check-interval=10s",
                "platform.licensing.notifications.link=https://evil.example/x?token=abc",
                "platform.licensing.notifications.release-date=1st of May"), withLicensingTypes())
                .withBean(ActivatedSlots.class, () -> tenant -> Map.of())
                .run(ctx -> {
                    LicensingConfigException e = root(ctx.getStartupFailure(), LicensingConfigException.class);
                    assertEquals(3, e.problems().size(), e.problems().toString());
                    assertTrue(e.getMessage().contains("platform.licensing.notifications.check-interval"));
                    assertTrue(e.getMessage().contains("platform.licensing.notifications.link"));
                    assertTrue(e.getMessage().contains("platform.licensing.notifications.release-date"));
                    assertFalse(e.getMessage().contains("token=abc"));
                });
    }

    @Test
    void switchedOffByProperty() {
        withNotifications(licensing("platform.licensing.notifications.enabled=false"), withLicensingTypes())
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBeansOfType(LicensingNotices.class).isEmpty());
                });
    }

    @Test
    void aVaultKeyIdWithItsVersionBindsWhenWrittenInBrackets() {
        String key = Ed25519.encodePublic(licences.keyPair().getPublic());
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LicensingAutoConfiguration.class))
                .withBean(LicensingClock.class, () -> new LicensingClock(Clock.fixed(NOW, ZoneOffset.UTC)))
                .withBean(LicenceStore.class, () -> store)
                .withBean(LastSeenStore.class, InMemoryLastSeenStore::new)
                .withBean(UsageLedger.class, InMemoryUsageLedger::new)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t",
                        Map.of("platform.licensing.trusted-keys[lic-2026:1]", key,
                                "platform.licensing.trusted-keys[" + licences.keyId() + "]", key,
                                "platform.licensing.product-code", TestLicences.PRODUCT))))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBean(LicensingConfig.class).trustedKeys().keyIds().contains("lic-2026:1"),
                            ctx.getBean(LicensingConfig.class).trustedKeys().keyIds().toString());
                });
    }

    private static <T extends Throwable> T root(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }
}
