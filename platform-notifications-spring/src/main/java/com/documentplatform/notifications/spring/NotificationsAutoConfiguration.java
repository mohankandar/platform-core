package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.spring.internal.NotificationsConfig;
import com.documentplatform.notifications.ActionLinks;
import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.CustomerZones;
import com.documentplatform.notifications.EntityZones;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.approval.ActionTokens;
import com.documentplatform.notifications.approval.ApprovalAudit;
import com.documentplatform.notifications.approval.ApprovalLinks;
import com.documentplatform.notifications.approval.ApprovalStore;
import com.documentplatform.notifications.approval.ApprovalType;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.ApproverCheck;
import com.documentplatform.notifications.jdbc.NotificationSchema;
import com.documentplatform.notifications.jdbc.PostgresApprovalStore;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresOutboxStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.transport.HttpTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Builds the {@link Notifier} from the app's beans and the install config ({@code platform.notifications.*}).
 *
 * <p>The app supplies: an {@link EventCatalogue} (its event types), a {@link RecipientResolver} (role → people), a
 * {@link SecretResolver} (reads the mail password and webhook addresses from the secret store) and the
 * {@link DataSource} holding the {@code notif_} tables. Nothing app-specific is here.
 *
 * <p>At startup: the config is checked (every {@code platform.notifications.*} value) and the tables are checked
 * ({@link NotificationSchema#verify}); either stops the app with a specific message. Startup never creates tables or
 * inserts rows: that is the separate schema step the customer runs.
 *
 * <p>Every bean backs off when the app defines its own of the same type (for example in-memory stores in tests).
 * {@code platform.notifications.enabled=false} switches the whole module off.
 */
@AutoConfiguration(afterName = {"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "com.documentplatform.outbound.spring.OutboundAutoConfiguration"})
@ConditionalOnProperty(prefix = NotificationsProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(NotificationsProperties.class)
public class NotificationsAutoConfiguration {

    @Bean
    NotificationsConfig notificationsConfig(NotificationsProperties properties) {
        return NotificationsConfig.from(properties);
    }

    /** The checked tables: created only when a Postgres store is, so apps with their own stores need no database. */
    @Bean
    @Lazy
    NotificationTables notificationTables(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfUnique();
        if (ds == null) {
            throw new NotificationsConfigException(List.of("Notifications needs the app's DataSource (the database "
                    + "holding the notif_ tables): none, or several without one marked @Primary, was found"));
        }
        NotificationSchema.verify(ds);
        return new NotificationTables(ds);
    }

    @Bean
    @ConditionalOnMissingBean
    SettingsStore notificationSettingsStore(NotificationTables tables) {
        return new PostgresSettingsStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxStore notificationOutboxStore(NotificationTables tables) {
        return new PostgresOutboxStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    SummaryStore notificationSummaryStore(NotificationTables tables) {
        return new PostgresSummaryStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    ConditionStore notificationConditionStore(NotificationTables tables) {
        return new PostgresConditionStore(tables.dataSource());
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationSettings notificationSettings(ObjectProvider<EventCatalogue> catalogue, SettingsStore store) {
        return new NotificationSettings(required(catalogue, EventCatalogue.class, "the app's event catalogue"), store);
    }

    @Bean
    @ConditionalOnMissingBean
    DestinationHealth notificationDestinationHealth() {
        return new DestinationHealth(Clock.systemUTC());
    }

    /** Microsoft 365 mail tokens (auth OAUTH2_MICROSOFT): held by the e-mail sender, shown (state only) in health. */
    @Bean
    @ConditionalOnMissingBean
    MicrosoftTokens notificationMailTokens(NotificationsConfig config, ObjectProvider<HttpTransport> transports) {
        Duration connect = config.mail().map(MailServer::connectTimeout).orElse(MailServer.DEFAULT_CONNECT_TIMEOUT);
        HttpTransport transport = transport(config, transports);
        if (transport == null) {
            return new MicrosoftTokens(Clock.systemUTC(), connect);
        }
        // P059: only the sign-ins whose mail server says via-outbound; the others keep the direct client
        return new MicrosoftTokens(Clock.systemUTC(), connect, oauth -> config.viaOutbound(oauth) ? transport : null);
    }

    /**
     * P059 (LV-24.3 step 1): the transport over platform-outbound when a setting says {@code via-outbound}, else
     * null (every call as before 1.3.0). A {@code via-outbound} without platform-outbound stops the app, listing each
     * setting.
     */
    static HttpTransport transport(NotificationsConfig config, ObjectProvider<HttpTransport> transports) {
        config.notes().forEach(n -> LOG.log(System.Logger.Level.WARNING, n));
        if (!config.anyViaOutbound()) {
            return null;
        }
        HttpTransport t = transports.getIfUnique();
        if (t == null) {
            List<String> problems = new java.util.ArrayList<>();
            List<String> keys = new java.util.ArrayList<>(config.outboundSignIns().keySet());
            if (config.webhooksViaOutbound()) {
                keys.add("webhook");
            }
            for (String k : keys) {
                problems.add(NotificationsProperties.PREFIX + "." + k + ".via-outbound: true, but "
                        + "platform.outbound.installation is not set (or platform-outbound-spring is not on the class "
                        + "path); set the outbound settings (docs/install/integration.md section 1), or switch "
                        + "via-outbound off");
            }
            throw new NotificationsConfigException(problems);
        }
        return t;
    }

    private static final System.Logger LOG = System.getLogger(NotificationsAutoConfiguration.class.getName());

    /** P059: the senders' transport over the installation's {@code Outbound} (platform-outbound-spring). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.documentplatform.outbound.Outbound")
    static class OutboundTransportConfiguration {

        @Bean
        @ConditionalOnBean(type = "com.documentplatform.outbound.Outbound")
        @ConditionalOnMissingBean
        HttpTransport notificationOutboundTransport(com.documentplatform.outbound.Outbound outbound,
                                                    NotificationsConfig config) {
            com.documentplatform.notifications.spring.internal.OutboundHttpTransport.checkAtStart(config,
                    outbound.installation());
            return new com.documentplatform.notifications.spring.internal.OutboundHttpTransport(outbound);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationChannels notificationChannels(NotificationsConfig config, ObjectProvider<SecretResolver> secrets,
                                              DestinationHealth health, MicrosoftTokens tokens,
                                              ObjectProvider<HttpTransport> transports) {
        boolean anyChannel = config.anyMail() || !config.teams().isEmpty() || !config.slack().isEmpty();
        SecretResolver resolver = anyChannel
                ? required(secrets, SecretResolver.class, "the reader of the secret store (mail password or "
                + "Microsoft 365 app credential, webhook addresses)")
                : ref -> java.util.Optional.empty();
        return new NotificationChannels(config, resolver, health, tokens,
                config.webhooksViaOutbound() ? transport(config, transports) : null);
    }

    @Bean
    @ConditionalOnMissingBean
    SenderSignal notificationSenderSignal() {
        return new SenderSignal();
    }

    @Bean
    @ConditionalOnMissingBean
    Notifier notifier(NotificationSettings settings, ObjectProvider<RecipientResolver> recipients,
                      NotificationChannels channels, OutboxStore outbox, SummaryStore summaries,
                      ConditionStore conditions, NotificationsConfig config, SenderSignal signal,
                      ObjectProvider<ActionLinks> actionLinks, ObjectProvider<EntityZones> entityZones,
                      ObjectProvider<CustomerZones> customerZones) {
        Notifier.Builder b = Notifier.builder(settings, required(recipients, RecipientResolver.class,
                        "the app's recipient resolver (role → people)"))
                .channels(channels.channels())
                .outbox(outbox).summaries(summaries).conditions(conditions)
                .retryPolicy(config.retry())
                .lease(config.lease())
                .keepFinishedFor(config.retention())
                .batchSize(config.batchSize())
                .whenQueued(signal::raise);
        actionLinks.ifUnique(b::actionLinks);
        entityZones.ifUnique(b::entityZones);
        customerZones.ifUnique(b::customerZones);
        return b.build();
    }

    /**
     * The customer's zone from identity, the one source of it, when the app has platform-identity's
     * {@code EntityTimeZones} as a bean: notifications then uses {@code idn_customer.time_zone} and falls back to its
     * own tenant zone only when identity has none. Backs off for an app-defined {@link CustomerZones}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.documentplatform.identity.store.EntityTimeZones")
    static class IdentityZoneConfiguration {

        @Bean
        @ConditionalOnBean(type = "com.documentplatform.identity.store.EntityTimeZones")
        @ConditionalOnMissingBean
        CustomerZones identityCustomerZones(com.documentplatform.identity.store.EntityTimeZones zones) {
            return tenant -> zones.customerZone(tenant.value());
        }
    }

    /**
     * Approval links, switched on by {@code platform.notifications.approvals.link-key-secret}. The app supplies its
     * request types ({@link ApprovalType} beans) and its {@link ApprovalAudit}; it may supply an
     * {@link ApproverCheck}, otherwise the recipient resolver answers who holds the approver role.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = NotificationsProperties.PREFIX + ".approvals", name = "link-key-secret")
    static class ApprovalConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ApprovalStore notificationApprovalStore(NotificationTables tables) {
            return new PostgresApprovalStore(tables.dataSource());
        }

        @Bean
        @ConditionalOnMissingBean
        ActionTokens notificationActionTokens(NotificationsConfig config, ObjectProvider<SecretResolver> secrets) {
            return new ActionTokens(required(secrets, SecretResolver.class, "the reader of the secret store (the "
                    + "approval link key)"), approvalConfig(config).keySecret());
        }

        @Bean
        @ConditionalOnMissingBean
        ApprovalLinks notificationApprovalLinks(ApprovalStore store, ActionTokens tokens, NotificationsConfig config) {
            return new ApprovalLinks(store, tokens, approvalConfig(config).pagePath());
        }

        @Bean
        @ConditionalOnMissingBean
        Approvals approvals(ObjectProvider<ApprovalType> types, NotificationSettings settings, ApprovalStore store,
                            ActionTokens tokens, Notifier notifier, ObjectProvider<ApproverCheck> approvers,
                            ObjectProvider<RecipientResolver> recipients, ObjectProvider<ApprovalAudit> audit) {
            List<ApprovalType> all = types.orderedStream().toList();
            if (all.isEmpty()) {
                throw new NotificationsConfigException(List.of("Approval links are switched on ("
                        + NotificationsProperties.PREFIX + ".approvals.link-key-secret) but the app registers no "
                        + "request types: give at least one ApprovalType bean"));
            }
            ApproverCheck check = approvers.getIfUnique();
            if (check == null) {
                check = ApproverCheck.from(required(recipients, RecipientResolver.class,
                        "the app's recipient resolver (role → people)"));
            }
            return Approvals.builder(all, settings.catalogue(), store, tokens, notifier).approvers(check)
                    .audit(required(audit, ApprovalAudit.class, "the app's audit of approval decisions")).build();
        }

        private static NotificationsConfig.ApprovalLinksConfig approvalConfig(NotificationsConfig config) {
            return config.approvals().orElseThrow(() -> new NotificationsConfigException(List.of(
                    NotificationsProperties.PREFIX + ".approvals.link-key-secret: not valid")));
        }
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationWorkers notificationWorkers(Notifier notifier, SenderSignal signal, NotificationsConfig config,
                                            ObjectProvider<Approvals> approvals) {
        return new NotificationWorkers(notifier, signal, Clock.systemUTC(), config.tickInterval(),
                config.pollInterval(), approvals.getIfUnique(), config.retention());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean
        NotificationsHealthIndicator notificationsHealthIndicator(DestinationHealth health, NotificationsConfig config,
                                                                  MicrosoftTokens tokens) {
            java.util.Map<String, MailServer> more = new java.util.LinkedHashMap<>();
            config.mailServers().forEach((name, m) -> more.put(name, m.server()));
            return new NotificationsHealthIndicator(health, config.mail().orElse(null), tokens, more);
        }
    }

    private static <T> T required(ObjectProvider<T> provider, Class<T> type, String what) {
        T bean = provider.getIfUnique();
        if (bean == null) {
            throw new NotificationsConfigException(List.of("Notifications needs a " + type.getSimpleName()
                    + " bean from the app: " + what + ". None, or several without one marked @Primary, was found"));
        }
        return bean;
    }

    /** The DataSource whose notif_ tables passed the startup check. */
    record NotificationTables(DataSource dataSource) {
    }
}
