package com.documentplatform.monitoring.checks;

import com.documentplatform.integration.result.HealthResult;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One check per enabled integration (destination or source), as the integration module's health gives it: healthy is
 * WORKING, busy is HAVING_PROBLEMS, anything else DOWN with its {@code INT_*} code. **Never** required at start-up and
 * never part of readiness: another system being down never makes this app unready.
 */
public final class IntegrationChecks implements Supplier<List<DependencyCheck>> {

    private final IntegrationStore store;
    private final Function<StoredIntegration, HealthResult> health;

    /**
     * @param health the integration's own health (for example {@code CallEngine::health} for API push and polling,
     *               {@code FolderExport::health}, {@code WatchedFolder::health}); null when the kind has none
     */
    public IntegrationChecks(IntegrationStore store, Function<StoredIntegration, HealthResult> health) {
        this.store = Objects.requireNonNull(store, "store");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override
    public List<DependencyCheck> get() {
        List<DependencyCheck> out = new ArrayList<>();
        for (StoredIntegration s : store.enabled()) out.add(new One(s.config().id()));
        return out;
    }

    private final class One implements DependencyCheck {
        private final UUID id;

        One(UUID id) {
            this.id = id;
        }

        @Override
        public String name() {
            return "integration-" + id;
        }

        @Override
        public String group() {
            return "integrations";
        }

        @Override
        public boolean requiredAtStartup() {
            return false;
        }

        @Override
        public Verdict check() {
            StoredIntegration s = store.current(id).orElse(null);
            if (s == null || !s.config().enabled()) return Verdict.working();
            HealthResult r = health.apply(s);
            if (r == null || r.healthy()) return Verdict.working();
            if (r.busy()) return Verdict.problems(r.code());
            return Verdict.down(r.code());
        }
    }
}
