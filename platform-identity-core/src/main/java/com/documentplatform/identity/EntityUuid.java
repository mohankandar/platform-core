package com.documentplatform.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The app's entity UUID for a licence entity slot: a name-based UUID (SHA-256, version and variant bits set as in
 * {@link StableIdentityId}) from {@code "platform-entity|" + slotId}. The same slot gives the same UUID in every
 * database, every install and on hosting, so the app's existing UUID keys need no change and nothing has to remember
 * which UUID was handed out.
 *
 * <p>Also holds the ID rules for customers ({@code TEN-…}) and entity slots ({@code ENT-…}): the licence's ID rule
 * (A-Z, 0-9 and '-', at most 64 characters) with the prefix.
 */
public final class EntityUuid {

    /** Prefix of the derivation input. Never change it: every stored entity UUID depends on it. */
    public static final String NAME_PREFIX = "platform-entity|";

    private static final Pattern SLOT_ID = Pattern.compile("ENT-[A-Z0-9-]{1,60}");
    private static final Pattern TENANT_ID = Pattern.compile("TEN-[A-Z0-9-]{1,60}");

    private EntityUuid() {
    }

    /** The entity UUID for this slot ID. */
    public static UUID fromSlotId(String slotId) {
        requireSlotId(slotId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((NAME_PREFIX + slotId).getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            long msb = buffer.getLong();
            long lsb = buffer.getLong();
            msb = (msb & 0xffffffffffff0fffL) | 0x0000000000005000L;
            lsb = (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Whether the value has the form of an entity slot ID ({@code ENT-…}). */
    public static boolean isSlotId(String value) {
        return value != null && SLOT_ID.matcher(value).matches();
    }

    /** Whether the value has the form of a customer (tenant) ID ({@code TEN-…}). */
    public static boolean isTenantId(String value) {
        return value != null && TENANT_ID.matcher(value).matches();
    }

    public static String requireSlotId(String value) {
        if (!isSlotId(value)) {
            throw new IllegalArgumentException("slot ID must be ENT- followed by 1 to 60 of A-Z 0-9 -");
        }
        return value;
    }

    public static String requireTenantId(String value) {
        if (!isTenantId(value)) {
            throw new IllegalArgumentException("tenant ID must be TEN- followed by 1 to 60 of A-Z 0-9 -");
        }
        return value;
    }
}
