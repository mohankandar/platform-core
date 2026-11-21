package com.documentplatform.licensing.spring.notifications;

import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.NoticeLog;
import com.documentplatform.licensing.spring.LicensingAutoConfiguration;
import com.documentplatform.licensing.spring.LicensingClock;
import com.documentplatform.licensing.spring.LicensingConfigException;
import com.documentplatform.licensing.spring.LicensingProperties;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Licensing notifications, switched on only when the app has platform-notifications (its {@link Notifier} bean).
 * Without it licensing works the same and publishes nothing: this class is not even loaded.
 *
 * <p>The app registers the licensing event types in its catalogue ({@link LicensingEvents#register}) and supplies its
 * {@link ActivatedSlots}. A catalogue without licensing types switches the check off (logged once at start).
 * {@code platform.licensing.notifications.enabled=false} switches it off too.
 */
@AutoConfiguration(after = LicensingAutoConfiguration.class,
        afterName = "com.documentplatform.notifications.spring.NotificationsAutoConfiguration")
@ConditionalOnClass(name = "com.documentplatform.notifications.Notifier")
@ConditionalOnBean(type = {"com.documentplatform.licensing.LicenceService",
        "com.documentplatform.notifications.Notifier"})
@ConditionalOnProperty(prefix = LicensingProperties.PREFIX + ".notifications", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class LicensingNotificationsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    LicensingNotices licensingNotices(LicensingProperties properties, LicenceService licence, Notifier notifier,
                                      NotificationSettings settings, NoticeLog log,
                                      ObjectProvider<ActivatedSlots> slots, LicensingClock clock) {
        LicensingNotificationsConfig config = LicensingNotificationsConfig.from(properties.getNotifications());
        ActivatedSlots activated = slots.getIfUnique();
        boolean business = LicensingNotices.registeredIn(settings.catalogue()).stream()
                .anyMatch(id -> settings.catalogue().require(id).kind() == EventKind.BUSINESS);
        if (activated == null) {
            if (business) {
                throw new LicensingConfigException(List.of("Licensing notifications need an ActivatedSlots bean (which"
                        + " entity slots the Business Admin has activated, slot ID -> entity ID): none, or several"
                        + " without one marked @Primary, was found. Business licensing events are published per"
                        + " activated slot."));
            }
            activated = tenant -> java.util.Map.of();
        }
        return new LicensingNotices(licence, notifier, settings, log, activated, clock.clock(), config.options());
    }

    @Bean
    @ConditionalOnMissingBean
    LicensingNoticeWorker licensingNoticeWorker(LicensingNotices notices, LicensingProperties properties) {
        return new LicensingNoticeWorker(notices,
                LicensingNotificationsConfig.from(properties.getNotifications()).checkInterval());
    }
}
