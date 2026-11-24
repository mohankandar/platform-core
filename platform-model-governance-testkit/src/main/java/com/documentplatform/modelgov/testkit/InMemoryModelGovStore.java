package com.documentplatform.modelgov.testkit;

import com.documentplatform.modelgov.Decisions;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Monitoring;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ModelGovStore} in memory, with the same rules as the Postgres store: keys unique, references checked,
 * versions checked in the update, validations, measures, approvals, acceptances and live switches insert only (there
 * is no way to change or remove them), and each change with its history row at once. Thread-safe. Tests only.
 */
public final class InMemoryModelGovStore implements ModelGovStore {

    /** A key or reference the database would refuse (primary key, unique or foreign key). */
    public static final class Refused extends RuntimeException {
        Refused(String what) {
            super("In-memory model-governance store refused: " + what);
        }
    }

    private record ThresholdKey(UUID useId, UUID entity, String measure) {
    }

    private record LiveKey(UUID useId, UUID entity) {
    }

    private final Map<UUID, Model> models = new HashMap<>();
    private final Map<UUID, List<HistoryRow>> modelHistory = new HashMap<>();
    private final Map<UUID, ModelUse> uses = new HashMap<>();
    private final Map<UUID, List<HistoryRow>> useHistory = new HashMap<>();
    private final Map<ThresholdKey, Threshold> thresholds = new LinkedHashMap<>();
    private final Map<UUID, ValidationRecord> validations = new LinkedHashMap<>();
    private final Map<UUID, Decisions.Approval> approvals = new HashMap<>();
    private final Map<UUID, Decisions.Acceptance> acceptances = new HashMap<>();
    private final Map<LiveKey, Decisions.Live> live = new HashMap<>();
    private final Map<LiveKey, List<Decisions.LiveChange>> liveHistory = new HashMap<>();
    private final Map<ThresholdKey, Monitoring.MonitorThreshold> monitorThresholds = new LinkedHashMap<>();
    private final Map<UUID, Monitoring.Reading> readings = new LinkedHashMap<>();
    private final Map<UUID, Monitoring.Drift> drifts = new LinkedHashMap<>();
    private final Map<UUID, Monitoring.DriftAction> driftActions = new HashMap<>();
    private final Set<String> reviewNotices = new HashSet<>();

    @Override
    public synchronized Model insertModel(Model m, Change change) {
        if (models.containsKey(m.modelId())) throw new Refused("model exists");
        models.put(m.modelId(), m);
        modelHistory.computeIfAbsent(m.modelId(), k -> new ArrayList<>()).add(row(m.version(), change, ModelGovStore.recorded(m)));
        return m;
    }

    @Override
    public synchronized Optional<Model> updateModel(Model m, long expectedVersion, Change change) {
        Model now = models.get(m.modelId());
        if (now == null || now.version() != expectedVersion) return Optional.empty();
        Model next = m.atVersion(expectedVersion + 1);
        models.put(next.modelId(), next);
        modelHistory.get(next.modelId()).add(row(next.version(), change, ModelGovStore.recorded(next)));
        return Optional.of(next);
    }

    @Override
    public synchronized Optional<Model> model(UUID id) {
        return Optional.ofNullable(models.get(id));
    }

    @Override
    public synchronized List<HistoryRow> modelHistory(UUID id) {
        return List.copyOf(modelHistory.getOrDefault(id, List.of()));
    }

    @Override
    public synchronized ModelUse insertUse(ModelUse u, Change change) {
        if (uses.containsKey(u.useId())) throw new Refused("use exists");
        if (!models.containsKey(u.modelId())) throw new Refused("use names an unknown model");
        uses.put(u.useId(), u);
        useHistory.computeIfAbsent(u.useId(), k -> new ArrayList<>()).add(row(u.version(), change, ModelGovStore.recorded(u)));
        return u;
    }

    @Override
    public synchronized Optional<ModelUse> updateUse(ModelUse u, long expectedVersion, Change change) {
        ModelUse now = uses.get(u.useId());
        if (now == null || now.version() != expectedVersion) return Optional.empty();
        if (!models.containsKey(u.modelId())) throw new Refused("use names an unknown model");
        ModelUse next = u.atVersion(expectedVersion + 1);
        uses.put(next.useId(), next);
        useHistory.get(next.useId()).add(row(next.version(), change, ModelGovStore.recorded(next)));
        return Optional.of(next);
    }

    @Override
    public synchronized Optional<ModelUse> use(UUID id) {
        return Optional.ofNullable(uses.get(id));
    }

    @Override
    public synchronized List<HistoryRow> useHistory(UUID id) {
        return List.copyOf(useHistory.getOrDefault(id, List.of()));
    }

    @Override
    public synchronized void putThreshold(Threshold t, Change change) {
        if (!uses.containsKey(t.useId())) throw new Refused("threshold names an unknown use");
        thresholds.put(new ThresholdKey(t.useId(), t.entity(), t.measure()), t);
    }

    @Override
    public synchronized List<Threshold> thresholds(UUID useId) {
        Comparator<Threshold> order = Comparator.comparing(Threshold::measure)
                .thenComparing(t -> t.entity() == null ? "" : t.entity().toString());
        return thresholds.values().stream().filter(t -> t.useId().equals(useId)).sorted(order).toList();
    }

    @Override
    public synchronized void insertValidation(ValidationRecord v) {
        if (validations.containsKey(v.validationId())) throw new Refused("validation exists");
        if (!uses.containsKey(v.useId())) throw new Refused("validation names an unknown use");
        if (!models.containsKey(v.modelId())) throw new Refused("validation names an unknown model");
        validations.put(v.validationId(), withSortedMeasures(v));
    }

    @Override
    public synchronized Optional<ValidationRecord> validation(UUID id) {
        return Optional.ofNullable(validations.get(id));
    }

    @Override
    public synchronized List<UUID> validationEntities(UUID useId) {
        return validations.values().stream().filter(v -> v.useId().equals(useId)).map(ValidationRecord::entity)
                .distinct().sorted().toList();
    }

    @Override
    public synchronized List<ValidationRecord> validations(UUID useId, UUID entity) {
        return validations.values().stream().filter(v -> v.useId().equals(useId) && v.entity().equals(entity))
                .sorted(Comparator.comparing(ValidationRecord::ranAt)
                        .thenComparing(v -> v.validationId().toString()))
                .toList();
    }

    @Override
    public synchronized void insertApproval(Decisions.Approval a) {
        if (!validations.containsKey(a.validationId())) throw new Refused("approval names an unknown validation");
        if (approvals.putIfAbsent(a.validationId(), a) != null) throw new Refused("approval exists");
    }

    @Override
    public synchronized Optional<Decisions.Approval> approval(UUID id) {
        return Optional.ofNullable(approvals.get(id));
    }

    @Override
    public synchronized void insertAcceptance(Decisions.Acceptance a) {
        if (!validations.containsKey(a.validationId())) throw new Refused("acceptance names an unknown validation");
        if (acceptances.putIfAbsent(a.validationId(), a) != null) throw new Refused("acceptance exists");
    }

    @Override
    public synchronized Optional<Decisions.Acceptance> acceptance(UUID id) {
        return Optional.ofNullable(acceptances.get(id));
    }

    @Override
    public synchronized Optional<Decisions.Live> live(UUID useId, UUID entity) {
        return Optional.ofNullable(live.get(new LiveKey(useId, entity)));
    }

    @Override
    public synchronized boolean switchLive(Decisions.Live next, long expectedVersion, Decisions.LiveChange change) {
        if (!validations.containsKey(next.validationId())) throw new Refused("live names an unknown validation");
        // The same rules as schema V5 (P045, SEC-6): the validation is this use's and entity's, and approved.
        ValidationRecord v = validations.get(next.validationId());
        if (!v.useId().equals(next.useId()) || !v.entity().equals(next.entity())) {
            throw new Refused("live names a validation of another use or entity");
        }
        if (!approvals.containsKey(next.validationId())) throw new Refused("live names a validation that is not approved");
        LiveKey k = new LiveKey(next.useId(), next.entity());
        Decisions.Live now = live.get(k);
        long version = now == null ? 0 : now.version();
        if (version != expectedVersion) return false;
        live.put(k, next);
        liveHistory.computeIfAbsent(k, x -> new ArrayList<>()).add(change);
        return true;
    }

    @Override
    public synchronized List<Decisions.LiveChange> liveHistory(UUID useId, UUID entity) {
        return List.copyOf(liveHistory.getOrDefault(new LiveKey(useId, entity), List.of()));
    }

    // ---- the register as a list, monitoring (P034)

    private static final Comparator<ModelUse> USE_ORDER = Comparator.comparing(ModelUse::product)
            .thenComparing(ModelUse::feature).thenComparing(u -> u.useId().toString());

    @Override
    public synchronized List<ModelUse> uses(String product) {
        return uses.values().stream().filter(u -> product == null || u.product().equals(product)).sorted(USE_ORDER)
                .toList();
    }

    @Override
    public synchronized List<ModelUse> usesOfModel(UUID modelId) {
        return uses.values().stream().filter(u -> u.modelId().equals(modelId)).sorted(USE_ORDER).toList();
    }

    @Override
    public synchronized void putMonitorThreshold(Monitoring.MonitorThreshold t, Change change) {
        if (!uses.containsKey(t.useId())) throw new Refused("threshold names an unknown use");
        monitorThresholds.put(new ThresholdKey(t.useId(), t.entity(), t.measure()), t);
    }

    @Override
    public synchronized List<Monitoring.MonitorThreshold> monitorThresholds(UUID useId) {
        return monitorThresholds.values().stream().filter(t -> t.useId().equals(useId))
                .sorted(Comparator.comparing(Monitoring.MonitorThreshold::measure)
                        .thenComparing(t -> t.entity() == null ? "" : t.entity().toString())).toList();
    }

    @Override
    public synchronized void insertReading(Monitoring.Reading r) {
        if (!uses.containsKey(r.useId())) throw new Refused("reading names an unknown use");
        if (readings.putIfAbsent(r.readingId(), r) != null) throw new Refused("reading exists");
    }

    @Override
    public synchronized List<Monitoring.Reading> readings(UUID useId, UUID entity, Instant from, Instant to) {
        return readings.values().stream().filter(r -> r.useId().equals(useId)
                        && (entity == null || r.entity().equals(entity))
                        && !r.reportedAt().isBefore(from) && r.reportedAt().isBefore(to))
                .sorted(Comparator.comparing(Monitoring.Reading::reportedAt)
                        .thenComparing(r -> r.readingId().toString())).toList();
    }

    @Override
    public synchronized int purgeReadings(Instant before) {
        int n = 0;
        for (var it = readings.values().iterator(); it.hasNext(); ) {
            if (it.next().reportedAt().isBefore(before)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void insertDrift(Monitoring.Drift d) {
        if (!uses.containsKey(d.useId())) throw new Refused("drift names an unknown use");
        if (drifts.putIfAbsent(d.driftId(), d) != null) throw new Refused("drift exists");
    }

    @Override
    public synchronized List<Monitoring.Drift> drifts(UUID useId, UUID entity) {
        return drifts.values().stream().filter(d -> d.useId().equals(useId)
                        && (entity == null || d.entity().equals(entity)))
                .sorted(Comparator.comparing(Monitoring.Drift::raisedAt).thenComparing(d -> d.driftId().toString()))
                .toList();
    }

    @Override
    public synchronized void insertDriftAction(Monitoring.DriftAction a) {
        if (!drifts.containsKey(a.driftId())) throw new Refused("action names an unknown drift");
        if (driftActions.putIfAbsent(a.driftId(), a) != null) throw new Refused("action exists");
    }

    @Override
    public synchronized Optional<Monitoring.DriftAction> driftAction(UUID driftId) {
        return Optional.ofNullable(driftActions.get(driftId));
    }

    @Override
    public synchronized boolean insertReviewNotice(UUID useId, LocalDate due, Monitoring.ReviewStage stage,
                                                   Instant at) {
        if (!uses.containsKey(useId)) throw new Refused("notice names an unknown use");
        return reviewNotices.add(useId + "|" + due + "|" + stage);
    }

    private static HistoryRow row(long version, Change c, Map<String, String> recorded) {
        return new HistoryRow(version, c.changedBy(), c.changedAt(), c.changedFields(), recorded);
    }

    /** As Postgres returns them: measures ordered by name. */
    private static ValidationRecord withSortedMeasures(ValidationRecord v) {
        List<ValidationRecord.MeasureResult> m = new ArrayList<>(v.measures());
        m.sort(Comparator.comparing(ValidationRecord.MeasureResult::measure));
        return new ValidationRecord(v.validationId(), v.useId(), v.entity(), v.modelId(), v.modelName(),
                v.modelVersion(), v.provider(), v.digest(), v.configFingerprint(), v.testSetName(), v.testSetVersion(),
                m, v.passed(), v.ranBy(), v.ranAt(), v.runsWhere());
    }

    @Override
    public String toString() {
        return "InMemoryModelGovStore[models=" + models.size() + " uses=" + uses.size() + "]";
    }
}
