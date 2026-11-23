package com.documentplatform.modelgov;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The next review date of a use <b>per entity</b> (design section 5; P036, model-governance-39), shared by the gate,
 * {@link ModelReviews} and the register's {@code nextReviewDue}:
 * <ul>
 *   <li>the entity's reference validation is its live one, else (not live yet) its latest passed one;</li>
 *   <li>the review is due that validation's date plus the use's interval ({@link #every});</li>
 *   <li>it is due at once, on the day of the change, when since that validation the use's model or configuration,
 *       or the model's version, digest or where it runs, changed.</li>
 * </ul>
 * A pass for one entity therefore never moves another entity's date. The use's own {@code nextReviewDue} is the
 * earliest date over its entities.
 */
final class ReviewDates {

    private static final Set<String> USE_FIELDS = Set.of("modelId", "configFingerprint");
    private static final Set<String> MODEL_FIELDS = Set.of("modelVersion", "digest", "runsWhere");

    private final ModelGovStore store;
    private final Map<ModelUse.Materiality, Period> reviewEvery;

    ReviewDates(ModelGovStore store, Map<ModelUse.Materiality, Period> reviewEvery) {
        this.store = Objects.requireNonNull(store, "store");
        this.reviewEvery = Map.copyOf(Objects.requireNonNull(reviewEvery, "reviewEvery"));
    }

    /** How long a validation of the use lasts: its own interval, else its materiality's. */
    Period every(ModelUse use) {
        return use.reviewEveryDays() != null ? Period.ofDays(use.reviewEveryDays())
                : reviewEvery.get(use.materiality());
    }

    /** The entity's next review date for the use; empty when the entity has no live or passed validation. */
    Optional<LocalDate> due(ModelUse use, UUID entity) {
        Optional<ValidationRecord> ref = reference(use.useId(), entity);
        if (ref.isEmpty()) return Optional.empty();
        ValidationRecord v = ref.get();
        LocalDate base = day(v.ranAt()).plus(every(use));
        Optional<LocalDate> changed = changedSince(use, v);
        return Optional.of(changed.filter(c -> c.isBefore(base)).orElse(base));
    }

    /** The earliest next review date over the use's entities; empty when none has a live or passed validation. */
    Optional<LocalDate> earliest(ModelUse use) {
        return entities(use).stream().map(e -> due(use, e)).flatMap(Optional::stream).min(Comparator.naturalOrder());
    }

    private List<UUID> entities(ModelUse use) {
        return use.entity() != null ? List.of(use.entity()) : store.validationEntities(use.useId());
    }

    private Optional<ValidationRecord> reference(UUID useId, UUID entity) {
        Optional<Decisions.Live> live = store.live(useId, entity);
        if (live.isPresent()) return store.validation(live.get().validationId());
        List<ValidationRecord> vs = store.validations(useId, entity);
        for (int i = vs.size() - 1; i >= 0; i--) if (vs.get(i).passed()) return Optional.of(vs.get(i));
        return Optional.empty();
    }

    /**
     * The day of the first change since the validation that makes the review due at once, if any. The model,
     * configuration, version, digest and (since P039) where the model runs are compared with what the validation
     * recorded, so a validation of the new state clears them; for a validation recorded before P039 without where the
     * model ran, a change of location is read from the model's history (at or after the validation's time).
     */
    private Optional<LocalDate> changedSince(ModelUse use, ValidationRecord v) {
        Model model = store.model(use.modelId()).orElse(null);
        boolean stale = !v.modelId().equals(use.modelId()) || !v.configFingerprint().equals(use.configFingerprint())
                || model == null || !v.modelVersion().equals(model.modelVersion()) || !v.digest().equals(model.digest())
                || locationChanged(v, model);
        if (!stale) return Optional.empty();
        Instant first = null;
        for (ModelGovStore.HistoryRow r : store.modelHistory(use.modelId())) {
            if (r.changedAt().isBefore(v.ranAt())) continue;
            if (r.changedFields().stream().anyMatch(MODEL_FIELDS::contains)
                    && (first == null || r.changedAt().isBefore(first))) {
                first = r.changedAt();
            }
        }
        for (ModelGovStore.HistoryRow r : store.useHistory(use.useId())) {
            if (r.changedAt().isBefore(v.ranAt())) continue;
            if (r.changedFields().stream().anyMatch(USE_FIELDS::contains)
                    && (first == null || r.changedAt().isBefore(first))) {
                first = r.changedAt();
            }
        }
        // Stale with no history row found (a store without history): due since the validation itself.
        return Optional.of(day(first != null ? first : v.ranAt()));
    }

    /**
     * Whether the model now runs elsewhere than when the validation ran (P039, design section 4). The validation's own
     * record says where; one recorded before P039 does not, and then a change of location in the model's history at
     * or after the validation's time counts (the safe side when both share an instant).
     */
    boolean locationChanged(ValidationRecord v, Model model) {
        if (model == null) return false;
        if (v.runsWhere() != null) return v.runsWhere() != model.runsWhere();
        for (ModelGovStore.HistoryRow r : store.modelHistory(model.modelId())) {
            if (!r.changedAt().isBefore(v.ranAt()) && r.changedFields().contains("runsWhere")) return true;
        }
        return false;
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }
}
