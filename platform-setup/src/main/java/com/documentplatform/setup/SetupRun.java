package com.documentplatform.setup;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.jdbc.PostgresMachineBindingStore;
import com.documentplatform.identity.mapping.ClientBindingsFile;
import com.documentplatform.identity.push.PushCaller;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.mapping.MappingError;
import com.documentplatform.identity.mapping.MappingPlan;
import com.documentplatform.identity.mapping.MappingRow;
import com.documentplatform.identity.mapping.RoleMappingFile;
import com.documentplatform.identity.mapping.SlotTargets;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.ConnectionChange;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.GrantAuthor;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.MachineBinding;
import com.documentplatform.identity.store.MachineBindingStatus;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static com.documentplatform.setup.Plan.Action.*;

/**
 * The database part of one setup run, on one connection inside one transaction that the caller opened (auto-commit
 * off) and commits or rolls back. {@link #plan} takes the transaction-scoped advisory lock first, so two runs on the
 * same database never interleave; then it reads everything and refuses before any write. {@link #apply} writes the
 * plan: licence, customer, entity slots, login connection, grants, client bindings. All stores work on this one
 * connection, so a failure anywhere rolls the whole database part back and a rerun does it all again.
 */
final class SetupRun {

    /** {@code pg_advisory_xact_lock} key of setup runs ("platsetu"). */
    static final long LOCK_KEY = 0x706C617473657475L;
    /** {@code changed_by} of the login connection history rows setup writes. */
    static final String CHANGED_BY = "setup";
    /** The role mapping files' format name; a {@code # Format:} comment must name this one. */
    static final String MAPPING_FORMAT = "platform-role-mapping/1";

    private final SetupInputs in;
    private final Clock clock;
    private final Connection c;
    private final DataSource joined;
    private final PostgresEntityStore entities;
    private final PostgresLoginConnectionStore connections;
    private final PostgresGroupGrantStore grants;
    private final PostgresMachineBindingStore bindings;
    private final String tenant;
    private final String runId;

    SetupRun(SetupInputs in, Clock clock, Connection c, String runId) {
        this.runId = runId;
        this.in = in;
        this.clock = clock;
        this.c = c;
        this.joined = Connections.joined(c);
        this.entities = PostgresEntityStore.onConnection(c);
        this.connections = PostgresLoginConnectionStore.onConnection(c);
        this.grants = PostgresGroupGrantStore.onConnection(c);
        this.bindings = PostgresMachineBindingStore.onConnection(c);
        this.tenant = in.licence().tenantId();
    }

    /** Locks, reads and checks; writes nothing (the caller rolls back after a plan-only run). */
    Plan plan() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_advisory_xact_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
        }
        requireOnlyThisCustomer();
        Plan p = new Plan();
        List<Licence> afterStore = planLicence(p);
        planCustomer(p);
        Set<String> licensed = new TreeSet<>();
        for (Licence l : afterStore) {
            for (EntitySlot s : l.entitySlots()) licensed.add(s.slotId());
        }
        p.licensedSlots = Set.copyOf(licensed);
        Map<String, EntityRecord> stored = planSlots(p, licensed);
        planTimeZones(p, licensed, stored);
        List<MappingRow> rows = readMapping(licensed, stored);
        planConnection(p);
        planGrants(p, rows.stream().filter(r -> !r.platformWide()).toList());
        planPlatformGrants(p, rows.stream().filter(MappingRow::platformWide).toList());
        planReplacedIssuerGrants(p);
        planClientBindings(p, licensed);
        for (SetupInputs.CheckedProfile cp : in.profiles()) {
            p.add(CHECKED, "integration profile " + cp.name() + " " + cp.version() + " (tag " + cp.tag() + ", file "
                    + cp.file() + "; stored nowhere, the app reads the file)");
        }
        planConfig(p);
        return p;
    }

    /** Writes the plan in the caller's transaction. */
    void apply(Plan p) {
        LicenceService service = service();
        try {
            service.store(in.licenceFile());
        } catch (LicenceException e) {
            throw new SetupException(SetupRefusal.SETUP_LICENCE_REFUSED, e.getMessage());
        }
        entities.saveCustomer(tenant, in.licence().customer());
        entities.syncSlots(tenant, p.licensedSlots);
        if (p.customerZone != null) entities.setCustomerTimeZone(tenant, p.customerZone);
        p.entityZones.forEach(entities::setEntityTimeZone);
        switch (p.connectionStep) {
            case INSERT -> connections.insert(p.connection, ChangeSource.SETUP, CHANGED_BY);
            case UPDATE -> connections.update(p.connection, p.connectionExpectedVersion, ChangeSource.SETUP, CHANGED_BY)
                    .orElseThrow(() -> new SetupException(SetupRefusal.SETUP_FAILED,
                            "The login connection was changed in the app while setup ran."));
            case NONE -> {
            }
        }
        String product = in.product();
        // One change per slot, so the grant history pairs a removed and an added role of one group as CHANGED.
        GrantAuthor author = GrantAuthor.setup(CHANGED_BY, runId);
        Set<String> slots = new TreeSet<>(p.grantsToAdd.keySet());
        if (in.removeUnlistedGrants()) slots.addAll(p.grantsToRemove.keySet());
        for (String slot : slots) {
            grants.change(slot, product, in.signin().issuer(), p.grantsToAdd.getOrDefault(slot, Set.of()),
                    in.removeUnlistedGrants() ? p.grantsToRemove.getOrDefault(slot, Set.of()) : Set.of(), author);
        }
        // Platform-wide grants (* rows): one change, so one group's role change is one CHANGED history row.
        Set<Grant> platformRemove = in.removeUnlistedGrants() ? p.platformToRemove : Set.of();
        if (!p.platformToAdd.isEmpty() || !platformRemove.isEmpty()) {
            grants.changePlatform(tenant, product, in.signin().issuer(), p.platformToAdd, platformRemove, author);
        }
        if (in.removeUnlistedGrants() && p.replacedIssuer != null) { // SEC-17
            for (Map.Entry<String, Set<Grant>> e : p.replacedIssuerGrants.entrySet()) {
                grants.change(e.getKey(), product, p.replacedIssuer, Set.of(), e.getValue(), author);
            }
            if (!p.replacedIssuerPlatformGrants.isEmpty()) {
                grants.changePlatform(tenant, product, p.replacedIssuer, Set.of(), p.replacedIssuerPlatformGrants,
                        author);
            }
        }
        for (MachineBinding b : p.bindingsToInsert) {
            bindings.insertBinding(b, ChangeSource.SETUP, CHANGED_BY);
        }
        for (Plan.BindingUpdate u : p.bindingsToUpdate) {
            bindings.updateBinding(u.binding(), u.expectedVersion(), ChangeSource.SETUP, CHANGED_BY)
                    .orElseThrow(() -> new SetupException(SetupRefusal.SETUP_FAILED, "The client binding of "
                            + u.binding().clientId() + " was changed in the app while setup ran."));
        }
    }

    private LicenceService service() {
        return new LicenceService(new PostgresLicenceStore(joined), new LicenceVerifier(in.build().keys()),
                new ClockGuard(clock, ClockGuard.DEFAULT_TOLERANCE, new PostgresLastSeenStore(joined),
                        in.build().builtAt()), new PostgresUsageLedger(joined),
                in.product(), tenant);
    }

    /** One installation serves one customer: another customer's licence or records refuse the run. */
    private void requireOnlyThisCustomer() throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select 1 from idn_customer where tenant_id <> ? "
                + "union all select 1 from lic_licence where tenant_id <> ? limit 1")) {
            ps.setString(1, tenant);
            ps.setString(2, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    throw new SetupException(SetupRefusal.SETUP_OTHER_CUSTOMER_INSTALLED,
                            "The database already holds records of a customer other than " + tenant + ".");
                }
            }
        }
    }

    /** As {@link LicenceService#store}: unchanged, first, replaces, or waits for its start date. */
    private List<Licence> planLicence(Plan p) {
        PostgresLicenceStore store = new PostgresLicenceStore(joined);
        LicenceVerifier verifier = new LicenceVerifier(in.build().keys());
        Optional<Licence> current = readable(store, LicenceStore.Slot.CURRENT, verifier);
        Optional<Licence> waiting = readable(store, LicenceStore.Slot.WAITING, verifier);
        Licence file = in.licence();
        String id = file.licenceId() + " v" + file.version();
        for (Optional<Licence> stored : List.of(current, waiting)) {
            if (stored.isPresent() && stored.get().licenceId().equals(file.licenceId())) {
                p.add(UNCHANGED, "licence " + id);
                return present(current, waiting);
            }
        }
        long newest = Math.max(current.map(Licence::version).orElse(0L), waiting.map(Licence::version).orElse(0L));
        newest = Math.max(newest, Math.max(store.highestVersion(LicenceStore.Slot.CURRENT),
                store.highestVersion(LicenceStore.Slot.WAITING)));
        if (file.version() < newest) {
            throw new SetupException(SetupRefusal.SETUP_LICENCE_OLDER, "Licence " + id + " is older than version "
                    + newest + ", which is already stored.");
        }
        if (file.version() == newest && (current.isPresent() || waiting.isPresent())) {
            throw new SetupException(SetupRefusal.SETUP_LICENCE_VERSION_CONFLICT, "Version " + newest
                    + " is already stored as a different licence than " + file.licenceId() + ".");
        }
        LocalDate today = LocalDate.ofInstant(new ClockGuard(clock, ClockGuard.DEFAULT_TOLERANCE,
                new PostgresLastSeenStore(joined), in.build().builtAt()).read(file.issuedAt()).effective(), ZoneOffset.UTC);
        if (current.isEmpty()) {
            p.add(CREATE, "licence " + id + " (first licence)");
            return List.of(file);
        }
        if (today.isBefore(file.startsOn())) {
            p.add(UPDATE, "licence " + id + " stored as renewal; takes over from " + current.get().licenceId()
                    + " v" + current.get().version() + " on " + file.startsOn());
            return List.of(current.get(), file);
        }
        p.add(UPDATE, "licence " + id + " replaces " + current.get().licenceId() + " v" + current.get().version());
        return List.of(file);
    }

    private static List<Licence> present(Optional<Licence> a, Optional<Licence> b) {
        List<Licence> out = new ArrayList<>();
        a.ifPresent(out::add);
        b.ifPresent(out::add);
        return out;
    }

    private Optional<Licence> readable(PostgresLicenceStore store, LicenceStore.Slot slot, LicenceVerifier verifier) {
        return store.get(slot).flatMap(row -> {
            try {
                return Optional.of(verifier.verify(row.file().getBytes(StandardCharsets.UTF_8), tenant));
            } catch (LicenceException e) {
                return Optional.empty(); // a broken stored row is replaced, as the licence command does
            }
        });
    }

    private void planCustomer(Plan p) {
        Optional<String> name = entities.customerName(tenant);
        if (name.isEmpty()) {
            p.add(CREATE, "customer " + tenant);
        } else if (!name.get().equals(in.licence().customer())) {
            p.add(UPDATE, "customer " + tenant + " name from the licence");
        } else {
            p.add(UNCHANGED, "customer " + tenant);
        }
    }

    private Map<String, EntityRecord> planSlots(Plan p, Set<String> licensed) {
        Map<String, EntityRecord> stored = new TreeMap<>();
        for (EntityRecord r : entities.entities(tenant)) stored.put(r.slotId(), r);
        Set<String> all = new TreeSet<>(licensed);
        all.addAll(stored.keySet());
        for (String slot : all) {
            EntityRecord r = stored.get(slot);
            boolean inLicence = licensed.contains(slot);
            if (r == null) {
                p.add(CREATE, "entity slot " + slot + " (" + EntityStatus.NOT_ACTIVATED + ")");
            } else if (inLicence && r.status() == EntityStatus.NOT_IN_LICENCE) {
                p.add(UPDATE, "entity slot " + slot + " back in the licence (status as before it left)");
            } else if (!inLicence && r.status() != EntityStatus.NOT_IN_LICENCE) {
                p.add(UPDATE, "entity slot " + slot + " " + r.status() + " -> " + EntityStatus.NOT_IN_LICENCE
                        + " (kept, never deleted; nobody can sign in to it)");
            } else {
                p.add(UNCHANGED, "entity slot " + slot + " (" + r.status() + ")");
            }
        }
        return stored;
    }

    /**
     * The sign-in file's time zones are defaults: stored where none is stored yet; a zone already stored (set in the
     * app or by an earlier run) is kept, so a rerun changes nothing. A zone for a slot the licence does not list
     * refuses the run.
     */
    private void planTimeZones(Plan p, Set<String> licensed, Map<String, EntityRecord> stored) {
        SigninFile s = in.signin();
        if (s.timeZone() != null) {
            Optional<java.time.ZoneId> now = entities.customerTimeZone(tenant);
            String label = "time zone of customer " + tenant;
            if (now.isEmpty()) {
                p.customerZone = s.timeZone();
                p.add(CREATE, label + ": " + s.timeZone().getId());
            } else if (now.get().equals(s.timeZone())) {
                p.add(UNCHANGED, label + ": " + now.get().getId());
            } else {
                p.add(UNCHANGED, label + ": " + now.get().getId() + " kept (already set; the sign-in file's "
                        + s.timeZone().getId() + " is a default only)");
            }
        }
        for (Map.Entry<String, java.time.ZoneId> e : s.entityTimeZones().entrySet()) {
            String slot = e.getKey();
            if (!licensed.contains(slot)) {
                throw new SetupException(SetupRefusal.SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE, "The sign-in file gives a "
                        + "time zone for entity slot " + slot + ", which the licence does not list.");
            }
            Optional<java.time.ZoneId> now = stored.containsKey(slot)
                    ? entities.timeZone(slot).map(EntityTimeZone::own) : Optional.empty();
            String label = "time zone of entity slot " + slot;
            if (now.isEmpty()) {
                p.entityZones.put(slot, e.getValue());
                p.add(CREATE, label + ": " + e.getValue().getId());
            } else if (now.get().equals(e.getValue())) {
                p.add(UNCHANGED, label + ": " + now.get().getId());
            } else {
                p.add(UNCHANGED, label + ": " + now.get().getId() + " kept (already set; the sign-in file's "
                        + e.getValue().getId() + " is a default only)");
            }
        }
    }

    private List<MappingRow> readMapping(Set<String> licensed, Map<String, EntityRecord> stored) {
        SlotTargets targets = slot -> {
            EntityRecord r = stored.get(slot);
            String name = r == null ? null : r.displayName();
            if (licensed.contains(slot)) return Optional.of(new SlotTargets.Slot(name, true));
            return r == null ? Optional.empty() : Optional.of(new SlotTargets.Slot(name, false));
        };
        RoleMappingFile.Result r = RoleMappingFile.forSlots(MAPPING_FORMAT, in.product(), in.roles(), targets)
                .read(in.mapping());
        if (!r.valid()) {
            List<String> lines = r.errors().stream().map(MappingError::display).toList();
            List<String> codes = r.errors().stream().map(e -> e.code().name()).distinct().toList();
            // A role of the wrong kind on an entity or a * row has its own code; any other error is the general one.
            SetupRefusal refusal = codes.contains(MappingError.Code.PLATFORM_ROLE_ON_ENTITY.name())
                    ? SetupRefusal.SETUP_PLATFORM_ROLE_ON_ENTITY
                    : codes.contains(MappingError.Code.ENTITY_ROLE_PLATFORM_WIDE.name())
                    ? SetupRefusal.SETUP_ENTITY_ROLE_PLATFORM_WIDE : SetupRefusal.SETUP_MAPPING_INVALID;
            throw new SetupException(refusal, "The role mapping file "
                    + in.mappingName() + " has " + r.errors().size() + " error(s) " + codes + ".", lines);
        }
        return r.rows();
    }

    private void planConnection(Plan p) throws SQLException {
        String product = in.product();
        SigninFile.Product signin = in.productSignin();
        Optional<LoginConnection> existing = latestConnection(product);
        if (existing.isEmpty()) {
            p.connectionStep = Plan.ConnectionStep.INSERT;
            p.connection = desired(UUID.randomUUID(), 1);
            p.add(CREATE, "login connection " + product + ": provider " + in.signin().providerId() + ", issuer "
                    + in.signin().issuer() + ", browser client ID " + signin.browserClientId() + ", API audience "
                    + signin.apiAudience());
            return;
        }
        LoginConnection now = existing.get();
        LoginConnection want = desired(now.connectionId(), now.configurationVersion() + 1);
        List<String> differs = differences(now, want);
        String label = "login connection " + product + " (version " + now.configurationVersion() + ")";
        if (differs.isEmpty()) {
            p.add(UNCHANGED, label);
            return;
        }
        List<ConnectionChange> history = connections.history(now.connectionId());
        ChangeSource lastSource = history.isEmpty() ? null : history.get(history.size() - 1).source();
        // A rollback made by the setup command is the customer's choice, as a change in the app is: never undone silently.
        boolean changedInApp = lastSource != ChangeSource.SETUP
                || history.get(history.size() - 1).changedBy().startsWith(SetupRollback.CHANGED_BY);
        if (changedInApp && !in.replaceConnection()) {
            p.add(CONFLICT, label + " was changed in the app since the last setup (" + String.join(", ", differs)
                    + " differ from the sign-in file); left as it is. Run again with --replace-connection to replace "
                    + "it with the sign-in file's values.");
            return;
        }
        if (!now.issuer().equals(want.issuer())) p.replacedIssuer = now.issuer();
        p.connectionStep = Plan.ConnectionStep.UPDATE;
        p.connection = want;
        p.connectionExpectedVersion = now.configurationVersion();
        p.add(UPDATE, label + " -> version " + want.configurationVersion() + ": " + String.join(", ", differs)
                + (changedInApp ? " (replaces the change made in the app)" : ""));
    }

    private LoginConnection desired(UUID id, long version) {
        SigninFile.Product s = in.productSignin();
        return new LoginConnection(id, tenant, in.product(), in.signin().providerId(), IdentityProviderType.OIDC,
                in.signin().issuer(), s.browserClientId(), s.apiAudience(), null, IdentityConnectionStatus.ACTIVE,
                version);
    }

    /**
     * The customer's connection for the product: the ACTIVE one, else the DISABLED one changed last. DRAFT and
     * VALIDATED connections (the IT Admin's drafts in the app) are left alone.
     */
    private Optional<LoginConnection> latestConnection(String product) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select connection_id from idn_login_connection "
                + "where tenant_id = ? and product_code = ? and status in ('ACTIVE', 'DISABLED') "
                + "order by (status = 'ACTIVE') desc, updated_at desc limit 1")) {
            ps.setString(1, tenant);
            ps.setString(2, product);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return connections.find(rs.getObject(1, UUID.class));
            }
        }
    }

    /** Names of the fields that differ (never their values: a secret reference is a reference, but still not shown). */
    private static List<String> differences(LoginConnection a, LoginConnection b) {
        List<String> out = new ArrayList<>();
        if (!a.providerId().equals(b.providerId())) out.add("providerId");
        if (a.providerType() != b.providerType()) out.add("providerType");
        if (!a.issuer().equals(b.issuer())) out.add("issuer");
        if (!java.util.Objects.equals(a.browserClientId(), b.browserClientId())) out.add("browserClientId");
        if (!a.apiAudience().equals(b.apiAudience())) out.add("apiAudience");
        if (!java.util.Objects.equals(a.secretReference(), b.secretReference())) out.add("secretReference");
        if (a.status() != b.status()) out.add("status");
        return out;
    }

    /**
     * The file is the customer's complete grant list for this product and login system: missing grants are added,
     * grants of the customer not in the file are listed for removal (removed only with --remove-unlisted-grants).
     */
    private void planGrants(Plan p, List<MappingRow> rows) {
        String product = in.product();
        var issuer = in.signin().issuer();
        MappingPlan mapping = MappingPlan.perProduct(rows,
                (entity, slotId, code) -> grants.grants(slotId, code, issuer));
        Map<String, Set<Grant>> current = grants.grantsOfCustomer(tenant, product, issuer);
        Set<String> inFile = new LinkedHashSet<>();
        for (MappingPlan.EntityChange change : mapping.entities()) {
            String slot = change.slotId();
            inFile.add(slot);
            if (!change.toAdd().isEmpty()) p.grantsToAdd.put(slot, change.toAdd());
            if (!change.toRemove().isEmpty()) p.grantsToRemove.put(slot, change.toRemove());
        }
        for (Map.Entry<String, Set<Grant>> e : current.entrySet()) {
            if (!inFile.contains(e.getKey())) p.grantsToRemove.put(e.getKey(), e.getValue());
        }
        // Aliases the file used: slot + grant -> the alias written; and alias -> rows, for one line per alias.
        Map<String, String> aliasOf = new java.util.HashMap<>();
        Map<String, Integer> aliasRows = new TreeMap<>();
        for (MappingRow r : rows) {
            if (r.alias() == null) continue;
            aliasOf.put(r.slotId() + "\u0000" + r.group() + "\u0000" + r.role(), r.alias());
            aliasRows.merge(r.alias(), 1, Integer::sum);
        }
        aliasRows.forEach((alias, n) -> p.add(CHECKED, "role alias \"" + alias + "\" -> " + in.roles().aliases().get(alias)
                + " (" + n + (n == 1 ? " row" : " rows") + " of " + in.mappingName() + "; stored as "
                + in.roles().aliases().get(alias) + ")"));
        Set<String> slots = new TreeSet<>(inFile);
        slots.addAll(p.grantsToRemove.keySet());
        for (String slot : slots) {
            for (Grant g : sorted(p.grantsToAdd.getOrDefault(slot, Set.of()))) {
                String alias = aliasOf.get(slot + "\u0000" + g.group() + "\u0000" + g.role());
                p.add(CREATE, "grant " + slot + " " + product + ": group \"" + g.group() + "\" -> "
                        + (alias == null ? g.role() : alias + " -> " + g.role()));
            }
            for (Grant g : sorted(p.grantsToRemove.getOrDefault(slot, Set.of()))) {
                String text = "grant " + slot + " " + product + ": group \"" + g.group() + "\" -> " + g.role()
                        + " (not in the mapping file)";
                if (in.removeUnlistedGrants()) {
                    p.add(REMOVE, text);
                } else {
                    p.add(NOT_REMOVED, text + "; kept. Run again with --remove-unlisted-grants to remove it.");
                }
            }
        }
        for (MappingPlan.EntityChange change : mapping.entities()) {
            if (change.unchanged() > 0) {
                p.add(UNCHANGED, "grants " + change.slotId() + " " + product + ": " + change.unchanged()
                        + (change.unchanged() == 1 ? " grant" : " grants"));
            }
        }
    }

    /**
     * P045, SEC-17: when this run moves the customer to another login system, the grants still stored under the one
     * it replaces would be live again after a rollback to it in the app. They are listed like any grant not in the
     * mapping file, and removed only with --remove-unlisted-grants.
     */
    private void planReplacedIssuerGrants(Plan p) {
        if (p.replacedIssuer == null) return;
        String product = in.product();
        String where = " (under the replaced login system " + p.replacedIssuer + "; live again after a rollback)";
        p.replacedIssuerGrants.putAll(grants.grantsOfCustomer(tenant, product, p.replacedIssuer));
        p.replacedIssuerPlatformGrants.addAll(grants.platformGrants(tenant, product, p.replacedIssuer));
        p.replacedIssuerGrants.forEach((slot, set) -> {
            for (Grant g : sorted(set)) unlisted(p, "grant " + slot + " " + product + ": group \"" + g.group()
                    + "\" -> " + g.role() + where);
        });
        for (Grant g : sorted(p.replacedIssuerPlatformGrants)) {
            unlisted(p, "platform grant " + product + " (every entity): group \"" + g.group() + "\" -> " + g.role()
                    + where);
        }
    }

    private void unlisted(Plan p, String text) {
        if (in.removeUnlistedGrants()) {
            p.add(REMOVE, text);
        } else {
            p.add(NOT_REMOVED, text + "; kept. Run again with --remove-unlisted-grants to remove it.");
        }
    }

    private static List<Grant> sorted(Set<Grant> set) {
        List<Grant> out = new ArrayList<>(set);
        out.sort(java.util.Comparator.comparing(Grant::group).thenComparing(Grant::role));
        return out;
    }

    /**
     * The client-binding file is the product's complete list of machine callers: a line not stored yet is created, a
     * line removed from the file switches its binding off (never deleted), and no file at all switches every binding
     * of the product off. A client already ACTIVE for another entity or source is refused: it is switched off first
     * (line removed, setup run), then bound again.
     */
    /**
     * The {@code *} rows: the customer's complete platform-wide grant list for this product and login system. Missing
     * grants are added; stored ones not in the file are listed for removal (removed only with
     * --remove-unlisted-grants).
     */
    private void planPlatformGrants(Plan p, List<MappingRow> rows) {
        String product = in.product();
        Set<Grant> stored = grants.platformGrants(tenant, product, in.signin().issuer());
        Set<Grant> inFile = new LinkedHashSet<>();
        for (MappingRow r : rows) inFile.add(r.grant());
        int unchanged = 0;
        for (Grant g : sorted(inFile)) {
            if (stored.contains(g)) {
                unchanged++;
            } else {
                p.platformToAdd.add(g);
                p.add(CREATE, "platform grant " + product + " (every entity): group \"" + g.group() + "\" -> "
                        + g.role());
            }
        }
        for (Grant g : sorted(stored)) {
            if (inFile.contains(g)) continue;
            p.platformToRemove.add(g);
            String text = "platform grant " + product + " (every entity): group \"" + g.group() + "\" -> " + g.role()
                    + " (not in the mapping file)";
            if (in.removeUnlistedGrants()) {
                p.add(REMOVE, text);
            } else {
                p.add(NOT_REMOVED, text + "; kept. Run again with --remove-unlisted-grants to remove it.");
            }
        }
        if (unchanged > 0) {
            p.add(UNCHANGED, "platform grants " + product + ": " + unchanged + (unchanged == 1 ? " grant" : " grants"));
        }
    }

    private void planClientBindings(Plan p, Set<String> licensed) {
        String product = in.product();
        Map<String, MachineBinding> stored = new TreeMap<>();
        for (MachineBinding b : bindings.bindings(tenant, product)) stored.put(b.clientId(), b);
        List<ClientBindingsFile.Row> rows = in.bindings() == null ? List.of() : in.bindings();

        List<ClientBindingsFile.Problem> problems = new ArrayList<>();
        for (ClientBindingsFile.Row r : rows) {
            if (r.clientId().startsWith(PushCaller.CERT_PREFIX) && !PushCaller.isFingerprint(r.clientId())) {
                problems.add(new ClientBindingsFile.Problem(r.line(), ClientBindingsFile.Code.CLIENT_ID_INVALID,
                        "a client certificate's client ID is cert-sha256: followed by the certificate's SHA-256 "
                                + "fingerprint as 64 lowercase hex digits"));
                continue;
            }
            if (!licensed.contains(r.slotId())) {
                problems.add(new ClientBindingsFile.Problem(r.line(), ClientBindingsFile.Code.SLOT_UNKNOWN,
                        "entity slot " + r.slotId() + " is not a slot of this customer's licence"));
                continue;
            }
            MachineBinding b = stored.get(r.clientId());
            if (b != null && b.status() == MachineBindingStatus.ACTIVE
                    && (!b.slotId().equals(r.slotId()) || !b.sourceKey().equals(r.source()))) {
                problems.add(new ClientBindingsFile.Problem(r.line(), ClientBindingsFile.Code.CLIENT_BOUND_ELSEWHERE,
                        "client \"" + r.clientId() + "\" is already bound to " + b.slotId() + ", source "
                                + b.sourceKey() + "; remove its line and run setup to switch that binding off first"));
            }
        }
        if (!problems.isEmpty()) throw SetupInputs.bindingsInvalid(in.bindingsName(), problems);

        Set<String> inFile = new java.util.HashSet<>();
        for (ClientBindingsFile.Row r : rows) {
            inFile.add(r.clientId());
            MachineBinding b = stored.get(r.clientId());
            String label = "client binding " + product + ": client \"" + r.clientId() + "\""
                    + (PushCaller.isFingerprint(r.clientId()) ? " (client certificate)" : "") + " -> " + r.slotId()
                    + ", source " + r.source();
            if (b == null) {
                p.bindingsToInsert.add(MachineBinding.create(tenant, product, r.clientId(), r.slotId(), r.source(),
                        r.description()));
                p.add(CREATE, label);
            } else if (b.status() == MachineBindingStatus.INACTIVE) {
                p.bindingsToUpdate.add(new Plan.BindingUpdate(b.activeFor(r.slotId(), r.source(), r.description()),
                        b.version()));
                p.add(UPDATE, label + " (switched on again)");
            } else if (!java.util.Objects.equals(b.description(), r.description())) {
                p.bindingsToUpdate.add(new Plan.BindingUpdate(b.activeFor(r.slotId(), r.source(), r.description()),
                        b.version()));
                p.add(UPDATE, label + " (description)");
            } else {
                p.add(UNCHANGED, label);
            }
        }
        for (MachineBinding b : stored.values()) {
            if (b.status() == MachineBindingStatus.ACTIVE && !inFile.contains(b.clientId())) {
                p.bindingsToUpdate.add(new Plan.BindingUpdate(b.switchedOff(), b.version()));
                p.add(SWITCH_OFF, "client binding " + product + ": client \"" + b.clientId() + "\" -> " + b.slotId()
                        + ", source " + b.sourceKey() + " ("
                        + (in.bindings() == null ? "the sign-in file names no client-binding file"
                        : "not in " + in.bindingsName()) + "; kept as INACTIVE, never deleted)");
            }
        }
    }

    private void planConfig(Plan p) {
        ConfigFragment.State state = ConfigFragment.check(in.configOut(), tenant);
        p.config = state;
        String text = "config " + in.configOut().getFileName() + " (" + ConfigFragment.KEY + "=" + tenant + ")";
        p.add(switch (state) {
            case MISSING -> CREATE;
            case NO_KEY -> UPDATE;
            case SAME -> UNCHANGED;
        }, text);
    }
}
