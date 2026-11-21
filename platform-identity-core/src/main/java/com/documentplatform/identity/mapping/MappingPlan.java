package com.documentplatform.identity.mapping;

import com.documentplatform.identity.admission.EntityId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * What loading a valid file would change. The file is the complete record for every entity it names: for those
 * entities the grants become exactly the file's rows (missing ones added, others removed). Entities not in the file
 * are not touched. Loading the same file twice gives a plan with no changes.
 *
 * <p>{@link #perProduct} keys the plan by entity <em>and</em> product: grants of one product are never compared with,
 * added to or removed from another product's. The caller decides whether removals are applied (the setup command
 * applies them only when asked to).
 */
public record MappingPlan(List<EntityChange> entities) {

    /**
     * The change for one entity (and product) named in the file.
     *
     * @param slotId      the slot ID when the file named the entity by slot ID, else null
     * @param productCode the product the grants are for, or null when the file was read without one
     */
    public record EntityChange(EntityId entity, String name, Set<Grant> toAdd, Set<Grant> toRemove, int unchanged,
                               String slotId, String productCode) {

        public EntityChange(EntityId entity, String name, Set<Grant> toAdd, Set<Grant> toRemove, int unchanged) {
            this(entity, name, toAdd, toRemove, unchanged, null, null);
        }

        public boolean changes() {
            return !toAdd.isEmpty() || !toRemove.isEmpty();
        }
    }

    /** The grants an entity has now for one product, read from the store. */
    @FunctionalInterface
    public interface CurrentGrants {
        /**
         * @param slotId      the slot ID when the file named the entity by slot ID, else null
         * @param productCode the product, or null when the file was read without one
         */
        Set<Grant> of(EntityId entity, String slotId, String productCode);
    }

    public boolean hasChanges() {
        return entities.stream().anyMatch(EntityChange::changes);
    }

    /**
     * @param rows    the rows of a valid file
     * @param current the grants each entity has now, read from the product's store
     */
    public static MappingPlan between(List<MappingRow> rows, Function<EntityId, Set<Grant>> current) {
        Objects.requireNonNull(current, "current");
        return perProduct(rows, (entity, slotId, productCode) -> current.apply(entity));
    }

    /**
     * @param rows    the rows of a valid file (each carries its product code)
     * @param current the grants each entity has now for the row's product
     */
    public static MappingPlan perProduct(List<MappingRow> rows, CurrentGrants current) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(current, "current");
        Map<Key, Set<Grant>> wanted = new LinkedHashMap<>();
        Map<Key, String> names = new LinkedHashMap<>();
        Map<Key, String> slots = new LinkedHashMap<>();
        for (MappingRow row : rows) {
            Key key = new Key(row.entity(), row.productCode());
            wanted.computeIfAbsent(key, e -> new LinkedHashSet<>()).add(row.grant());
            names.putIfAbsent(key, row.entityName());
            slots.putIfAbsent(key, row.slotId());
        }
        List<EntityChange> changes = new ArrayList<>();
        for (Map.Entry<Key, Set<Grant>> entry : wanted.entrySet()) {
            Key key = entry.getKey();
            Set<Grant> now = Objects.requireNonNullElse(current.of(key.entity(), slots.get(key), key.productCode()),
                    Set.of());
            Set<Grant> toAdd = new LinkedHashSet<>(entry.getValue());
            toAdd.removeAll(now);
            Set<Grant> toRemove = new LinkedHashSet<>(now);
            toRemove.removeAll(entry.getValue());
            int unchanged = entry.getValue().size() - toAdd.size();
            changes.add(new EntityChange(key.entity(), names.get(key), sorted(toAdd), sorted(toRemove), unchanged,
                    slots.get(key), key.productCode()));
        }
        return new MappingPlan(List.copyOf(changes));
    }

    private record Key(EntityId entity, String productCode) {
    }

    private static Set<Grant> sorted(Set<Grant> grants) {
        List<Grant> list = new ArrayList<>(grants);
        list.sort(Comparator.comparing(Grant::group).thenComparing(Grant::role));
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(list));
    }
}
