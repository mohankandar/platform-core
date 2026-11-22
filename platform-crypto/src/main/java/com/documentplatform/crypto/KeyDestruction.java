package com.documentplatform.crypto;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What {@link KeyService#destroyCustomerKeys} did, for the offboarding certificate: the customer, the key service,
 * each of the customer's keys with its name, its versions and whether it was destroyed now or was already gone, and
 * when. Names and version numbers only: no key material, no wrapped key, no token.
 *
 * @param customer   the customer whose keys were destroyed
 * @param keyService the kind of key service ({@link KeyService#id()})
 * @param keys       the wrapping key first, then the fingerprint key
 * @param at         when the destruction finished
 */
public record KeyDestruction(CustomerId customer, String keyService, List<Key> keys, Instant at) {

    public KeyDestruction {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(keyService, "keyService");
        keys = List.copyOf(keys);
        Objects.requireNonNull(at, "at");
    }

    /** Which of the customer's two keys. */
    public enum Role { WRAPPING, FINGERPRINT }

    /** What happened to one key. */
    public enum State {
        /** The key existed and was destroyed by this call, with all its versions. */
        DESTROYED,
        /** The key service had no such key (already destroyed, never created, or another prefix). */
        ABSENT
    }

    /**
     * One key.
     *
     * @param latestVersion the key's latest version when it was destroyed (versions 1 to this were destroyed); 0
     *                      when it was absent
     */
    public record Key(Role role, String name, int latestVersion, State state) {
        public Key {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
        }

        public static Key destroyed(Role role, String name, int latestVersion) {
            return new Key(role, name, latestVersion, State.DESTROYED);
        }

        public static Key absent(Role role, String name) {
            return new Key(role, name, 0, State.ABSENT);
        }
    }

    /** Whether at least one key was destroyed by this call. */
    public boolean destroyedAny() {
        return keys.stream().anyMatch(k -> k.state() == State.DESTROYED);
    }
}
