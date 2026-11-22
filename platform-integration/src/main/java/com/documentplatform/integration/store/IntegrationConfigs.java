package com.documentplatform.integration.store;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.ConfigJson;
import com.documentplatform.integration.config.ConfigProblem;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.safety.Installation;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Saving configurations: checked under the installation's rules, stored as a new version with who and when, and
 * given to the app's audit with the changed field names. A save that changes nothing is not a new version.
 */
public final class IntegrationConfigs {

    /** The configuration was refused; lists the problems (codes and where, no values). */
    public static final class Refused extends RuntimeException {
        private final List<ConfigProblem> problems;

        public Refused(List<ConfigProblem> problems) {
            super("Integration configuration refused: " + problems, null, false, false);
            this.problems = List.copyOf(problems);
        }

        public List<ConfigProblem> problems() {
            return problems;
        }
    }

    private static final Pattern BY = Pattern.compile("[\\x21-\\x7e][\\x20-\\x7e]{0,199}");
    private static final System.Logger LOG = System.getLogger(IntegrationConfigs.class.getName());

    private final IntegrationStore store;
    private final IntegrationAudit audit;
    private final Installation installation;
    private final Clock clock;
    private final com.documentplatform.integration.config.FieldLists fieldLists;

    /** Without field lists: a configuration with {field:<key>} placeholders is refused (INT_CONFIG_FIELD_UNKNOWN). */
    public IntegrationConfigs(IntegrationStore store, IntegrationAudit audit, Installation installation, Clock clock) {
        this(store, audit, installation, clock, com.documentplatform.integration.config.FieldLists.NONE);
    }

    /** @param fieldLists the entity's field lists, to check {field:<key>} placeholders and sensitive fields */
    public IntegrationConfigs(IntegrationStore store, IntegrationAudit audit, Installation installation, Clock clock,
                              com.documentplatform.integration.config.FieldLists fieldLists) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.installation = Objects.requireNonNull(installation, "installation");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fieldLists = Objects.requireNonNull(fieldLists, "fieldLists");
    }

    /** Version 1. */
    public StoredIntegration create(IntegrationConfig config, String by) {
        ConfigCheck.Report report = check(config, by);
        ConfigChange change = new ConfigChange(config.id(), config.entity(), config.product(), 1, by, now(),
                ConfigJson.fields(config), codes(report.warnings()));
        StoredIntegration saved = store.create(config, change);
        done(change);
        return saved;
    }

    /**
     * The next version, if {@code expectedVersion} is still the current one ({@code INT_CONFIG_VERSION_CONFLICT}
     * otherwise). Entity, product and kind never change. Returns the current version unchanged when nothing differs.
     */
    public StoredIntegration update(IntegrationConfig config, int expectedVersion, String by) {
        ConfigCheck.Report report = check(config, by);
        StoredIntegration current = store.current(config.id())
                .orElseThrow(() -> new IntegrationStore.Conflict(IntegrationCode.INT_CONFIG_NOT_FOUND));
        IntegrationConfig before = current.config();
        if (!before.entity().equals(config.entity()) || !before.product().equals(config.product())
                || before.kind() != config.kind()) {
            throw new Refused(List.of(new ConfigProblem(IntegrationCode.INT_CONFIG_INVALID, "entity/product/kind",
                    null)));
        }
        if (current.version() != expectedVersion) {
            throw new IntegrationStore.Conflict(IntegrationCode.INT_CONFIG_VERSION_CONFLICT);
        }
        List<String> changed = ConfigJson.changedFields(before, config);
        if (changed.isEmpty()) {
            return current;
        }
        ConfigChange change = new ConfigChange(config.id(), config.entity(), config.product(), expectedVersion + 1, by,
                now(), changed, codes(report.warnings()));
        StoredIntegration saved = store.update(config, expectedVersion, change);
        done(change);
        return saved;
    }

    /** Switches the integration on or off (a new version). */
    public StoredIntegration setEnabled(UUID id, boolean enabled, int expectedVersion, String by) {
        StoredIntegration current = store.current(id)
                .orElseThrow(() -> new IntegrationStore.Conflict(IntegrationCode.INT_CONFIG_NOT_FOUND));
        return update(current.config().withEnabled(enabled), expectedVersion, by);
    }

    public Optional<StoredIntegration> current(UUID id) {
        return store.current(id);
    }

    public Optional<StoredIntegration> version(UUID id, int version) {
        return store.version(id, version);
    }

    public List<ConfigChange> history(UUID id) {
        return store.history(id);
    }

    public List<StoredIntegration> list(UUID entity, String product) {
        return store.list(entity, product);
    }

    /** Every integration that is switched on (all entities and products). */
    public List<StoredIntegration> enabled() {
        return store.enabled();
    }

    private ConfigCheck.Report check(IntegrationConfig config, String by) {
        Objects.requireNonNull(config, "config");
        if (by == null || !BY.matcher(by).matches()) {
            throw new IllegalArgumentException("Who saved the change must be 1-200 printable characters");
        }
        ConfigCheck.Report report = ConfigCheck.check(config, installation, fieldLists);
        if (!report.ok()) throw new Refused(report.errors());
        return report;
    }

    private void done(ConfigChange change) {
        if (change.warnings().contains(IntegrationCode.INT_CONFIG_SIGN_IN_NONE_WARNING)) {
            LOG.log(System.Logger.Level.WARNING, "Integration {0} version {1} signs in with NONE (internal network, "
                    + "on premises only)", change.integrationId(), change.version());
        }
        LOG.log(System.Logger.Level.INFO, "Integration {0} saved as version {1} by {2}: {3} field(s) changed",
                change.integrationId(), change.version(), change.changedBy(), change.changedFields().size());
        audit.changed(change);
    }

    /** To the microsecond, as the database keeps it, so the stored history and the audit agree. */
    private java.time.Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static List<IntegrationCode> codes(List<ConfigProblem> warnings) {
        return warnings.stream().map(ConfigProblem::code).distinct().toList();
    }
}
