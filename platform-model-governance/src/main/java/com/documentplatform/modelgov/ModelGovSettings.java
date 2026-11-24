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
        // P045, SEC-32: role names compared as one form (trimmed, lower case), blanks refused, and separation of
        // duties needs at least one forbidden role in the core too, not only in the Spring settings.
        approverRoles = normalised(Objects.requireNonNull(approverRoles, "approverRoles"), "approver");
        forbiddenRoles = normalised(Objects.requireNonNull(forbiddenRoles, "forbiddenRoles"), "forbidden");
        if (approverRoles.isEmpty()) throw new IllegalArgumentException("at least one approver role is needed");
        if (forbiddenRoles.isEmpty()) {
            throw new IllegalArgumentException("at least one forbidden role is needed (the role an approver may never "
                    + "also hold)");
        }
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

    /** Whether {@code roles} hold an approver role (compared trimmed and in lower case). */
    public boolean holdsApproverRole(Set<String> roles) {
        return roles.stream().map(ModelGovSettings::form).anyMatch(approverRoles::contains);
    }

    /** The first of {@code roles} that is forbidden for an approver, if any (compared trimmed and in lower case). */
    public java.util.Optional<String> forbiddenRole(Set<String> roles) {
        return roles.stream().filter(r -> forbiddenRoles.contains(form(r))).findFirst();
    }

    /** Whether an approval made with {@code roles} counts: an approver role and no forbidden one. */
    public boolean permitted(Set<String> roles) {
        return holdsApproverRole(roles) && forbiddenRole(roles).isEmpty();
    }

    static String form(String role) {
        return role == null ? "" : role.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> normalised(Set<String> roles, String what) {
        Set<String> out = new java.util.HashSet<>();
        for (String r : roles) {
            String f = form(r);
            if (f.isEmpty()) throw new IllegalArgumentException("a " + what + " role name is blank");
            out.add(f);
        }
        return Set.copyOf(out);
    }
}
