package com.documentplatform.licensing;

import java.util.Objects;

/**
 * One allowance of an entity slot: for product {@code product}, up to {@code amount} of {@code type} (in
 * {@link AllowanceType#unit()}). An allowance is a reference for warnings, never a hard cap.
 */
public record Allowance(String product, AllowanceType type, long amount) {

    public Allowance {
        Licence.requireProductCode(product);
        Objects.requireNonNull(type, "type");
        if (amount < 1) {
            throw new IllegalArgumentException("allowance amount must be at least 1");
        }
    }
}
