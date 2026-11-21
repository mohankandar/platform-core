package com.documentplatform.identity.store;

import com.documentplatform.identity.EntityUuid;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import static com.documentplatform.identity.store.ActivationOutcome.Result.*;

/**
 * The Business Admin activates and names an entity slot, or renames an activated one. This is the app's API for its
 * activation screen.
 *
 * <ul>
 *   <li>Only a slot listed in the licence in force can be activated; without a valid licence nothing can.</li>
 *   <li>A slot is activated once; it is never deleted or deactivated here.</li>
 *   <li>The name is trimmed, 1 to {@value #MAX_NAME_LENGTH} characters, without control characters, and unique within
 *       the customer ignoring case (so nobody confuses two entities).</li>
 *   <li>The caller audits every call, refused or not, from the returned outcome. After ACTIVATED the app creates its
 *       own entity row keyed by {@link EntityRecord#entityUuid()} (in the same transaction where it can).</li>
 * </ul>
 */
public final class SlotActivation {

    public static final int MAX_NAME_LENGTH = 200;

    private final EntityStore store;
    private final LicensedSlots licence;
    private final Clock clock;

    public SlotActivation(EntityStore store, LicensedSlots licence, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.licence = Objects.requireNonNull(licence, "licence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param actor the actor ID of the Business Admin (stored as activated_by)
     */
    public ActivationOutcome activate(String slotId, String name, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        Optional<String> clean = cleanName(name);
        if (clean.isEmpty()) return new ActivationOutcome(NAME_INVALID, slotId, null);
        Optional<LicensedSlots.Current> current = licence.current();
        if (current.isEmpty()) return new ActivationOutcome(NO_LICENCE, slotId, null);
        Optional<EntityRecord> stored = store.entity(slotId);
        if (!current.get().slotIds().contains(slotId)
                || stored.isPresent() && !stored.get().tenantId().equals(current.get().tenantId())) {
            return new ActivationOutcome(NOT_IN_LICENCE, slotId, stored.orElse(null));
        }
        return store.activate(slotId, clean.get(), actor, clock.instant());
    }

    /** Renames an activated slot. The licence is not checked: a name is not a licensed thing. */
    public ActivationOutcome rename(String slotId, String name, String actor) {
        requireActor(actor);
        if (!EntityUuid.isSlotId(slotId)) return new ActivationOutcome(NOT_A_SLOT_ID, null, null);
        Optional<String> clean = cleanName(name);
        if (clean.isEmpty()) return new ActivationOutcome(NAME_INVALID, slotId, null);
        return store.rename(slotId, clean.get());
    }

    /** The name as stored: trimmed; empty when it breaks the name rule. */
    public static Optional<String> cleanName(String name) {
        if (name == null) return Optional.empty();
        String s = name.strip();
        if (s.isEmpty() || s.length() > MAX_NAME_LENGTH) return Optional.empty();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isISOControl(c) || c == '​' || c == '‌' || c == '‍' || c == '⁠'
                    || c == '﻿') {
                return Optional.empty();
            }
        }
        return Optional.of(s);
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor is required");
    }
}
