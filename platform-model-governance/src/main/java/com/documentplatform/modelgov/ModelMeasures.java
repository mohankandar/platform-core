package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.modelgov.ModelGovCode.*;

/**
 * Monitoring intake (design section 5): the app reports measures per use and entity over time (numbers only); each
 * measure has a pass mark (the app's default, or the customer's stricter one) with a direction and a minimum sample
 * size. A reading past its pass mark raises {@link ModelEvent.Kind#DRIFT} and the use needs review until someone
 * records what was done ({@link #recordAction}). While a drift of a measure is open, further readings past the mark
 * are kept but raise no new drift; once an action is recorded, the next reading past the mark raises a new one.
 * Readings are kept for the installation's retention ({@link #purge}, default 400 days).
 */
public final class ModelMeasures {

    public static final Duration DEFAULT_RETENTION = Duration.ofDays(400);
    public static final Duration MIN_RETENTION = Duration.ofDays(30);

    /** What one report did: the reading, and the drift it raised (empty when none). */
    public record Reported(Monitoring.Reading reading, Optional<Monitoring.Drift> drift) {
    }

    /** A drift with what was done about it (empty while the use needs review). */
    public record DriftStatus(Monitoring.Drift drift, Optional<Monitoring.DriftAction> action) {
        public boolean open() {
            return action.isEmpty();
        }
    }

    private final ModelGovStore store;
    private final ModelEvents events;
    private final Clock clock;
    private final Duration retention;

    public ModelMeasures(ModelGovStore store, ModelEvents events, Clock clock, Duration retention) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.compareTo(MIN_RETENTION) < 0) throw new IllegalArgumentException("retention under 30 days");
    }

    public Duration retention() {
        return retention;
    }

    // ---- pass marks

    /** The app's default monitoring pass mark for a measure of the use. */
    public void setDefaultThreshold(Monitoring.MonitorThreshold t, String by) {
        if (t.entity() != null) throw Values.invalid("threshold.entity", "a default has no entity");
        use(t.useId());
        store.putMonitorThreshold(t, change(by, t.measure()));
    }

    /**
     * The customer's pass mark for an entity: at least as strict as the default in value and direction, and over no
     * more samples ({@code MOD_THRESHOLD_LOOSER}; {@code MOD_THRESHOLD_MISSING} without a default).
     */
    public void setEntityThreshold(Monitoring.MonitorThreshold t, String by) {
        if (t.entity() == null) throw Values.invalid("threshold.entity", "an entity threshold names its entity");
        use(t.useId());
        Monitoring.MonitorThreshold def = store.monitorThresholds(t.useId()).stream()
                .filter(x -> x.entity() == null && x.measure().equals(t.measure())).findFirst()
                .orElseThrow(() -> new ModelGovException(MOD_THRESHOLD_MISSING, Map.of("measure", t.measure())));
        if (def.direction() != t.direction() || !def.direction().atLeastAsStrict(t.value(), def.value())
                || t.minSample() > def.minSample()) {
            throw new ModelGovException(MOD_THRESHOLD_LOOSER, Map.of("measure", t.measure(),
                    "value", t.value().toPlainString() + " over " + t.minSample(),
                    "default", def.value().toPlainString() + " over " + def.minSample()));
        }
        store.putMonitorThreshold(t, change(by, t.measure()));
    }

    /** The pass marks in force for the use and entity: the entity's where set, else the default; by measure. */
    public Map<String, Monitoring.MonitorThreshold> thresholdsInForce(UUID useId, UUID entity) {
        Map<String, Monitoring.MonitorThreshold> out = new LinkedHashMap<>();
        List<Monitoring.MonitorThreshold> all = store.monitorThresholds(useId);
        for (Monitoring.MonitorThreshold t : all) if (t.entity() == null) out.put(t.measure(), t);
        for (Monitoring.MonitorThreshold t : all) if (entity != null && entity.equals(t.entity())) out.put(t.measure(), t);
        return out;
    }

    // ---- readings and drift

    /** Reports a measure (a number) for the use and entity over a period, as the app's reporter {@code "app"}. */
    public Reported report(UUID useId, UUID entity, String measure, BigDecimal value, Monitoring.MeasurePeriod period) {
        return report(useId, entity, measure, value, period, "app");
    }

    /**
     * Reports a measure. The measure needs a pass mark ({@code MOD_THRESHOLD_MISSING}); a retired use takes no
     * readings ({@code MOD_RETIRED}); a use scoped to one entity takes readings for that entity only
     * ({@code MOD_WRONG_USE}).
     */
    public Reported report(UUID useId, UUID entity, String measure, BigDecimal value, Monitoring.MeasurePeriod period,
                           String reportedBy) {
        Objects.requireNonNull(entity, "entity");
        ModelUse use = use(useId);
        if (use.status() == ModelUse.Status.RETIRED) throw new ModelGovException(MOD_RETIRED, Map.of("use", useId));
        if (use.entity() != null && !use.entity().equals(entity)) {
            throw new ModelGovException(MOD_WRONG_USE, Map.of("validation", "-", "use", useId, "entity", entity));
        }
        Values.name("measure", measure);
        Monitoring.MonitorThreshold t = thresholdsInForce(useId, entity).get(measure);
        if (t == null) throw new ModelGovException(MOD_THRESHOLD_MISSING, Map.of("measure", measure));
        Instant now = clock.instant();
        Monitoring.Reading r = new Monitoring.Reading(UUID.randomUUID(), useId, entity, measure, value, period,
                reportedBy, now);
        store.insertReading(r);
        if (!t.drifted(value, period.samples())) return new Reported(r, Optional.empty());
        boolean open = store.drifts(useId, entity).stream()
                .anyMatch(d -> d.measure().equals(measure) && store.driftAction(d.driftId()).isEmpty());
        if (open) return new Reported(r, Optional.empty());
        Monitoring.Drift d = new Monitoring.Drift(UUID.randomUUID(), useId, entity, measure, value, t.value(),
                t.direction(), period.samples(), r.readingId(), now);
        store.insertDrift(d);
        events.raised(ModelEvent.drift(use, d));
        return new Reported(r, Optional.of(d));
    }

    /** Records what was done about a drift (a note with no content); the use no longer needs review for it. */
    public Monitoring.DriftAction recordAction(UUID useId, UUID driftId, String note, String by) {
        use(useId);
        Monitoring.Drift d = store.drifts(useId, null).stream().filter(x -> x.driftId().equals(driftId)).findFirst()
                .orElseThrow(() -> new ModelGovException(MOD_UNKNOWN, Map.of("what", "drift", "id", driftId)));
        Optional<Monitoring.DriftAction> done = store.driftAction(d.driftId());
        if (done.isPresent()) {
            throw new ModelGovException(MOD_ACTION_ALREADY_RECORDED, Map.of("drift", driftId,
                    "by", done.get().actedBy()));
        }
        if (note == null || note.isBlank()) throw new ModelGovException(MOD_REASON_REQUIRED, Map.of());
        Monitoring.DriftAction a = new Monitoring.DriftAction(driftId, note, by, clock.instant());
        store.insertDriftAction(a);
        return a;
    }

    /** The use's drifts (for one entity, or every entity when null) with their actions, oldest first. */
    public List<DriftStatus> drifts(UUID useId, UUID entity) {
        return store.drifts(useId, entity).stream().map(d -> new DriftStatus(d, store.driftAction(d.driftId())))
                .toList();
    }

    /** Whether the use shows "needs review": a drift (of any entity) has no recorded action yet. */
    public boolean needsReview(UUID useId) {
        return drifts(useId, null).stream().anyMatch(DriftStatus::open);
    }

    /** The drifts with no action yet, over every use of the register. */
    public List<DriftStatus> openDrifts() {
        return store.uses(null).stream().flatMap(u -> drifts(u.useId(), null).stream()).filter(DriftStatus::open)
                .toList();
    }

    /** Removes the readings older than the retention. Drifts and actions are kept. @return how many */
    public int purge() {
        return store.purgeReadings(clock.instant().minus(retention));
    }

    private ModelUse use(UUID id) {
        return store.use(id).orElseThrow(() -> new ModelGovException(MOD_UNKNOWN, Map.of("what", "use",
                "id", String.valueOf(id))));
    }

    private ModelGovStore.Change change(String by, String measure) {
        Values.text("changedBy", by, 200, true);
        return new ModelGovStore.Change(by, clock.instant(), List.of("monitoring threshold " + measure));
    }
}
