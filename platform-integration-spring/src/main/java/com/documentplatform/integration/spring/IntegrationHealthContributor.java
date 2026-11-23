package com.documentplatform.integration.spring;

import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.folder.FolderExport;
import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.scan.FileScanner;
import com.documentplatform.integration.scan.Scanning;
import com.documentplatform.integration.store.IntegrationConfigs;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.integration.watch.WatchedFolder;
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
 * {@link CallEngine#health} (API push and API polling), {@link FolderExport#health} (folder export) or
 * {@link WatchedFolder#health} (watched folder); and, when a virus scanner is configured, one named {@value #SCANNER}.
 * Details: the outcome and the code only. Healthy and busy are UP (busy: the destination answers but asks to wait;
 * the outcome says BUSY); anything else is DOWN. Each result is kept for the configured time, so a busy health
 * endpoint does not call every destination each time. The list is read from the store when asked, so a new or
 * switched-off integration shows without a restart.
 *
 * <p>A destination that is down makes this contributor DOWN. Keep it out of the app's liveness and readiness groups
 * (the install guide says how) unless the app should stop taking work when a destination is down.
 */
public final class IntegrationHealthContributor implements CompositeHealthContributor {

    /** The scanner's entry. */
    public static final String SCANNER = "scanner";

    private record Cached(int version, Health health, Instant until) {
    }

    private final IntegrationConfigs configs;
    private final CallEngine engine;
    private final FolderExport folders;
    private final WatchedFolder watched;
    private final Scanning scanning;
    private final Duration cacheFor;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private volatile Cached scannerCache;

    public IntegrationHealthContributor(IntegrationConfigs configs, CallEngine engine, FolderExport folders,
                                        WatchedFolder watched, Scanning scanning, Duration cacheFor, Clock clock) {
        this.configs = configs;
        this.engine = engine;
        this.folders = folders;
        this.watched = watched;
        this.scanning = scanning;
        this.cacheFor = cacheFor;
        this.clock = clock;
    }

    private static boolean checked(StoredIntegration s) {
        Kind k = s.config().kind();
        return k == Kind.API_PUSH || k == Kind.FOLDER_EXPORT || k == Kind.API_POLL || k == Kind.WATCHED_FOLDER;
    }

    @Override
    public HealthContributor getContributor(String name) {
        if (name.equals(SCANNER)) return scanning.on() ? scannerIndicator() : null;
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
        if (scanning.on()) out.add(NamedContributor.of(SCANNER, scannerIndicator()));
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

    private HealthIndicator scannerIndicator() {
        return () -> {
            Instant now = clock.instant();
            Cached c = scannerCache;
            if (c != null && now.isBefore(c.until())) return c.health();
            FileScanner.Health h = scanning.health();
            Health health = (h != null && h.up() ? Health.up() : Health.down())
                    .withDetail("scanner", scanning.scanner().kind())
                    .withDetail("code", h == null ? "INT_SCAN_UNREACHABLE" : h.code().name()).build();
            scannerCache = new Cached(0, health, now.plus(cacheFor));
            return health;
        };
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
            r = switch (s.config().kind()) {
                case FOLDER_EXPORT -> folders.health(s);
                case WATCHED_FOLDER -> watched.health(s);
                default -> engine.health(s);
            };
        } catch (RuntimeException unexpected) {
            return Health.down().withDetail("reason", "health check failed").build();
        }
        Health.Builder b = r.healthy() || r.outcome() == Outcome.BUSY ? Health.up() : Health.down();
        return b.withDetail("outcome", r.outcome().name()).withDetail("code", r.code().name()).build();
    }
}
