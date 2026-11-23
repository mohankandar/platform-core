package com.documentplatform.modelgov.spring;

import com.documentplatform.modelgov.ModelGovSettings;
import com.documentplatform.modelgov.ModelMeasures;
import com.documentplatform.modelgov.ModelUse;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The checked settings of {@code platform.model-governance.*}: every problem is listed at once. */
public record ModelGovConfig(ModelGovSettings settings, Duration measureRetention, boolean workerEnabled,
                             Duration workerEvery) {

    /** The settings are wrong; the message lists every problem with the property to fix. */
    public static final class Invalid extends IllegalStateException {
        private final List<String> problems;

        Invalid(List<String> problems) {
            super("Model governance settings are not valid (" + ModelGovProperties.PREFIX + "): "
                    + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    public static ModelGovConfig from(ModelGovProperties p) {
        List<String> problems = new ArrayList<>();
        Set<String> approvers = clean(p.getApproverRoles());
        Set<String> forbidden = clean(p.getForbiddenRoles());
        if (approvers.isEmpty()) {
            problems.add(ModelGovProperties.PREFIX + ".approver-roles: name at least one role that may approve models "
                    + "(the Model approver)");
        }
        for (String r : approvers) {
            if (forbidden.contains(r)) {
                problems.add(ModelGovProperties.PREFIX + ".forbidden-roles: " + r + " is also an approver role; a "
                        + "role cannot be both");
            }
        }
        Map<ModelUse.Materiality, Period> every = new EnumMap<>(ModelUse.Materiality.class);
        days(problems, "high", p.getReviewEvery().getHigh()).ifPresent(d -> every.put(ModelUse.Materiality.HIGH, d));
        days(problems, "medium", p.getReviewEvery().getMedium()).ifPresent(d -> every.put(ModelUse.Materiality.MEDIUM, d));
        days(problems, "low", p.getReviewEvery().getLow()).ifPresent(d -> every.put(ModelUse.Materiality.LOW, d));
        Duration retention = p.getMeasureRetention();
        if (retention == null || retention.compareTo(ModelMeasures.MIN_RETENTION) < 0) {
            problems.add(ModelGovProperties.PREFIX + ".measure-retention: at least 30 days (default 400d)");
        }
        Duration workerEvery = p.getWorker().getEvery();
        if (workerEvery == null || workerEvery.compareTo(Duration.ofMinutes(1)) < 0) {
            problems.add(ModelGovProperties.PREFIX + ".worker.every: at least one minute (default 1h)");
        }
        if (!problems.isEmpty()) throw new Invalid(problems);
        return new ModelGovConfig(new ModelGovSettings(approvers, forbidden, every), retention,
                p.getWorker().isEnabled(), workerEvery);
    }

    private static java.util.Optional<Period> days(List<String> problems, String name, Duration d) {
        if (d == null || d.isNegative() || d.isZero() || d.toDays() > 3650 || !d.equals(Duration.ofDays(d.toDays()))) {
            problems.add(ModelGovProperties.PREFIX + ".review-every." + name + ": whole days from 1d to 3650d");
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Period.ofDays((int) d.toDays()));
    }

    private static Set<String> clean(List<String> roles) {
        Set<String> out = new LinkedHashSet<>();
        if (roles != null) for (String r : roles) if (r != null && !r.isBlank()) out.add(r.strip());
        return out;
    }
}
