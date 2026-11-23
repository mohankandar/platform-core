package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.DeliveryReport;
import com.documentplatform.notifications.DeliveryStatus;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresOutboxStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.documentplatform.notifications.spring.TestApp.APPROVAL;
import static com.documentplatform.notifications.spring.TestApp.STORE_DOWN;
import static com.documentplatform.notifications.spring.TestApp.TENANT;
import static com.documentplatform.notifications.spring.TestApp.approval;
import static com.documentplatform.notifications.spring.TestApp.await;
import static com.documentplatform.notifications.spring.TestApp.mailProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringTest {

    private final GreenMail mail = TestApp.startMail();
    private final Db db = Db.migrated();

    @AfterEach
    void stop() {
        mail.stop();
    }

    private ApplicationContextRunner app(DataSource ds) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> ds);
    }

    @Test
    void buildsTheNotifierFromTheAppsBeansWithPostgresStores() {
        app(db.owner).withPropertyValues(mailProperties(mail.getSmtp().getPort())).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(Notifier.class));
            assertNotNull(ctx.getBean(NotificationSettings.class));
            assertInstanceOf(PostgresSettingsStore.class, ctx.getBean(SettingsStore.class));
            assertInstanceOf(PostgresOutboxStore.class, ctx.getBean(OutboxStore.class));
            assertInstanceOf(PostgresSummaryStore.class, ctx.getBean(SummaryStore.class));
            assertInstanceOf(PostgresConditionStore.class, ctx.getBean(ConditionStore.class));
            assertNotNull(ctx.getBean(NotificationsHealthIndicator.class));
            assertTrue(ctx.getBean(NotificationWorkers.class).isRunning());
            assertEquals(1, ctx.getBean(NotificationChannels.class).channels().size(), "e-mail only");
        });
    }

    @Test
    void anImmediateEventIsSentByTheBackgroundSender() {
        app(db.owner).withPropertyValues(mailProperties(mail.getSmtp().getPort())).run(ctx -> {
            ctx.getBean(NotificationSettings.class).connectChannels(TENANT, TestApp.tenant());
            List<DeliveryReport> reports = ctx.getBean(Notifier.class).publish(approval("LN 1"));
            assertEquals(List.of(DeliveryStatus.PENDING), reports.stream().map(DeliveryReport::status).toList(),
                    "publish only saves");
            await(Duration.ofSeconds(10), "the e-mail", () -> mail.getReceivedMessages().length == 1);
            MimeMessage m = mail.getReceivedMessages()[0];
            assertEquals("Approval waiting: LN 1", m.getSubject());
            // The mail server has the message a moment before the sender records it as sent.
            await(Duration.ofSeconds(10), "the outbox row marked SENT",
                    () -> db.count("select count(*) from notif_outbox where state = 'SENT'") == 1);
        });
    }

    @Test
    void tickIsScheduledAndRaisesConditionAlerts() {
        app(db.owner).withPropertyValues(mailProperties(mail.getSmtp().getPort(),
                "platform.notifications.tick-interval=1s")).run(ctx -> {
            NotificationSettings settings = ctx.getBean(NotificationSettings.class);
            settings.connectChannels(TENANT, TestApp.tenant());
            settings.setTechnicalEvent(TENANT, STORE_DOWN,
                    EventSettings.defaultsFor(settings.catalogue().require(STORE_DOWN)));
            Notifier n = ctx.getBean(Notifier.class);
            assertTrue(n.conditionFailing(NotificationEvent.builder(STORE_DOWN, TENANT)
                    .identifier(RecordIdentifier.of("store-1")).build()).isEmpty(), "under the 1 s threshold");
            // Nobody calls tick here: the scheduled tick raises the alert once the threshold has passed.
            await(Duration.ofSeconds(10), "the alert", () -> mail.getReceivedMessages().length == 1);
            assertEquals("Store down: store-1", mail.getReceivedMessages()[0].getSubject());

            // notifications-85: the module imposes nothing on the app's scheduling. There is no @EnableScheduling
            // here (no ScheduledAnnotationBeanPostProcessor), yet the tick ran; it and the sender are the module's
            // own daemon threads, one each for this instance.
            assertTrue(ctx.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty(),
                    "the context has no @EnableScheduling");
            for (String name : List.of("platform-notifications-tick", "platform-notifications-sender")) {
                List<Thread> threads = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().equals(name) && t.isAlive()).toList();
                assertEquals(1, threads.size(), "one " + name + " thread: " + threads);
                assertTrue(threads.get(0).isDaemon(), name + " is a daemon thread");
            }
        });
    }

    @Test
    void twoAppInstancesOnOneDatabaseSendEachMessageOnce() {
        ApplicationContextRunner runner = app(db.owner).withPropertyValues(mailProperties(mail.getSmtp().getPort()));
        runner.run(one -> runner.run(two -> {
            one.getBean(NotificationSettings.class).connectChannels(TENANT, TestApp.tenant());
            for (int i = 0; i < 20; i++) {
                (i % 2 == 0 ? one : two).getBean(Notifier.class).publish(approval("LN " + i));
            }
            await(Duration.ofSeconds(20), "20 e-mails", () -> mail.getReceivedMessages().length >= 20);
            Thread.sleep(2000); // both senders keep polling: nothing more may arrive
            Set<String> subjects = new HashSet<>();
            Arrays.stream(mail.getReceivedMessages()).forEach(m -> {
                try {
                    assertTrue(subjects.add(m.getSubject()), "sent twice: " + m.getSubject());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertEquals(20, subjects.size());
            assertEquals(20, db.count("select count(*) from notif_outbox where state = 'SENT' and attempts = 1"));
        }));
    }

    @Test
    void canBeSwitchedOff() {
        app(db.owner).withPropertyValues("platform.notifications.enabled=false").run(ctx -> {
            assertFalse(ctx.containsBean("notifier"));
            assertTrue(ctx.getBeansOfType(Notifier.class).isEmpty());
        });
    }

    @Test
    void theAppsOwnStoresAreUsedAndNoDatabaseIsNeeded() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(SettingsStore.class, () -> new PostgresSettingsStore(db.owner))
                .withBean(OutboxStore.class, () -> new PostgresOutboxStore(db.owner))
                .withBean(SummaryStore.class, () -> new PostgresSummaryStore(db.owner))
                .withBean(ConditionStore.class, () -> new PostgresConditionStore(db.owner))
                .run(ctx -> {
                    assertEquals(null, ctx.getStartupFailure());
                    assertNotNull(ctx.getBean(Notifier.class));
                    assertTrue(ctx.getBeansOfType(DataSource.class).isEmpty());
                });
    }

    @Test
    void aMissingAppBeanIsNamed() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.NoCatalogue.class)
                .withBean(DataSource.class, () -> db.owner)
                .run(ctx -> {
                    Throwable failure = rootOf(ctx.getStartupFailure(), NotificationsConfigException.class);
                    assertTrue(failure.getMessage().contains("Notifications needs a EventCatalogue bean from the app: "
                            + "the app's event catalogue"), failure.getMessage());
                });
    }

    @Test
    void publishEventsBeforeConnectingChannelsReportNoDestination() {
        app(db.owner).withPropertyValues(mailProperties(mail.getSmtp().getPort())).run(ctx -> {
            ctx.getBean(NotificationSettings.class);
            DeliveryReport r = ctx.getBean(Notifier.class).publish(approval("LN 9")).get(0);
            assertEquals(DeliveryStatus.NO_DESTINATION, r.status());
            assertEquals(Set.of(APPROVAL), r.eventTypes());
        });
    }

    static Throwable rootOf(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return c;
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }
}
