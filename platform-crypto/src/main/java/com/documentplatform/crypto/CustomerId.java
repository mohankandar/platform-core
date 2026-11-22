package com.documentplatform.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The customer whose keys protect the data. Every customer has its own keys, also when several customers share one
 * hosted installation.
 *
 * <p>Licences and the identity tables name a customer by its tenant ID ({@code TEN-…}); {@link #fromTenantId} turns
 * that into this UUID the same way everywhere (name-based, SHA-256, like the entity UUID of an entity slot), so the
 * set-up step and the app always arrive at the same keys without storing a mapping.
 */
public record CustomerId(UUID value) {

    /** Prefix of the derivation input. Never change it: every customer's key names depend on it. */
    public static final String NAME_PREFIX = "platform-customer|";

    private static final Pattern TENANT_ID = Pattern.compile("TEN-[A-Z0-9-]{1,60}");

    public CustomerId {
        Objects.requireNonNull(value, "value");
    }

    public static CustomerId of(UUID value) {
        return new CustomerId(value);
    }

    public static CustomerId of(String value) {
        return new CustomerId(UUID.fromString(value));
    }

    /**
     * The customer of a tenant ID ({@code TEN-} and 1 to 60 of A-Z 0-9 -): a name-based UUID (SHA-256 of
     * {@code "platform-customer|" + tenantId}, version 5 and variant bits set).
     */
    public static CustomerId fromTenantId(String tenantId) {
        if (tenantId == null || !TENANT_ID.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("A tenant ID is TEN- followed by 1 to 60 of A-Z 0-9 -");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest((NAME_PREFIX + tenantId).getBytes(StandardCharsets.UTF_8)));
            long msb = (b.getLong() & 0xffffffffffff0fffL) | 0x0000000000005000L;
            long lsb = (b.getLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new CustomerId(new UUID(msb, lsb));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The 32 lower-case hex digits, as used in key names. */
    public String hex() {
        return value.toString().replace("-", "");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
