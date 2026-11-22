package com.documentplatform.identity.store;

/**
 * The result of setting an entity's or the customer's time zone. The caller audits it (who, which slot or customer,
 * the result code, the zone name).
 *
 * @param subject the slot ID or tenant ID; null when it was not one
 * @param zone    the entity's (or customer's) zones after the call, for an entity; null otherwise
 */
public record TimeZoneOutcome(Result result, String subject, EntityTimeZone zone) {

    public enum Result {
        /** The zone is now the one given. */
        SET(false),
        /** The entity has no zone of its own any more: it follows the customer's. */
        CLEARED(false),
        /** It was already so; nothing changed. */
        UNCHANGED(false),
        /** Not a slot ID ({@code ENT-…}) or tenant ID ({@code TEN-…}). */
        NOT_AN_ID(true),
        /** Not an IANA time zone name such as {@code America/New_York} (offsets like {@code +05:00} are not taken). */
        TIME_ZONE_INVALID(true),
        /** The setup command has not stored the slot. */
        SLOT_UNKNOWN(true),
        /** The setup command has not stored the customer. */
        CUSTOMER_UNKNOWN(true);

        private final boolean refused;

        Result(boolean refused) {
            this.refused = refused;
        }

        public boolean refused() {
            return refused;
        }
    }

    public boolean refused() {
        return result.refused();
    }
}
