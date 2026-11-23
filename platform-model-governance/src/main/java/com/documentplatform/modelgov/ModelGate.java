package com.documentplatform.modelgov;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.documentplatform.modelgov.ModelGovCode.*;

/**
 * The gate at model start: the app calls {@link #requireLive} when it starts (or reloads) the model of a use for an
 * entity, with the digest of the model actually running and the fingerprint of its actual configuration. It refuses
 * (a {@link ModelGovException} whose code is a {@code PlatformCode} with cause and fix, for the app's IT notice):
 * {@code MOD_RETIRED}, {@code MOD_NOT_VALIDATED}, {@code MOD_NOT_APPROVED}, {@code MOD_NOT_LIVE},
 * {@code MOD_DIGEST_MISMATCH} (the model file or pull changed), {@code MOD_CONFIG_CHANGED} (the prompt or settings
 * changed without a validation), and {@code MOD_REVIEW_OVERDUE} when the use is set to refuse it (else it is a
 * warning on the answer); the review date is the entity's own, from its live validation.
 */
public final class ModelGate {

    private final ModelGovStore store;
    private final Clock clock;
    private final ReviewDates dates;

    /** @param settings the app's settings: their {@code reviewEvery} intervals date each entity's review */
    public ModelGate(ModelGovStore store, ModelGovSettings settings, Clock clock) {
        this(store, Objects.requireNonNull(settings, "settings").reviewEvery(), clock);
    }

    /**
     * With the default review intervals ({@link ModelGovSettings#DEFAULT_REVIEW}).
     *
     * @deprecated use {@link #ModelGate(ModelGovStore, ModelGovSettings, Clock)}, which takes the app's intervals
     */
    @Deprecated
    public ModelGate(ModelGovStore store, Clock clock) {
        this(store, ModelGovSettings.DEFAULT_REVIEW, clock);
    }

    private ModelGate(ModelGovStore store, java.util.Map<ModelUse.Materiality, java.time.Period> reviewEvery,
                      Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dates = new ReviewDates(store, reviewEvery);
    }

    public LiveModel requireLive(UUID useId, UUID entity, ModelDigest observedDigest, String observedConfigFingerprint) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(observedDigest, "observedDigest");
        ModelUse use = store.use(useId).orElseThrow(() -> new ModelGovException(MOD_UNKNOWN,
                Map.of("what", "use", "id", String.valueOf(useId))));
        if (use.status() == ModelUse.Status.RETIRED) throw new ModelGovException(MOD_RETIRED, Map.of("use", useId));
        Optional<Decisions.Live> live = store.live(useId, entity);
        if (live.isEmpty()) {
            List<ValidationRecord> vs = store.validations(useId, entity);
            if (vs.isEmpty()) throw new ModelGovException(MOD_NOT_VALIDATED, Map.of("use", useId, "entity", entity));
            boolean approved = vs.stream().anyMatch(v -> store.approval(v.validationId()).isPresent());
            throw new ModelGovException(approved ? MOD_NOT_LIVE : MOD_NOT_APPROVED, Map.of("use", useId,
                    "entity", entity));
        }
        ValidationRecord v = store.validation(live.get().validationId()).orElseThrow(() -> new ModelGovException(
                MOD_UNKNOWN, Map.of("what", "validation", "id", String.valueOf(live.get().validationId()))));
        if (!v.digest().equals(observedDigest)) {
            throw new ModelGovException(MOD_DIGEST_MISMATCH, Map.of("use", useId, "observed", observedDigest,
                    "expected", v.digest()));
        }
        if (!v.configFingerprint().equals(observedConfigFingerprint)) {
            throw new ModelGovException(MOD_CONFIG_CHANGED, Map.of("use", useId,
                    "observed", String.valueOf(observedConfigFingerprint), "expected", v.configFingerprint()));
        }
        List<ModelGovCode> warnings = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        // This entity's own date, from its live validation: another entity's pass never moves it (P036).
        Optional<LocalDate> due = dates.due(use, entity);
        if (due.isPresent() && !today.isBefore(due.get())) {
            if (use.reviewOverdueRefuses()) {
                throw new ModelGovException(MOD_REVIEW_OVERDUE, Map.of("use", useId, "entity", entity,
                        "due", due.get()));
            }
            warnings.add(MOD_REVIEW_OVERDUE);
        }
        return new LiveModel(use, v, warnings);
    }
}
