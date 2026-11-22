package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.MachineEntityBindings;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.GroupGrantStore;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingChange;
import com.documentplatform.identity.store.MachineBindingStatus;
import com.documentplatform.identity.store.MachineBindingStore;
import com.documentplatform.identity.store.SlotSync;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;

/**
 * In-memory {@code idn_} tables for tests: customers, entity slots, login connections with history, and group grants,
 * with the same rules as the Postgres stores (nothing deleted except grants, one ACTIVE connection per customer and
 * product, names unique per customer ignoring case). {@link #lookup} gives the sign-in lookups for one product.
 * Thread safe (one lock).
 */
public final class InMemoryIdentityStore implements EntityStore, LoginConnectionStore, GroupGrantStore,
        MachineBindingStore {

    private record GrantKey(String slotId, String productCode, String issuer) {
    }

    private final Map<String, String> customers = new TreeMap<>();
    private final Map<String, EntityRecord> entities = new TreeMap<>();
    private final Map<String, ZoneId> customerZones = new TreeMap<>();
    private final Map<String, ZoneId> entityZones = new TreeMap<>();
    /** status_before_removal: the status of a NOT_IN_LICENCE slot when it left the licence (activated ones only). */
    private final Map<String, EntityStatus> statusBeforeRemoval = new TreeMap<>();
    private final Map<UUID, LoginConnection> connections = new LinkedHashMap<>();
    private final Map<UUID, List<ConnectionChange>> history = new LinkedHashMap<>();
    private final Map<GrantKey, Set<Grant>> grants = new LinkedHashMap<>();
    private final Map<UUID, MachineBinding> bindings = new LinkedHashMap<>();
    private final Map<UUID, List<MachineBindingChange>> bindingHistory = new LinkedHashMap<>();
    private final Clock clock;

    public InMemoryIdentityStore() {
        this(Clock.systemUTC());
    }

    /** @param clock the time written to connection history rows */
    public InMemoryIdentityStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- customers and entity slots

    @Override
    public synchronized Change saveCustomer(String tenantId, String name) {
        EntityUuid.requireTenantId(tenantId);
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(ch -> ch < 0x20)) {
            throw new IllegalArgumentException("customer name must be 1 to 200 printable characters");
        }
        String before = customers.put(tenantId, name);
        return before == null ? Change.CREATED : before.equals(name) ? Change.UNCHANGED : Change.UPDATED;
    }

    @Override
    public synchronized Optional<String> customerName(String tenantId) {
        return Optional.ofNullable(customers.get(tenantId));
    }

    @Override
    public synchronized Optional<ZoneId> customerTimeZone(String tenantId) {
        return Optional.ofNullable(customerZones.get(tenantId));
    }

    @Override
    public synchronized Optional<Change> setCustomerTimeZone(String tenantId, ZoneId zone) {
        EntityUuid.requireTenantId(tenantId);
        Objects.requireNonNull(zone, "zone");
        if (!customers.containsKey(tenantId)) return Optional.empty();
        return Optional.of(zone.equals(customerZones.put(tenantId, zone)) ? Change.UNCHANGED : Change.UPDATED);
    }

    @Override
    public synchronized Optional<EntityTimeZone> timeZone(String slotId) {
        EntityRecord r = entities.get(slotId);
        if (r == null) return Optional.empty();
        return Optional.of(new EntityTimeZone(r.slotId(), r.entityUuid(), r.tenantId(), entityZones.get(slotId),
                customerZones.get(r.tenantId())));
    }

    @Override
    public synchronized Optional<EntityTimeZone> timeZone(UUID entityUuid) {
        return entities.values().stream().filter(r -> r.entityUuid().equals(entityUuid)).findFirst()
                .flatMap(r -> timeZone(r.slotId()));
    }

    @Override
    public synchronized Optional<Change> setEntityTimeZone(String slotId, ZoneId zone) {
        EntityUuid.requireSlotId(slotId);
        if (!entities.containsKey(slotId)) return Optional.empty();
        ZoneId before = zone == null ? entityZones.remove(slotId) : entityZones.put(slotId, zone);
        return Optional.of(Objects.equals(before, zone) ? Change.UNCHANGED : Change.UPDATED);
    }

    @Override
    public synchronized SlotSync syncSlots(String tenantId, Collection<String> licensedSlotIds) {
        EntityUuid.requireTenantId(tenantId);
        requireCustomer(tenantId);
        Set<String> licensed = new LinkedHashSet<>();
        for (String slot : licensedSlotIds) {
            licensed.add(EntityUuid.requireSlotId(slot));
            EntityRecord r = entities.get(slot);
            if (r != null && !r.tenantId().equals(tenantId)) {
                throw new IllegalStateException("slot " + slot + " belongs to another customer");
            }
        }
        List<String> created = new ArrayList<>(), back = new ArrayList<>(), gone = new ArrayList<>(),
                unchanged = new ArrayList<>();
        for (String slot : licensed) {
            EntityRecord r = entities.get(slot);
            if (r == null) {
                entities.put(slot, new EntityRecord(slot, EntityUuid.fromSlotId(slot), tenantId,
                        EntityStatus.NOT_ACTIVATED, null, null, null));
                created.add(slot);
            } else if (r.status() == EntityStatus.NOT_IN_LICENCE) {
                EntityStatus before = statusBeforeRemoval.remove(slot);
                entities.put(slot, with(r, before != null ? before
                        : r.activatedAt() != null ? EntityStatus.ACTIVE : EntityStatus.NOT_ACTIVATED));
                back.add(slot);
            } else {
                unchanged.add(slot);
            }
        }
        for (EntityRecord r : List.copyOf(entities.values())) {
            if (!r.tenantId().equals(tenantId) || licensed.contains(r.slotId())) continue;
            if (r.status() == EntityStatus.NOT_IN_LICENCE) {
                unchanged.add(r.slotId());
            } else {
                if (r.status().activated()) statusBeforeRemoval.put(r.slotId(), r.status());
                entities.put(r.slotId(), with(r, EntityStatus.NOT_IN_LICENCE));
                gone.add(r.slotId());
            }
        }
        return new SlotSync(created, back, gone, unchanged);
    }

    @Override
    public synchronized Optional<EntityRecord> entity(String slotId) {
        return Optional.ofNullable(entities.get(slotId));
    }

    @Override
    public synchronized Optional<EntityRecord> entity(UUID entityUuid) {
        return entities.values().stream().filter(r -> r.entityUuid().equals(entityUuid)).findFirst();
    }

    @Override
    public synchronized List<EntityRecord> entities(String tenantId) {
        return entities.values().stream().filter(r -> r.tenantId().equals(tenantId)).toList();
    }

    @Override
    public synchronized Map<String, String> activated(String tenantId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (EntityRecord r : entities(tenantId)) {
            if (r.status().activated()) out.put(r.slotId(), r.entityUuid().toString());
        }
        return Map.copyOf(out);
    }

    @Override
    public synchronized ActivationOutcome activate(String slotId, String name, String actor, Instant at) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(at, "at");
        EntityRecord r = entities.get(slotId);
        if (r == null) return new ActivationOutcome(SLOT_UNKNOWN, slotId, null);
        if (r.status() == EntityStatus.NOT_IN_LICENCE) return new ActivationOutcome(NOT_IN_LICENCE, slotId, r);
        if (r.status() != EntityStatus.NOT_ACTIVATED) return new ActivationOutcome(ALREADY_ACTIVATED, slotId, r);
        if (nameTaken(r.tenantId(), slotId, name)) return new ActivationOutcome(NAME_TAKEN, slotId, r);
        EntityRecord now = new EntityRecord(slotId, r.entityUuid(), r.tenantId(), EntityStatus.ACTIVE, name, actor, at);
        entities.put(slotId, now);
        return new ActivationOutcome(ACTIVATED, slotId, now);
    }

    @Override
    public synchronized ActivationOutcome rename(String slotId, String name) {
        EntityRecord r = entities.get(slotId);
        if (r == null) return new ActivationOutcome(SLOT_UNKNOWN, slotId, null);
        if (!r.status().activated()) return new ActivationOutcome(NOT_ACTIVATED, slotId, r);
        if (name.equals(r.displayName())) return new ActivationOutcome(UNCHANGED, slotId, r);
        if (nameTaken(r.tenantId(), slotId, name)) return new ActivationOutcome(NAME_TAKEN, slotId, r);
        EntityRecord now = new EntityRecord(slotId, r.entityUuid(), r.tenantId(), r.status(), name, r.activatedBy(),
                r.activatedAt());
        entities.put(slotId, now);
        return new ActivationOutcome(RENAMED, slotId, now);
    }

    @Override
    public synchronized ActivationOutcome suspend(String slotId) {
        return setActivatedStatus(slotId, EntityStatus.SUSPENDED, SUSPENDED, ALREADY_SUSPENDED);
    }

    @Override
    public synchronized ActivationOutcome resume(String slotId) {
        return setActivatedStatus(slotId, EntityStatus.ACTIVE, RESUMED, NOT_SUSPENDED);
    }

    private ActivationOutcome setActivatedStatus(String slotId, EntityStatus to, ActivationOutcome.Result done,
                                                 ActivationOutcome.Result already) {
        EntityRecord r = entities.get(slotId);
        if (r == null) return new ActivationOutcome(SLOT_UNKNOWN, slotId, null);
        if (r.status() == EntityStatus.NOT_IN_LICENCE) return new ActivationOutcome(NOT_IN_LICENCE, slotId, r);
        if (r.status() == EntityStatus.NOT_ACTIVATED) return new ActivationOutcome(NOT_ACTIVATED, slotId, r);
        if (r.status() == to) return new ActivationOutcome(already, slotId, r);
        EntityRecord now = with(r, to);
        entities.put(slotId, now);
        return new ActivationOutcome(done, slotId, now);
    }

    /** Test helper: sets an activated slot's status directly (the apps use {@code SlotActivation.suspend}). */
    public synchronized void setStatus(String slotId, EntityStatus status) {
        EntityRecord r = Objects.requireNonNull(entities.get(slotId), "slot not stored");
        if (status.activated() && r.activatedAt() == null || status == EntityStatus.NOT_ACTIVATED && r.activatedAt() != null) {
            throw new IllegalStateException("status " + status + " does not fit the slot's activation");
        }
        entities.put(slotId, with(r, status));
    }

    // ---- login connections

    @Override
    public synchronized Optional<LoginConnection> active(String tenantId, String productCode) {
        return connections.values().stream().filter(c -> c.tenantId().equals(tenantId)
                && c.productCode().equals(productCode) && c.status() == IdentityConnectionStatus.ACTIVE).findFirst();
    }

    @Override
    public synchronized Optional<LoginConnection> find(UUID connectionId) {
        return Optional.ofNullable(connections.get(connectionId));
    }

    @Override
    public synchronized LoginConnection insert(LoginConnection connection, ChangeSource source, String changedBy) {
        requireChange(source, changedBy);
        LoginConnection v1 = connection.withVersion(1);
        requireCustomer(v1.tenantId());
        if (connections.containsKey(v1.connectionId())) {
            throw new IllegalStateException("login connection " + v1.connectionId() + " already exists");
        }
        requireNoOtherActive(v1);
        connections.put(v1.connectionId(), v1);
        history.computeIfAbsent(v1.connectionId(), id -> new ArrayList<>()).add(change(v1, source, changedBy));
        return v1;
    }

    @Override
    public synchronized Optional<LoginConnection> update(LoginConnection connection, long expectedVersion,
                                                         ChangeSource source, String changedBy) {
        requireChange(source, changedBy);
        LoginConnection stored = connections.get(connection.connectionId());
        if (stored == null || stored.configurationVersion() != expectedVersion) return Optional.empty();
        if (!stored.tenantId().equals(connection.tenantId()) || !stored.productCode().equals(connection.productCode())) {
            throw new IllegalArgumentException("a login connection keeps its customer and product");
        }
        LoginConnection next = connection.withVersion(expectedVersion + 1);
        requireNoOtherActive(next);
        connections.put(next.connectionId(), next);
        history.get(next.connectionId()).add(change(next, source, changedBy));
        return Optional.of(next);
    }

    @Override
    public synchronized List<ConnectionChange> history(UUID connectionId) {
        return List.copyOf(history.getOrDefault(connectionId, List.of()));
    }

    // ---- group grants

    @Override
    public synchronized Set<Grant> grants(String slotId, String productCode, URI issuer) {
        return Set.copyOf(grants.getOrDefault(new GrantKey(slotId, productCode, issuer.toString()), Set.of()));
    }

    @Override
    public synchronized Map<String, Set<Grant>> grantsOfCustomer(String tenantId, String productCode, URI issuer) {
        Map<String, Set<Grant>> out = new TreeMap<>();
        grants.forEach((key, set) -> {
            EntityRecord r = entities.get(key.slotId());
            if (r != null && r.tenantId().equals(tenantId) && key.productCode().equals(productCode)
                    && key.issuer().equals(issuer.toString()) && !set.isEmpty()) {
                out.put(key.slotId(), Set.copyOf(set));
            }
        });
        return Map.copyOf(out);
    }

    @Override
    public synchronized int add(String slotId, String productCode, URI issuer, Collection<Grant> add) {
        EntityUuid.requireSlotId(slotId);
        LoginConnection.requireProductCode(productCode);
        if (!entities.containsKey(slotId)) throw new IllegalStateException("slot " + slotId + " is not stored");
        Set<Grant> set = grants.computeIfAbsent(new GrantKey(slotId, productCode, issuer.toString()),
                k -> new LinkedHashSet<>());
        int added = 0;
        for (Grant g : add) {
            if (set.add(g)) added++;
        }
        return added;
    }

    @Override
    public synchronized int remove(String slotId, String productCode, URI issuer, Collection<Grant> remove) {
        Set<Grant> set = grants.get(new GrantKey(slotId, productCode, issuer.toString()));
        if (set == null) return 0;
        int removed = 0;
        for (Grant g : remove) {
            if (set.remove(g)) removed++;
        }
        return removed;
    }

    // ---- integration client bindings

    @Override
    public synchronized List<MachineBinding> bindings(String tenantId, String productCode) {
        return bindings.values().stream()
                .filter(b -> b.tenantId().equals(tenantId) && b.productCode().equals(productCode))
                .sorted(java.util.Comparator.comparing(MachineBinding::clientId)).toList();
    }

    @Override
    public synchronized Optional<MachineBinding> activeBinding(String productCode, String clientId) {
        return bindings.values().stream().filter(b -> b.productCode().equals(productCode)
                && b.clientId().equals(clientId) && b.status() == MachineBindingStatus.ACTIVE).findFirst();
    }

    @Override
    public synchronized MachineBinding insertBinding(MachineBinding binding, ChangeSource source, String changedBy) {
        requireBindingChange(source, changedBy);
        MachineBinding v1 = binding.withVersion(1);
        if (!customers.containsKey(v1.tenantId())) throw new IllegalStateException("customer is not stored");
        if (!entities.containsKey(v1.slotId())) throw new IllegalStateException("entity slot is not stored");
        boolean taken = bindings.values().stream().anyMatch(b -> b.tenantId().equals(v1.tenantId())
                && b.productCode().equals(v1.productCode()) && b.clientId().equals(v1.clientId()));
        if (taken || bindings.containsKey(v1.bindingId())) {
            throw new IllegalStateException("client already has a binding for the product");
        }
        bindings.put(v1.bindingId(), v1);
        recordBinding(v1, source, changedBy);
        return v1;
    }

    @Override
    public synchronized Optional<MachineBinding> updateBinding(MachineBinding binding, long expectedVersion,
                                                               ChangeSource source, String changedBy) {
        requireBindingChange(source, changedBy);
        MachineBinding stored = bindings.get(binding.bindingId());
        if (stored == null || stored.version() != expectedVersion) return Optional.empty();
        if (!stored.tenantId().equals(binding.tenantId()) || !stored.productCode().equals(binding.productCode())
                || !stored.clientId().equals(binding.clientId())) {
            throw new IllegalArgumentException("a client binding keeps its customer, product and client ID");
        }
        if (!entities.containsKey(binding.slotId())) throw new IllegalStateException("entity slot is not stored");
        MachineBinding next = binding.withVersion(expectedVersion + 1);
        bindings.put(next.bindingId(), next);
        recordBinding(next, source, changedBy);
        return Optional.of(next);
    }

    @Override
    public synchronized List<MachineBindingChange> bindingHistory(UUID bindingId) {
        return List.copyOf(bindingHistory.getOrDefault(bindingId, List.of()));
    }

    private void recordBinding(MachineBinding b, ChangeSource source, String changedBy) {
        bindingHistory.computeIfAbsent(b.bindingId(), k -> new ArrayList<>()).add(new MachineBindingChange(b.version(),
                b.clientId(), b.slotId(), b.sourceKey(), b.description(), b.status(), source, changedBy,
                clock.instant()));
    }

    private static void requireBindingChange(ChangeSource source, String changedBy) {
        Objects.requireNonNull(source, "source");
        if (changedBy == null || changedBy.isBlank()) throw new IllegalArgumentException("changedBy is required");
    }

    /**
     * Machine bindings for one product over this store's own client bindings, with the same rules as
     * {@code new PostgresMachineEntityBindings(ds, product)}.
     */
    public MachineEntityBindings machineBindings(String productCode) {
        return machineBindings(productCode, client -> activeBinding(productCode, client)
                .map(b -> Set.of(b.entityUuid())).orElse(Set.of()));
    }

    // ---- sign-in lookups

    /** The sign-in lookups for one product, with the same rules as {@code PostgresIdentityLookup}. */
    public Lookup lookup(String productCode) {
        return new Lookup(LoginConnection.requireProductCode(productCode));
    }

    /**
     * Machine bindings for one product, with the same rules as {@code PostgresMachineEntityBindings}.
     *
     * @param clients client principal → entity UUIDs with an enabled machine integration (the app's side)
     */
    public MachineEntityBindings machineBindings(String productCode, Function<String, Set<UUID>> clients) {
        Lookup lookup = lookup(productCode);
        Objects.requireNonNull(clients, "clients");
        return (key, clientPrincipal) -> {
            if (clientPrincipal == null || clientPrincipal.isBlank()) return Set.of();
            Set<EntityId> active = lookup.find(key);
            Set<UUID> bound = clients.apply(clientPrincipal);
            if (bound == null) return null;
            Set<EntityId> out = new LinkedHashSet<>();
            for (UUID e : bound) {
                if (e != null && active.contains(EntityId.of(e))) out.add(EntityId.of(e));
            }
            return Set.copyOf(out);
        };
    }

    public final class Lookup implements ActiveEntityCandidates, EntityGroupGrants, OidcConnectionBindingResolver {

        private final String productCode;

        private Lookup(String productCode) {
            this.productCode = productCode;
        }

        @Override
        public Set<EntityId> find(ConnectionKey key) {
            Set<EntityId> out = new LinkedHashSet<>();
            for (Match m : matches(key)) out.add(m.entity().entity());
            return Set.copyOf(out);
        }

        @Override
        public Map<EntityId, Map<String, Set<String>>> activeGrants(ConnectionKey key) {
            Map<EntityId, Map<String, Set<String>>> out = new LinkedHashMap<>();
            synchronized (InMemoryIdentityStore.this) {
                for (Match m : matches(key)) {
                    Set<Grant> set = grants.getOrDefault(new GrantKey(m.entity().slotId(), productCode,
                            m.connection().issuer().toString()), Set.of());
                    if (set.isEmpty()) continue;
                    Map<String, Set<String>> groups = new LinkedHashMap<>();
                    for (Grant g : set) groups.computeIfAbsent(g.group(), x -> new LinkedHashSet<>()).add(g.role());
                    Map<String, Set<String>> copy = new LinkedHashMap<>();
                    groups.forEach((g, roles) -> copy.put(g, Set.copyOf(roles)));
                    out.put(m.entity().entity(), Map.copyOf(copy));
                }
            }
            return Map.copyOf(out);
        }

        @Override
        public Optional<OidcConnectionBinding> findActive(String providerId, URI issuer, String audience) {
            List<Match> m = matches(new ConnectionKey(providerId, issuer, audience));
            if (m.size() != 1) return Optional.empty();
            LoginConnection c = m.get(0).connection();
            return Optional.of(new OidcConnectionBinding(c.connectionId(), m.get(0).entity().entityUuid(), providerId,
                    issuer, audience, c.configurationVersion()));
        }

        private record Match(LoginConnection connection, EntityRecord entity) {
        }

        private List<Match> matches(ConnectionKey key) {
            if (key == null || key.issuer() == null || key.providerId() == null || key.providerId().isBlank()
                    || key.audience() == null || key.audience().isBlank()) {
                return List.of();
            }
            List<Match> out = new ArrayList<>();
            synchronized (InMemoryIdentityStore.this) {
                for (LoginConnection c : connections.values()) {
                    if (c.status() != IdentityConnectionStatus.ACTIVE || !c.productCode().equals(productCode)
                            || !c.providerId().equals(key.providerId())
                            || !c.issuer().toString().equals(key.issuer().toString())
                            || !c.apiAudience().equals(key.audience())) {
                        continue;
                    }
                    for (EntityRecord e : entities.values()) {
                        if (e.tenantId().equals(c.tenantId()) && e.status() == EntityStatus.ACTIVE) {
                            out.add(new Match(c, e));
                        }
                    }
                }
            }
            return out;
        }
    }

    // ---- helpers

    private boolean nameTaken(String tenantId, String slotId, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return entities.values().stream().anyMatch(r -> r.tenantId().equals(tenantId) && !r.slotId().equals(slotId)
                && r.displayName() != null && r.displayName().toLowerCase(Locale.ROOT).equals(lower));
    }

    private void requireCustomer(String tenantId) {
        if (!customers.containsKey(tenantId)) throw new IllegalStateException("customer " + tenantId + " is not stored");
    }

    private void requireNoOtherActive(LoginConnection conn) {
        if (conn.status() != IdentityConnectionStatus.ACTIVE) return;
        boolean other = connections.values().stream().anyMatch(c -> !c.connectionId().equals(conn.connectionId())
                && c.tenantId().equals(conn.tenantId()) && c.productCode().equals(conn.productCode())
                && c.status() == IdentityConnectionStatus.ACTIVE);
        if (other) {
            throw new IllegalStateException("customer " + conn.tenantId() + " already has an ACTIVE login connection for "
                    + conn.productCode());
        }
    }

    private static void requireChange(ChangeSource source, String changedBy) {
        Objects.requireNonNull(source, "source");
        if (changedBy == null || changedBy.isBlank()) throw new IllegalArgumentException("changedBy is required");
    }

    private ConnectionChange change(LoginConnection c, ChangeSource source, String changedBy) {
        return new ConnectionChange(c.configurationVersion(), c.status(), source, changedBy, clock.instant());
    }

    private static EntityRecord with(EntityRecord r, EntityStatus status) {
        return new EntityRecord(r.slotId(), r.entityUuid(), r.tenantId(), status, r.displayName(), r.activatedBy(),
                r.activatedAt());
    }
}
