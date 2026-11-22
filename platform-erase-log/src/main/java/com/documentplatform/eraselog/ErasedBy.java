package com.documentplatform.eraselog;

import java.util.Objects;

/**
 * Who or what erased: a retention rule (its ID) or a person (their actor ID, not their e-mail address or name).
 *
 * @param id 1–128 of A-Z a-z 0-9 _ . : @ / -
 */
public record ErasedBy(Type type, String id) {

    public enum Type { RULE, PERSON }

    public ErasedBy {
        Objects.requireNonNull(type, "type");
        EraseRequest.require("by", id, EraseRequest.ID);
        EraseRequest.notAValue("by", id);
    }

    public static ErasedBy rule(String ruleId) {
        return new ErasedBy(Type.RULE, ruleId);
    }

    public static ErasedBy person(String actorId) {
        return new ErasedBy(Type.PERSON, actorId);
    }

    @Override
    public String toString() {
        return type.name().toLowerCase(java.util.Locale.ROOT) + " " + id;
    }
}
