package com.documentplatform.notifications;

import java.util.Optional;

/**
 * Where settings are kept. Stores hold what they are given; {@link NotificationSettings} checks the rules before it
 * saves and again when it reads. Postgres implementation: {@code jdbc.PostgresSettingsStore}; an in-memory one for
 * tests is in the testkit module.
 *
 * <p>SPI: implemented by stores; apps call {@link NotificationSettings}.
 */
public interface SettingsStore {

    Optional<TenantSettings> tenant(TenantId tenant);

    void saveTenant(TenantId tenant, TenantSettings settings);

    /** Tenant-level settings of a technical event type. */
    Optional<EventSettings> technical(TenantId tenant, EventTypeId type);

    void saveTechnical(TenantId tenant, EventTypeId type, EventSettings settings);

    /** Per-entity settings of a business event type. */
    Optional<EventSettings> business(TenantId tenant, EntityId entity, EventTypeId type);

    void saveBusiness(TenantId tenant, EntityId entity, EventTypeId type, EventSettings settings);
}
