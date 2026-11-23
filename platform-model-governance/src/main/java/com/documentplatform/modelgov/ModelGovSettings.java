package com.documentplatform.modelgov;

import java.time.Period;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The app's configuration of the change gate. No role name is built in: the app names its roles.
 *
 * @param approverRoles  the roles that may approve (the Model approver; a compliance officer where configured)
 * @param forbiddenRoles the roles an approver may never also hold (the Business Admin role)
 * @param reviewEvery    how long a validation lasts per materiality before the next review is due (defaults high 90
 *                       days, medium 180, low 365)
 */
public record ModelGovSettings(Set<String> approverRoles, Set<String> forbiddenRoles,
                               Map<ModelUse.Materiality, Period> reviewEvery) {

    public static final Map<ModelUse.Materiality, Period> DEFAULT_REVIEW = Map.of(
            ModelUse.Materiality.HIGH, Period.ofDays(90),
            ModelUse.Materiality.MEDIUM, Period.ofDays(180),
            ModelUse.Materiality.LOW, Period.ofDays(365));

    public ModelGovSettings {
        approverRoles = Set.copyOf(Objects.requireNonNull(approverRoles, "approverRoles"));
        forbiddenRoles = Set.copyOf(Objects.requireNonNull(forbiddenRoles, "forbiddenRoles"));
        if (approverRoles.isEmpty()) throw new IllegalArgumentException("at least one approver role is needed");
        for (String r : approverRoles) {
            if (forbiddenRoles.contains(r)) throw new IllegalArgumentException("a role cannot be both approver and forbidden");
        }
        Map<ModelUse.Materiality, Period> m = new EnumMap<>(ModelUse.Materiality.class);
        m.putAll(DEFAULT_REVIEW);
        if (reviewEvery != null) m.putAll(reviewEvery);
        reviewEvery = Map.copyOf(m);
    }

    public static ModelGovSettings of(Set<String> approverRoles, Set<String> forbiddenRoles) {
        return new ModelGovSettings(approverRoles, forbiddenRoles, DEFAULT_REVIEW);
    }
}
