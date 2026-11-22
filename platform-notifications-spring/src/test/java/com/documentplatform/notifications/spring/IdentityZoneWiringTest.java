package com.documentplatform.notifications.spring;

import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.notifications.CustomerZones;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identity is the one source of the customer's zone: with an {@code EntityTimeZones} bean, notifications reads it. */
class IdentityZoneWiringTest {

    private static final String TENANT = "TEN-ZONE-0001";

    private ApplicationContextRunner app() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> Db.migrated().owner)
                .withPropertyValues(TestApp.mailProperties(2525));
    }

    @Test
    void withIdentitysTimeZonesTheCustomersZoneComesFromIdentity() {
        InMemoryIdentityStore store = new InMemoryIdentityStore();
        store.saveCustomer(TENANT, "Zone customer");
        EntityTimeZones zones = new EntityTimeZones(store);
        app().withBean(EntityTimeZones.class, () -> zones).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.containsBean("notifier") || ctx.getBeansOfType(Notifier.class).size() == 1);
            CustomerZones customer = ctx.getBean(CustomerZones.class);
            assertEquals(Optional.empty(), customer.zoneOf(TenantId.of(TENANT)), "identity has none yet: fallback");
            zones.setCustomerZone(TENANT, "America/Chicago", "it-admin");
            assertEquals(Optional.of(ZoneId.of("America/Chicago")), customer.zoneOf(TenantId.of(TENANT)));
            assertEquals(Optional.empty(), customer.zoneOf(TenantId.of("other-tenant")), "not a tenant ID");
        });
    }

    @Test
    void withoutIdentityOrWithTheAppsOwnThereIsNoBridge() {
        app().run(ctx -> assertFalse(ctx.containsBean("identityCustomerZones")));
        CustomerZones own = tenant -> Optional.of(ZoneId.of("Asia/Tokyo"));
        InMemoryIdentityStore store = new InMemoryIdentityStore();
        app().withBean(EntityTimeZones.class, () -> new EntityTimeZones(store))
                .withBean(CustomerZones.class, () -> own)
                .run(ctx -> assertSame(own, ctx.getBean(CustomerZones.class)));
    }
}
