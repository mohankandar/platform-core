package com.documentplatform.integration.testkit;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.store.StoredIntegration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Configurations and history in memory, with the same rules as the Postgres store. For tests. */
public final class InMemoryIntegrationStore implements IntegrationStore {

    private record Entry(List<StoredIntegration> versions, List<ConfigChange> history) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    @Override
    public synchronized StoredIntegration create(IntegrationConfig config, ConfigChange change) {
        if (entries.containsKey(config.id())) throw new Conflict(IntegrationCode.INT_CONFIG_EXISTS);
        StoredIntegration s = new StoredIntegration(config, 1, change.changedBy(), change.changedAt());
        Entry e = new Entry(new ArrayList<>(), new ArrayList<>());
        e.versions().add(s);
        e.history().add(change);
        entries.put(config.id(), e);
        return s;
    }

    @Override
    public synchronized StoredIntegration update(IntegrationConfig config, int expectedVersion, ConfigChange change) {
        Entry e = entries.get(config.id());
        if (e == null) throw new Conflict(IntegrationCode.INT_CONFIG_NOT_FOUND);
        if (e.versions().size() != expectedVersion) throw new Conflict(IntegrationCode.INT_CONFIG_VERSION_CONFLICT);
        IntegrationConfig first = e.versions().get(0).config();
        if (!first.entity().equals(config.entity()) || !first.product().equals(config.product())
                || first.kind() != config.kind()) {
            throw new IllegalArgumentException("entity, product and kind never change");
        }
        StoredIntegration s = new StoredIntegration(config, expectedVersion + 1, change.changedBy(),
                change.changedAt());
        e.versions().add(s);
        e.history().add(change);
        return s;
    }

    @Override
    public synchronized Optional<StoredIntegration> current(UUID id) {
        Entry e = entries.get(id);
        return e == null ? Optional.empty() : Optional.of(e.versions().get(e.versions().size() - 1));
    }

    @Override
    public synchronized Optional<StoredIntegration> version(UUID id, int version) {
        Entry e = entries.get(id);
        if (e == null || version < 1 || version > e.versions().size()) return Optional.empty();
        return Optional.of(e.versions().get(version - 1));
    }

    @Override
    public synchronized List<ConfigChange> history(UUID id) {
        Entry e = entries.get(id);
        return e == null ? List.of() : List.copyOf(e.history());
    }

    @Override
    public synchronized List<StoredIntegration> list(UUID entity, String product) {
        List<StoredIntegration> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            StoredIntegration s = e.versions().get(e.versions().size() - 1);
            if (s.config().entity().equals(entity) && s.config().product().equals(product)) out.add(s);
        }
        return out;
    }

    @Override
    public synchronized List<StoredIntegration> enabled() {
        List<StoredIntegration> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            StoredIntegration s = e.versions().get(e.versions().size() - 1);
            if (s.config().enabled()) out.add(s);
        }
        return out;
    }
}
