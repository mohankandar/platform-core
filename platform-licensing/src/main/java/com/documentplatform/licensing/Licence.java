package com.documentplatform.licensing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The content of one signed licence: one per customer (tenant), listing the products bought and the entity slots
 * with their allowances. Built by the issuing command, read back by {@link LicenceVerifier} after the signature has
 * been checked. A change of slots or allowances is a new licence (same tenant, next {@code version}).
 *
 * <p>Dates are calendar dates in UTC. {@code expiresOn} is the last day the licence is valid (inclusive).
 *
 * @param expiresOn    subscription only (null for perpetual)
 * @param updatesUntil perpetual only (null for subscription): the last product release date covered
 */
public record Licence(
        String licenceId,
        long version,
        String customer,
        String tenantId,
        List<String> products,
        List<EntitySlot> entitySlots,
        LicenceType type,
        LicenceKind kind,
        LocalDate startsOn,
        LocalDate expiresOn,
        LocalDate updatesUntil,
        Deployment deployment,
        String keyId,
        Instant issuedAt) {

    private static final Pattern ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{2,63}");
    private static final Pattern PRODUCT = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    public Licence {
        requireId(licenceId, "licence ID");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        Objects.requireNonNull(customer, "customer");
        if (customer.isBlank() || customer.length() > 200 || customer.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("customer must be 1 to 200 printable characters");
        }
        requireId(tenantId, "tenant ID");
        products = List.copyOf(products);
        if (products.isEmpty()) {
            throw new IllegalArgumentException("a licence lists at least one product");
        }
        if (new HashSet<>(products).size() != products.size()) {
            throw new IllegalArgumentException("a product is listed twice");
        }
        products.forEach(Licence::requireProductCode);
        entitySlots = List.copyOf(entitySlots);
        if (entitySlots.isEmpty()) {
            throw new IllegalArgumentException("a licence has at least one entity slot");
        }
        Set<String> slotIds = new HashSet<>();
        for (EntitySlot slot : entitySlots) {
            if (!slotIds.add(slot.slotId())) {
                throw new IllegalArgumentException("slot " + slot.slotId() + " is listed twice");
            }
            if (slot.slotId().equals(tenantId)) {
                throw new IllegalArgumentException("a slot ID cannot equal the tenant ID");
            }
            for (Allowance a : slot.allowances()) {
                if (!products.contains(a.product())) {
                    throw new IllegalArgumentException("slot " + slot.slotId() + " has an allowance for "
                            + a.product() + ", which is not a listed product");
                }
            }
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(deployment, "deployment");
        requireKeyId(keyId);
        Objects.requireNonNull(issuedAt, "issuedAt");
        switch (type) {
            case SUBSCRIPTION -> {
                if (expiresOn == null) {
                    throw new IllegalArgumentException("a subscription licence has an expiry date");
                }
                if (updatesUntil != null) {
                    throw new IllegalArgumentException("a subscription licence has no updates-until date");
                }
                if (expiresOn.isBefore(startsOn)) {
                    throw new IllegalArgumentException("expiry date is before the start date");
                }
            }
            case PERPETUAL -> {
                if (expiresOn != null) {
                    throw new IllegalArgumentException("a perpetual licence has no expiry date");
                }
                if (updatesUntil == null) {
                    throw new IllegalArgumentException("a perpetual licence has an updates-until date");
                }
                if (updatesUntil.isBefore(startsOn)) {
                    throw new IllegalArgumentException("updates-until date is before the start date");
                }
            }
        }
        if (kind == LicenceKind.INSTALL && type != LicenceType.SUBSCRIPTION) {
            throw new IllegalArgumentException("an install licence is a subscription (it has an expiry date)");
        }
    }

    /** IDs (tenant, slot, licence): 3 to 64 of A-Z 0-9 and '-'. */
    public static void requireId(String id, String what) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException(what + " must be 3 to 64 of A-Z 0-9 -");
        }
    }

    /** Product codes: 2 to 32 of A-Z 0-9 and '_', starting with a letter. */
    public static void requireProductCode(String code) {
        if (code == null || !PRODUCT.matcher(code).matches()) {
            throw new IllegalArgumentException("product code must be 2 to 32 of A-Z 0-9 _ (starting with a letter)");
        }
    }

    /** Key IDs: 1 to 64 of a-z 0-9 '.' '_' '-', starting with a letter or digit. */
    public static void requireKeyId(String keyId) {
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("key ID must be 1 to 64 of a-z 0-9 . _ - (starting with a letter or digit)");
        }
    }

    public Optional<EntitySlot> slot(String slotId) {
        return entitySlots.stream().filter(s -> s.slotId().equals(slotId)).findFirst();
    }

    public Optional<LocalDate> expiry() {
        return Optional.ofNullable(expiresOn);
    }

    public Optional<LocalDate> updatesUntilDate() {
        return Optional.ofNullable(updatesUntil);
    }

    /** Whether the app must show its "Test environment" marking (see {@link LicenceKind#testEnvironment()}). */
    public boolean testEnvironment() {
        return kind.testEnvironment();
    }

    /**
     * Whether a product release dated {@code releaseDate} is covered by this licence. A perpetual licence covers
     * releases dated on or before its "updates until" date; a subscription covers every release (its expiry and grace
     * already govern use). A release that is not covered is reported by the app with
     * {@link #releaseNotCoveredMessage()}; the version already installed keeps working.
     */
    public boolean coversRelease(LocalDate releaseDate) {
        Objects.requireNonNull(releaseDate, "releaseDate");
        return type == LicenceType.SUBSCRIPTION || !releaseDate.isAfter(updatesUntil);
    }

    /** The text an app shows when {@link #coversRelease} is false. */
    public String releaseNotCoveredMessage() {
        return "This version is not covered by your licence's update period (updates until " + updatesUntil
                + "). The version you had before keeps working.";
    }
}
