package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.StoredIntegration;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /actuator/health/integrations}: one contributor per enabled integration, named by its ID, from
 * {@link CallEngine#health} (API push) or {@link FolderExport#health} (folder export). Details: the outcome and the
 * code only. Healthy and busy are UP (busy: the destination answers but asks to wait; the outcome says BUSY); anything
 * else is DOWN. Each result is kept for the configured time, so a busy health endpoint does not call every
 * destination each time. The list is read from the store when asked, so a new or switched-off integration shows
 * without a restart.
 *
 * <p>A destination that is down makes this contributor DOWN. Keep it out of the app's liveness and readiness groups
 * (the install guide says how) unless the app should stop taking work when a destination is down.
 */
public final class IntegrationHealthContributor implements CompositeHealthContributor {

    private record Cached(int version, Health health, Instant until) {
    }

    private final IntegrationConfigs configs;
    private final CallEngine engine;
    private final FolderExport folders;
    private final Duration cacheFor;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public IntegrationHealthContributor(IntegrationConfigs configs, CallEngine engine, FolderExport folders,
                                        Duration cacheFor, Clock clock) {
        this.configs = configs;
        this.engine = engine;
        this.folders = folders;
        this.cacheFor = cacheFor;
        this.clock = clock;
    }

    private static boolean checked(StoredIntegration s) {
        return s.config().kind() == Kind.API_PUSH || s.config().kind() == Kind.FOLDER_EXPORT;
    }

    @Override
    public HealthContributor getContributor(String name) {
        UUID id;
        try {
            id = UUID.fromString(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Optional<StoredIntegration> s = configs.current(id);
        if (s.isEmpty() || !s.get().config().enabled() || !checked(s.get())) return null;
        return indicator(id);
    }

    @Override
    public Iterator<NamedContributor<HealthContributor>> iterator() {
        List<NamedContributor<HealthContributor>> out = new ArrayList<>();
        List<StoredIntegration> enabled;
        try {
            enabled = configs.enabled();
        } catch (RuntimeException storeDown) {
            HealthIndicator down = () -> Health.down().withDetail("reason", "integration store unavailable").build();
            out.add(NamedContributor.of("store", down));
            return out.iterator();
        }
        for (StoredIntegration s : enabled) {
            if (checked(s)) out.add(NamedContributor.of(s.id().toString(), indicator(s.id())));
        }
        return out.iterator();
    }

    private HealthIndicator indicator(UUID id) {
        return () -> {
            Optional<StoredIntegration> s = configs.current(id);
            if (s.isEmpty()) return Health.unknown().build();
            Cached c = cache.get(id);
            Instant now = clock.instant();
            if (c != null && c.version() == s.get().version() && now.isBefore(c.until())) return c.health();
            Health h = health(s.get());
            cache.put(id, new Cached(s.get().version(), h, now.plus(cacheFor)));
            return h;
        };
    }

    private Health health(StoredIntegration s) {
        HealthResult r;
        try {
            r = s.config().kind() == Kind.FOLDER_EXPORT ? folders.health(s) : engine.health(s);
        } catch (RuntimeException unexpected) {
            return Health.down().withDetail("reason", "health check failed").build();
        }
        Health.Builder b = r.healthy() || r.outcome() == Outcome.BUSY ? Health.up() : Health.down();
        return b.withDetail("outcome", r.outcome().name()).withDetail("code", r.code().name()).build();
    }
}
