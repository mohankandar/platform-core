package com.documentplatform.fields.inmemory;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitionException;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Field definitions in memory, with the same rules as the Postgres store: keys unique per entity and product
 * ignoring capitals, a change only from the version read, every version in the history, nothing ever deleted. For
 * tests.
 */
public final class InMemoryFieldStore implements FieldStore {

    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    private final Map<UUID, List<HistoryEntry>> history = new HashMap<>();
    private final Map<UUID, Map<String, String>> extensions = new HashMap<>();

    @Override
    public synchronized List<FieldDefinition> list(FieldScope scope) {
        return fields.values().stream().filter(d -> d.scope().equals(scope))
                .sorted(Comparator.comparingInt((FieldDefinition d) -> d.spec().displayOrder())
                        .thenComparing(FieldDefinition::key))
                .toList();
    }

    @Override
    public synchronized Optional<FieldDefinition> find(FieldScope scope, FieldKey key) {
        return Optional.ofNullable(fields.get(slot(scope, key)));
    }

    @Override
    public synchronized List<FieldDefinition> create(FieldScope scope, List<FieldSpec> specs, Change change,
                                                     String actor, Instant at) {
        List<FieldDefinition> out = new ArrayList<>();
        Map<String, FieldDefinition> pending = new LinkedHashMap<>();
        for (FieldSpec spec : specs) {
            String slot = slot(scope, spec.key());
            if (fields.containsKey(slot) || pending.containsKey(slot)) {
                throw FieldDefinitionException.keyTaken(spec.key());
            }
            FieldDefinition d = new FieldDefinition(UUID.randomUUID(), scope, spec, 1, at, at, actor);
            pending.put(slot, d);
            out.add(d);
        }
        fields.putAll(pending);
        for (FieldDefinition d : out) {
            history.computeIfAbsent(d.id(), id -> new ArrayList<>()).add(entry(d, change));
        }
        return out;
    }

    @Override
    public synchronized FieldDefinition update(FieldDefinition current, FieldSpec next, Change change, String actor,
                                               Instant at) {
        if (!current.key().equals(next.key())) {
            throw new FieldDefinitionException(FieldDefinitionException.Code.FIELD_KEY_LOCKED,
                    "Field " + current.key() + ": a field's key never changes");
        }
        String slot = slot(current.scope(), current.key());
        FieldDefinition stored = fields.get(slot);
        if (stored == null || !stored.id().equals(current.id())) {
            throw FieldDefinitionException.unknown(current.key());
        }
        if (stored.version() != current.version()) {
            throw FieldDefinitionException.changedMeanwhile(current.key());
        }
        FieldDefinition d = new FieldDefinition(stored.id(), stored.scope(), next, stored.version() + 1,
                stored.createdAt(), at, actor);
        fields.put(slot, d);
        history.get(d.id()).add(entry(d, change));
        return d;
    }

    @Override
    public synchronized List<FieldDefinition> saveAll(FieldScope scope, List<Save> saves, String actor, Instant at) {
        Map<String, FieldDefinition> before = new LinkedHashMap<>(fields);
        Map<UUID, List<HistoryEntry>> historyBefore = new HashMap<>();
        history.forEach((id, h) -> historyBefore.put(id, new ArrayList<>(h)));
        List<FieldDefinition> out = new ArrayList<>();
        try {
            for (Save s : saves) {
                out.add(s.current() == null ? create(scope, List.of(s.next()), Change.CREATED, actor, at).get(0)
                        : update(s.current(), s.next(), Change.CHANGED, actor, at));
            }
            return out;
        } catch (RuntimeException e) {
            fields.clear();
            fields.putAll(before);
            history.clear();
            history.putAll(historyBefore);
            throw e;
        }
    }

    @Override
    public synchronized List<HistoryEntry> history(FieldScope scope, FieldKey key) {
        return find(scope, key).map(d -> List.copyOf(history.get(d.id()))).orElse(List.of());
    }

    @Override
    public synchronized Map<String, String> extensions(FieldScope scope, FieldKey key) {
        return find(scope, key).map(d -> Map.copyOf(extensions.getOrDefault(d.id(), Map.of()))).orElse(Map.of());
    }

    @Override
    public synchronized void putExtension(FieldScope scope, FieldKey key, String name, String value, Instant at) {
        FieldDefinition d = find(scope, key).orElseThrow(() -> FieldDefinitionException.unknown(key));
        Map<String, String> m = extensions.computeIfAbsent(d.id(), id -> new TreeMap<>());
        if (value == null) {
            m.remove(name);
        } else {
            m.put(name, value);
        }
    }

    /** Every definition of every entity, in the order created. */
    public synchronized List<FieldDefinition> all() {
        return List.copyOf(fields.values());
    }

    private static HistoryEntry entry(FieldDefinition d, Change change) {
        return new HistoryEntry(d.version(), change, d.updatedAt(), d.updatedBy(), d.spec());
    }

    private static String slot(FieldScope scope, FieldKey key) {
        return scope.entity() + "\u001f" + scope.product() + "\u001f" + key.folded();
    }
}
