package com.documentplatform.modelgov.testkit;

import com.documentplatform.modelgov.Decisions;
import com.documentplatform.modelgov.Model;
import com.documentplatform.modelgov.ModelGovStore;
import com.documentplatform.modelgov.ModelUse;
import com.documentplatform.modelgov.Threshold;
import com.documentplatform.modelgov.ValidationRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    @Override
    public synchronized Model insertModel(Model m, Change change) {
        if (models.containsKey(m.modelId())) throw new Refused("model exists");
        models.put(m.modelId(), m);
        modelHistory.computeIfAbsent(m.modelId(), k -> new ArrayList<>()).add(row(m.version(), change));
        return m;
    }

    @Override
    public synchronized Optional<Model> updateModel(Model m, long expectedVersion, Change change) {
        Model now = models.get(m.modelId());
        if (now == null || now.version() != expectedVersion) return Optional.empty();
        Model next = new Model(m.modelId(), m.name(), m.provider(), m.modelVersion(), m.digest(), m.runsWhere(),
                expectedVersion + 1);
        models.put(next.modelId(), next);
        modelHistory.get(next.modelId()).add(row(next.version(), change));
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
        useHistory.computeIfAbsent(u.useId(), k -> new ArrayList<>()).add(row(u.version(), change));
        return u;
    }

    @Override
    public synchronized Optional<ModelUse> updateUse(ModelUse u, long expectedVersion, Change change) {
        ModelUse now = uses.get(u.useId());
        if (now == null || now.version() != expectedVersion) return Optional.empty();
        if (!models.containsKey(u.modelId())) throw new Refused("use names an unknown model");
        ModelUse next = new ModelUse(u.useId(), u.product(), u.feature(), u.entity(), u.purpose(), u.materiality(),
                u.materialityReason(), u.humanInTheLoop(), u.vendorOwner(), u.customerOwner(), u.modelId(),
                u.configFingerprint(), u.status(), u.lastValidatedAt(), u.nextReviewDue(), u.reviewOverdueRefuses(),
                expectedVersion + 1);
        uses.put(next.useId(), next);
        useHistory.get(next.useId()).add(row(next.version(), change));
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

    private static HistoryRow row(long version, Change c) {
        return new HistoryRow(version, c.changedBy(), c.changedAt(), c.changedFields());
    }

    /** As Postgres returns them: measures ordered by name. */
    private static ValidationRecord withSortedMeasures(ValidationRecord v) {
        List<ValidationRecord.MeasureResult> m = new ArrayList<>(v.measures());
        m.sort(Comparator.comparing(ValidationRecord.MeasureResult::measure));
        return new ValidationRecord(v.validationId(), v.useId(), v.entity(), v.modelId(), v.modelName(),
                v.modelVersion(), v.provider(), v.digest(), v.configFingerprint(), v.testSetName(), v.testSetVersion(),
                m, v.passed(), v.ranBy(), v.ranAt());
    }

    @Override
    public String toString() {
        return "InMemoryModelGovStore[models=" + models.size() + " uses=" + uses.size() + "]";
    }
}
