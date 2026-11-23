package com.documentplatform.modelgov;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Periodic re-validation (design section 5). Each entity's next review date comes from its own live validation (else
 * its latest passed one) and the use's interval; a use is listed by its earliest entity, so one entity's pass never
 * hides another entity running past its date (P036). The interval is the materiality's (default: high 90 days,
 * medium 180, low 365) or the use's own; a change of model, version, digest, configuration or where it runs since an
 * entity's validation makes that entity due at once. {@link #due(Instant)} lists
 * the uses coming due (30 and 7 days before) and overdue (on or after the date), and raises
 * {@link ModelEvent.Kind#REVIEW_DUE} once per use, due date and stage.
 */
public final class ModelReviews {

    /** A use coming due, and whether this call raised its event. */
    public record ReviewDue(ModelUse use, LocalDate dueDate, Monitoring.ReviewStage stage, long daysLeft,
                            boolean raisedNow) {
    }

    private final ModelGovStore store;
    private final ModelEvents events;
    private final Clock clock;
    private final ReviewDates dates;

    /** @param settings the app's settings: their {@code reviewEvery} intervals date each entity's review */
    public ModelReviews(ModelGovStore store, ModelEvents events, ModelGovSettings settings, Clock clock) {
        this(store, events, Objects.requireNonNull(settings, "settings").reviewEvery(), clock);
    }

    /**
     * With the default review intervals ({@link ModelGovSettings#DEFAULT_REVIEW}).
     *
     * @deprecated use {@link #ModelReviews(ModelGovStore, ModelEvents, ModelGovSettings, Clock)}, which takes the
     * app's intervals
     */
    @Deprecated
    public ModelReviews(ModelGovStore store, ModelEvents events, Clock clock) {
        this(store, events, ModelGovSettings.DEFAULT_REVIEW, clock);
    }

    private ModelReviews(ModelGovStore store, ModelEvents events,
                         java.util.Map<ModelUse.Materiality, java.time.Period> reviewEvery, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dates = new ReviewDates(store, reviewEvery);
    }

    /** The uses due within 30 days or overdue at {@code now}, soonest first; raises each stage's event once. */
    public List<ReviewDue> due(Instant now) {
        List<ReviewDue> out = new ArrayList<>();
        for (ReviewDue r : list(now)) {
            boolean raised = store.insertReviewNotice(r.use().useId(), r.dueDate(), r.stage(), now);
            if (raised) events.raised(ModelEvent.reviewDue(r.use(), r.dueDate(), r.stage(), now));
            out.add(new ReviewDue(r.use(), r.dueDate(), r.stage(), r.daysLeft(), raised));
        }
        return out;
    }

    /** {@link #due(Instant)} at the clock's time. */
    public List<ReviewDue> due() {
        return due(clock.instant());
    }

    /** The overdue uses at {@code now}, without raising anything (for checks and screens). */
    public List<ReviewDue> overdue(Instant now) {
        return list(now).stream().filter(r -> r.stage() == Monitoring.ReviewStage.OVERDUE).toList();
    }

    private List<ReviewDue> list(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<ReviewDue> out = new ArrayList<>();
        for (ModelUse u : store.uses(null)) {
            if (u.status() == ModelUse.Status.RETIRED) continue;
            java.util.Optional<LocalDate> earliest = dates.earliest(u);
            if (earliest.isEmpty()) continue;
            LocalDate dueDate = earliest.get();
            long days = ChronoUnit.DAYS.between(today, dueDate);
            Monitoring.ReviewStage stage = days <= 0 ? Monitoring.ReviewStage.OVERDUE
                    : days <= 7 ? Monitoring.ReviewStage.IN_7_DAYS
                    : days <= 30 ? Monitoring.ReviewStage.IN_30_DAYS : null;
            if (stage != null) out.add(new ReviewDue(u, dueDate, stage, days, false));
        }
        out.sort((a, b) -> a.dueDate().compareTo(b.dueDate()) != 0 ? a.dueDate().compareTo(b.dueDate())
                : a.use().useId().compareTo(b.use().useId()));
        return out;
    }
}
