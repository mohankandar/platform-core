package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.jdbc.NotificationSchema;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresOutboxStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/**
 * Builds the {@link Notifier} from the app's beans and the install config ({@code platform.notifications.*}).
 *
 * <p>The app supplies: an {@link EventCatalogue} (its event types), a {@link RecipientResolver} (role → people), a
 * {@link SecretResolver} (reads the mail password and webhook addresses from the secret store) and the
 * {@link DataSource} holding the {@code notif_} tables. Nothing app-specific is here.
 *
 * <p>At startup: the config is checked ({@link NotificationsConfig}) and the tables are checked
 * ({@link NotificationSchema#verify}); either stops the app with a specific message. Startup never creates tables or
 * inserts rows: that is the separate schema step the customer runs.
 *
 * <p>Every bean backs off when the app defines its own of the same type (for example in-memory stores in tests).
 * {@code platform.notifications.enabled=false} switches the whole module off.
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
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
    MicrosoftTokens notificationMailTokens(NotificationsConfig config) {
        return new MicrosoftTokens(Clock.systemUTC(),
                config.mail().map(MailServer::connectTimeout).orElse(MailServer.DEFAULT_CONNECT_TIMEOUT));
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationChannels notificationChannels(NotificationsConfig config, ObjectProvider<SecretResolver> secrets,
                                              DestinationHealth health, MicrosoftTokens tokens) {
        boolean anyChannel = config.mail().isPresent() || !config.teams().isEmpty() || !config.slack().isEmpty();
        SecretResolver resolver = anyChannel
                ? required(secrets, SecretResolver.class, "the reader of the secret store (mail password or "
                + "Microsoft 365 app credential, webhook addresses)")
                : ref -> java.util.Optional.empty();
        return new NotificationChannels(config, resolver, health, tokens);
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
                      ConditionStore conditions, NotificationsConfig config, SenderSignal signal) {
        return Notifier.builder(settings, required(recipients, RecipientResolver.class,
                        "the app's recipient resolver (role → people)"))
                .channels(channels.channels())
                .outbox(outbox).summaries(summaries).conditions(conditions)
                .retryPolicy(config.retry())
                .lease(config.lease())
                .keepFinishedFor(config.retention())
                .batchSize(config.batchSize())
                .whenQueued(signal::raise)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    NotificationWorkers notificationWorkers(Notifier notifier, SenderSignal signal, NotificationsConfig config) {
        return new NotificationWorkers(notifier, signal, Clock.systemUTC(), config.tickInterval(),
                config.pollInterval());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean
        NotificationsHealthIndicator notificationsHealthIndicator(DestinationHealth health, NotificationsConfig config,
                                                                  MicrosoftTokens tokens) {
            return new NotificationsHealthIndicator(health, config.mail().orElse(null), tokens);
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
