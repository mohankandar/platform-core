package com.documentplatform.identity.admission;

import java.util.Objects;

/**
 * A sign-in or entity choice was refused. Always fail closed: the product maps every reason to a refusal of the
 * request, never to "allowed". The message is safe for logs; whether it is shown to users is the product's choice.
 *
 * <p>Deliberately not an {@link IllegalArgumentException}, so that callers parsing input cannot swallow it by accident.
 */
public final class IdentityAdmissionException extends RuntimeException {

    /** Why the admission was refused. Lets products choose a status and a safe message per reason. */
    public enum Reason implements com.documentplatform.errors.PlatformCode {
        /** Shared-issuer selection is switched off, or connections are not persisted. */
        NOT_ENABLED,
        /** The verified identity has no admissible entity. */
        NO_VERIFIED_MEMBERSHIP,
        /** A verified membership is not backed by an ACTIVE connection. */
        MEMBERSHIP_NOT_BACKED,
        /** More than one entity is admissible and none was chosen. */
        AMBIGUOUS_SELECTION,
        /** The chosen entity is not one the verified identity may enter. */
        NOT_AUTHORIZED_FOR_ENTITY,
        /** The entity choice could not be read. */
        INVALID_SELECTION,
        /** The identity matched an entity's groups but no role resulted. */
        NO_ROLE_FOR_ENTITY,
        /** A grant names a role the product does not know. */
        UNKNOWN_ROLE,
        /** A membership carries no role, or the platform role (checked by {@link SharedIssuerPreflight}). */
        INVALID_ROLE_GRANT,
        /** A platform-wide grant gives a role that is not one of the product's platform roles. */
        ENTITY_ROLE_IN_PLATFORM_GRANT,
        /** A grant gives the product's platform role through an entity group. */
        PLATFORM_ROLE_IN_ENTITY_GRANT,
        /** The verified groups claim is not a list. */
        GROUPS_NOT_A_COLLECTION,
        /** The entity has no group grants at all. */
        NO_ADMISSION_POLICY,
        /** There is no verified issuer, or no audience is configured. */
        CONTEXT_UNAVAILABLE,
        /** The grants or the ACTIVE entities could not be read from the product's store. */
        POLICY_UNAVAILABLE,
        /** A machine identity is bound to no entity, or to more than one. */
        MACHINE_BINDING_NOT_UNIQUE
    }

    private final Reason reason;

    public IdentityAdmissionException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
