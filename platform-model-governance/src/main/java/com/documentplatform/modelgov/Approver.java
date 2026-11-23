package com.documentplatform.modelgov;

import java.util.Objects;
import java.util.Set;

/** The person approving (or accepting), with the roles the app knows them to hold. Never a token or claim. */
public record Approver(String id, Set<String> roles) {
    public Approver {
        Values.text("approver", id, 200, true);
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
