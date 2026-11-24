package com.documentplatform.integration.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The entity slot ID rule and the entity UUID derived from it, as identity's {@code EntityUuid} has them (name-based
 * UUID, SHA-256 of {@code "platform-entity|" + slotId} with the version and variant bits set). Kept here since P028 so
 * that platform-integration does not depend on platform-identity-core; a test checks both give the same UUIDs.
 */
public final class SlotIds {

    private static final String NAME_PREFIX = "platform-entity|";
    private static final Pattern SLOT_ID = Pattern.compile("ENT-[A-Z0-9-]{1,60}");

    private SlotIds() {
    }

    /** Whether the value has the form of an entity slot ID ({@code ENT-…}). */
    public static boolean isSlotId(String value) {
        return value != null && SLOT_ID.matcher(value).matches();
    }

    /** The entity UUID for this slot ID. */
    public static UUID entityUuid(String slotId) {
        if (!isSlotId(slotId)) {
            throw new IllegalArgumentException("slot ID must be ENT- followed by 1 to 60 of A-Z 0-9 -");
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256")
                    .digest((NAME_PREFIX + slotId).getBytes(StandardCharsets.UTF_8)));
            long msb = (b.getLong() & 0xffffffffffff0fffL) | 0x0000000000005000L;
            long lsb = (b.getLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
