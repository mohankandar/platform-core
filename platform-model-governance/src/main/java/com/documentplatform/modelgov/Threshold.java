package com.documentplatform.modelgov;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * A pass mark: for this use (and this entity, or null for the app's default), the measure must be at least (higher is
 * better) or at most (lower is better) this value.
 */
public record Threshold(UUID useId, UUID entity, String measure, BigDecimal value, Direction direction) {

    public enum Direction {
        HIGHER_IS_BETTER,
        LOWER_IS_BETTER;

        /** Whether a result passes this threshold. */
        public boolean passes(BigDecimal result, BigDecimal threshold) {
            int c = result.compareTo(threshold);
            return this == HIGHER_IS_BETTER ? c >= 0 : c <= 0;
        }

        /** Whether {@code candidate} is at least as strict as {@code base}. */
        public boolean atLeastAsStrict(BigDecimal candidate, BigDecimal base) {
            int c = candidate.compareTo(base);
            return this == HIGHER_IS_BETTER ? c >= 0 : c <= 0;
        }
    }

    public Threshold {
        Objects.requireNonNull(useId, "useId");
        Values.name("measure", measure);
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(direction, "direction");
    }

    /** The app's default for the use. */
    public static Threshold byDefault(UUID useId, String measure, BigDecimal value, Direction direction) {
        return new Threshold(useId, null, measure, value, direction);
    }

    /** The customer's (stricter) one for an entity. */
    public static Threshold forEntity(UUID useId, UUID entity, String measure, BigDecimal value, Direction direction) {
        return new Threshold(useId, Objects.requireNonNull(entity, "entity"), measure, value, direction);
    }
}
