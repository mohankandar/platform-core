package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
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
import com.documentplatform.notifications.approval.ApprovalAction;
import com.documentplatform.notifications.approval.ApprovalAudit;
import com.documentplatform.notifications.approval.ApprovalResult;
import com.documentplatform.notifications.approval.ApprovalStore;
import com.documentplatform.notifications.approval.ApprovalType;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.NewApprovalRequest;
import com.documentplatform.notifications.approval.SignedInPerson;
import com.documentplatform.notifications.jdbc.PostgresApprovalStore;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.icegreen.greenmail.util.GreenMail;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.documentplatform.notifications.spring.TestApp.E1;
import static com.documentplatform.notifications.spring.TestApp.IT_ADMIN;
import static com.documentplatform.notifications.spring.TestApp.SECRETS;
import static com.documentplatform.notifications.spring.TestApp.TENANT;
import static com.documentplatform.notifications.spring.TestApp.await;
import static com.documentplatform.notifications.spring.TestApp.mailProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalWiringTest {

    static final RoleId APPROVER = RoleId.of("print-approver");
    static final EventTypeId WAITING = EventTypeId.of("approval.print-waiting");
    static final EventTypeId APPROVED = EventTypeId.of("approval.print-approved");
    static final EventTypeId DECLINED = EventTypeId.of("approval.print-declined");
    static final Recipient REQ = new Recipient("u-req", "req@customer.example");
    static final Recipient CY = new Recipient("u-cy", "cy@customer.example");
    static final String KEY_REF = "notifications/approval-link-key";
    static final String KEY = "Approval-LINK-KEY-SECRET-0123456789abcdefghij";
    static final List<ApprovalAudit.Entry> AUDIT = Collections.synchronizedList(new ArrayList<>());

    private final GreenMail mail = TestApp.startMail();
    private final Db db = Db.migrated();

    @AfterEach
    void stop() {
        mail.stop();
    }

    /** The beans an app with approvals defines. */
    @Configuration(proxyBeanMethods = false)
    static class AppWithoutApprovalTypes {
        @Bean
        EventCatalogue catalogue() {
            return EventCatalogue.builder(IT_ADMIN)
                    .register(EventType.builder(WAITING.value(), "Print request waiting", EventKind.BUSINESS)
                            .schedule(Schedule.immediately()).recipients(APPROVER).build())
                    .register(EventType.builder(APPROVED.value(), "Print request approved", EventKind.BUSINESS)
                            .schedule(Schedule.immediately()).recipients(APPROVER).build())
                    .register(EventType.builder(DECLINED.value(), "Print request declined", EventKind.BUSINESS)
                            .schedule(Schedule.immediately()).recipients(APPROVER).build())
                    .build();
        }

        @Bean
        RecipientResolver recipients() {
            return new RecipientResolver() {
                @Override
                public Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
                    return role.equals(APPROVER) ? Set.of(CY, REQ) : Set.of();
                }

                @Override
                public Optional<Recipient> person(TenantId tenant, String userId) {
                    return Set.of(CY, REQ).stream().filter(r -> r.userId().equals(userId)).findFirst();
                }
            };
        }

        @Bean
        SecretResolver secrets() {
            return ref -> Optional.ofNullable(SECRETS.get(ref)).map(Secret::of);
        }

        @Bean
        ApprovalAudit audit() {
            return AUDIT::add;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class App extends AppWithoutApprovalTypes {
        @Bean
        ApprovalType printRequest() {
            return ApprovalType.of("print", WAITING, APPROVED, DECLINED, APPROVER);
        }
    }

    private ApplicationContextRunner app(Class<?> beans, String... properties) {
        SECRETS.put(SecretRef.of(KEY_REF), KEY);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(beans)
                .withBean(DataSource.class, () -> db.owner)
                .withPropertyValues(mailProperties(mail.getSmtp().getPort(), properties));
    }

    @Test
    void approvalLinksWorkEndToEndWhenTheKeyIsConfigured() {
        app(App.class, "platform.notifications.approvals.link-key-secret=" + KEY_REF,
                "platform.notifications.approvals.link-path=/print/approve").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertInstanceOf(PostgresApprovalStore.class, ctx.getBean(ApprovalStore.class));
            ctx.getBean(NotificationSettings.class).connectChannels(TENANT, TestApp.tenant());
            Approvals approvals = ctx.getBean(Approvals.class);
            approvals.create(new NewApprovalRequest("print", TENANT, E1, REQ.userId(), RecordIdentifier.of("PR-7"),
                    AppLink.of("/print-requests/PR-7")));

            await(Duration.ofSeconds(10), "the approver's e-mail", () -> mail.getReceivedMessages().length == 1);
            MimeMessage m = mail.getReceivedMessages()[0];
            assertEquals(CY.emailAddress(), m.getAllRecipients()[0].toString(), "not to the requester");
            String body = ((String) m.getContent()).replace("\r\n", "\n");
            Matcher link = Pattern.compile("Approve: https://app\\.customer\\.example/print/approve/([A-Za-z0-9_.-]+)")
                    .matcher(body);
            assertTrue(link.find(), body);

            SignedInPerson cy = new SignedInPerson(TENANT, CY.userId(), "Cy Lee");
            assertInstanceOf(ApprovalResult.Ready.class, approvals.open(link.group(1), cy));
            assertInstanceOf(ApprovalResult.Decided.class, approvals.decide(link.group(1), cy, null));
            await(Duration.ofSeconds(10), "the requester's e-mail", () -> mail.getReceivedMessages().length == 2);
            assertTrue(Arrays.stream(mail.getReceivedMessages()).anyMatch(x -> {
                try {
                    return x.getSubject().equals("Print request approved: PR-7")
                            && x.getAllRecipients()[0].toString().equals(REQ.emailAddress());
                } catch (Exception e) {
                    return false;
                }
            }));
            assertTrue(AUDIT.stream().anyMatch(a -> a.outcome() == ApprovalAudit.Outcome.DECIDED
                    && a.action() == ApprovalAction.APPROVE));
        });
    }

    @Test
    void withoutTheKeyThereAreNoApprovalBeans() {
        app(App.class).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertNotNull(ctx.getBean(Notifier.class));
            assertTrue(ctx.getBeansOfType(Approvals.class).isEmpty());
            assertTrue(ctx.getBeansOfType(ApprovalStore.class).isEmpty());
        });
    }

    @Test
    void mistakesAreNamedWithTheirKeyAndNoValueIsRepeated() {
        String pasted = "https://vault.example/v1/KEY-PASTED-SECRET";
        app(App.class, "platform.notifications.approvals.link-key-secret=" + pasted).run(ctx -> {
            String message = failure(ctx.getStartupFailure());
            assertTrue(message.contains("platform.notifications.approvals.link-key-secret: not a valid secret "
                    + "reference"), message);
            assertFalse(message.contains("KEY-PASTED-SECRET"), message);
        });
        app(App.class, "platform.notifications.approvals.link-key-secret=" + KEY_REF,
                "platform.notifications.approvals.link-path=/approve?id=1").run(ctx ->
                assertTrue(failure(ctx.getStartupFailure()).contains("platform.notifications.approvals.link-path: "
                        + "must be a path in the app")));
        app(App.class, "platform.notifications.approvals.link-path=/print/approve").run(ctx ->
                assertTrue(failure(ctx.getStartupFailure()).contains(
                        "platform.notifications.approvals.link-key-secret: missing")));
        app(AppWithoutApprovalTypes.class, "platform.notifications.approvals.link-key-secret=" + KEY_REF).run(ctx ->
                assertTrue(failure(ctx.getStartupFailure()).contains("the app registers no request types: give at "
                        + "least one ApprovalType bean")));
    }

    private static String failure(Throwable t) {
        assertNotNull(t, "the app should not start");
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NotificationsConfigException) {
                return c.getMessage();
            }
        }
        throw new AssertionError("not a config failure", t);
    }
}
